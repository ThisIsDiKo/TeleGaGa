package ru.dikoresearch.di

import org.koin.core.qualifier.named
import org.koin.dsl.module
import ru.dikoresearch.domain.mcp.CompositeMcpService
import ru.dikoresearch.domain.mcp.McpService
import ru.dikoresearch.domain.mcp.StdioMcpServiceAdapter
import ru.dikoresearch.infrastructure.mcp.StdioMcpService

/**
 * Koin module for MCP services
 */
val mcpModule = module {
    // Git MCP Service (Docker)
    single(named("gitMcp")) {
        StdioMcpService(
            serverConfigs = listOf(
                StdioMcpService.ServerConfig(
                    name = "mcp-server-docker",
                    command = "/Users/dmitriikonovalov/.local/bin/mcp-server-docker",
                    args = emptyList()
                )
            )
        )
    }

    // GitHub MCP Service (optional, based on config)
    single(named("githubMcp")) {
        val config = get<ru.dikoresearch.infrastructure.config.ConfigService>()
        if (config.githubToken != null) {
            StdioMcpService(
                serverConfigs = listOf(
                    StdioMcpService.ServerConfig(
                        name = "mcp-server-github",
                        command = "npx",
                        args = listOf("-y", "@modelcontextprotocol/server-github"),
                        envVars = mapOf("GITHUB_PERSONAL_ACCESS_TOKEN" to config.githubToken)
                    )
                )
            )
        } else {
            null
        }
    }

    // MCP Service adapters
    single<McpService>(named("gitMcpAdapter")) {
        StdioMcpServiceAdapter(get(named("gitMcp")))
    }

    single<McpService?>(named("githubMcpAdapter")) {
        val githubMcp: StdioMcpService? = get(named("githubMcp"))
        githubMcp?.let { StdioMcpServiceAdapter(it) }
    }

    // Composite MCP Service (aggregates all available MCP services)
    single<McpService> {
        val services = mutableListOf<McpService>()
        services.add(get(named("gitMcpAdapter")))

        val githubAdapter: McpService? = get(named("githubMcpAdapter"))
        githubAdapter?.let { services.add(it) }

        CompositeMcpService(services)
    }
}
