package ru.dikoresearch.domain.mcp

import ru.dikoresearch.infrastructure.mcp.StdioMcpService

/**
 * Adapter that wraps StdioMcpService to implement McpService interface
 * Enables dependency inversion for Stdio MCP integration
 */
class StdioMcpServiceAdapter(
    private val stdioMcpService: StdioMcpService
) : McpService {
    override suspend fun initialize() {
        stdioMcpService.initialize()
    }

    override suspend fun listTools(): List<McpTool> {
        return stdioMcpService.listTools().map { stdioTool ->
            McpTool(
                name = stdioTool.name,
                description = stdioTool.description,
                inputSchema = stdioTool.inputSchema
            )
        }
    }

    override suspend fun callTool(name: String, args: Map<String, Any>): McpCallToolResponse {
        val stdioResponse = stdioMcpService.callTool(name, args)
        return McpCallToolResponse(
            content = stdioResponse.content.map { stdioContent ->
                McpToolContent(
                    type = stdioContent.type,
                    text = stdioContent.text
                )
            },
            isError = stdioResponse.isError
        )
    }

    override fun isAvailable(): Boolean {
        return stdioMcpService.isAvailable()
    }

    override suspend fun shutdown() {
        stdioMcpService.shutdown()
    }
}
