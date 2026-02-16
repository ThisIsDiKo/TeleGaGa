package ru.dikoresearch.di

import org.koin.dsl.module
import ru.dikoresearch.application.commands.CommandRegistry
import ru.dikoresearch.application.commands.handlers.*

/**
 * Упрощенный Koin module для command handlers (только базовые команды)
 */
val commandModule = module {
    // Command Handlers
    single { StartCommandHandler() }

    single {
        ChangeRoleCommandHandler(
            settingsManager = get()
        )
    }

    single {
        ChangeTemperatureCommandHandler(
            settingsManager = get()
        )
    }

    single {
        ClearChatCommandHandler(
            multiModelOrchestrator = get()
        )
    }

    // Command Registry (aggregates all handlers)
    single {
        CommandRegistry(
            handlers = listOf(
                get<StartCommandHandler>(),
                get<ChangeRoleCommandHandler>(),
                get<ChangeTemperatureCommandHandler>(),
                get<ClearChatCommandHandler>()
            )
        )
    }
}
