package ru.dikoresearch.application.commands.handlers

import ru.dikoresearch.application.commands.CommandContext
import ru.dikoresearch.application.commands.CommandHandler
import ru.dikoresearch.domain.valueobjects.SystemPrompt
import ru.dikoresearch.infrastructure.persistence.ChatSettingsManager

/**
 * Handles the /changeRole command - updates the system prompt for all three models
 */
class ChangeRoleCommandHandler(
    private val settingsManager: ChatSettingsManager
) : CommandHandler {
    override val commandName = "changeRole"

    override suspend fun handle(context: CommandContext) {
        val newSystemRole = context.arguments

        if (newSystemRole.isBlank()) {
            context.sendMessage("Использование: /changeRole <текст нового системного промпта>")
            return
        }

        try {
            val settings = settingsManager.loadSettings(context.chatId)
            val updatedSettings = settings.copy(systemPrompt = SystemPrompt(newSystemRole))
            settingsManager.saveSettings(context.chatId, updatedSettings)

            context.sendMessage("✅ Системный промпт изменен для всех трех моделей:\n\n$newSystemRole")
            println("Системный промпт изменен для чата ${context.chatId}")
        } catch (e: Exception) {
            context.sendMessage("❌ Ошибка при изменении системного промпта: ${e.message}")
        }
    }
}
