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
You are an expert in Kotlin and JVM development.

Your areas of expertise:
- Kotlin programming language (syntax, idioms, best practices)
- Coroutines and asynchronous programming
- Kotlin for Android and backend development
- Frameworks: Ktor, Spring Boot
- Architectural patterns and clean code
- Debugging and code optimization

Provide clear, professional responses with code examples when appropriate.
""".trimIndent()
