package ru.dikoresearch.application.commands.handlers

import ru.dikoresearch.application.commands.CommandContext
import ru.dikoresearch.application.commands.CommandHandler

/**
 * Handles the /start command
 */
class StartCommandHandler : CommandHandler {
    override val commandName = "start"

    override suspend fun handle(context: CommandContext) {
        val welcomeMessage = buildString {
            appendLine("👋 Привет! Я TeleGaGa — аналитический бот на Qwen 2.5.")
            appendLine()
            appendLine("📊 Я умею анализировать данные замеров пневморессор:")
            appendLine("• Считать среднее, мин, макс, стандартное отклонение")
            appendLine("• Работать с несколькими моделями (180D1, 160D1, 140_2, ...)")
            appendLine("• Анализировать данные по датам")
            appendLine()
            appendLine("💬 Примеры вопросов:")
            appendLine("• «Какое среднее давление 8 бар для 180D1?»")
            appendLine("• «Какие модели доступны?»")
            appendLine("• «Покажи замеры 160D1 за ноябрь 2025»")
            appendLine("• \"What is the mean 8 bar height for 180D1?\"")
            appendLine()
            appendLine("📋 Команды:")
            appendLine("/start — показать это сообщение")
            appendLine("/changeRole <текст> — изменить системный промпт")
            appendLine("/changeT <0.0–1.0> — изменить температуру")
            appendLine("/clearChat — очистить историю чата")
            appendLine()
            appendLine("⚙️ Требования:")
            appendLine("• Ollama: http://localhost:11434")
            appendLine("• Модель: ollama pull qwen2.5:1.5b")
        }

        context.sendMessage(welcomeMessage)
    }
}
