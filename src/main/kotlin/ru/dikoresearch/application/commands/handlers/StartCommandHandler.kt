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
            appendLine("👋 Привет! Я TeleGaGa - мульти-модельный бот на Ollama.")
            appendLine()
            appendLine("🧠 Как я работаю:")
            appendLine("• Использую три модели одновременно:")
            appendLine("  - Gemma3 1B")
            appendLine("  - Qwen3 1.7B")
            appendLine("  - Llama3.2 3B")
            appendLine("• Каждая модель сохраняет свою историю отдельно")
            appendLine("• Все три модели отвечают на каждое сообщение")
            appendLine()
            appendLine("💬 Просто задайте вопрос, и получите три ответа!")
            appendLine()
            appendLine("📋 Доступные команды:")
            appendLine("/start - показать это сообщение")
            appendLine("/changeRole <текст> - изменить системный промпт для всех моделей")
            appendLine("/changeT <число> - изменить температуру (0.0-1.0)")
            appendLine("/clearChat - очистить историю для всех моделей")
            appendLine()
            appendLine("💡 Требования:")
            appendLine("• Ollama должна быть запущена (http://localhost:11434)")
            appendLine("• Модели должны быть загружены:")
            appendLine("  ollama pull gemma3:1b")
            appendLine("  ollama pull qwen3:1.7b")
            appendLine("  ollama pull llama3.2:3b")
        }

        context.sendMessage(welcomeMessage)
    }
}
