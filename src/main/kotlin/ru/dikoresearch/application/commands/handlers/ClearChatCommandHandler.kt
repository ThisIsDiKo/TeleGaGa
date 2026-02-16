package ru.dikoresearch.application.commands.handlers

import ru.dikoresearch.application.commands.CommandContext
import ru.dikoresearch.application.commands.CommandHandler
import ru.dikoresearch.domain.MultiModelChatOrchestrator
import ru.dikoresearch.domain.valueobjects.ChatId

/**
 * Handles the /clearChat command - clears the conversation history for all three models
 */
class ClearChatCommandHandler(
    private val multiModelOrchestrator: MultiModelChatOrchestrator
) : CommandHandler {
    override val commandName = "clearChat"

    override suspend fun handle(context: CommandContext) {
        try {
            val cleared = multiModelOrchestrator.clearHistory(ChatId(context.chatId))

            if (cleared) {
                context.sendMessage("✅ История очищена для всех трех моделей (Gemma3, Qwen3, Llama3). Начнем с чистого листа!")
                println("История очищена для чата ${context.chatId}")
            } else {
                context.sendMessage("⚠️ История для этого чата уже пуста")
            }
        } catch (e: Exception) {
            context.sendMessage("❌ Ошибка при очистке истории: ${e.message}")
        }
    }
}
