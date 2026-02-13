package ru.dikoresearch.domain.mcp

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Common MCP tool model that abstracts over different MCP service implementations
 * Used across HTTP and Stdio MCP protocols
 */
@Serializable
data class McpTool(
    val name: String,
    val description: String,
    val inputSchema: JsonObject
)
