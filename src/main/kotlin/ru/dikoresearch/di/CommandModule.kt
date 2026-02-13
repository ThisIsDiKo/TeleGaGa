package ru.dikoresearch.di

import org.koin.core.qualifier.named
import org.koin.dsl.module
import ru.dikoresearch.application.commands.CommandRegistry
import ru.dikoresearch.application.commands.handlers.*
import ru.dikoresearch.application.github.PullRequestAnalysisService

/**
 * Koin module for command handlers
 */
val commandModule = module {
    // Pull Request Analysis Service (optional, depends on GitHub config)
    single {
        val config = get<ru.dikoresearch.infrastructure.config.ConfigService>()
        val githubMcpAdapter: ru.dikoresearch.domain.mcp.McpService? = getOrNull(named("githubMcpAdapter"))

        if (githubMcpAdapter != null &&
            config.githubOwner != null &&
            config.githubRepo != null) {
            PullRequestAnalysisService(
                githubMcpService = githubMcpAdapter,
                ragService = get(),
                gigaChatClient = get(),
                gigaChatModel = config.gigaChatModel,
                githubOwner = config.githubOwner,
                githubRepo = config.githubRepo
            )
        } else {
            null
        }
    }

    // Command Handlers
    single { StartCommandHandler() }

    single {
        ChangeRoleCommandHandler(
            chatOrchestrator = get()
        )
    }

    single {
        ChangeTemperatureCommandHandler(
            settingsManager = get()
        )
    }

    single {
        ClearChatCommandHandler(
            chatOrchestrator = get()
        )
    }

    single {
        SetThresholdCommandHandler(
            settingsManager = get()
        )
    }

    single {
        CreateEmbeddingsCommandHandler(
            embeddingService = get(),
            embeddingsManager = get(),
            textChunker = get()
        )
    }

    single {
        val config = get<ru.dikoresearch.infrastructure.config.ConfigService>()
        HelpCommandHandler(
            ragService = get(),
            chatOrchestrator = get(),
            settingsManager = get(),
            gigaChatModel = config.gigaChatModel
        )
    }

    single {
        TestRagCommandHandler(
            ragService = get(),
            ollamaClient = get()
        )
    }

    single {
        CompareRagCommandHandler(
            ragService = get(),
            ollamaClient = get(),
            settingsManager = get()
        )
    }

    single {
        val githubMcpAdapter: ru.dikoresearch.domain.mcp.McpService? = getOrNull(named("githubMcpAdapter"))
        ListGitHubToolsCommandHandler(
            githubMcpService = githubMcpAdapter
        )
    }

    single {
        val config = get<ru.dikoresearch.infrastructure.config.ConfigService>()
        val prAnalysisService: PullRequestAnalysisService? = get()
        ShowPRCommandHandler(
            analysisService = prAnalysisService,
            githubOwner = config.githubOwner,
            githubRepo = config.githubRepo
        )
    }

    // Command Registry (aggregates all handlers)
    single {
        CommandRegistry(
            handlers = listOf(
                get<StartCommandHandler>(),
                get<ChangeRoleCommandHandler>(),
                get<ChangeTemperatureCommandHandler>(),
                get<ClearChatCommandHandler>(),
                get<SetThresholdCommandHandler>(),
                get<CreateEmbeddingsCommandHandler>(),
                get<HelpCommandHandler>(),
                get<TestRagCommandHandler>(),
                get<CompareRagCommandHandler>(),
                get<ListGitHubToolsCommandHandler>(),
                get<ShowPRCommandHandler>()
            )
        )
    }
}
