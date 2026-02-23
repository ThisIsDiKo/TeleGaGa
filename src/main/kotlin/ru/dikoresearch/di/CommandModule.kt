package ru.dikoresearch.di

import org.koin.dsl.module
import ru.dikoresearch.application.commands.CommandRegistry
import ru.dikoresearch.application.commands.handlers.*

/**
 * Koin module for command handlers
 */
val commandModule = module {
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
                get<CompareRagCommandHandler>()
            )
        )
    }
}
