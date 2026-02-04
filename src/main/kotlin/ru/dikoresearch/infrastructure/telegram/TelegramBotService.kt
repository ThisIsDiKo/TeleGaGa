package ru.dikoresearch.infrastructure.telegram

import GigaChatMessage
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
    private val ollamaClient: ru.dikoresearch.infrastructure.http.OllamaClient,
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
                    appendLine()
                    appendLine("🧠 RAG команды:")
                    appendLine("/createEmbeddings - создать embeddings из rag_docs/readme.md")
                    appendLine("/testRag <вопрос> - сравнить ответы с RAG и без RAG")
                    appendLine("/compareRag <вопрос> - сравнить 3 подхода (без RAG, RAG, RAG+фильтр)")
                    appendLine("/setThreshold <0.0-1.0> - настроить порог релевантности")
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

        command("testRag") {
            val chatId = message.chat.id
            val query = args.joinToString(" ")

            if (query.isBlank()) {
                bot.sendMessage(
                    ChatId.fromId(chatId),
                    "Использование: /testRag <ваш вопрос>\n\n" +
                    "Пример: /testRag Какие MCP серверы используются в проекте?"
                )
                return@command
            }

            applicationScope.launch {
                try {
                    bot.sendMessage(ChatId.fromId(chatId), "🔍 Поиск релевантных чанков...")

                    // 1. Создаем RagService и ищем релевантные чанки
                    val ragService = ru.dikoresearch.domain.RagService(embeddingService)
                    val topChunks = ragService.findRelevantChunks(query, "readme", 5)

                    // 2. Системный промпт для RAG
                    val systemRole = "Ты - полезный ассистент. Отвечай кратко и по существу."

                    // 3. Генерируем ответ БЕЗ RAG (используем Ollama)
                    bot.sendMessage(ChatId.fromId(chatId), "🤖 Генерация ответа БЕЗ RAG (Ollama llama3.2:1b)...")
                    val messagesWithoutRag = listOf(
                        GigaChatMessage(role = "system", content = systemRole),
                        GigaChatMessage(role = "user", content = query)
                    )
                    val responseWithoutRag = ollamaClient.chatCompletion(messagesWithoutRag)
                    val answerWithoutRag = responseWithoutRag.message.content

                    // 4. Генерируем ответ С RAG (используем Ollama)
                    bot.sendMessage(ChatId.fromId(chatId), "🧠 Генерация ответа С RAG (Ollama llama3.2:1b + топ-5 чанков)...")
                    val ragContext = ragService.formatContext(topChunks)
                    val ragPrompt = """
                        Используя следующую информацию из документации, ответь на вопрос.
                        Если информации недостаточно для ответа, так и скажи.

                        $ragContext

                        Вопрос: $query
                    """.trimIndent()

                    val messagesWithRag = listOf(
                        GigaChatMessage(role = "system", content = systemRole),
                        GigaChatMessage(role = "user", content = ragPrompt)
                    )
                    val responseWithRag = ollamaClient.chatCompletion(messagesWithRag)
                    val answerWithRag = responseWithRag.message.content

                    // 5. Форматируем результат
                    val resultMessage = buildString {
                        appendLine("━━━━━━━━━━━━━━━━━━━━")
                        appendLine("🤖 ОТВЕТ БЕЗ RAG:")
                        appendLine("━━━━━━━━━━━━━━━━━━━━")
                        appendLine()
                        // Обрезаем до 800 символов для экономии места
                        appendLine(if (answerWithoutRag.length > 800) {
                            answerWithoutRag.take(797) + "..."
                        } else {
                            answerWithoutRag
                        })
                        appendLine()
                        appendLine("━━━━━━━━━━━━━━━━━━━━")
                        appendLine("🧠 ОТВЕТ С RAG (топ-5 чанков):")
                        appendLine("━━━━━━━━━━━━━━━━━━━━")
                        appendLine()
                        // Обрезаем до 800 символов
                        appendLine(if (answerWithRag.length > 800) {
                            answerWithRag.take(797) + "..."
                        } else {
                            answerWithRag
                        })
                        appendLine()
                        appendLine("━━━━━━━━━━━━━━━━━━━━")
                        appendLine("📊 СТАТИСТИКА:")
                        appendLine("━━━━━━━━━━━━━━━━━━━━")
                        appendLine()
                        appendLine("Найденные чанки:")
                        topChunks.forEachIndexed { i, (_, relevance, index) ->
                            appendLine("${i + 1}. Чанк #$index: %.4f (%.0f%%)".format(
                                relevance, relevance * 100
                            ))
                        }
                        appendLine()
                        appendLine("Использовано токенов (Ollama):")
                        val tokensWithoutRag = responseWithoutRag.prompt_eval_count + responseWithoutRag.eval_count
                        val tokensWithRag = responseWithRag.prompt_eval_count + responseWithRag.eval_count

                        appendLine("• Без RAG: $tokensWithoutRag токенов (prompt: ${responseWithoutRag.prompt_eval_count}, response: ${responseWithoutRag.eval_count})")
                        appendLine("• С RAG: $tokensWithRag токенов (prompt: ${responseWithRag.prompt_eval_count}, response: ${responseWithRag.eval_count})")

                        val increase = if (tokensWithoutRag > 0) {
                            ((tokensWithRag - tokensWithoutRag).toFloat() / tokensWithoutRag * 100).toInt()
                        } else {
                            0
                        }

                        if (increase > 0) {
                            appendLine("  (+$increase% для контекста)")
                        }
                        appendLine()
                        appendLine("━━━━━━━━━━━━━━━━━━━━")
                        appendLine("💡 ВЫВОД:")
                        appendLine("━━━━━━━━━━━━━━━━━━━━")
                        appendLine()

                        // Простой автоматический анализ
                        val avgRelevance = topChunks.map { it.second }.average()
                        when {
                            avgRelevance >= 0.7 -> {
                                appendLine("✅ RAG помог: найдены высокорелевантные чанки (${(avgRelevance * 100).toInt()}%)")
                                appendLine("Ответ С RAG должен быть точнее и содержательнее.")
                            }
                            avgRelevance >= 0.5 -> {
                                appendLine("⚠️ RAG частично помог: средняя релевантность (${(avgRelevance * 100).toInt()}%)")
                                appendLine("Проверьте, насколько ответ С RAG точнее.")
                            }
                            else -> {
                                appendLine("❌ RAG не помог: низкая релевантность чанков (${(avgRelevance * 100).toInt()}%)")
                                appendLine("Вопрос выходит за рамки документации.")
                            }
                        }
                    }

                    // Отправляем результат (проверяем лимит Telegram)
                    sendMessageSafely(chatId, if (resultMessage.length > 3800) {
                        resultMessage.take(3797) + "..."
                    } else {
                        resultMessage
                    })

                } catch (e: Exception) {
                    sendMessageSafely(
                        chatId,
                        "❌ Ошибка при RAG-тестировании:\n${e.message}\n\n" +
                        "💡 Убедитесь, что:\n" +
                        "1. Создали embeddings командой /createEmbeddings\n" +
                        "2. Ollama запущена и доступна"
                    )
                    e.printStackTrace()
                }
            }
        }

        command("setThreshold") {
            val chatId = message.chat.id
            val thresholdStr = args.joinToString(" ")

            if (thresholdStr.isBlank()) {
                applicationScope.launch {
                    val settings = settingsManager.loadSettings(chatId)
                    bot.sendMessage(
                        ChatId.fromId(chatId),
                        """
📊 Текущий порог релевантности: ${settings.ragRelevanceThreshold}

Использование: /setThreshold <значение>
Диапазон: 0.0 - 1.0

Рекомендации:
• 0.3-0.4 (низкий) - больше результатов, может быть шум
• 0.5-0.6 (средний) - сбалансированный подход
• 0.7-0.8 (высокий) - только высокорелевантные чанки

Пример: /setThreshold 0.6
                        """.trimIndent()
                    )
                }
                return@command
            }

            val newThreshold = try {
                thresholdStr.toFloat()
            } catch (e: Exception) {
                bot.sendMessage(
                    ChatId.fromId(chatId),
                    "❌ Ошибка: порог должен быть числом от 0.0 до 1.0\n\n" +
                    "Пример: /setThreshold 0.6"
                )
                return@command
            }

            if (newThreshold < 0.0f || newThreshold > 1.0f) {
                bot.sendMessage(
                    ChatId.fromId(chatId),
                    "❌ Ошибка: порог должен быть в диапазоне 0.0 - 1.0\n\n" +
                    "Вы ввели: $newThreshold"
                )
                return@command
            }

            applicationScope.launch {
                val settings = settingsManager.loadSettings(chatId)
                settingsManager.saveSettings(chatId, settings.copy(
                    ragRelevanceThreshold = newThreshold
                ))

                bot.sendMessage(
                    ChatId.fromId(chatId),
                    """
✅ Порог релевантности обновлен: $newThreshold

Интерпретация:
${when {
                        newThreshold < 0.4f -> "• Низкий порог - будет больше результатов"
                        newThreshold < 0.7f -> "• Средний порог - сбалансированный подход"
                        else -> "• Высокий порог - только самые релевантные чанки"
                    }}

Протестируйте с помощью /compareRag <вопрос>
                    """.trimIndent()
                )
                println("✅ Порог релевантности обновлен для чата $chatId: $newThreshold")
            }
        }

        command("compareRag") {
            val chatId = message.chat.id
            val query = args.joinToString(" ")

            if (query.isBlank()) {
                bot.sendMessage(
                    ChatId.fromId(chatId),
                    "Использование: /compareRag <ваш вопрос>\n\n" +
                    "Сравнит 3 подхода:\n" +
                    "1. БЕЗ RAG\n" +
                    "2. С RAG (топ-5 без фильтра)\n" +
                    "3. С RAG + фильтр релевантности\n\n" +
                    "Пример: /compareRag Какие MCP серверы используются?"
                )
                return@command
            }

            applicationScope.launch {
                try {
                    // Получаем порог из настроек
                    val settings = settingsManager.loadSettings(chatId)
                    val threshold = settings.ragRelevanceThreshold

                    bot.sendMessage(ChatId.fromId(chatId),
                        "🔍 Начинаю сравнение трех подходов RAG (порог: ${threshold})..."
                    )

                    val ragService = ru.dikoresearch.domain.RagService(embeddingService)
                    val systemRole = "Ты - полезный ассистент. Отвечай кратко и по существу."

                    // === ПОДХОД 1: БЕЗ RAG ===
                    bot.sendMessage(ChatId.fromId(chatId), "1/3 Генерация ответа БЕЗ RAG...")
                    val startTimeNoRag = System.currentTimeMillis()
                    val messagesNoRag = listOf(
                        GigaChatMessage(role = "system", content = systemRole),
                        GigaChatMessage(role = "user", content = query)
                    )
                    val responseNoRag = ollamaClient.chatCompletion(messagesNoRag)
                    val timeNoRag = System.currentTimeMillis() - startTimeNoRag
                    val answerNoRag = responseNoRag.message.content

                    // === ПОДХОД 2: С RAG (топ-5, без фильтра) ===
                    bot.sendMessage(ChatId.fromId(chatId), "2/3 Генерация ответа С RAG (без фильтра)...")
                    val startTimeRagNoFilter = System.currentTimeMillis()
                    val topChunksNoFilter = ragService.findRelevantChunks(query, "readme", 5)
                    val contextNoFilter = ragService.formatContext(topChunksNoFilter)
                    val ragPromptNoFilter = """
Используя следующую информацию из документации, ответь на вопрос.
Если информации недостаточно для ответа, так и скажи.

$contextNoFilter

Вопрос: $query
                    """.trimIndent()

                    val messagesRagNoFilter = listOf(
                        GigaChatMessage(role = "system", content = systemRole),
                        GigaChatMessage(role = "user", content = ragPromptNoFilter)
                    )
                    val responseRagNoFilter = ollamaClient.chatCompletion(messagesRagNoFilter)
                    val timeRagNoFilter = System.currentTimeMillis() - startTimeRagNoFilter
                    val answerRagNoFilter = responseRagNoFilter.message.content

                    // === ПОДХОД 3: С RAG + ФИЛЬТР ===
                    bot.sendMessage(ChatId.fromId(chatId), "3/3 Генерация ответа С RAG + фильтр (≥${threshold})...")
                    val startTimeRagFiltered = System.currentTimeMillis()
                    val searchResult = ragService.findRelevantChunksWithFilter(
                        query, "readme", 5, threshold
                    )

                    val answerRagFiltered = if (searchResult.filteredCount > 0) {
                        val contextFiltered = ragService.formatContext(searchResult.chunks)
                        val ragPromptFiltered = """
Используя следующую информацию из документации, ответь на вопрос.
Если информации недостаточно для ответа, так и скажи.

$contextFiltered

Вопрос: $query
                        """.trimIndent()

                        val messagesRagFiltered = listOf(
                            GigaChatMessage(role = "system", content = systemRole),
                            GigaChatMessage(role = "user", content = ragPromptFiltered)
                        )
                        val responseRagFiltered = ollamaClient.chatCompletion(messagesRagFiltered)
                        responseRagFiltered.message.content
                    } else {
                        "⚠️ Не найдено релевантных чанков (все < ${threshold}). Отвечаю без RAG:\n\n$answerNoRag"
                    }
                    val timeRagFiltered = System.currentTimeMillis() - startTimeRagFiltered

                    // === ФОРМАТИРУЕМ РЕЗУЛЬТАТ ===
                    val resultMessage = buildString {
                        appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                        appendLine("📊 СРАВНЕНИЕ ТРЕХ ПОДХОДОВ RAG")
                        appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                        appendLine()
                        appendLine("🔍 Вопрос: $query")
                        appendLine()

                        // 1. БЕЗ RAG
                        appendLine("━━━ 1️⃣ БЕЗ RAG ━━━")
                        appendLine(answerNoRag.take(600))
                        appendLine()
                        appendLine("⏱️ Время: ${timeNoRag}ms")
                        appendLine()

                        // 2. RAG БЕЗ ФИЛЬТРА
                        appendLine("━━━ 2️⃣ RAG (топ-5, без фильтра) ━━━")
                        appendLine(answerRagNoFilter.take(600))
                        appendLine()
                        appendLine("📊 Чанки:")
                        topChunksNoFilter.forEachIndexed { i, (_, rel, idx) ->
                            appendLine("   ${i+1}. Чанк #$idx: %.4f (%.0f%%)".format(rel, rel * 100))
                        }
                        appendLine("⏱️ Время: ${timeRagNoFilter}ms")
                        appendLine()

                        // 3. RAG С ФИЛЬТРОМ
                        appendLine("━━━ 3️⃣ RAG + ФИЛЬТР (≥${threshold}) ━━━")
                        appendLine(answerRagFiltered.take(600))
                        appendLine()
                        appendLine("📊 Чанки:")
                        appendLine("   Найдено кандидатов: ${searchResult.originalCount}")
                        appendLine("   Прошли фильтр: ${searchResult.filteredCount}")
                        if (searchResult.filteredCount > 0) {
                            appendLine("   Средняя релевантность: %.4f".format(searchResult.avgRelevance))
                            searchResult.chunks.forEachIndexed { i, (_, rel, idx) ->
                                appendLine("   ${i+1}. Чанк #$idx: %.4f (%.0f%%)".format(rel, rel * 100))
                            }
                        }
                        appendLine("⏱️ Время: ${timeRagFiltered}ms")
                        appendLine()

                        // ВЫВОДЫ
                        appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                        appendLine("💡 АВТОМАТИЧЕСКИЙ АНАЛИЗ:")
                        appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                        appendLine()

                        when {
                            searchResult.filteredCount == 0 -> {
                                appendLine("❌ Фильтр отсек все чанки - релевантность низкая")
                                appendLine("   Рекомендация: уменьшить порог или вопрос вне документации")
                            }
                            searchResult.filteredCount < topChunksNoFilter.size -> {
                                appendLine("✅ Фильтр отсек ${topChunksNoFilter.size - searchResult.filteredCount} нерелевантных чанков")
                                appendLine("   Ответ 3 должен быть точнее ответа 2")
                            }
                            else -> {
                                appendLine("✅ Все чанки прошли фильтр - высокая релевантность")
                                appendLine("   Ответы 2 и 3 должны быть схожи")
                            }
                        }

                        appendLine()
                        appendLine("Используйте /setThreshold для изменения порога")
                    }

                    // Отправляем результат (проверяем лимит Telegram)
                    sendMessageSafely(chatId, if (resultMessage.length > 3800) {
                        resultMessage.take(3797) + "..."
                    } else {
                        resultMessage
                    })

                } catch (e: Exception) {
                    sendMessageSafely(chatId,
                        "❌ Ошибка при сравнении RAG:\n${e.message}"
                    )
                    e.printStackTrace()
                }
            }
        }

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
