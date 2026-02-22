package ru.dikoresearch.domain

import GigaChatMessage
import OllamaToolCall
import OllamaToolCallFunction
import OllamaToolFunction
import OllamaTool
import kotlinx.serialization.json.*
import ru.dikoresearch.domain.valueobjects.ChatId
import ru.dikoresearch.domain.valueobjects.SystemPrompt
import ru.dikoresearch.domain.valueobjects.Temperature
import ru.dikoresearch.infrastructure.http.OllamaClient
import ru.dikoresearch.infrastructure.mcp.StdioMcpService
import ru.dikoresearch.infrastructure.persistence.ChatHistoryManager
import ru.dikoresearch.infrastructure.persistence.OllamaModel

/**
 * Data class для ответа от модели
 */
data class ModelResponse(
    val modelName: String,
    val content: String,
    val generationTimeMs: Long
)

/**
 * Оркестратор для работы с qwen2.5:1.5b и MCP tool calling
 */
class MultiModelChatOrchestrator(
    private val qwen25Client: OllamaClient,
    private val historyManager: ChatHistoryManager,
    private val artifactsMcpService: StdioMcpService? = null
) {
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        /** Максимум итераций цикла tool calling */
        const val MAX_TOOL_CALLS = 5

        /**
         * Системный промпт для анализа данных пневморессор.
         * Описывает структуру данных и инструменты MCP.
         */
        val ARTIFACTS_SYSTEM_PROMPT = """
You are an analytical assistant specializing in pneumatic spring (пневморессора / air spring) measurement data analysis.

## Data Overview
You have access to measurement protocol data stored as CSV files. Each file contains geometric parameter measurements of pneumatic springs taken on specific dates. Each session measures 10 samples.

## Measurement Columns
- **h** — spring height at no pressure (мм)
- **D** — spring diameter at no pressure (мм)
- **h_compressed** — height when mechanically compressed (hсж, мм)
- **D_compressed** — diameter when mechanically compressed (Dсж, мм)
- **h_8bar** — height when inflated to 8 bar pressure (h 8 бар, мм)
- **D_8bar** — diameter when inflated to 8 bar pressure (D 8 бар, мм)

## Available Models
Use `list_artifact_models` to get the current list. Common models: 140_2, 140D2, 160D1, 160D2, 180D1, 180D2, 250.

## Tool Usage Rules
1. **ALWAYS** use tools to retrieve actual measurement data — never invent or estimate values.
2. For statistical questions (mean, average, min, max), use `calculate_column_stats`.
3. For questions about a specific date, use `get_measurements_by_date`.
4. When exploring available data, start with `list_artifact_models`, then `list_model_dates`.
5. After getting tool results, provide a clear, concise answer based on the actual data.

## Example Queries & Tool Calls
- "What is the mean 8 bar height for 180D1?" → `calculate_column_stats(model="180D1", column="h_8bar")`
- "What models are available?" → `list_artifact_models()`
- "Show measurements for 160D1 from November 2025" → first `list_model_dates(model="160D1")`, then `get_measurements_by_date(model="160D1", date="...")`
- "Compare h_8bar for 180D1 and 160D1" → two calls to `calculate_column_stats`

Respond in the same language the user uses. Provide numerical results with appropriate context (units, date range, sample count).
        """.trimIndent()
    }

    /**
     * Обрабатывает сообщение пользователя через Qwen 2.5.
     * Поддерживает цикл вызова MCP инструментов (tool calling loop).
     */
    suspend fun processMessage(
        chatId: ChatId,
        userMessage: String,
        systemRole: SystemPrompt,
        temperature: Temperature
    ): List<ModelResponse> {
        return listOf(
            processWithModel(
                model = OllamaModel.QWEN25,
                client = qwen25Client,
                chatId = chatId,
                userMessage = userMessage,
                systemRole = systemRole,
                temperature = temperature
            )
        )
    }

    private suspend fun processWithModel(
        model: OllamaModel,
        client: OllamaClient,
        chatId: ChatId,
        userMessage: String,
        systemRole: SystemPrompt,
        temperature: Temperature
    ): ModelResponse {
        // 1. Загружаем историю
        val history = historyManager.loadHistory(chatId.value, model)

        // 2. Обновляем системный промпт
        if (history.isEmpty() || history.first().role != "system") {
            history.add(0, GigaChatMessage(role = "system", content = systemRole.value))
        } else {
            history[0] = GigaChatMessage(role = "system", content = systemRole.value)
        }

        // 3. Добавляем сообщение пользователя
        history.add(GigaChatMessage(role = "user", content = userMessage))

        val startTime = System.currentTimeMillis()

        // 4. Загружаем MCP инструменты (если сервис доступен)
        val ollamaTools = loadOllamaTools()

        // 5. Цикл tool calling
        var lastResponseContent = ""
        var lastDurationNs = 0L
        var iteration = 0

        while (iteration < MAX_TOOL_CALLS) {
            val response = try {
                client.chatCompletion(
                    messages = history,
                    temperature = temperature.value,
                    tools = ollamaTools.takeIf { it.isNotEmpty() }
                )
            } catch (e: Exception) {
                println("❌ Ollama error on iteration $iteration: ${e.message}")
                val duration = System.currentTimeMillis() - startTime
                historyManager.saveHistory(chatId.value, model, history)
                return ModelResponse(
                    modelName = model.displayName,
                    content = "❌ Ошибка: ${e.message}",
                    generationTimeMs = duration
                )
            }

            lastDurationNs = response.total_duration
            val assistantMessage = response.message
            val toolCalls = assistantMessage.toolCalls

            // Добавляем ответ ассистента в историю
            history.add(assistantMessage)

            if (toolCalls.isNullOrEmpty()) {
                // Финальный ответ — выходим из цикла
                lastResponseContent = assistantMessage.content
                break
            }

            // Есть вызовы инструментов — выполняем их
            println("🔧 Tool calls requested (iteration ${iteration + 1}): ${toolCalls.map { it.function.name }}")

            for (toolCall in toolCalls) {
                val toolName = toolCall.function.name
                val toolArgs = parseToolArguments(toolCall.function)

                println("   📞 Calling tool: $toolName with args: $toolArgs")

                val toolResult = try {
                    val callResponse = artifactsMcpService!!.callTool(toolName, toolArgs)
                    val text = callResponse.content.joinToString("\n") { it.text }
                    if (callResponse.isError) "Tool error: $text" else text
                } catch (e: Exception) {
                    "Tool execution failed: ${e.message}"
                }

                println("   📥 Tool result: ${toolResult.take(200)}")

                // Добавляем результат инструмента в историю
                history.add(GigaChatMessage(role = "tool", content = toolResult))
            }

            iteration++
        }

        // Сохраняем историю
        historyManager.saveHistory(chatId.value, model, history)

        val generationTimeMs = if (lastDurationNs > 0) lastDurationNs / 1_000_000 else System.currentTimeMillis() - startTime

        return ModelResponse(
            modelName = model.displayName,
            content = lastResponseContent.ifBlank { "Пустой ответ" },
            generationTimeMs = generationTimeMs
        )
    }

    /**
     * Загружает доступные MCP инструменты и конвертирует в формат Ollama
     */
    private suspend fun loadOllamaTools(): List<OllamaTool> {
        if (artifactsMcpService == null || !artifactsMcpService.isAvailable()) return emptyList()

        return try {
            artifactsMcpService.listTools().map { mcpTool ->
                OllamaTool(
                    type = "function",
                    function = OllamaToolFunction(
                        name = mcpTool.name,
                        description = mcpTool.description,
                        parameters = mcpTool.inputSchema
                    )
                )
            }
        } catch (e: Exception) {
            println("⚠️ Could not load MCP tools: ${e.message}")
            emptyList()
        }
    }

    /**
     * Конвертирует аргументы tool call из JsonElement в Map<String, Any>
     * Обрабатывает и JsonObject, и строку-JSON (некоторые модели возвращают строку)
     */
    private fun parseToolArguments(toolCallFunction: OllamaToolCallFunction): Map<String, Any> {
        val argsElement = toolCallFunction.arguments
        val argsObject: JsonObject = when (argsElement) {
            is JsonObject -> argsElement
            is JsonPrimitive -> {
                // Некоторые модели возвращают аргументы как JSON-строку
                try {
                    json.parseToJsonElement(argsElement.content).jsonObject
                } catch (e: Exception) {
                    return emptyMap()
                }
            }
            else -> return emptyMap()
        }

        return argsObject.entries.associate { (key, value) ->
            key to when (value) {
                is JsonPrimitive -> value.content
                else -> value.toString()
            }
        }
    }

    /**
     * Очищает историю для всех моделей
     */
    fun clearHistory(chatId: ChatId): Boolean {
        return historyManager.clearAllHistories(chatId.value)
    }
}
