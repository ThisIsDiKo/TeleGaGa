package ru.dikoresearch.di

import org.koin.dsl.module
import ru.dikoresearch.infrastructure.config.UserProfileService
import ru.dikoresearch.infrastructure.telegram.TelegramBotService

/**
 * Koin module for Telegram bot service
 */
val telegramModule = module {
    single {
        val config = get<ru.dikoresearch.infrastructure.config.ConfigService>()
        val userProfile = get<UserProfileService>()
        TelegramBotService(
            telegramToken = config.telegramToken,
            commandRegistry = get(),
            chatOrchestrator = get(),
            settingsManager = get(),
            embeddingService = get(),
            applicationScope = get(),
            defaultSystemRole = buildAssistantRole(userProfile),
            gigaChatModel = config.gigaChatModel
        )
    }
}

/**
 * Builds the default assistant system prompt, injecting user profile if configured.
 */
private fun buildAssistantRole(profile: UserProfileService): String {
    val profileBlock = profile.toSystemPromptBlock()
    val languageHint = if (profile.language.isNotBlank()) {
        "Always respond in ${profile.language}."
    } else {
        "Answer in Russian by default unless the user writes in another language."
    }

    return buildString {
        appendLine("You are a personal AI assistant.")
        appendLine()
        if (profileBlock.isNotBlank()) {
            appendLine(profileBlock)
            appendLine()
        }
        appendLine(languageHint)
        appendLine("Be concise and specific. When documentation is provided, use it as the primary source of information.")
        if (profile.name.isNotBlank()) {
            appendLine("Address the user as ${profile.name}.")
        }
    }.trimEnd()
}
