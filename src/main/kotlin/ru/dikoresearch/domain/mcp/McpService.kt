package ru.dikoresearch.domain.mcp

import kotlinx.serialization.Serializable

/**
 * Abstraction for MCP (Model Context Protocol) services
 * Enables dependency inversion for different MCP implementations (HTTP, Stdio, etc.)
 */
interface McpService {
    /**
     * Initialize the MCP service (start servers, create sessions, load tools)
     */
    suspend fun initialize()

    /**
     * Get all available tools from this MCP service
     *
     * @return list of available tools
     */
    suspend fun listTools(): List<McpTool>

    /**
     * Call a tool by name with arguments
     *
     * @param name tool name
     * @param args tool arguments
     * @return tool call response
     */
    suspend fun callTool(name: String, args: Map<String, Any>): McpCallToolResponse

    /**
     * Check if service is available and ready
     *
     * @return true if service is available
     */
    fun isAvailable(): Boolean

    /**
     * Shutdown the MCP service (stop servers, close connections)
     */
    suspend fun shutdown()
}

/**
 * Response from MCP tool call
 */
@Serializable
data class McpCallToolResponse(
    val content: List<McpToolContent>,
    val isError: Boolean = false
)

/**
 * Content item in tool call response
 */
@Serializable
data class McpToolContent(
    val type: String,
    val text: String
)
