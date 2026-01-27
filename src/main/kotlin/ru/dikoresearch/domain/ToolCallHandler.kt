package ru.dikoresearch.domain

import GigaChatFunction
import GigaChatFunctionCall
import GigaChatFunctionParameters
import GigaChatPropertySchema
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import kotlinx.serialization.json.Json
import ru.dikoresearch.infrastructure.mcp.McpService

/**
 * Обработчик вызовов инструментов от модели GigaChat через MCP
 * Преобразует MCP инструменты в формат GigaChat functions и выполняет их вызовы
 */
class ToolCallHandler(
    private val mcpService: McpService
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Получает список доступных инструментов от MCP сервера и преобразует их в GigaChat functions
     */
    suspend fun getAvailableFunctions(): List<GigaChatFunction> {
        if (!mcpService.isAvailable()) {
            println("MCP сервис недоступен, возвращаем пустой список инструментов")
            return emptyList()
        }

        return try {
            val toolsResult = mcpService.listTools()
            val functions = toolsResult.tools.map { tool ->
                convertMcpToolToGigaChatFunction(tool)
            }
            println("Получено ${functions.size} инструментов от MCP сервера")
            functions
        } catch (e: Exception) {
            println("Ошибка при получении инструментов: ${e.message}")
            emptyList()
        }
    }

    /**
     * Выполняет вызов инструмента через MCP сервер
     * @param functionCall вызов функции от модели
     * @return результат выполнения инструмента с визуальной меткой
     */
    suspend fun executeFunctionCall(functionCall: GigaChatFunctionCall): ToolExecutionResult {
        val toolName = functionCall.name
        val argumentsJsonElement = functionCall.arguments

        println("Выполняем вызов инструмента: $toolName")
        println("Аргументы (JsonElement): $argumentsJsonElement")

        return try {
            // Парсим аргументы из JsonElement в Map
            val arguments = parseArgumentsToMap(argumentsJsonElement)
            println("Распарсенные аргументы (Map): $arguments")

            // Вызываем MCP инструмент
            val result = mcpService.callTool(toolName, arguments)

            // Форматируем результат с визуальной меткой
            val formattedResult = formatToolResult(toolName, result)

            ToolExecutionResult(
                success = true,
                result = formattedResult,
                toolName = toolName
            )
        } catch (e: Exception) {
            println("Ошибка при выполнении инструмента $toolName: ${e.message}")
            e.printStackTrace()

            ToolExecutionResult(
                success = false,
                result = "[MCP Tool: $toolName] Ошибка: ${e.message}",
                toolName = toolName,
                error = e.message
            )
        }
    }

    /**
     * Преобразует MCP Tool в GigaChat Function
     */
    private fun convertMcpToolToGigaChatFunction(tool: Tool): GigaChatFunction {
        println("\n=== Конвертация MCP Tool: ${tool.name} ===")
        println("Tool description: ${tool.description}")
        println("Tool inputSchema type: ${tool.inputSchema?.javaClass?.name}")
        println("Tool inputSchema: $tool.inputSchema}")

        val properties = mutableMapOf<String, GigaChatPropertySchema>()
        val required = mutableListOf<String>()

        // Преобразуем inputSchema в параметры функции
        tool.inputSchema?.let { schema ->
            try {
                // Извлекаем properties из схемы (schema это Map)
                val schemaMap = schema as? Map<*, *>
                println("SchemaMap: $schemaMap")

                val schemaProperties = schemaMap?.get("properties") as? Map<*, *>
                println("SchemaProperties: $schemaProperties")

                schemaProperties?.forEach { (key, value) ->
                    val propMap = value as? Map<*, *>
                    if (propMap != null) {
                        val propertySchema = GigaChatPropertySchema(
                            type = propMap["type"] as? String ?: "string",
                            description = propMap["description"] as? String,
                            enum = (propMap["enum"] as? List<*>)?.mapNotNull { it as? String }
                        )
                        properties[key.toString()] = propertySchema
                        println("  Добавлено свойство: $key -> $propertySchema")
                    }
                }

                // Извлекаем required поля
                val schemaRequired = schemaMap?.get("required") as? List<*>
                println("SchemaRequired: $schemaRequired")
                schemaRequired?.forEach { field ->
                    required.add(field.toString())
                }
            } catch (e: Exception) {
                println("Ошибка преобразования схемы для ${tool.name}: ${e.message}")
                e.printStackTrace()
            }
        }

        val gigaChatFunction = GigaChatFunction(
            name = tool.name,
            description = tool.description ?: "MCP инструмент ${tool.name}",
            parameters = GigaChatFunctionParameters(
                type = "object",
                properties = properties,
                required = if (required.isNotEmpty()) required else null
            )
        )

        println("Результат конвертации: properties=${properties.size}, required=${required.size}")
        println("=== Конец конвертации ===\n")

        return gigaChatFunction
    }

    /**
     * Парсит JsonElement аргументов в Map
     */
    private fun parseArgumentsToMap(argumentsJsonElement: kotlinx.serialization.json.JsonElement): Map<String, Any> {
        return try {
            // Если это JsonObject - преобразуем в Map
            val jsonObject = when (argumentsJsonElement) {
                is kotlinx.serialization.json.JsonObject -> argumentsJsonElement
                is kotlinx.serialization.json.JsonPrimitive -> {
                    // Если это строка с JSON - парсим
                    if (argumentsJsonElement.isString) {
                        json.parseToJsonElement(argumentsJsonElement.content) as? kotlinx.serialization.json.JsonObject
                    } else null
                }
                else -> null
            } ?: return emptyMap()

            jsonObject.mapValues { (_, value) ->
                when (value) {
                    is kotlinx.serialization.json.JsonPrimitive -> {
                        when {
                            value.isString -> value.content
                            value.content == "true" || value.content == "false" -> value.content.toBoolean()
                            value.content.toDoubleOrNull() != null -> value.content.toDouble()
                            else -> value.content
                        }
                    }
                    else -> value.toString()
                }
            }
        } catch (e: Exception) {
            println("Ошибка парсинга аргументов: ${e.message}")
            emptyMap()
        }
    }

    /**
     * Форматирует результат вызова инструмента в JSON для GigaChat
     * GigaChat требует, чтобы role=function содержал валидный JSON
     */
    private fun formatToolResult(toolName: String, result: CallToolResult): String {
        val contentText = result.content.joinToString("\n") { content ->
            // Проверяем тип контента и извлекаем данные
            val contentStr = content.toString()
            when {
                contentStr.contains("text=") -> {
                    // Извлекаем текст из строкового представления
                    contentStr.substringAfter("text=").substringBefore(",").trim()
                }
                contentStr.contains("resource=") -> {
                    "Resource: ${contentStr.substringAfter("uri=").substringBefore(")").trim()}"
                }
                else -> contentStr
            }
        }

        // Экранируем специальные символы для JSON
        val escapedText = contentText
            .replace("\\", "\\\\")  // Обратный слэш
            .replace("\"", "\\\"")  // Кавычки
            .replace("\n", "\\n")   // Переносы строк
            .replace("\r", "\\r")   // Возврат каретки
            .replace("\t", "\\t")   // Табуляция

        // Возвращаем валидный JSON объект для GigaChat
        return """{"result": "$escapedText"}"""
    }
}

/**
 * Результат выполнения инструмента
 */
data class ToolExecutionResult(
    val success: Boolean,
    val result: String,
    val toolName: String,
    val error: String? = null
)
