package ru.dikoresearch.infrastructure.mcp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter

/**
 * Stdio-based MCP service for managing Python MCP servers via stdin/stdout protocol
 */
class StdioMcpService(
    private val serverConfigs: List<ServerConfig>
) {
    private val mutex = Mutex()
    private val processes = mutableMapOf<String, ProcessInfo>()
    private val serverTools = mutableMapOf<String, List<Tool>>()
    private var requestId = 0
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
        encodeDefaults = true  // Важно: включаем сериализацию дефолтных значений
    }

    data class ServerConfig(
        val name: String,
        val command: String,  // e.g., "/Users/user/.local/bin/mcp-server-docker"
        val args: List<String> = emptyList()
    )

    data class ProcessInfo(
        val process: Process,
        val writer: BufferedWriter,
        val reader: BufferedReader
    )

    @Serializable
    data class Tool(
        val name: String,
        val description: String,
        val inputSchema: JsonObject
    )

    @Serializable
    data class JsonRpcRequest(
        val jsonrpc: String = "2.0",
        val id: Int,
        val method: String,
        val params: JsonObject? = null
    )

    @Serializable
    data class JsonRpcResponse(
        val jsonrpc: String,
        val id: Int? = null,
        val result: JsonElement? = null,
        val error: JsonElement? = null
    )

    /**
     * Initialize all stdio MCP servers
     */
    suspend fun initialize() = mutex.withLock {
        println("🔄 Initializing Stdio MCP servers...")

        for (config in serverConfigs) {
            try {
                // Start Python process
                val processInfo = startServer(config)
                processes[config.name] = processInfo

                // Send initialize request
                val initResult = sendRequest(processInfo, "initialize", buildJsonObject {
                    put("protocolVersion", "2024-11-05")
                    put("capabilities", buildJsonObject {})
                    put("clientInfo", buildJsonObject {
                        put("name", "TeleGaGa")
                        put("version", "1.0.0")
                    })
                })

                println("   📡 ${config.name} initialized: ${initResult?.jsonObject?.get("result")}")

                // Send initialized notification
                sendNotification(processInfo, "notifications/initialized")

                // List tools
                val toolsResult = sendRequest(processInfo, "tools/list", buildJsonObject {})
                val tools = parseTools(toolsResult)
                serverTools[config.name] = tools

                println("   ✅ ${config.name}: ${tools.size} tools")
            } catch (e: Exception) {
                println("   ❌ Failed to initialize ${config.name}: ${e.message}")
                e.printStackTrace()
                throw e
            }
        }

        println("✅ All Stdio MCP servers initialized successfully")
    }

    /**
     * Start a stdio-based MCP server
     */
    private suspend fun startServer(config: ServerConfig): ProcessInfo = withContext(Dispatchers.IO) {
        println("   🚀 Starting ${config.name}...")

        val command = mutableListOf(config.command) + config.args
        val processBuilder = ProcessBuilder(command)
            .redirectError(ProcessBuilder.Redirect.INHERIT)  // Show errors in console

        val process = processBuilder.start()
        val writer = BufferedWriter(OutputStreamWriter(process.outputStream))
        val reader = BufferedReader(InputStreamReader(process.inputStream))

        println("   🔍 Process started, isAlive=${process.isAlive}, pid=${process.pid()}")

        ProcessInfo(process, writer, reader)
    }

    /**
     * Send JSON-RPC request and wait for response
     */
    private suspend fun sendRequest(
        processInfo: ProcessInfo,
        method: String,
        params: JsonObject
    ): JsonElement? = withContext(Dispatchers.IO) {
        val id = ++requestId
        val request = JsonRpcRequest(
            jsonrpc = "2.0",
            id = id,
            method = method,
            params = params
        )

        val requestJson = json.encodeToString(JsonRpcRequest.serializer(), request)

        println("   📤 Sending: $method (id=$id)")
        println("   📋 JSON: $requestJson")

        synchronized(processInfo.writer) {
            processInfo.writer.write(requestJson)
            processInfo.writer.newLine()
            processInfo.writer.flush()
        }

        // Read lines until we find the response with matching id
        // (server may send notifications before the response)
        var attempts = 0
        val maxAttempts = 20

        while (attempts < maxAttempts) {
            attempts++

            val responseLine = synchronized(processInfo.reader) {
                processInfo.reader.readLine()
            }

            if (responseLine == null) {
                throw IllegalStateException("No response from server after $attempts attempts")
            }

            println("   📥 Received (attempt $attempts): ${responseLine.take(150)}...")

            // Try to parse as generic JSON to check if it's a notification or response
            val jsonElement = json.parseToJsonElement(responseLine)
            val jsonObj = jsonElement.jsonObject

            // Check if this is a notification (has "method" but no "id", or has different id)
            val messageMethod = jsonObj["method"]?.jsonPrimitive?.contentOrNull
            val messageId = jsonObj["id"]?.jsonPrimitive?.intOrNull

            if (messageMethod != null) {
                // This is a notification, log and continue
                println("   🔔 Notification: $messageMethod")
                continue
            }

            if (messageId != null && messageId != id) {
                // Response for different request, skip
                println("   ⚠️ Response for different request (expected $id, got $messageId)")
                continue
            }

            // This should be our response
            val response = json.decodeFromString<JsonRpcResponse>(responseLine)

            if (response.error != null) {
                throw IllegalStateException("Server error: ${response.error}")
            }

            return@withContext response.result
        }

        throw IllegalStateException("Failed to get response after $maxAttempts attempts")
    }

    /**
     * Send JSON-RPC notification (no response expected)
     */
    private suspend fun sendNotification(
        processInfo: ProcessInfo,
        method: String,
        params: JsonObject = buildJsonObject {}
    ) = withContext(Dispatchers.IO) {
        val notification = buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", method)
            if (params.isNotEmpty()) {
                put("params", params)
            }
        }

        val notificationJson = json.encodeToString(JsonElement.serializer(), notification)

        synchronized(processInfo.writer) {
            processInfo.writer.write(notificationJson)
            processInfo.writer.newLine()
            processInfo.writer.flush()
        }

        println("   📤 Sent notification: $method")
    }

    /**
     * Parse tools from list response
     */
    private fun parseTools(result: JsonElement?): List<Tool> {
        if (result == null) return emptyList()

        val toolsArray = result.jsonObject["tools"]?.jsonArray ?: return emptyList()

        return toolsArray.map { toolElement ->
            val toolObj = toolElement.jsonObject
            Tool(
                name = toolObj["name"]?.jsonPrimitive?.content ?: "",
                description = toolObj["description"]?.jsonPrimitive?.content ?: "",
                inputSchema = toolObj["inputSchema"]?.jsonObject ?: buildJsonObject {}
            )
        }
    }

    /**
     * Get all available tools from all servers
     */
    suspend fun listTools(): List<Tool> = mutex.withLock {
        return serverTools.values.flatten()
    }

    /**
     * Converts Any value to JsonElement, handling nested Maps and Lists
     */
    private fun convertAnyToJsonElement(value: Any?): JsonElement {
        return when (value) {
            null -> JsonPrimitive(null as String?)
            is String -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            is Map<*, *> -> {
                buildJsonObject {
                    value.forEach { (k, v) ->
                        val key = k.toString()
                        put(key, convertAnyToJsonElement(v))
                    }
                }
            }
            is List<*> -> {
                JsonArray(value.map { convertAnyToJsonElement(it) })
            }
            else -> JsonPrimitive(value.toString())
        }
    }

    /**
     * Call a tool on the appropriate server
     */
    suspend fun callTool(name: String, args: Map<String, Any>): CallToolResponse {
        // Find which server has this tool
        val serverName = mutex.withLock {
            serverTools.entries.find { (_, tools) ->
                tools.any { it.name == name }
            }?.key
        } ?: throw IllegalArgumentException("Tool not found: $name")

        val processInfo = processes[serverName]
            ?: throw IllegalStateException("No process for server: $serverName")

        // Convert args to JsonObject, handling nested structures
        val jsonArgs = buildJsonObject {
            args.forEach { (key, value) ->
                put(key, convertAnyToJsonElement(value))
            }
        }

        // Send tools/call request
        val result = sendRequest(processInfo, "tools/call", buildJsonObject {
            put("name", name)
            put("arguments", jsonArgs)
        })

        return parseCallToolResponse(result)
    }

    /**
     * Parse tool call response
     */
    private fun parseCallToolResponse(result: JsonElement?): CallToolResponse {
        if (result == null) {
            return CallToolResponse(
                content = listOf(ToolContent(type = "text", text = "No result")),
                isError = true
            )
        }

        val resultObj = result.jsonObject
        val content = resultObj["content"]?.jsonArray?.map { contentElement ->
            val contentObj = contentElement.jsonObject
            ToolContent(
                type = contentObj["type"]?.jsonPrimitive?.content ?: "text",
                text = contentObj["text"]?.jsonPrimitive?.content ?: ""
            )
        } ?: listOf(ToolContent(type = "text", text = result.toString()))

        val isError = resultObj["isError"]?.jsonPrimitive?.booleanOrNull ?: false

        return CallToolResponse(content = content, isError = isError)
    }

    @Serializable
    data class CallToolResponse(
        val content: List<ToolContent>,
        val isError: Boolean = false
    )

    @Serializable
    data class ToolContent(
        val type: String,
        val text: String
    )

    /**
     * Check if service is available
     */
    fun isAvailable(): Boolean {
        val processesNotEmpty = processes.isNotEmpty()
        val allProcessesAlive = processes.values.all { it.process.isAlive }
        val available = processesNotEmpty && allProcessesAlive
        println("🔍 StdioMcpService.isAvailable(): processes=$processesNotEmpty (${processes.size}), processesAlive=$allProcessesAlive, result=$available")
        return available
    }

    /**
     * Shutdown all servers
     */
    suspend fun shutdown() = mutex.withLock {
        println("🔄 Shutting down Stdio MCP servers...")

        processes.forEach { (name, processInfo) ->
            try {
                processInfo.writer.close()
                processInfo.reader.close()
                processInfo.process.destroy()
                processInfo.process.waitFor()
                println("   ✅ Stopped $name")
            } catch (e: Exception) {
                println("   ❌ Error stopping $name: ${e.message}")
            }
        }

        processes.clear()
        serverTools.clear()

        println("✅ All Stdio MCP servers stopped")
    }
}
