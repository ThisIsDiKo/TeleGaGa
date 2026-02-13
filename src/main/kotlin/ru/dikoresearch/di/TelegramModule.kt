package ru.dikoresearch.di

import org.koin.dsl.module
import ru.dikoresearch.infrastructure.telegram.TelegramBotService

/**
 * Koin module for Telegram bot service
 */
val telegramModule = module {
    single {
        val config = get<ru.dikoresearch.infrastructure.config.ConfigService>()
        TelegramBotService(
            telegramToken = config.telegramToken,
            commandRegistry = get(),
            chatOrchestrator = get(),
            settingsManager = get(),
            embeddingService = get(),
            applicationScope = get(),
            defaultSystemRole = getAssistantRole(),
            gigaChatModel = config.gigaChatModel
        )
    }
}

/**
 * Default assistant role system prompt
 */
private fun getAssistantRole() = """
You are a helpful assistant for the TeleGaGa project.

Answer questions clearly and concisely in English.
When documentation is provided, use it as your primary source of information.
""".trimIndent()
