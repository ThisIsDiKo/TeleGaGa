package ru.dikoresearch.domain

import GigaChatFunction
import GigaChatFunctionCall
import GigaChatFunctionParameters
import GigaChatPropertySchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import ru.dikoresearch.infrastructure.mcp.HttpMcpService

/**
 * Обработчик вызовов инструментов от модели GigaChat через MCP
 * Преобразует MCP инструменты в формат GigaChat functions и выполняет их вызовы
 */
class ToolCallHandler(
    private val mcpService: HttpMcpService
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
            val tools = mcpService.listTools()
            val functions = tools.map { tool ->
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

            // Логируем результат для отладки
            println("✅ Результат от MCP инструмента '$toolName':")
            result.content.forEach { content ->
                println("   Content: $content")
            }

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

            // Возвращаем ошибку в JSON формате для GigaChat
            val errorMsg = e.message ?: "Unknown error"
            val escapedError = errorMsg
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")

            ToolExecutionResult(
                success = false,
                result = """{"error": "$escapedError"}""",
                toolName = toolName,
                error = e.message
            )
        }
    }

    /**
     * Преобразует MCP Tool в GigaChat Function
     */
    private fun convertMcpToolToGigaChatFunction(tool: HttpMcpService.Tool): GigaChatFunction {
        println("\n=== Конвертация MCP Tool: ${tool.name} ===")
        println("Tool description: ${tool.description}")
        println("Tool inputSchema: ${tool.inputSchema}")

        val properties = mutableMapOf<String, GigaChatPropertySchema>()
        val required = mutableListOf<String>()

        // Преобразуем inputSchema в параметры функции
        try {
            val schema = tool.inputSchema
            val schemaProperties = schema["properties"] as? JsonObject
            println("SchemaProperties: $schemaProperties")

            schemaProperties?.forEach { (key, value) ->
                // value это JsonElement, парсим его в Map
                val propMap = when (value) {
                    is JsonObject -> {
                        value.entries.associate { it.key to it.value }
                    }
                    else -> null
                }

                if (propMap != null) {
                    val typeElement = propMap["type"] as? JsonPrimitive
                    val descElement = propMap["description"] as? JsonPrimitive
                    val enumElement = propMap["enum"] as? JsonArray

                    val propertySchema = GigaChatPropertySchema(
                        type = typeElement?.content ?: "string",
                        description = descElement?.content,
                        enum = enumElement?.mapNotNull { (it as? JsonPrimitive)?.content }
                    )
                    properties[key] = propertySchema
                    println("  Добавлено свойство: $key -> $propertySchema")
                }
            }

            // Извлекаем required поля
            val schemaRequired = schema["required"] as? JsonArray
            println("SchemaRequired: $schemaRequired")
            schemaRequired?.forEach { field ->
                (field as? JsonPrimitive)?.content?.let { required.add(it) }
            }
        } catch (e: Exception) {
            println("Ошибка преобразования схемы для ${tool.name}: ${e.message}")
            e.printStackTrace()
        }

        val gigaChatFunction = GigaChatFunction(
            name = tool.name,
            description = tool.description,
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
    private fun formatToolResult(toolName: String, result: HttpMcpService.CallToolResponse): String {
        val contentText = result.content.joinToString("\n") { content ->
            content.text
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
