package ru.dikoresearch.domain

import GigaChatFunction
import GigaChatFunctionCall
import GigaChatMessage
import Usage
import kotlinx.serialization.json.*
import ru.dikoresearch.infrastructure.http.GigaChatClient
import ru.dikoresearch.infrastructure.persistence.ChatHistoryManager

/**
 * Оркестратор чат-логики - чистая бизнес-логика без зависимости от Telegram
 * Отвечает за обработку сообщений пользователя, управление историей и суммаризацию
 */
class ChatOrchestrator(
    private val gigaClient: GigaChatClient,
    private val historyManager: ChatHistoryManager
) {
    /**
     * Обрабатывает сообщение без сохранения в историю (для RAG-тестирования)
     *
     * @param systemRole системный промпт
     * @param userMessage текст сообщения пользователя
     * @param temperature температура модели (0.0 - 1.0)
     * @param model название модели GigaChat
     * @return пара из текста ответа и статистики использования токенов
     */
    suspend fun processMessageWithoutHistory(
        systemRole: String,
        userMessage: String,
        temperature: Float,
        model: String = "GigaChat"
    ): Pair<String, Usage> {
        val tempHistory = mutableListOf(
            GigaChatMessage(role = "system", content = systemRole),
            GigaChatMessage(role = "user", content = userMessage)
        )

        val response = try {
            gigaClient.chatCompletion(
                model = model,
                messages = tempHistory,
                temperature = temperature
            )
        } catch (e: Exception) {
            println("GigaChat error in processMessageWithoutHistory: ${e}")
            throw ChatException("Ошибка при обращении к GigaChat LLM", e)
        }

        val answer = response.choices.firstOrNull()?.message?.content ?: "Пустой ответ"
        return answer to response.usage
    }

    /**
     * Обрабатывает сообщение с поддержкой function calling
     *
     * @param systemRole системный промпт
     * @param userMessage текст сообщения пользователя
     * @param temperature температура модели
     * @param model название модели GigaChat
     * @param functions список доступных функций
     * @param functionExecutor функция для выполнения вызовов функций
     * @param maxIterations максимальное количество итераций (защита от зацикливания)
     * @return тройка: (финальный ответ, usage, история вызовов функций)
     */
    suspend fun processMessageWithFunctionCalling(
        systemRole: String,
        userMessage: String,
        temperature: Float,
        model: String = "GigaChat",
        functions: List<GigaChatFunction>,
        functionExecutor: suspend (String, Map<String, Any>) -> String,
        maxIterations: Int = 5
    ): Triple<String, Usage, List<String>> {
        val history = mutableListOf(
            GigaChatMessage(role = "system", content = systemRole),
            GigaChatMessage(role = "user", content = userMessage)
        )

        val functionCallsLog = mutableListOf<String>()
        var totalUsage = Usage(promptTokens = 0, completionTokens = 0, totalTokens = 0, precachedPromptTokens = 0)

        // LOG: Show initial request to GigaChat
        println("\n" + "=".repeat(80))
        println("📤 REQUEST TO GIGACHAT")
        println("=".repeat(80))
        println("SYSTEM ROLE (${systemRole.length} chars):")
        println(systemRole)
        println("\n${"-".repeat(80)}")
        println("USER MESSAGE (${userMessage.length} chars):")
        println(userMessage)
        println("\n${"-".repeat(80)}")
        println("FUNCTIONS AVAILABLE: ${functions.size}")
        functions.forEach { println("   - ${it.name}: ${it.description}") }
        println("=".repeat(80) + "\n")

        repeat(maxIterations) { iteration ->
            println("🔄 Function calling iteration ${iteration + 1}/$maxIterations")

            // Make request with functions
            val response = try {
                gigaClient.chatCompletion(
                    model = model,
                    messages = history,
                    temperature = temperature,
                    functions = functions,
                    functionCall = "auto"
                )
            } catch (e: Exception) {
                println("❌ GigaChat error in processMessageWithFunctionCalling: ${e}")
                throw ChatException("Error calling GigaChat LLM", e)
            }

            // Accumulate token usage
            totalUsage = Usage(
                promptTokens = totalUsage.promptTokens + response.usage.promptTokens,
                completionTokens = totalUsage.completionTokens + response.usage.completionTokens,
                totalTokens = totalUsage.totalTokens + response.usage.totalTokens,
                precachedPromptTokens = totalUsage.precachedPromptTokens + response.usage.precachedPromptTokens
            )

            val choice = response.choices.firstOrNull()
            val message = choice?.message

            // LOG: Show GigaChat response
            println("\n" + "=".repeat(80))
            println("📥 RESPONSE FROM GIGACHAT")
            println("=".repeat(80))
            println("FINISH REASON: ${choice?.finishReason}")
            println("MESSAGE ROLE: ${message?.role}")
            println("MESSAGE CONTENT: ${message?.content}")
            println("FUNCTION CALL: ${message?.functionCall?.name}")
            if (message?.functionCall != null) {
                println("FUNCTION ARGUMENTS: ${message.functionCall.arguments}")
            }
            println("=".repeat(80) + "\n")

            // Check if model wants to call a function
            if (choice?.finishReason == "function_call" && message?.functionCall != null) {
                val functionCall = message.functionCall!!
                val functionName = functionCall.name

                println("📞 Function call requested: $functionName")
                functionCallsLog.add("Called: $functionName")

                // Parse arguments
                val argsMap = try {
                    when (val args = functionCall.arguments) {
                        is JsonObject -> args.toMap().mapValues { (_, v) ->
                            when (v) {
                                is JsonPrimitive -> v.contentOrNull ?: v.toString()
                                else -> v.toString()
                            }
                        }
                        is JsonPrimitive -> {
                            // Try to parse as JSON object string
                            val argsStr = args.content
                            if (argsStr.startsWith("{")) {
                                Json.parseToJsonElement(argsStr).jsonObject.toMap().mapValues { (_, v) ->
                                    when (v) {
                                        is JsonPrimitive -> v.contentOrNull ?: v.toString()
                                        else -> v.toString()
                                    }
                                }
                            } else {
                                emptyMap()
                            }
                        }
                        else -> emptyMap()
                    }
                } catch (e: Exception) {
                    println("⚠️ Failed to parse function arguments: ${e.message}")
                    emptyMap()
                }

                // Execute function
                val result = try {
                    functionExecutor(functionName, argsMap)
                } catch (e: Exception) {
                    println("❌ Function execution error: ${e.message}")
                    // Already JSON format for errors
                    "{\"error\": \"${e.message}\"}"
                }

                println("✅ Function result: ${result.take(100)}...")

                // Truncate result if too long (to avoid 413 Request Entity Too Large)
                val truncatedResult = if (result.length > 1500) {
                    result.take(1500) + "\n\n... (truncated due to length, showing first 1500 chars)"
                } else {
                    result
                }

                if (result.length > 1500) {
                    println("⚠️ Function result truncated: ${result.length} -> 1500 chars")
                }

                // GigaChat requires function results to be valid JSON
                // Wrap plain text results in JSON object
                val jsonResult = if (truncatedResult.trim().startsWith("{") || truncatedResult.trim().startsWith("[")) {
                    // Already JSON
                    truncatedResult
                } else {
                    // Plain text - wrap in JSON object
                    // Escape quotes and newlines
                    val escapedResult = truncatedResult
                        .replace("\\", "\\\\")
                        .replace("\"", "\\\"")
                        .replace("\n", "\\n")
                        .replace("\r", "\\r")
                        .replace("\t", "\\t")
                    "{\"result\": \"$escapedResult\"}"
                }

                println("📋 JSON result for GigaChat: ${jsonResult.take(100)}...")

                // Add function call and result to history
                history.add(GigaChatMessage(
                    role = "assistant",
                    content = "",
                    functionCall = functionCall
                ))
                history.add(GigaChatMessage(
                    role = "function",
                    content = jsonResult,
                    name = functionName
                ))

                // Continue to next iteration
            } else {
                // Got final answer
                val answer = message?.content ?: "Empty response"
                println("✅ Final answer received (${answer.length} chars)")
                return Triple(answer, totalUsage, functionCallsLog)
            }
        }

        // Max iterations reached
        println("⚠️ Max iterations ($maxIterations) reached")
        return Triple("Max function calling iterations reached", totalUsage, functionCallsLog)
    }

    /**
     * Обрабатывает сообщение пользователя и возвращает ответ
     *
     * @param chatId идентификатор чата
     * @param userMessage текст сообщения пользователя
     * @param systemRole системный промпт для чата
     * @param temperature температура модели (0.0 - 1.0)
     * @param model название модели GigaChat
     * @return ответ с текстом и информацией об использовании токенов
     */
    suspend fun processMessage(
        chatId: Long,
        userMessage: String,
        systemRole: String,
        temperature: Float,
        model: String = "GigaChat"
    ): ChatResponse {
        // Получаем или создаем историю для текущего чата
        val history = loadOrCreateHistory(chatId, systemRole)

        // Добавляем сообщение пользователя в историю
        history.add(GigaChatMessage(role = "user", content = userMessage))

        // Получаем ответ от GigaChat (без MCP функций)
        val modelResponse = try {
            println("📤 Вызов gigaClient.chatCompletion...")
            gigaClient.chatCompletion(
                model = model,
                messages = history,
                temperature = temperature
            )
        } catch (e: Exception) {
            println("GigaChat error: ${e}")
            throw ChatException("Ошибка при обращении к GigaChat LLM", e)
        }

        val choice = modelResponse.choices.firstOrNull()
            ?: throw ChatException("Пустой ответ от модели")

        val assistantMessage = choice.message.content

        // Добавляем ответ ассистента в историю
        history.add(GigaChatMessage(role = "assistant", content = assistantMessage))

        // Сохраняем историю
        historyManager.saveHistory(chatId, history)

        // Проверяем, нужна ли суммаризация
        val summaryMessage = if (history.size > 20) {
            summarizeHistory(chatId, history, systemRole, model)
        } else {
            null
        }

        // Обрезаем текст до лимита Telegram (3800 символов)
        val truncatedText = if (assistantMessage.length > 3800) {
            assistantMessage.take(3799) + "..."
        } else {
            assistantMessage
        }

        return ChatResponse(
            text = truncatedText,
            tokenUsage = TokenUsage(
                promptTokens = modelResponse.usage.promptTokens,
                completionTokens = modelResponse.usage.completionTokens,
                totalTokens = modelResponse.usage.totalTokens
            ),
            temperature = temperature,
            summaryMessage = summaryMessage,
            toolsUsed = false
        )
    }

    /**
     * Загружает существующую историю или создает новую с системным промптом
     */
    private fun loadOrCreateHistory(chatId: Long, systemRole: String): MutableList<GigaChatMessage> {
        val loadedHistory = historyManager.loadHistory(chatId)
        return if (loadedHistory.isNotEmpty()) {
            println("Загружена существующая история для чата $chatId")
            loadedHistory
        } else {
            println("Создана новая история для чата $chatId")
            mutableListOf(GigaChatMessage(role = "system", content = systemRole))
        }
    }

    /**
     * Выполняет суммаризацию истории когда она превышает 20 сообщений
     * Возвращает текст краткого описания для отправки пользователю
     */
    private suspend fun summarizeHistory(
        chatId: Long,
        history: MutableList<GigaChatMessage>,
        systemRole: String,
        model: String
    ): String {
        println("Запускаем процесс суммаризации для чата $chatId")

        val summarySystemPrompt = GigaChatMessage(
            role = "system",
            content = "Ты - мастер пересказа. Кратко (до 3000 символов) опиши суть этого диалога, только факты без воды. Без примеров кода"
        )

        val summaryUserPrompt = GigaChatMessage(
            role = "user",
            content = history.joinToString("\n") { "${it.role}: ${it.content}" }
        )

        val summaryRequest = listOf(summarySystemPrompt, summaryUserPrompt)

        val modelAnswer = try {
            gigaClient.chatCompletion(
                model = model,
                messages = summaryRequest,
                temperature = 0.0F
            )
        } catch (e: Exception) {
            println("Ошибка при суммаризации: ${e}")
            throw ChatException("Ошибка при суммаризации истории", e)
        }

        val chatSummary = modelAnswer.choices.firstOrNull()?.message?.content
            ?: throw ChatException("Пустой ответ при суммаризации")

        println("Получена суммаризация: $chatSummary")

        // Создаем новый системный промпт с контекстом
        val newSystemMessage = buildString {
            appendLine(systemRole)
            appendLine("Предыдущий контекст:")
            appendLine(chatSummary)
        }

        val newSystemPrompt = GigaChatMessage(role = "system", content = newSystemMessage)

        // Очищаем историю и добавляем новый системный промпт
        history.clear()
        history.add(newSystemPrompt)

        // Сохраняем обновленную историю
        historyManager.saveHistory(chatId, history)

        return "Диалог из 10 сообщений, выполнена суммаризация.\n\nКраткое описание диалога:\n$chatSummary"
    }

    /**
     * Обновляет системный промпт для указанного чата
     */
    fun updateSystemRole(chatId: Long, newSystemRole: String) {
        val history = historyManager.loadHistory(chatId).takeIf { it.isNotEmpty() }
            ?: mutableListOf(GigaChatMessage(role = "system", content = newSystemRole))

        // Обновляем системный промпт (первое сообщение в истории)
        history[0] = GigaChatMessage(role = "system", content = newSystemRole)
        historyManager.saveHistory(chatId, history)

        println("Системный промпт обновлен для чата $chatId")
    }

    /**
     * Очищает историю для указанного чата
     */
    fun clearHistory(chatId: Long): Boolean {
        return historyManager.clearHistory(chatId)
    }
}

/**
 * Ответ от ChatOrchestrator на обработку сообщения
 */
data class ChatResponse(
    val text: String,
    val tokenUsage: TokenUsage,
    val temperature: Float,
    val summaryMessage: String? = null,
    val toolsUsed: Boolean = false
)

/**
 * Информация об использовании токенов
 */
data class TokenUsage(
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int
)

/**
 * Исключение при работе с чатом
 */
class ChatException(message: String, cause: Throwable? = null) : Exception(message, cause)
