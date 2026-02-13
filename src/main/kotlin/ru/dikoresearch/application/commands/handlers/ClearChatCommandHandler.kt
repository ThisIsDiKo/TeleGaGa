package ru.dikoresearch.application.commands.handlers

import ru.dikoresearch.application.commands.CommandContext
import ru.dikoresearch.application.commands.CommandHandler
import ru.dikoresearch.domain.ChatOrchestrator
import ru.dikoresearch.domain.valueobjects.ChatId

/**
 * Handles the /clearChat command - clears the conversation history for a chat
 */
class ClearChatCommandHandler(
    private val chatOrchestrator: ChatOrchestrator
) : CommandHandler {
    override val commandName = "clearChat"

    override suspend fun handle(context: CommandContext) {
        try {
            val cleared = chatOrchestrator.clearHistory(ChatId(context.chatId))

            if (cleared) {
                context.sendMessage("История чата очищена. Начнем с чистого листа!")
                println("История очищена для чата ${context.chatId}")
            } else {
                context.sendMessage("История для этого чата уже пуста")
            }
        } catch (e: Exception) {
            context.sendMessage("Ошибка при очистке истории: ${e.message}")
        }
    }
}
