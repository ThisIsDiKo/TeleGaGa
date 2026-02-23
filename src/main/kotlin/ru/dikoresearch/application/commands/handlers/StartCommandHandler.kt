package ru.dikoresearch.application.commands.handlers

import ru.dikoresearch.application.commands.CommandContext
import ru.dikoresearch.application.commands.CommandHandler

/**
 * Handles the /start command - sends welcome message with available commands
 */
class StartCommandHandler : CommandHandler {
    override val commandName = "start"

    override suspend fun handle(context: CommandContext) {
        val welcomeMessage = buildString {
            appendLine("Привет! Я твой персональный ассистент.")
            appendLine()
            appendLine("Просто пиши — отвечу с учётом контекста из документации (RAG).")
            appendLine()
            appendLine("Команды:")
            appendLine("/start — это сообщение")
            appendLine("/clearChat — очистить историю диалога")
            appendLine("/changeRole <текст> — сменить системный промпт")
            appendLine("/changeT <0.0-1.0> — изменить температуру модели")
            appendLine("/setThreshold <0.0-1.0> — порог релевантности RAG (по умолч. 0.5)")
            appendLine("/createEmbeddings — создать эмбеддинги из rag_docs/")
            appendLine("/help <вопрос> — поиск по документации")
        }

        context.sendMessage(welcomeMessage)
    }
}
