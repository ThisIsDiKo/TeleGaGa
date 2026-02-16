package ru.dikoresearch.infrastructure.telegram

import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.bot
import com.github.kotlintelegrambot.dispatch
import com.github.kotlintelegrambot.dispatcher.Dispatcher
import com.github.kotlintelegrambot.dispatcher.command
import com.github.kotlintelegrambot.dispatcher.message
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.ParseMode
import com.github.kotlintelegrambot.extensions.filters.Filter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.dikoresearch.application.commands.CommandContext
import ru.dikoresearch.application.commands.CommandRegistry
import ru.dikoresearch.domain.MultiModelChatOrchestrator
import ru.dikoresearch.domain.valueobjects.ChatId as ChatIdValue
import ru.dikoresearch.domain.valueobjects.SystemPrompt
import ru.dikoresearch.infrastructure.persistence.ChatSettingsManager

/**
 * Упрощенный Telegram Bot Service для мульти-модельного режима
 *
 * Обрабатывает сообщения через три модели Ollama:
 * - gemma3:1b
 * - qwen3:1.7b
 * - llama3.2:3b
 *
 * Каждая модель отправляет ответ в отдельном сообщении
 */
class TelegramBotService(
    private val telegramToken: String,
    private val commandRegistry: CommandRegistry,
    private val multiModelOrchestrator: MultiModelChatOrchestrator,
    private val settingsManager: ChatSettingsManager,
    private val applicationScope: CoroutineScope,
    private val defaultSystemRole: String
) {
    lateinit var bot: Bot
        private set

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
        println("Registered commands: ${commandRegistry.getAllCommands().joinToString(", ")}")
    }

    /**
     * Останавливает бота
     */
    fun stop() {
        if (::bot.isInitialized) {
            println("Telegram бот останавливается")
        }
    }

    /**
     * Настройка обработчиков команд через Command Registry
     */
    private fun Dispatcher.setupCommands() {
        // Register all commands from the registry
        commandRegistry.getAllCommands().forEach { commandName ->
            command(commandName) {
                val chatId = message.chat.id
                val messageId = message.messageId
                val userId = message.from?.id ?: 0
                val arguments = args.joinToString(" ")

                // Create command context
                val context = CommandContext(
                    bot = bot,
                    chatId = chatId,
                    messageId = messageId,
                    userId = userId,
                    arguments = arguments,
                    sendMessage = { text ->
                        bot.sendMessage(
                            chatId = ChatId.fromId(chatId),
                            text = text
                        )
                    },
                    editMessage = { text ->
                        bot.sendMessage(
                            chatId = ChatId.fromId(chatId),
                            text = text,
                            replyToMessageId = messageId
                        )
                    }
                )

                // Dispatch to handler using coroutine scope
                applicationScope.launch {
                    try {
                        val handler = commandRegistry.getHandler(commandName)
                        handler?.handle(context) ?: run {
                            bot.sendMessage(
                                chatId = ChatId.fromId(chatId),
                                text = "Command not found: /$commandName"
                            )
                        }
                    } catch (e: Exception) {
                        bot.sendMessage(
                            chatId = ChatId.fromId(chatId),
                            text = "Error executing command: ${e.message}"
                        )
                        e.printStackTrace()
                    }
                }
            }
        }
    }

    /**
     * Настройка обработчиков текстовых сообщений
     *
     * Отправляет сообщение всем трем моделям и получает три ответа
     */
    private fun Dispatcher.setupMessageHandlers() {
        message(filter = Filter.Text) {
            val chatId = message.chat.id
            val userMessage = message.text ?: return@message

            println("📨 Message received from chat $chatId: '$userMessage'")

            // Используем applicationScope для обработки сообщения
            applicationScope.launch {
                try {
                    println("⚙️ Loading settings for chat $chatId...")
                    // Получаем настройки для чата
                    val settings = settingsManager.loadSettings(chatId)
                    val temperature = settings.temperature
                    val systemRole = settings.systemPrompt ?: SystemPrompt(defaultSystemRole)
                    println("✅ Settings loaded, temperature: $temperature")

                    println("📤 Sending message to all three models...")
                    // Обрабатываем сообщение через все три модели
                    val responses = multiModelOrchestrator.processMessage(
                        chatId = ChatIdValue(chatId),
                        userMessage = userMessage,
                        systemRole = systemRole,
                        temperature = temperature
                    )

                    println("📥 Received ${responses.size} responses")

                    // Отправляем ответ от каждой модели в отдельном сообщении
                    responses.forEach { response ->
                        // Добавляем небольшую задержку между сообщениями (защита от rate limiting)
                        delay(100)

                        val responseText = buildString {
                            appendLine("*${response.modelName}*")
                            appendLine()
                            appendLine(response.content)
                            appendLine()
                            appendLine("_⏱ ${response.generationTimeMs}ms_")
                        }

                        bot.sendMessage(
                            chatId = ChatId.fromId(chatId),
                            text = responseText,
                            parseMode = ParseMode.MARKDOWN
                        )

                        println("✅ Sent response from ${response.modelName}")
                    }

                } catch (e: Exception) {
                    println("❌ Error processing message: ${e.message}")
                    e.printStackTrace()

                    val errorMessage = "Произошла ошибка при обработке вашего сообщения: ${e.message}"

                    bot.sendMessage(
                        chatId = ChatId.fromId(chatId),
                        text = errorMessage
                    )
                }
            }
        }
    }
}
