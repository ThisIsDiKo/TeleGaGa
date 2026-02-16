package ru.dikoresearch.di

import org.koin.dsl.module
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
            defaultSystemRole = getDefaultSystemRole()
        )
    }
}

/**
 * Default system role для всех трех моделей
 */
private fun getDefaultSystemRole() = """
You are a helpful assistant that answers user questions.

Provide clear, concise, and accurate responses.
""".trimIndent()
