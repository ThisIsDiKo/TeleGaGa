package ru.dikoresearch.application.commands.handlers

import ru.dikoresearch.application.commands.CommandContext
import ru.dikoresearch.application.commands.CommandHandler
import ru.dikoresearch.domain.ChatOrchestrator
import ru.dikoresearch.domain.valueobjects.ChatId
import ru.dikoresearch.domain.valueobjects.SystemPrompt

/**
 * Handles the /changeRole command - updates the system prompt for a chat
 */
class ChangeRoleCommandHandler(
    private val chatOrchestrator: ChatOrchestrator
) : CommandHandler {
    override val commandName = "changeRole"

    override suspend fun handle(context: CommandContext) {
        val newSystemRole = context.arguments

        if (newSystemRole.isBlank()) {
            context.sendMessage("Использование: /changeRole <текст нового системного промпта>")
            return
        }

        try {
            chatOrchestrator.updateSystemRole(ChatId(context.chatId), SystemPrompt(newSystemRole))
            context.sendMessage("Системный промпт изменен на:\n$newSystemRole")
            println("Системный промпт изменен для чата ${context.chatId}")
        } catch (e: Exception) {
            context.sendMessage("Ошибка при изменении системного промпта: ${e.message}")
        }
    }
}
