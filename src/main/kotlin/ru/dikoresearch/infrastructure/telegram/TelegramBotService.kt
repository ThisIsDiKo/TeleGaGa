package ru.dikoresearch.infrastructure.telegram

import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.bot
import com.github.kotlintelegrambot.dispatch
import com.github.kotlintelegrambot.dispatcher.Dispatcher
import com.github.kotlintelegrambot.dispatcher.command
import com.github.kotlintelegrambot.dispatcher.message
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.extensions.filters.Filter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import ru.dikoresearch.domain.ChatException
import ru.dikoresearch.domain.ChatOrchestrator
import ru.dikoresearch.infrastructure.mcp.McpService

/**
 * Сервис для управления Telegram ботом
 * Инкапсулирует всю логику работы с Telegram API и обработку команд
 */
class TelegramBotService(
    private val telegramToken: String,
    private val chatOrchestrator: ChatOrchestrator,
    private val mcpService: McpService?,
    private val applicationScope: CoroutineScope,
    private val defaultSystemRole: String,
    private val defaultTemperature: Float,
    private val gigaChatModel: String
) {
    private lateinit var bot: Bot

    // Храним температуру для каждого чата (chatId -> temperature)
    private val chatTemperatures = mutableMapOf<Long, Float>()

    /**
     * Запускает бота с настроенными обработчиками команд
     */
    fun start() {
        bot = bot {
            token = telegramToken

            dispatch {
                setupCommands()
                setupMessageHandlers()
            }
        }

        bot.startPolling()
        println("Telegram бот запущен и ожидает сообщений")
    }

    /**
     * Останавливает бота
     */
    fun stop() {
        if (::bot.isInitialized) {
            // Telegram bot API не предоставляет метод явной остановки polling
            // Polling останавливается при завершении процесса
            println("Telegram бот останавливается")
        }
    }

    /**
     * Настройка обработчиков команд
     */
    private fun Dispatcher.setupCommands() {
        command("start") {
            val chatId = message.chat.id
            bot.sendMessage(
                chatId = ChatId.fromId(chatId),
                text = "Привет! Я TeleGaGa бот на основе GigaChat с поддержкой MCP инструментов.\n\n" +
                        "Доступные команды:\n" +
                        "/listTools - список доступных MCP инструментов\n" +
                        "/enableMcp - включить MCP режим с доступом к инструментам\n" +
                        "/changeRole <текст> - изменить системный промпт\n" +
                        "/changeT <число> - изменить температуру модели (0.0 - 1.0)\n" +
                        "/clearChat - очистить историю чата\n" +
                        "/destroyContext - информация о старом методе разрушения контекста"
            )
        }

        command("destroyContext") {
            val chatId = message.chat.id
            bot.sendMessage(
                chatId = ChatId.fromId(chatId),
                text = "Раньше я отправлял примерно 160к токенов (максимум заявлен в 128к)"
            )
        }

        command("listTools") {
            val chatId = message.chat.id

            // Используем applicationScope.launch для вызова suspend функции
            applicationScope.launch {
                try {
                    if (mcpService == null) {
                        bot.sendMessage(
                            chatId = ChatId.fromId(chatId),
                            text = "MCP сервис недоступен. Запустите сервер: ./gradlew :mcp-chuck-server:run"
                        )
                        return@launch
                    }

                    val mcpTools = mcpService.listTools()
                    val toolNames = mcpTools.tools.map { it.name }
                    val message = "Доступные MCP инструменты (${toolNames.size}):\n" +
                            toolNames.joinToString("\n") { "• $it" }

                    bot.sendMessage(
                        chatId = ChatId.fromId(chatId),
                        text = message
                    )
                } catch (e: Exception) {
                    bot.sendMessage(
                        chatId = ChatId.fromId(chatId),
                        text = "Ошибка при получении списка инструментов: ${e.message}"
                    )
                }
            }
        }

        command("changeRole") {
            val chatId = message.chat.id
            val newSystemRole = args.joinToString(separator = " ")

            if (newSystemRole.isBlank()) {
                bot.sendMessage(
                    chatId = ChatId.fromId(chatId),
                    text = "Использование: /changeRole <текст нового системного промпта>"
                )
                return@command
            }

            try {
                chatOrchestrator.updateSystemRole(chatId, newSystemRole)
                bot.sendMessage(
                    chatId = ChatId.fromId(chatId),
                    text = "Системный промпт изменен на:\n$newSystemRole"
                )
                println("Системный промпт изменен для чата $chatId")
            } catch (e: Exception) {
                bot.sendMessage(
                    chatId = ChatId.fromId(chatId),
                    text = "Ошибка при изменении системного промпта: ${e.message}"
                )
            }
        }

        command("changeT") {
            val chatId = message.chat.id
            val temperatureStr = args.joinToString(separator = " ")

            val newTemperature = try {
                temperatureStr.toFloat()
            } catch (e: Exception) {
                bot.sendMessage(
                    chatId = ChatId.fromId(chatId),
                    text = "Температура должна быть числом от 0.0 до 1.0"
                )
                return@command
            }

            if (newTemperature < 0.0f || newTemperature > 1.0f) {
                bot.sendMessage(
                    chatId = ChatId.fromId(chatId),
                    text = "Температура должна быть в диапазоне от 0.0 до 1.0"
                )
                return@command
            }

            chatTemperatures[chatId] = newTemperature

            bot.sendMessage(
                chatId = ChatId.fromId(chatId),
                text = "Новая температура ответов: $newTemperature"
            )
            println("Температура изменена для чата $chatId: $newTemperature")
        }

        command("clearChat") {
            val chatId = message.chat.id

            try {
                val deleted = chatOrchestrator.clearHistory(chatId)
                chatTemperatures.remove(chatId)

                val responseText = if (deleted) {
                    "История чата успешно удалена. Начинаем с чистого листа!"
                } else {
                    "Произошла ошибка при удалении истории чата"
                }

                bot.sendMessage(
                    chatId = ChatId.fromId(chatId),
                    text = responseText
                )
                println("Команда clearChat выполнена для чата $chatId: deleted=$deleted")
            } catch (e: Exception) {
                bot.sendMessage(
                    chatId = ChatId.fromId(chatId),
                    text = "Ошибка при очистке истории: ${e.message}"
                )
            }
        }

        command("enableMcp") {
            val chatId = message.chat.id

            try {
                // Используем McpEnabledRole из Main.kt
                val mcpRole = ru.dikoresearch.McpEnabledRole
                chatOrchestrator.updateSystemRole(chatId, mcpRole)

                bot.sendMessage(
                    chatId = ChatId.fromId(chatId),
                    text = "⚡ MCP режим активирован!\n\n" +
                            "Теперь у меня есть доступ к инструментам:\n" +
                            "• fetch - получение данных из интернета\n" +
                            "• search - поиск информации\n" +
                            "• file operations - работа с файлами\n" +
                            "• memory - сохранение информации\n\n" +
                            "Попробуй задать вопрос, требующий актуальных данных!"
                )
                println("MCP режим включен для чата $chatId")
            } catch (e: Exception) {
                bot.sendMessage(
                    chatId = ChatId.fromId(chatId),
                    text = "Ошибка при включении MCP режима: ${e.message}"
                )
            }
        }
    }

    /**
     * Настройка обработчиков текстовых сообщений
     */
    private fun Dispatcher.setupMessageHandlers() {
        message(filter = Filter.Text) {
            val chatId = message.chat.id
            val userMessage = message.text ?: return@message

            // Получаем температуру для чата или используем дефолтную
            val temperature = chatTemperatures.getOrDefault(chatId, defaultTemperature)

            // Используем applicationScope для обработки сообщения
            applicationScope.launch {
                try {
                    // Обрабатываем сообщение через ChatOrchestrator
                    val response = chatOrchestrator.processMessage(
                        chatId = chatId,
                        userMessage = userMessage,
                        systemRole = defaultSystemRole,
                        temperature = temperature,
                        model = gigaChatModel
                    )

                    // Формируем ответ с информацией о токенах
                    val fullResponse = buildString {
                        if (response.toolsUsed) {
                            appendLine("⚡ MCP TOOLS АКТИВНЫ ⚡")
                            appendLine()
                        }
                        appendLine("*** GigaChat T = $temperature ***")
                        appendLine(response.text)
                        appendLine()
                        if (response.toolsUsed) {
                            appendLine("▶ MCP инструменты использованы")
                        }
                        appendLine("Отправлены токены: ${response.tokenUsage.promptTokens}")
                        appendLine("Получены токены: ${response.tokenUsage.completionTokens}")
                        appendLine("Оплачены токены: ${response.tokenUsage.totalTokens}")
                    }

                    // Отправляем основной ответ
                    sendMessageSafely(chatId, fullResponse)

                    // Если была выполнена суммаризация, отправляем дополнительное сообщение
                    response.summaryMessage?.let { summaryMsg ->
                        sendMessageSafely(chatId, summaryMsg)
                    }

                } catch (e: Exception) {
                    println("Ошибка при обработке сообщения от чата $chatId: ${e.message}")
                    sendMessageSafely(
                        chatId,
                        "Произошла ошибка при обработке сообщения: ${e.message}"
                    )
                }
            }
        }
    }

    /**
     * Безопасная отправка сообщения с обработкой ошибок
     */
    private fun sendMessageSafely(chatId: Long, text: String) {
        val result = bot.sendMessage(
            chatId = ChatId.fromId(chatId),
            text = text.ifBlank { "Получен пустой ответ от модели" }
        )

        result.fold(
            ifSuccess = { },
            ifError = { error ->
                println("Ошибка отправки сообщения в Telegram для чата $chatId: $error")
            }
        )
    }
}
