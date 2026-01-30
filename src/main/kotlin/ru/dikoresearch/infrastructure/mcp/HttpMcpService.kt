package ru.dikoresearch.infrastructure.mcp

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.io.File

/**
 * HTTP-based MCP service managing multiple MCP servers via Streamable HTTP protocol
 */
class HttpMcpService(
    private val httpClient: HttpClient,
    private val serverConfigs: List<ServerConfig>
) {
    private val mutex = Mutex()
    private val processes = mutableMapOf<String, Process>()
    private val sessions = mutableMapOf<String, SessionInfo>()
    private val serverTools = mutableMapOf<String, List<Tool>>()

    data class ServerConfig(
        val name: String,
        val directory: String,
        val port: Int
    )

    data class SessionInfo(
        val sessionId: String,
        val serverUrl: String,
        val serverName: String
    )

    @Serializable
    data class Tool(
        val name: String,
        val description: String,
        val inputSchema: JsonObject
    )

    @Serializable
    data class InitializeRequest(
        val protocolVersion: String,
        val clientInfo: ClientInfo,
        val capabilities: JsonObject
    )

    @Serializable
    data class ClientInfo(
        val name: String,
        val version: String
    )

    @Serializable
    data class InitializeResponse(
        val protocolVersion: String,
        val serverInfo: ServerInfo,
        val capabilities: JsonObject,
        val sessionId: String
    )

    @Serializable
    data class ServerInfo(
        val name: String,
        val version: String
    )

    @Serializable
    data class ToolsListResponse(
        val tools: List<Tool>
    )

    @Serializable
    data class CallToolRequest(
        val name: String,
        val arguments: JsonObject
    )

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
     * Initialize all MCP servers
     */
    suspend fun initialize() = mutex.withLock {
        println("🔄 Initializing HTTP MCP servers...")

        // Убиваем старые процессы на портах перед запуском новых
        println("🧹 Очистка портов от старых процессов...")
        serverConfigs.forEach { config ->
            killProcessOnPort(config.port)
        }

        for (config in serverConfigs) {
            try {
                // Start Node.js server process
                val process = startServer(config)
                processes[config.name] = process

                // Wait for server to be ready
                waitForServerReady(config.port)

                // Create HTTP session
                val session = createSession(config)
                sessions[config.name] = session

                // Load tools
                val tools = loadTools(session)
                serverTools[config.name] = tools

                println("   ✅ ${config.name}: ${tools.size} tools on port ${config.port}")
            } catch (e: Exception) {
                println("   ❌ Failed to initialize ${config.name}: ${e.message}")
                throw e
            }
        }

        println("✅ All MCP servers initialized successfully")

        // Проверяем состояние процессов после инициализации
        println("🔍 Проверка состояния MCP процессов:")
        processes.forEach { (name, process) ->
            println("   - $name: isAlive=${process.isAlive()}, pid=${process.pid()}")
        }
    }

    /**
     * Kill process running on specified port
     */
    private fun killProcessOnPort(port: Int) {
        try {
            val processBuilder = ProcessBuilder("bash", "-c", "lsof -ti:$port | xargs kill -9 2>/dev/null || true")
            val process = processBuilder.start()
            process.waitFor()
            println("   🧹 Port $port cleared")
        } catch (e: Exception) {
            println("   ⚠️ Could not clear port $port: ${e.message}")
        }
    }

    /**
     * Start a Node.js MCP server
     */
    private fun startServer(config: ServerConfig): Process {
        val serverDir = File(config.directory)
        if (!serverDir.exists()) {
            throw IllegalStateException("Server directory not found: ${config.directory}")
        }

        println("   🚀 Starting ${config.name} server in ${config.directory}...")
        val processBuilder = ProcessBuilder("node", "index.js")
            .directory(serverDir)
            .inheritIO()  // Показываем вывод Node.js процессов в консоли

        val process = processBuilder.start()
        println("   🔍 Process started, isAlive=${process.isAlive()}, pid=${process.pid()}")
        return process
    }

    /**
     * Wait for server to be ready by checking health endpoint
     */
    private suspend fun waitForServerReady(port: Int, maxAttempts: Int = 10) {
        repeat(maxAttempts) { attempt ->
            try {
                val response: HttpResponse = httpClient.get("http://localhost:$port/health")
                if (response.status.isSuccess()) {
                    return
                }
            } catch (e: Exception) {
                if (attempt == maxAttempts - 1) {
                    throw IllegalStateException("Server on port $port failed to start")
                }
                delay(500)
            }
        }
    }

    /**
     * Create MCP session with server
     */
    private suspend fun createSession(config: ServerConfig): SessionInfo {
        val serverUrl = "http://localhost:${config.port}"
        val request = InitializeRequest(
            protocolVersion = "2024-11-05",
            clientInfo = ClientInfo(name = "TeleGaGa", version = "1.0.0"),
            capabilities = buildJsonObject {}
        )

        val response: InitializeResponse = httpClient.post("$serverUrl/mcp/v1/initialize") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()

        return SessionInfo(
            sessionId = response.sessionId,
            serverUrl = serverUrl,
            serverName = config.name
        )
    }

    /**
     * Load tools from server
     */
    private suspend fun loadTools(session: SessionInfo): List<Tool> {
        val response: ToolsListResponse = httpClient.post("${session.serverUrl}/mcp/v1/tools/list") {
            header("mcp-session-id", session.sessionId)
        }.body()

        return response.tools
    }

    /**
     * Get all available tools from all servers
     */
    suspend fun listTools(): List<Tool> = mutex.withLock {
        return serverTools.values.flatten()
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

        val session = sessions[serverName]
            ?: throw IllegalStateException("No session for server: $serverName")

        // Convert args to JsonObject
        val jsonArgs = buildJsonObject {
            args.forEach { (key, value) ->
                when (value) {
                    is String -> put(key, value)
                    is Number -> put(key, JsonPrimitive(value))
                    is Boolean -> put(key, value)
                    else -> put(key, value.toString())
                }
            }
        }

        val request = CallToolRequest(name = name, arguments = jsonArgs)

        return httpClient.post("${session.serverUrl}/mcp/v1/tools/call") {
            header("mcp-session-id", session.sessionId)
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()
    }

    /**
     * Check if service is available
     */
    fun isAvailable(): Boolean {
        val sessionsNotEmpty = sessions.isNotEmpty()
        val allProcessesAlive = processes.values.all { it.isAlive }
        val available = sessionsNotEmpty && allProcessesAlive
        println("🔍 HttpMcpService.isAvailable(): sessions=$sessionsNotEmpty (${sessions.size}), processesAlive=$allProcessesAlive (${processes.size}), result=$available")
        return available
    }

    /**
     * Shutdown all servers
     */
    suspend fun shutdown() = mutex.withLock {
        println("🔄 Shutting down HTTP MCP servers...")

        processes.forEach { (name, process) ->
            try {
                process.destroy()
                process.waitFor()
                println("   ✅ Stopped $name")
            } catch (e: Exception) {
                println("   ❌ Error stopping $name: ${e.message}")
            }
        }

        processes.clear()
        sessions.clear()
        serverTools.clear()

        println("✅ All MCP servers stopped")
    }
}
