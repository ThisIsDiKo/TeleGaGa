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
import ru.dikoresearch.infrastructure.mcp.StdioMcpService

/**
 * Обработчик вызовов инструментов от модели GigaChat через MCP
 * Преобразует MCP инструменты в формат GigaChat functions и выполняет их вызовы
 * Поддерживает как HTTP, так и Stdio MCP серверы
 */
class ToolCallHandler(
    private val httpMcpService: HttpMcpService? = null,
    private val stdioMcpService: StdioMcpService? = null
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Получает список доступных инструментов от всех MCP серверов и преобразует их в GigaChat functions
     */
    suspend fun getAvailableFunctions(): List<GigaChatFunction> {
        val allFunctions = mutableListOf<GigaChatFunction>()

        // Получаем функции от HTTP MCP сервера
        if (httpMcpService?.isAvailable() == true) {
            try {
                val httpTools = httpMcpService.listTools()
                val httpFunctions = httpTools.map { tool ->
                    convertHttpMcpToolToGigaChatFunction(tool)
                }
                allFunctions.addAll(httpFunctions)
                println("Получено ${httpFunctions.size} инструментов от HTTP MCP сервера")
            } catch (e: Exception) {
                println("Ошибка при получении HTTP MCP инструментов: ${e.message}")
            }
        }

        // Получаем функции от Stdio MCP сервера
        if (stdioMcpService?.isAvailable() == true) {
            try {
                val stdioTools = stdioMcpService.listTools()
                val stdioFunctions = stdioTools.map { tool ->
                    convertStdioMcpToolToGigaChatFunction(tool)
                }
                allFunctions.addAll(stdioFunctions)
                println("Получено ${stdioFunctions.size} инструментов от Stdio MCP сервера")
            } catch (e: Exception) {
                println("Ошибка при получении Stdio MCP инструментов: ${e.message}")
            }
        }

        if (allFunctions.isEmpty()) {
            println("Нет доступных MCP сервисов, возвращаем пустой список инструментов")
        }

        // Фильтруем функции - оставляем только базовые для упрощения работы LLM
        val allowedFunctions = setOf(
            "run_container",      // Запуск контейнера (основная)
            "list_containers",    // Список контейнеров
            "stop_container",     // Остановка
            "remove_container",   // Удаление
            "fetch_container_logs" // Логи
        )

        val filteredFunctions = allFunctions.filter { it.name in allowedFunctions }
        println("Отфильтровано ${filteredFunctions.size} из ${allFunctions.size} функций: ${filteredFunctions.map { it.name }}")

        return filteredFunctions
    }

    /**
     * Выполняет вызов инструмента через MCP сервер (HTTP или Stdio)
     * @param functionCall вызов функции от модели
     * @return результат выполнения инструмента
     */
    suspend fun executeFunctionCall(functionCall: GigaChatFunctionCall): ToolExecutionResult {
        val toolName = functionCall.name
        val argumentsJsonElement = functionCall.arguments

        println("Выполняем вызов инструмента: $toolName")
        println("Аргументы (JsonElement): $argumentsJsonElement")

        return try {
            // Парсим аргументы из JsonElement в Map
            val rawArguments = parseArgumentsToMap(argumentsJsonElement)
            println("Распарсенные аргументы (Map): $rawArguments")

            // Очищаем аргументы от мусора
            val arguments = cleanArguments(toolName, rawArguments)
            println("Очищенные аргументы (Map): $arguments")

            // Пытаемся найти и вызвать инструмент сначала в HTTP, затем в Stdio MCP
            val result = when {
                httpMcpService?.isAvailable() == true -> {
                    try {
                        val httpResult = httpMcpService.callTool(toolName, arguments)
                        formatHttpToolResult(toolName, httpResult)
                    } catch (e: Exception) {
                        // Если инструмент не найден в HTTP, пробуем Stdio
                        if (stdioMcpService?.isAvailable() == true) {
                            val stdioResult = stdioMcpService.callTool(toolName, arguments)
                            formatStdioToolResult(toolName, stdioResult)
                        } else {
                            throw e
                        }
                    }
                }
                stdioMcpService?.isAvailable() == true -> {
                    val stdioResult = stdioMcpService.callTool(toolName, arguments)
                    formatStdioToolResult(toolName, stdioResult)
                }
                else -> throw IllegalStateException("No MCP service available")
            }

            // Логируем результат для отладки
            println("✅ Результат от MCP инструмента '$toolName': ${result.take(200)}...")

            ToolExecutionResult(
                success = true,
                result = result,
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
     * Преобразует HTTP MCP Tool в GigaChat Function
     */
    private fun convertHttpMcpToolToGigaChatFunction(tool: HttpMcpService.Tool): GigaChatFunction {
        val properties = mutableMapOf<String, GigaChatPropertySchema>()
        val required = mutableListOf<String>()

        // Преобразуем inputSchema в параметры функции
        try {
            val schema = tool.inputSchema
            val schemaProperties = schema["properties"] as? JsonObject

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

                    val originalType = typeElement?.content ?: "string"
                    val description = descElement?.content

                    // Если описание говорит о map/dictionary, создаём схему словаря с additionalProperties
                    val isDictionary = if (originalType == "string" && description != null) {
                        val lowerDesc = description.lowercase()
                        lowerDesc.contains("map") ||
                            lowerDesc.contains("dictionary") ||
                            lowerDesc.contains("dict ") ||
                            (lowerDesc.contains("key") && lowerDesc.contains("value"))
                    } else {
                        false
                    }

                    val propertySchema = if (isDictionary) {
                        GigaChatPropertySchema(
                            type = "object",
                            description = description,
                            enum = null,
                            items = null,
                            properties = emptyMap(), // ВАЖНО: пустой Map, а не null!
                            additionalProperties = GigaChatPropertySchema(
                                type = "string", // По умолчанию значения словаря - строки
                                description = null,
                                enum = null,
                                items = null,
                                properties = null,
                                additionalProperties = null
                            )
                        )
                    } else {
                        GigaChatPropertySchema(
                            type = originalType,
                            description = description,
                            enum = enumElement?.mapNotNull { (it as? JsonPrimitive)?.content },
                            items = null,
                            properties = null,
                            additionalProperties = null
                        )
                    }
                    properties[key] = propertySchema
                }
            }

            // Извлекаем required поля
            val schemaRequired = schema["required"] as? JsonArray
            schemaRequired?.forEach { field ->
                (field as? JsonPrimitive)?.content?.let { required.add(it) }
            }
        } catch (e: Exception) {
            println("Ошибка преобразования схемы для ${tool.name}: ${e.message}")
            e.printStackTrace()
        }

        return GigaChatFunction(
            name = tool.name,
            description = tool.description,
            parameters = GigaChatFunctionParameters(
                type = "object",
                properties = properties,
                required = if (required.isNotEmpty()) required else null
            )
        )
    }

    /**
     * Преобразует Stdio MCP Tool в GigaChat Function
     */
    private fun convertStdioMcpToolToGigaChatFunction(tool: StdioMcpService.Tool): GigaChatFunction {
        val properties = mutableMapOf<String, GigaChatPropertySchema>()
        val required = mutableListOf<String>()

        // Преобразуем inputSchema в параметры функции
        try {
            val schema = tool.inputSchema
            val schemaProperties = schema["properties"] as? JsonObject

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

                    val originalType = typeElement?.content ?: "string"
                    val description = descElement?.content

                    // Если описание говорит о map/dictionary, создаём схему словаря с additionalProperties
                    val isDictionary = if (originalType == "string" && description != null) {
                        val lowerDesc = description.lowercase()
                        lowerDesc.contains("map") ||
                            lowerDesc.contains("dictionary") ||
                            lowerDesc.contains("dict ") ||
                            (lowerDesc.contains("key") && lowerDesc.contains("value"))
                    } else {
                        false
                    }

                    val propertySchema = if (isDictionary) {
                        GigaChatPropertySchema(
                            type = "object",
                            description = description,
                            enum = null,
                            items = null,
                            properties = emptyMap(), // ВАЖНО: пустой Map, а не null!
                            additionalProperties = GigaChatPropertySchema(
                                type = "string", // По умолчанию значения словаря - строки
                                description = null,
                                enum = null,
                                items = null,
                                properties = null,
                                additionalProperties = null
                            )
                        )
                    } else {
                        GigaChatPropertySchema(
                            type = originalType,
                            description = description,
                            enum = enumElement?.mapNotNull { (it as? JsonPrimitive)?.content },
                            items = null,
                            properties = null,
                            additionalProperties = null
                        )
                    }
                    properties[key] = propertySchema
                }
            }

            // Извлекаем required поля
            val schemaRequired = schema["required"] as? JsonArray
            schemaRequired?.forEach { field ->
                (field as? JsonPrimitive)?.content?.let { required.add(it) }
            }
        } catch (e: Exception) {
            println("Ошибка преобразования схемы для ${tool.name}: ${e.message}")
            e.printStackTrace()
        }

        return GigaChatFunction(
            name = tool.name,
            description = tool.description,
            parameters = GigaChatFunctionParameters(
                type = "object",
                properties = properties,
                required = if (required.isNotEmpty()) required else null
            )
        )
    }

    /**
     * Очищает аргументы от мусора, который иногда добавляет LLM
     */
    private fun cleanArguments(toolName: String, arguments: Map<String, Any>): Map<String, Any> {
        println("🧹 Начинаем очистку аргументов для функции '$toolName'")
        val cleaned = arguments.toMutableMap()

        // Очистка строковых значений от мусора ($, переносы строк, лишние пробелы)
        cleaned.forEach { (key, value) ->
            if (value is String) {
                val cleanedValue = value
                    .replace(Regex("""\$,\s*"""), "")  // Убираем $, и пробелы
                    .replace(Regex("""\n\s*"""), "")   // Убираем переносы строк и отступы
                    .trim()

                if (cleanedValue != value) {
                    println("🧹 Очищен параметр '$key': '$value' → '$cleanedValue'")
                    cleaned[key] = cleanedValue
                }
            }
        }

        // Специальная обработка для функций запуска контейнеров
        if (toolName == "run_container" || toolName == "create_container" || toolName == "recreate_container") {
            // Удаляем параметр command если он пустой или содержит только мусор
            val command = cleaned["command"] as? String
            if (command.isNullOrBlank() || command.contains("docker\$") || command.contains("adapter")) {
                println("🧹 Удален некорректный параметр command: '$command'")
                cleaned.remove("command")
            }

            // Проверяем что ports не пустой для популярных образов
            val ports = cleaned["ports"]
            val image = cleaned["image"] as? String
            if (ports is Map<*, *> && ports.isEmpty() && image != null) {
                when {
                    image.contains("caddy") -> {
                        println("🔧 Добавлены порты для Caddy: 80, 443")
                        cleaned["ports"] = mapOf("80" to 80, "443" to 443)
                    }
                    image.contains("nginx") -> {
                        println("🔧 Добавлен порт для Nginx: 80")
                        cleaned["ports"] = mapOf("80" to 80)
                    }
                }
            }
        }

        return cleaned
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

            jsonObject.mapValues { (key, value) ->
                parseJsonElementToAny(value, key)
            }
        } catch (e: Exception) {
            println("Ошибка парсинга аргументов: ${e.message}")
            e.printStackTrace()
            emptyMap()
        }
    }

    /**
     * Рекурсивно парсит JsonElement в Any, поддерживая вложенные структуры
     */
    private fun parseJsonElementToAny(value: kotlinx.serialization.json.JsonElement, key: String = ""): Any {
        return when (value) {
            is kotlinx.serialization.json.JsonPrimitive -> {
                when {
                    value.isString -> {
                        val content = value.content
                        // Пытаемся распарсить строки, которые выглядят как JSON объекты или массивы
                        if ((content.trim().startsWith("{") && content.trim().endsWith("}")) ||
                            (content.trim().startsWith("[") && content.trim().endsWith("]"))) {
                            try {
                                println("Обнаружена JSON-подобная строка в параметре '$key': ${content.take(100)}...")
                                val parsed = json.parseToJsonElement(content)
                                parseJsonElementToAny(parsed, key)
                            } catch (e: Exception) {
                                println("Не удалось распарсить как JSON, оставляем как строку: ${e.message}")
                                content
                            }
                        } else {
                            content
                        }
                    }
                    value.content == "true" || value.content == "false" -> value.content.toBoolean()
                    value.content.toIntOrNull() != null -> value.content.toInt()
                    value.content.toDoubleOrNull() != null -> value.content.toDouble()
                    else -> value.content
                }
            }
            is kotlinx.serialization.json.JsonObject -> {
                // Рекурсивно обрабатываем вложенные объекты
                value.mapValues { (nestedKey, nestedValue) ->
                    parseJsonElementToAny(nestedValue, nestedKey)
                }
            }
            is kotlinx.serialization.json.JsonArray -> {
                // Обрабатываем массивы
                value.map { parseJsonElementToAny(it, key) }
            }
        }
    }

    /**
     * Форматирует результат вызова HTTP MCP инструмента в JSON для GigaChat
     * GigaChat требует, чтобы role=function содержал валидный JSON
     */
    private fun formatHttpToolResult(toolName: String, result: HttpMcpService.CallToolResponse): String {
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

    /**
     * Форматирует результат вызова Stdio MCP инструмента в JSON для GigaChat
     * GigaChat требует, чтобы role=function содержал валидный JSON
     */
    private fun formatStdioToolResult(toolName: String, result: StdioMcpService.CallToolResponse): String {
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
