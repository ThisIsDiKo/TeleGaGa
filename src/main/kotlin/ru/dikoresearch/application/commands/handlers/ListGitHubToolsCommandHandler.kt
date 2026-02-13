package ru.dikoresearch.application.commands.handlers

import ru.dikoresearch.application.commands.CommandContext
import ru.dikoresearch.application.commands.CommandHandler
import ru.dikoresearch.domain.mcp.McpService

/**
 * Handles the /listGitHubTools command - lists all available GitHub MCP tools
 */
class ListGitHubToolsCommandHandler(
    private val githubMcpService: McpService?
) : CommandHandler {
    override val commandName = "listGitHubTools"

    override suspend fun handle(context: CommandContext) {
        if (githubMcpService == null || !githubMcpService.isAvailable()) {
            context.sendMessage("GitHub MCP service is not available.")
            return
        }

        try {
            val tools = githubMcpService.listTools()
            val toolsList = tools.joinToString("\n") { tool ->
                "- ${tool.name}: ${tool.description}"
            }

            context.sendMessage(
                """
                Available GitHub MCP Tools (${tools.size}):

                $toolsList
                """.trimIndent()
            )
        } catch (e: Exception) {
            context.sendMessage("Error listing tools: ${e.message}")
        }
    }
}
