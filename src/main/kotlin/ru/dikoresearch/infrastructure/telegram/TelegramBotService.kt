package ru.dikoresearch.infrastructure.telegram

import GigaChatMessage
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
import ru.dikoresearch.application.commands.CommandContext
import ru.dikoresearch.application.commands.CommandRegistry
import ru.dikoresearch.domain.ChatOrchestrator
import ru.dikoresearch.domain.RagService
import ru.dikoresearch.domain.valueobjects.ChatId as ChatIdValue
import ru.dikoresearch.domain.valueobjects.SystemPrompt
import ru.dikoresearch.infrastructure.embeddings.EmbeddingService
import ru.dikoresearch.infrastructure.persistence.ChatSettingsManager

/**
 * Refactored Telegram Bot Service using Command Registry pattern
 * Reduced from 1583 lines to <300 lines using Single Responsibility Principle
 */
class TelegramBotService(
    private val telegramToken: String,
    private val commandRegistry: CommandRegistry,
    private val chatOrchestrator: ChatOrchestrator,
    private val settingsManager: ChatSettingsManager,
    private val embeddingService: EmbeddingService,
    private val applicationScope: CoroutineScope,
    private val defaultSystemRole: String,
    private val gigaChatModel: String
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
     * Настройка обработчиков текстовых сообщений (сохранено из оригинальной версии)
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
                    println("✅ Settings loaded, temperature: $temperature")

                    // Step 1: Search RAG documentation
                    println("🔍 Searching relevant documentation via RAG...")
                    val ragService = RagService(embeddingService)

                    val ragContext = try {
                        val searchResult = ragService.findRelevantChunksAcrossAllFiles(
                            question = userMessage,
                            topK = 5,
                            relevanceThreshold = settings.ragRelevanceThreshold.value
                        )

                        if (searchResult.chunks.isNotEmpty()) {
                            println("✅ Found ${searchResult.chunks.size} relevant fragments (avg relevance: %.2f)".format(searchResult.avgRelevance))
                            val context = ragService.formatContextForMultipleFiles(searchResult.chunks)
                            println("📄 RAG context size: ${context.length} chars")
                            println("📋 First 300 chars of context:")
                            println(context.take(300))
                            println("...")
                            context
                        } else {
                            println("⚠️ No relevant documentation found")
                            ""
                        }
                    } catch (e: Exception) {
                        println("⚠️ RAG unavailable: ${e.message}")
                        e.printStackTrace()
                        ""
                    }

                    // Step 2: Prepare system role with RAG context
                    val systemRoleWithRag = if (ragContext.isNotBlank()) {
                        """
                        You are a helpful assistant for the TeleGaGa project.

                        Below is documentation extracted from the project. You MUST use this information to answer questions.

                        DOCUMENTATION:
                        $ragContext

                        CRITICAL INSTRUCTIONS:
                        1. Answer using ONLY the information from the documentation above
                        2. If the answer is in the documentation, provide it with specific details
                        3. Quote relevant parts when answering
                        4. Do NOT say information is not available if it exists in documentation
                        5. Answer in English
                        6. Be specific and cite exact information from the fragments
                        """.trimIndent()
                    } else {
                        defaultSystemRole
                    }

                    println("📤 Sending message to ChatOrchestrator...")
                    // Step 3: Send to ChatOrchestrator
                    val response = chatOrchestrator.processMessage(
                        chatId = ChatIdValue(chatId),
                        userMessage = userMessage,
                        systemRole = SystemPrompt(systemRoleWithRag),
                        temperature = temperature,
                        model = gigaChatModel
                    )

                    println("📥 Response received (${response.text.length} chars)")
                    println("📊 Tokens used: ${response.tokenUsage.totalTokens} total (${response.tokenUsage.promptTokens} prompt + ${response.tokenUsage.completionTokens} completion)")

                    // Step 4: Send response to user
                    val responseText = if (ragContext.isNotBlank()) {
                        buildString {
                            appendLine(response.text)
                            if (response.summaryMessage != null) {
                                appendLine()
                                appendLine(response.summaryMessage)
                            }
                        }
                    } else {
                        if (response.summaryMessage != null) {
                            response.text + "\n\n" + response.summaryMessage
                        } else {
                            response.text
                        }
                    }

                    bot.sendMessage(
                        chatId = ChatId.fromId(chatId),
                        text = responseText
                    )

                } catch (e: Exception) {
                    println("❌ Error processing message: ${e.message}")
                    e.printStackTrace()

                    val errorMessage = when (e) {
                        is ru.dikoresearch.domain.ChatException -> "Ошибка при обработке сообщения: ${e.message}"
                        else -> "Произошла ошибка при обработке вашего сообщения. Попробуйте позже."
                    }

                    bot.sendMessage(
                        chatId = ChatId.fromId(chatId),
                        text = errorMessage
                    )
                }
            }
        }
    }
}
