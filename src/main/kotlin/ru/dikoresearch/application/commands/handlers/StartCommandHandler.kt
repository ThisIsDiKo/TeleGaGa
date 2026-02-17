package ru.dikoresearch.application.commands.handlers

import ru.dikoresearch.application.commands.CommandContext
import ru.dikoresearch.application.commands.CommandHandler

/**
 * Handles the /start command - sends welcome message for multi-model bot
 */
class StartCommandHandler : CommandHandler {
    override val commandName = "start"

    override suspend fun handle(context: CommandContext) {
        val welcomeMessage = buildString {
            appendLine("👋 Привет! Я TeleGaGa - бот на Ollama (Gemma3).")
            appendLine()
            appendLine("🧠 Как я работаю:")
            appendLine("• Использую модель Gemma3 1B")
            appendLine("• Сохраняю историю разговора")
            appendLine()
            appendLine("💬 Просто задайте вопрос!")
            appendLine()
            appendLine("📋 Доступные команды:")
            appendLine("/start - показать это сообщение")
            appendLine("/changeRole <текст> - изменить системный промпт")
            appendLine("/changeT <число> - изменить температуру (0.0-1.0)")
            appendLine("/clearChat - очистить историю чата")
            appendLine()
            appendLine("💡 Требования:")
            appendLine("• Ollama должна быть запущена (http://localhost:11434)")
            appendLine("• Модель должна быть загружена:")
            appendLine("  ollama pull gemma3:1b")
        }

        context.sendMessage(welcomeMessage)
    }
}
