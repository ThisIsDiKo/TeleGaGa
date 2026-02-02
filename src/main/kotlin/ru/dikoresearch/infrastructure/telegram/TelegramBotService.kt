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
import kotlinx.coroutines.launch
import ru.dikoresearch.domain.ChatException
import ru.dikoresearch.domain.ChatOrchestrator
import ru.dikoresearch.domain.TextChunker
import ru.dikoresearch.infrastructure.embeddings.EmbeddingService
import ru.dikoresearch.infrastructure.persistence.ChatSettingsManager
import ru.dikoresearch.infrastructure.persistence.EmbeddingsManager
import java.io.File

/**
 * Сервис для управления Telegram ботом
 * Инкапсулирует всю логику работы с Telegram API и обработку команд
 */
class TelegramBotService(
    private val telegramToken: String,
    private val chatOrchestrator: ChatOrchestrator,
    private val settingsManager: ChatSettingsManager,
    private val embeddingService: EmbeddingService,
    private val embeddingsManager: EmbeddingsManager,
    private val textChunker: TextChunker,
    private val applicationScope: CoroutineScope,
    private val defaultSystemRole: String,
    private val defaultTemperature: Float,
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
                text = buildString {
                    appendLine("👋 Привет! Я TeleGaGa бот с поддержкой RAG.")
                    appendLine()
                    appendLine("🤖 AI модели:")
                    appendLine("• GigaChat - основная модель для чата")
                    appendLine("• Ollama (nomic-embed-text) - локальные embeddings")
                    appendLine()
                    appendLine("📋 Доступные команды:")
                    appendLine("/changeRole <текст> - изменить системный промпт")
                    appendLine("/changeT <число> - изменить температуру (0.0-1.0)")
                    appendLine("/clearChat - очистить историю чата")
                    appendLine("/createEmbeddings - создать embeddings из rag_docs/readme.md")
                    appendLine()
                    appendLine("💡 Для работы embeddings нужна запущенная Ollama:")
                    appendLine("ollama pull nomic-embed-text")
                }
            )
        }

        command("destroyContext") {
            val chatId = message.chat.id
            bot.sendMessage(
                chatId = ChatId.fromId(chatId),
                text = "Раньше я отправлял примерно 160к токенов (максимум заявлен в 128к)"
            )
        }

        // MCP commands removed - no longer needed for RAG implementation
        /*
        command("listTools") {
            ...
        }
        */

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

            applicationScope.launch {
                val settings = settingsManager.loadSettings(chatId)
                settingsManager.saveSettings(chatId, settings.copy(temperature = newTemperature))

                bot.sendMessage(
                    chatId = ChatId.fromId(chatId),
                    text = "Новая температура ответов: $newTemperature"
                )
                println("Температура изменена для чата $chatId: $newTemperature")
            }
        }

        command("clearChat") {
            val chatId = message.chat.id

            try {
                val deleted = chatOrchestrator.clearHistory(chatId)

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

        // MCP commands removed for RAG implementation
        /*
        command("setReminderTime") { ... }
        command("disableReminders") { ... }
        command("enableMcp") { ... }
        */

        command("createEmbeddings") {
            val chatId = message.chat.id

            applicationScope.launch {
                try {
                    bot.sendMessage(
                        chatId = ChatId.fromId(chatId),
                        text = "🦙 Начинаю генерацию embeddings из файла rag_docs/readme.md...\n" +
                               "(используется локальная модель Ollama)"
                    )

                    // Читаем файл
                    val file = File("rag_docs/readme.md")
                    if (!file.exists()) {
                        bot.sendMessage(
                            chatId = ChatId.fromId(chatId),
                            text = "❌ Ошибка: файл rag_docs/readme.md не найден.\n\n" +
                                   "Создайте папку rag_docs и поместите туда readme.md"
                        )
                        return@launch
                    }

                    val originalText = file.readText()
                    val startTime = System.currentTimeMillis()

                    // Генерируем embeddings с предобработкой Markdown
                    // (удаление код-блоков, разбиение на абзацы)
                    val embeddings = embeddingService.generateEmbeddingsForMarkdown(originalText)

                    val endTime = System.currentTimeMillis()
                    val durationSeconds = (endTime - startTime) / 1000.0

                    // Сохраняем в JSON
                    val outputPath = embeddingsManager.saveEmbeddings(
                        fileName = "readme",
                        embeddings = embeddings,
                        chunkSize = textChunker.chunkSize
                    )

                    // Получаем размерность вектора (берем из первого embedding)
                    val vectorDimension = embeddings.firstOrNull()?.second?.size ?: 0

                    // Формируем детальное сообщение о результате
                    val resultMessage = buildString {
                        appendLine("✅ Embeddings успешно созданы!")
                        appendLine()
                        appendLine("📊 Статистика:")
                        appendLine("• Исходный файл: ${file.name}")
                        appendLine("• Размер файла: ${originalText.length} символов")
                        appendLine("• Создано чанков: ${embeddings.size}")
                        appendLine("• Размерность векторов: $vectorDimension")
                        appendLine("• Время обработки: %.1f сек".format(durationSeconds))
                        appendLine()
                        appendLine("💾 Результат сохранен:")
                        appendLine("$outputPath")
                        appendLine()
                        appendLine("📝 Preview первого чанка:")

                        // Показываем первый чанк и небольшую часть вектора
                        if (embeddings.isNotEmpty()) {
                            val firstChunk = embeddings.first()
                            val chunkPreview = if (firstChunk.first.length > 150) {
                                firstChunk.first.take(150) + "..."
                            } else {
                                firstChunk.first
                            }
                            appendLine("\"$chunkPreview\"")
                            appendLine()
                            appendLine("🔢 Вектор (первые 10 значений):")
                            val vectorPreview = firstChunk.second.take(10).joinToString(", ") { "%.4f".format(it) }
                            appendLine("[$vectorPreview, ...]")
                        }
                    }

                    // Проверяем, что сообщение не превышает лимит Telegram (4096 символов)
                    val finalMessage = if (resultMessage.length > 4000) {
                        resultMessage.take(3997) + "..."
                    } else {
                        resultMessage
                    }

                    bot.sendMessage(
                        chatId = ChatId.fromId(chatId),
                        text = finalMessage
                    )

                } catch (e: Exception) {
                    bot.sendMessage(
                        chatId = ChatId.fromId(chatId),
                        text = "❌ Ошибка при генерации embeddings:\n${e.message}\n\n" +
                               "💡 Убедитесь, что Ollama запущена:\n" +
                               "ollama pull nomic-embed-text"
                    )
                    e.printStackTrace()
                }
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

            // Используем applicationScope для обработки сообщения
            applicationScope.launch {
                try {
                    // Получаем температуру для чата из настроек
                    val settings = settingsManager.loadSettings(chatId)
                    val temperature = settings.temperature

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
                        appendLine(response.text)
                        appendLine()
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
