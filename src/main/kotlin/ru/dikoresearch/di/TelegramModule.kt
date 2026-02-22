package ru.dikoresearch.di

import org.koin.dsl.module
import ru.dikoresearch.domain.MultiModelChatOrchestrator
import ru.dikoresearch.infrastructure.telegram.TelegramBotService

/**
 * Koin module для Telegram bot service (упрощенный для мульти-модельного режима)
 */
val telegramModule = module {
    single {
        val config = get<ru.dikoresearch.infrastructure.config.ConfigService>()
        TelegramBotService(
            telegramToken = config.telegramToken,
            commandRegistry = get(),
            multiModelOrchestrator = get(),
            settingsManager = get(),
            applicationScope = get(),
            defaultSystemRole = MultiModelChatOrchestrator.ARTIFACTS_SYSTEM_PROMPT
        )
    }
}
