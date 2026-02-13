package ru.dikoresearch.domain.mcp

import ru.dikoresearch.infrastructure.mcp.HttpMcpService

/**
 * Adapter that wraps HttpMcpService to implement McpService interface
 * Enables dependency inversion for HTTP MCP integration
 */
class HttpMcpServiceAdapter(
    private val httpMcpService: HttpMcpService
) : McpService {
    override suspend fun initialize() {
        httpMcpService.initialize()
    }

    override suspend fun listTools(): List<McpTool> {
        return httpMcpService.listTools().map { httpTool ->
            McpTool(
                name = httpTool.name,
                description = httpTool.description,
                inputSchema = httpTool.inputSchema
            )
        }
    }

    override suspend fun callTool(name: String, args: Map<String, Any>): McpCallToolResponse {
        val httpResponse = httpMcpService.callTool(name, args)
        return McpCallToolResponse(
            content = httpResponse.content.map { httpContent ->
                McpToolContent(
                    type = httpContent.type,
                    text = httpContent.text
                )
            },
            isError = httpResponse.isError
        )
    }

    override fun isAvailable(): Boolean {
        return httpMcpService.isAvailable()
    }

    override suspend fun shutdown() {
        httpMcpService.shutdown()
    }
}
