package ru.dikoresearch.domain

import GigaChatMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import ru.dikoresearch.infrastructure.http.GigaChatClient
import ru.dikoresearch.infrastructure.mcp.McpService
import ru.dikoresearch.infrastructure.persistence.ChatHistoryManager

/**
 * Оркестратор чат-логики - чистая бизнес-логика без зависимости от Telegram
 * Отвечает за обработку сообщений пользователя, управление историей и суммаризацию
 */
class ChatOrchestrator(
    private val gigaClient: GigaChatClient,
    private val historyManager: ChatHistoryManager,
    private val mcpService: McpService?
) {
    private val toolCallHandler = mcpService?.let { ToolCallHandler(it) }
    /**
     * Обрабатывает сообщение пользователя и возвращает ответ
     *
     * @param chatId идентификатор чата
     * @param userMessage текст сообщения пользователя
     * @param systemRole системный промпт для чата
     * @param temperature температура модели (0.0 - 1.0)
     * @param model название модели GigaChat
     * @param enableMcp включить поддержку MCP инструментов (по умолчанию true)
     * @return ответ с текстом и информацией об использовании токенов
     */
    suspend fun processMessage(
        chatId: Long,
        userMessage: String,
        systemRole: String,
        temperature: Float,
        model: String = "GigaChat",
        enableMcp: Boolean = true
    ): ChatResponse {
        // Получаем или создаем историю для текущего чата
        val history = loadOrCreateHistory(chatId, systemRole)

        // Добавляем сообщение пользователя в историю
        history.add(GigaChatMessage(role = "user", content = userMessage))

        // Получаем доступные MCP функции если включено
        val availableFunctions = if (enableMcp && mcpService?.isAvailable() == true && toolCallHandler != null) {
            toolCallHandler.getAvailableFunctions()
        } else {
            null
        }

        // Основной цикл обработки с поддержкой tool calling
        var continueProcessing = true
        var finalAssistantMessage = ""
        val toolExecutionResults = mutableListOf<Pair<String, String>>() // Пара (имя_инструмента, результат)
        var totalPromptTokens = 0
        var totalCompletionTokens = 0
        var totalTokensUsed = 0
        var iterationCount = 0
        val maxIterations = 5 // Ограничиваем количество итераций tool calling

        while (continueProcessing && iterationCount < maxIterations) {
            iterationCount++

            // Получаем ответ от GigaChat
            val modelResponse = try {
                gigaClient.chatCompletion(
                    model = model,
                    messages = history,
                    temperature = temperature,
                    functions = availableFunctions,
                    functionCall = if (availableFunctions != null) "auto" else null
                )
            } catch (e: Exception) {
                println("GigaChat error: ${e}")
                throw ChatException("Ошибка при обращении к GigaChat LLM", e)
            }

            // Накапливаем токены
            totalPromptTokens += modelResponse.usage.promptTokens
            totalCompletionTokens += modelResponse.usage.completionTokens
            totalTokensUsed += modelResponse.usage.totalTokens

            val choice = modelResponse.choices.firstOrNull()
                ?: throw ChatException("Пустой ответ от модели")

            val message = choice.message

            // Проверяем, есть ли function_call в ответе
            if (message.functionCall != null && choice.finishReason == "function_call" && toolCallHandler != null) {
                println("Модель запросила вызов функции: ${message.functionCall.name}")

                // Выполняем вызов функции через MCP
                val executionResult = toolCallHandler.executeFunctionCall(message.functionCall)

                // Сохраняем имя инструмента и результат для отображения пользователю
                toolExecutionResults.add(executionResult.toolName to executionResult.result)

                // Добавляем в историю запрос функции от ассистента
                history.add(
                    GigaChatMessage(
                        role = "assistant",
                        content = message.content,
                        functionCall = message.functionCall
                    )
                )

                // Добавляем в историю результат функции
                history.add(
                    GigaChatMessage(
                        role = "function",
                        content = if (executionResult.success) {
                            executionResult.result
                        } else {
                            "Ошибка при выполнении функции: ${executionResult.error}"
                        }
                    )
                )

                // Продолжаем цикл, чтобы модель обработала результат
                continueProcessing = true
            } else {
                // Модель вернула обычный текстовый ответ
                finalAssistantMessage = message.content

                // Добавляем ответ ассистента в историю
                history.add(GigaChatMessage(role = "assistant", content = finalAssistantMessage))

                continueProcessing = false
            }
        }

        if (iterationCount >= maxIterations) {
            println("Достигнут лимит итераций tool calling: $maxIterations")
        }

        // Сохраняем историю
        historyManager.saveHistory(chatId, history)

        // Проверяем, нужна ли суммаризация
        val summaryMessage = if (history.size > 20) {
            summarizeHistory(chatId, history, systemRole, model)
        } else {
            null
        }

        // Формируем итоговый ответ с результатами tool calls
        val fullResponse = buildString {
            if (toolExecutionResults.isNotEmpty()) {
                appendLine("🔧 Использованы MCP инструменты:")
                appendLine()
                toolExecutionResults.forEach { (toolName, jsonResult) ->
                    // Извлекаем текст из JSON результата {"result": "text"}
                    val actualResult = try {
                        val jsonElement = Json.parseToJsonElement(jsonResult)
                        jsonElement.jsonObject["result"]?.jsonPrimitive?.content ?: jsonResult
                    } catch (e: Exception) {
                        // Если не JSON или ошибка парсинга - используем как есть
                        jsonResult
                    }

                    appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                    appendLine("🛠️ Инструмент: $toolName")
                    appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                    appendLine(actualResult)
                    appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                    appendLine()
                }
                appendLine("💬 Итоговый ответ:")
                appendLine()
            }
            append(finalAssistantMessage)
        }

        // Обрезаем текст до лимита Telegram (3800 символов)
        val truncatedText = if (fullResponse.length > 3800) {
            fullResponse.take(3799) + "..."
        } else {
            fullResponse
        }

        return ChatResponse(
            text = truncatedText,
            tokenUsage = TokenUsage(
                promptTokens = totalPromptTokens,
                completionTokens = totalCompletionTokens,
                totalTokens = totalTokensUsed
            ),
            temperature = temperature,
            summaryMessage = summaryMessage,
            toolsUsed = toolExecutionResults.isNotEmpty()
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
