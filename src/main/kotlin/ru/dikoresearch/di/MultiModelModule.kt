package ru.dikoresearch.di

import org.koin.core.qualifier.named
import org.koin.dsl.module
import ru.dikoresearch.domain.MultiModelChatOrchestrator
import ru.dikoresearch.infrastructure.http.OllamaClient
import ru.dikoresearch.infrastructure.mcp.StdioMcpService

/**
 * Koin module для MultiModelChatOrchestrator с MCP artifacts сервером
 */
val multiModelModule = module {
    // MCP сервер для анализа артефактов (CSV измерений пневморессор)
    single(named("artifactsMcp")) {
        StdioMcpService(
            serverConfigs = listOf(
                StdioMcpService.ServerConfig(
                    name = "artifacts",
                    command = "python3",
                    args = listOf("mcp-artifacts-server/server.py")
                )
            )
        )
    }

    single {
        MultiModelChatOrchestrator(
            qwen25Client = get<OllamaClient>(named("qwen25")),
            historyManager = get(),
            artifactsMcpService = get<StdioMcpService>(named("artifactsMcp"))
        )
    }
}
