package ru.dikoresearch.infrastructure.telegram

import GigaChatFunction
import GigaChatFunctionCall
import GigaChatFunctionParameters
import GigaChatMessage
import GigaChatPropertySchema
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
import kotlinx.serialization.json.*
import ru.dikoresearch.domain.ChatException
import ru.dikoresearch.domain.ChatOrchestrator
import ru.dikoresearch.domain.DiffAnalyzer
import ru.dikoresearch.domain.DiffChunker
import ru.dikoresearch.domain.DiffParser
import ru.dikoresearch.domain.PullRequestInfo
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
    private val gigaChatClient: ru.dikoresearch.infrastructure.http.GigaChatClient,
    private val gitMcpService: ru.dikoresearch.infrastructure.mcp.StdioMcpService,
    private val githubMcpService: ru.dikoresearch.infrastructure.mcp.StdioMcpService?,
    private val githubOwner: String?,
    private val githubRepo: String?,
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
     * Helper function to create embeddings for a single file
     */
    private suspend fun createEmbeddingsForFile(
        chatId: Long,
        filePath: String,
        bot: Bot,
        embeddingService: EmbeddingService,
        embeddingsManager: EmbeddingsManager,
        textChunker: TextChunker
    ): String {
        val file = File(filePath)
        if (!file.exists()) {
            return "❌ File not found: $filePath"
        }

        val originalText = file.readText()
        val startTime = System.currentTimeMillis()

        val embeddingsWithMetadata = embeddingService.generateEmbeddingsWithMetadata(
            originalText,
            file.name
        )

        val endTime = System.currentTimeMillis()
        val durationSeconds = (endTime - startTime) / 1000.0

        val fileNameWithoutExtension = file.nameWithoutExtension
        embeddingsManager.saveEmbeddingsWithMetadata(
            fileName = fileNameWithoutExtension,
            embeddings = embeddingsWithMetadata,
            chunkSize = textChunker.chunkSize
        )

        return "✅ ${file.name}: ${embeddingsWithMetadata.size} chunks in %.1f sec".format(durationSeconds)
    }

    /**
     * Helper function to convert MCP tools to GigaChat functions format
     */
    private fun convertMcpToolsToGigaChatFunctions(
        tools: List<ru.dikoresearch.infrastructure.mcp.StdioMcpService.Tool>
    ): List<GigaChatFunction> {
        return tools.map { tool ->
            val inputSchema = tool.inputSchema

            // Extract properties from JSON Schema
            val properties = inputSchema["properties"]?.jsonObject?.mapValues { (_, value) ->
                val propObj = value.jsonObject
                GigaChatPropertySchema(
                    type = propObj["type"]?.jsonPrimitive?.content ?: "string",
                    description = propObj["description"]?.jsonPrimitive?.content
                )
            } ?: emptyMap()

            // Extract required fields
            val required = inputSchema["required"]?.jsonArray?.mapNotNull {
                it.jsonPrimitive?.content
            }

            GigaChatFunction(
                name = tool.name,
                description = tool.description,
                parameters = GigaChatFunctionParameters(
                    type = "object",
                    properties = properties,
                    required = required
                )
            )
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
                    appendLine("👋 Hello! I'm TeleGaGa bot with RAG support.")
                    appendLine()
                    appendLine("🤖 AI Models:")
                    appendLine("• GigaChat - main chat model (analysis & responses)")
                    appendLine("• Ollama (nomic-embed-text) - local embeddings for RAG")
                    appendLine()
                    appendLine("📋 Chat Commands:")
                    appendLine("/changeRole <text> - change system prompt")
                    appendLine("/changeT <number> - change temperature (0.0-1.0)")
                    appendLine("/clearChat - clear chat history")
                    appendLine()
                    appendLine("🔍 RAG Commands:")
                    appendLine("/help <question> - Ask questions about project (uses GigaChat + RAG)")
                    appendLine("/createEmbeddings - create embeddings from all .md files in rag_docs/")
                    appendLine("/setThreshold <0.0-1.0> - set relevance threshold for RAG")
                    appendLine()
                    appendLine("🔧 GitHub Commands:")
                    appendLine("/showPR [number] - Analyze Pull Request with RAG + GigaChat")
                    appendLine("/listGitHubTools - Show available GitHub MCP tools")
                    appendLine()
                    appendLine("💡 Requirements:")
                    appendLine("• Ollama must be running: ollama pull nomic-embed-text")
                    appendLine("• GigaChat API key configured")
                    appendLine("• GitHub token in config.properties (for /showPR)")
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

                    // 2. System prompt for RAG
                    val systemRole = "You are a helpful assistant. Answer concisely and to the point in English."

                    // 3. Generate answer WITHOUT RAG (using Ollama)
                    bot.sendMessage(ChatId.fromId(chatId), "🤖 Generating answer WITHOUT RAG (Ollama llama3.2:3b)...")
                    val messagesWithoutRag = listOf(
                        GigaChatMessage(role = "system", content = systemRole),
                        GigaChatMessage(role = "user", content = query)
                    )
                    val responseWithoutRag = ollamaClient.chatCompletion(messagesWithoutRag)
                    val answerWithoutRag = responseWithoutRag.message.content

                    // 4. Generate answer WITH RAG (using Ollama)
                    bot.sendMessage(ChatId.fromId(chatId), "🧠 Generating answer WITH RAG (Ollama llama3.2:3b + top-5 chunks)...")
                    val ragContext = ragService.formatContext(topChunks)
                    val ragPrompt = """
                        Using the following information from documentation, answer the question.
                        If information is not sufficient for the answer, say so.
                        Answer in English.

                        $ragContext

                        Question: $query
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
                    val systemRole = "You are a helpful assistant. Answer concisely and to the point in English."

                    // === APPROACH 1: WITHOUT RAG ===
                    bot.sendMessage(ChatId.fromId(chatId), "1/3 Generating answer WITHOUT RAG...")
                    val startTimeNoRag = System.currentTimeMillis()
                    val messagesNoRag = listOf(
                        GigaChatMessage(role = "system", content = systemRole),
                        GigaChatMessage(role = "user", content = query)
                    )
                    val responseNoRag = ollamaClient.chatCompletion(messagesNoRag)
                    val timeNoRag = System.currentTimeMillis() - startTimeNoRag
                    val answerNoRag = responseNoRag.message.content

                    // === APPROACH 2: WITH RAG (top-5, no filter) ===
                    bot.sendMessage(ChatId.fromId(chatId), "2/3 Generating answer WITH RAG (no filter)...")
                    val startTimeRagNoFilter = System.currentTimeMillis()
                    val topChunksNoFilter = ragService.findRelevantChunks(query, "readme", 5)
                    val contextNoFilter = ragService.formatContext(topChunksNoFilter)
                    val ragPromptNoFilter = """
Using the following information from documentation, answer the question.
If information is not sufficient for the answer, say so.
Answer in English.

$contextNoFilter

Question: $query
                    """.trimIndent()

                    val messagesRagNoFilter = listOf(
                        GigaChatMessage(role = "system", content = systemRole),
                        GigaChatMessage(role = "user", content = ragPromptNoFilter)
                    )
                    val responseRagNoFilter = ollamaClient.chatCompletion(messagesRagNoFilter)
                    val timeRagNoFilter = System.currentTimeMillis() - startTimeRagNoFilter
                    val answerRagNoFilter = responseRagNoFilter.message.content

                    // === APPROACH 3: WITH RAG + FILTER ===
                    bot.sendMessage(ChatId.fromId(chatId), "3/3 Generating answer WITH RAG + filter (≥${threshold})...")
                    val startTimeRagFiltered = System.currentTimeMillis()
                    val searchResult = ragService.findRelevantChunksWithFilter(
                        query, "readme", 5, threshold
                    )

                    val answerRagFiltered = if (searchResult.filteredCount > 0) {
                        val contextFiltered = ragService.formatContext(searchResult.chunks)
                        val ragPromptFiltered = """
Using the following information from documentation, answer the question.
If information is not sufficient for the answer, say so.
Answer in English.

$contextFiltered

Question: $query
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

        command("testRagCitations") {
            val chatId = message.chat.id
            val query = args.joinToString(" ")

            if (query.isBlank()) {
                bot.sendMessage(
                    ChatId.fromId(chatId),
                    "Использование: /testRagCitations <ваш вопрос>\n\n" +
                    "Команда для тестирования RAG с обязательным цитированием источников.\n" +
                    "Использует модель qwen3:4b для генерации ответов.\n\n" +
                    "Пример: /testRagCitations Какие MCP серверы используются в проекте?"
                )
                return@command
            }

            applicationScope.launch {
                try {
                    bot.sendMessage(ChatId.fromId(chatId),
                        "🔍 Тестирование RAG с цитированием источников...\n" +
                        "Модель: qwen3:4b"
                    )

                    val ragService = ru.dikoresearch.domain.RagService(embeddingService)

                    // Получаем порог из настроек
                    val settings = settingsManager.loadSettings(chatId)
                    val threshold = settings.ragRelevanceThreshold

                    bot.sendMessage(ChatId.fromId(chatId),
                        "📚 Поиск релевантных чанков (порог: $threshold)..."
                    )

                    // Находим релевантные чанки с фильтром
                    val searchResult = ragService.findRelevantChunksWithFilter(
                        query, "readme", 5, threshold
                    )

                    if (searchResult.filteredCount == 0) {
                        bot.sendMessage(ChatId.fromId(chatId),
                            "⚠️ Не найдено релевантных чанков (порог: $threshold)\n" +
                            "Попробуйте снизить порог командой /setThreshold"
                        )
                        return@launch
                    }

                    bot.sendMessage(ChatId.fromId(chatId),
                        "✅ Найдено ${searchResult.filteredCount} релевантных чанков\n" +
                        "Средняя релевантность: %.2f%%".format(searchResult.avgRelevance * 100)
                    )

                    // Format context with citations
                    val contextWithCitations = ragService.formatContextWithCitations(
                        searchResult.chunks,
                        "readme.md"
                    )

                    // System prompt for citations
                    val systemRole = ru.dikoresearch.RagWithCitationsRole

                    val ragPrompt = """
                        $contextWithCitations

                        Question: $query
                    """.trimIndent()

                    // DEBUG: Print prompt to console
                    println("=== RAG PROMPT DEBUG ===")
                    println("System role length: ${systemRole.length}")
                    println("RAG prompt length: ${ragPrompt.length}")
                    println("Context preview (first 500 chars):")
                    println(contextWithCitations.take(500))
                    println("======================")

                    bot.sendMessage(ChatId.fromId(chatId),
                        "🤖 Generating answer with citations (llama3.2:3b)..."
                    )

                    val messages = listOf(
                        GigaChatMessage(role = "system", content = systemRole),
                        GigaChatMessage(role = "user", content = ragPrompt)
                    )

                    val response = ollamaClient.chatCompletion(messages)
                    val answer = response.message.content

                    // Analyze presence of citations in answer
                    val citationPattern = """\[.*?\]""".toRegex()
                    val citations = citationPattern.findAll(answer).count()

                    val resultMessage = buildString {
                        appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                        appendLine("📖 RAG WITH SOURCE CITATIONS")
                        appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                        appendLine()
                        appendLine("🔍 Question: $query")
                        appendLine()
                        appendLine("━━━ ANSWER ━━━")
                        appendLine(answer)
                        appendLine()
                        appendLine("━━━ STATISTICS ━━━")
                        appendLine("📊 Chunks used: ${searchResult.filteredCount}")
                        appendLine("📎 Citations found: $citations")
                        appendLine("✅ Relevance threshold: $threshold")
                        appendLine("📈 Average relevance: %.2f%%".format(searchResult.avgRelevance * 100))
                        appendLine()

                        if (citations == 0) {
                            appendLine("⚠️ WARNING: Answer doesn't contain source references!")
                            appendLine("   Model may have ignored instructions.")
                        } else {
                            appendLine("✅ Answer contains $citations source references in square brackets")
                        }
                    }

                    // Отправляем результат
                    sendMessageSafely(chatId, if (resultMessage.length > 3800) {
                        resultMessage.take(3797) + "..."
                    } else {
                        resultMessage
                    })

                } catch (e: Exception) {
                    sendMessageSafely(chatId,
                        "❌ Ошибка при тестировании RAG с цитированием:\n${e.message}"
                    )
                    e.printStackTrace()
                }
            }
        }

        command("createEmbeddings") {
            val chatId = message.chat.id
            val fileName = args.firstOrNull()

            applicationScope.launch {
                try {
                    if (fileName != null) {
                        // Single file mode
                        bot.sendMessage(
                            chatId = ChatId.fromId(chatId),
                            text = "🦙 Generating embeddings for $fileName...\n(using Ollama local model)"
                        )

                        val result = createEmbeddingsForFile(
                            chatId,
                            "rag_docs/$fileName",
                            bot,
                            embeddingService,
                            embeddingsManager,
                            textChunker
                        )

                        bot.sendMessage(ChatId.fromId(chatId), result)
                    } else {
                        // Process ALL .md files in rag_docs/
                        val ragDir = File("rag_docs")
                        if (!ragDir.exists()) {
                            bot.sendMessage(
                                ChatId.fromId(chatId),
                                "❌ Directory rag_docs/ not found"
                            )
                            return@launch
                        }

                        val mdFiles = ragDir.listFiles { file ->
                            file.extension == "md"
                        }?.toList() ?: emptyList()

                        if (mdFiles.isEmpty()) {
                            bot.sendMessage(
                                ChatId.fromId(chatId),
                                "❌ No .md files found in rag_docs/"
                            )
                            return@launch
                        }

                        bot.sendMessage(
                            ChatId.fromId(chatId),
                            "🦙 Found ${mdFiles.size} markdown files. Processing...\n(using Ollama local model)"
                        )

                        val results = mutableListOf<String>()
                        mdFiles.forEachIndexed { i, file ->
                            bot.sendMessage(
                                ChatId.fromId(chatId),
                                "[${i + 1}/${mdFiles.size}] Processing ${file.name}..."
                            )

                            val result = createEmbeddingsForFile(
                                chatId,
                                file.absolutePath,
                                bot,
                                embeddingService,
                                embeddingsManager,
                                textChunker
                            )
                            results.add(result)
                        }

                        val summary = buildString {
                            appendLine("✅ Embeddings created for ${mdFiles.size} files!")
                            appendLine()
                            results.forEach { appendLine(it) }
                        }

                        bot.sendMessage(ChatId.fromId(chatId), summary)
                    }
                } catch (e: Exception) {
                    bot.sendMessage(
                        chatId = ChatId.fromId(chatId),
                        text = "❌ Error: ${e.message}\n\n" +
                               "💡 Make sure Ollama is running:\n" +
                               "ollama pull nomic-embed-text"
                    )
                    e.printStackTrace()
                }
            }
        }

        command("help") {
            val chatId = message.chat.id
            val question = args.joinToString(" ")

            if (question.isBlank()) {
                bot.sendMessage(
                    chatId = ChatId.fromId(chatId),
                    text = """
                        🔍 PROJECT HELP

                        Usage: /help <your question>

                        Examples:
                        • /help What MCP servers are available?
                        • /help How do I change temperature?
                        • /help How to add a new command?

                        This command uses RAG to search all project documentation.
                    """.trimIndent()
                )
                return@command
            }

            applicationScope.launch {
                try {
                    bot.sendMessage(
                        chatId = ChatId.fromId(chatId),
                        text = "🔍 Searching documentation..."
                    )

                    // Step 1: Load RAG settings
                    val settings = settingsManager.loadSettings(chatId)

                    // Step 2: Search across all documentation files
                    val ragService = ru.dikoresearch.domain.RagService(embeddingService)

                    val searchResult = ragService.findRelevantChunksAcrossAllFiles(
                        question = question,
                        topK = 5,
                        relevanceThreshold = settings.ragRelevanceThreshold
                    )

                    if (searchResult.chunks.isEmpty()) {
                        bot.sendMessage(
                            chatId = ChatId.fromId(chatId),
                            text = """
                                ❌ No relevant documentation found.

                                Try:
                                • Rephrasing your question
                                • Running /createEmbeddings to refresh docs
                                • Checking if embeddings exist
                            """.trimIndent()
                        )
                        return@launch
                    }

                    // Step 3: Format context with citations
                    val ragContext = ragService.formatContextForMultipleFiles(searchResult.chunks)

                    // Step 4: Build prompt for GigaChat with RAG context (documentation only)
                    val systemRole = """
                        You are a programming assistant for the TeleGaGa project.
                        Answer questions based on the provided documentation fragments.

                        RULES:
                        - Answer in English only
                        - Use documentation fragments as primary source
                        - Include code snippets when relevant
                        - Cite sources using [filename:lines] format
                        - Be concise and specific
                        - Include citations for facts from documentation
                    """.trimIndent()

                    val userPrompt = buildString {
                        appendLine(ragContext)
                        appendLine()
                        appendLine("Question: $question")
                    }

                    // Step 5: Get answer from GigaChat (no function calling for /help)
                    val (answer, usage) = chatOrchestrator.processMessageWithoutHistory(
                        systemRole = systemRole,
                        userMessage = userPrompt,
                        temperature = 0.7f,
                        model = gigaChatModel
                    )

                    // Step 6: Format final response
                    val formattedAnswer = buildString {
                        appendLine("━━━ PROJECT HELP (RAG) ━━━")
                        appendLine()
                        appendLine("🔍 Question: $question")
                        appendLine()
                        appendLine("📖 Answer:")
                        appendLine(answer)
                        appendLine()
                        appendLine("━━━ SOURCES ━━━")
                        searchResult.chunks.forEach { chunk ->
                            appendLine("• ${chunk.fileName} (lines ${chunk.startLine}-${chunk.endLine})")
                        }
                        appendLine()
                        appendLine("📊 Statistics:")
                        appendLine("• Chunks found: ${searchResult.filteredCount}")
                        appendLine("• Avg relevance: %.0f%%".format(searchResult.avgRelevance * 100))
                        appendLine("• Threshold: ${settings.ragRelevanceThreshold}")
                        appendLine("• Tokens used: ${usage.totalTokens}")
                    }

                    // Truncate if too long (Telegram limit: 4096 chars)
                    val finalAnswer = if (formattedAnswer.length > 3800) {
                        formattedAnswer.take(3800) + "\n\n... (truncated)"
                    } else {
                        formattedAnswer
                    }

                    bot.sendMessage(
                        chatId = ChatId.fromId(chatId),
                        text = finalAnswer
                    )

                } catch (e: IllegalStateException) {
                    // No embeddings found
                    bot.sendMessage(
                        chatId = ChatId.fromId(chatId),
                        text = """
                            ❌ ${e.message}

                            Please run: /createEmbeddings
                        """.trimIndent()
                    )
                } catch (e: Exception) {
                    bot.sendMessage(
                        chatId = ChatId.fromId(chatId),
                        text = "❌ Error: ${e.message}"
                    )
                    e.printStackTrace()
                }
            }
        }

        command("listGitHubTools") {
            val chatId = message.chat.id

            if (githubMcpService == null || !githubMcpService.isAvailable()) {
                bot.sendMessage(
                    chatId = ChatId.fromId(chatId),
                    text = "GitHub MCP service is not available."
                )
                return@command
            }

            applicationScope.launch {
                try {
                    val tools = githubMcpService.listTools()
                    val toolsList = tools.joinToString("\n") { tool ->
                        "- ${tool.name}: ${tool.description}"
                    }

                    bot.sendMessage(
                        chatId = ChatId.fromId(chatId),
                        text = """
                            Available GitHub MCP Tools (${tools.size}):

                            $toolsList
                        """.trimIndent()
                    )
                } catch (e: Exception) {
                    bot.sendMessage(
                        chatId = ChatId.fromId(chatId),
                        text = "Error listing tools: ${e.message}"
                    )
                }
            }
        }

        command("showPR") {
            val chatId = message.chat.id
            val prNumberArg = args.firstOrNull()

            // Check if GitHub MCP is available
            if (githubMcpService == null || !githubMcpService.isAvailable()) {
                bot.sendMessage(
                    chatId = ChatId.fromId(chatId),
                    text = """
                        GitHub MCP service is not available.

                        Setup:
                        1. Add GitHub token to config.properties:
                           github.token=ghp_your_token_here
                           github.owner=your_username
                           github.repo=your_repo

                        2. Restart the bot

                        Token scopes needed:
                        - public_repo (for public repos)
                        - repo (for private repos)
                    """.trimIndent()
                )
                return@command
            }

            // Check if owner and repo are configured
            if (githubOwner.isNullOrBlank() || githubRepo.isNullOrBlank()) {
                bot.sendMessage(
                    chatId = ChatId.fromId(chatId),
                    text = """
                        GitHub owner and repo not configured.

                        Please add to config.properties:
                        github.owner=your_username
                        github.repo=your_repo

                        Then restart the bot.
                    """.trimIndent()
                )
                return@command
            }

            applicationScope.launch {
                try {
                    // Step 1: Fetch PR information
                    bot.sendMessage(
                        chatId = ChatId.fromId(chatId),
                        text = if (prNumberArg != null) {
                            "Fetching Pull Request #$prNumberArg..."
                        } else {
                            "Fetching latest open Pull Request..."
                        }
                    )

                    val prInfo = if (prNumberArg != null) {
                        getPullRequestByNumber(prNumberArg.toInt())
                    } else {
                        getLatestOpenPullRequest()
                    }

                    if (prInfo == null) {
                        bot.sendMessage(
                            chatId = ChatId.fromId(chatId),
                            text = "No Pull Request found."
                        )
                        return@launch
                    }

                    // Send PR summary
                    bot.sendMessage(
                        chatId = ChatId.fromId(chatId),
                        text = prInfo.formatSummary()
                    )

                    // Step 2: RAG Analysis
                    bot.sendMessage(
                        chatId = ChatId.fromId(chatId),
                        text = "Analyzing Pull Request with RAG and local documentation..."
                    )

                    val ragContext = try {
                        val ragService = ru.dikoresearch.domain.RagService(embeddingService)
                        val query = buildString {
                            appendLine("Context: Analyzing pull request for code review")
                            appendLine()
                            appendLine("PR Title: ${prInfo.title}")
                            appendLine("PR Description: ${prInfo.description.take(500)}")
                            appendLine("Modified files: ${prInfo.modifiedFiles.joinToString(", ")}")
                            appendLine()
                            appendLine("Question: What project components, patterns, and best practices are relevant to this PR?")
                        }

                        val searchResult = ragService.findRelevantChunksAcrossAllFiles(
                            question = query,
                            topK = 5,
                            relevanceThreshold = 0.5f
                        )

                        ragService.formatContextForMultipleFiles(searchResult.chunks)
                    } catch (e: Exception) {
                        // RAG is optional, continue without it
                        ""
                    }

                    // Step 3: Get diff and analyze
                    bot.sendMessage(
                        chatId = ChatId.fromId(chatId),
                        text = "Getting diff from Pull Request and analyzing it..."
                    )

                    val diff = getPullRequestDiff(prInfo.number)

                    if (diff.isBlank()) {
                        bot.sendMessage(
                            chatId = ChatId.fromId(chatId),
                            text = "No diff available for this Pull Request."
                        )
                        return@launch
                    }

                    // Parse diff
                    val files = DiffParser.parseDiffIntoFiles(diff)
                    val analyzableFiles = files.filter { DiffParser.shouldAnalyzeFile(it.fileName) }

                    if (analyzableFiles.isEmpty()) {
                        bot.sendMessage(
                            chatId = ChatId.fromId(chatId),
                            text = "No code files to analyze (only binary files or no changes)."
                        )
                        return@launch
                    }

                    // Chunk files
                    val chunks = DiffChunker.groupFilesIntoChunks(analyzableFiles, maxChunkSize = 3000)

                    // Analyze each chunk
                    val analyzer = DiffAnalyzer(
                        gigaChatClient = gigaChatClient,
                        model = gigaChatModel
                    )

                    val analyses = chunks.mapIndexed { index, chunk ->
                        bot.sendMessage(
                            chatId = ChatId.fromId(chatId),
                            text = "Analyzing chunk ${index + 1} of ${chunks.size}..."
                        )

                        analyzer.analyzeChunk(
                            chunk = chunk,
                            ragContext = ragContext,
                            chunkIndex = index,
                            totalChunks = chunks.size
                        )
                    }

                    // Step 4: Synthesize and send report
                    val report = analyzer.synthesizeReport(analyses)

                    val reportText = report.format()

                    // Split report if too long
                    if (reportText.length <= 4000) {
                        bot.sendMessage(
                            chatId = ChatId.fromId(chatId),
                            text = reportText,
                            parseMode = ParseMode.MARKDOWN
                        )
                    } else {
                        // Send in multiple messages
                        val parts = reportText.chunked(4000)
                        parts.forEachIndexed { index, part ->
                            bot.sendMessage(
                                chatId = ChatId.fromId(chatId),
                                text = if (index == 0) part else "...$part",
                                parseMode = ParseMode.MARKDOWN
                            )
                        }
                    }

                } catch (e: Exception) {
                    bot.sendMessage(
                        chatId = ChatId.fromId(chatId),
                        text = "Error analyzing PR: ${e.message}"
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

            println("📨 Получено сообщение от чата $chatId: '$userMessage'")

            // Используем applicationScope для обработки сообщения
            applicationScope.launch {
                try {
                    println("⚙️ Загрузка настроек для чата $chatId...")
                    // Получаем температуру для чата из настроек
                    val settings = settingsManager.loadSettings(chatId)
                    val temperature = settings.temperature
                    println("✅ Настройки загружены, температура: $temperature")

                    // Get Git MCP tools
                    val gitTools = gitMcpService.listTools()
                    val gitFunctions = convertMcpToolsToGigaChatFunctions(gitTools)

                    println("🔧 Git MCP tools available: ${gitFunctions.size}")

                    if (gitFunctions.isNotEmpty()) {
                        println("🤖 Обработка сообщения через ChatOrchestrator с Git MCP function calling...")

                        // Enhanced system role with git tools instructions
                        val enhancedSystemRole = """
                            $defaultSystemRole

                            IMPORTANT: You have access to Git tools for repository /Users/dmitriikonovalov/Documents/TeleGaGa.

                            MANDATORY - Use git tools when user asks about:
                            - branches (list/current) → CALL git_list_branches or git_show_current_branch
                            - commits/history → CALL git_log
                            - repository status/changes → CALL git_status
                            - files in repository → CALL appropriate git tool

                            DO NOT just describe git commands - ALWAYS CALL the actual tool to get real data.
                            Answer in English only.
                        """.trimIndent()

                        // Process with function calling
                        val (answer, usage, functionCalls) = chatOrchestrator.processMessageWithFunctionCalling(
                            systemRole = enhancedSystemRole,
                            userMessage = userMessage,
                            temperature = temperature,
                            model = gigaChatModel,
                            functions = gitFunctions,
                            functionExecutor = { functionName, args ->
                                println("🔧 Executing git tool: $functionName with args: $args")
                                val result = gitMcpService.callTool(functionName, args)
                                val text = result.content.firstOrNull()?.text ?: result.toString()
                                println("✅ Git tool result: ${text.take(200)}...")
                                text
                            },
                            maxIterations = 3
                        )

                        println("✅ Получен ответ от ChatOrchestrator (${answer.length} символов)")
                        if (functionCalls.isNotEmpty()) {
                            println("🔧 Function calls used: ${functionCalls.joinToString(", ")}")
                        }

                        // Формируем ответ с информацией о токенах
                        val fullResponse = buildString {
                            appendLine(answer)
                            appendLine()
                            if (functionCalls.isNotEmpty()) {
                                appendLine("🔧 Git tools used: ${functionCalls.joinToString(", ")}")
                                appendLine()
                            }
                            appendLine("Отправлены токены: ${usage.promptTokens}")
                            appendLine("Получены токены: ${usage.completionTokens}")
                            appendLine("Оплачены токены: ${usage.totalTokens}")
                        }

                        // Отправляем основной ответ
                        sendMessageSafely(chatId, fullResponse)
                    } else {
                        // Fallback to simple message processing without git MCP
                        println("🤖 Обработка сообщения через ChatOrchestrator (без Git MCP)...")
                        val response = chatOrchestrator.processMessage(
                            chatId = chatId,
                            userMessage = userMessage,
                            systemRole = defaultSystemRole,
                            temperature = temperature,
                            model = gigaChatModel
                        )
                        println("✅ Получен ответ от ChatOrchestrator (${response.text.length} символов)")

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
                    }

                } catch (e: Exception) {
                    println("❌ Ошибка при обработке сообщения от чата $chatId: ${e.message}")
                    e.printStackTrace()
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

    /**
     * Get latest open Pull Request using GitHub MCP
     */
    private suspend fun getLatestOpenPullRequest(): PullRequestInfo? {
        if (githubMcpService == null || githubOwner.isNullOrBlank() || githubRepo.isNullOrBlank()) {
            return null
        }

        return try {
            // List open PRs
            val result = githubMcpService.callTool(
                name = "list_pull_requests",
                args = mapOf(
                    "owner" to githubOwner,
                    "repo" to githubRepo,
                    "state" to "open"
                )
            )

            // Parse first PR from result
            parsePullRequestFromList(result)
        } catch (e: Exception) {
            println("Error fetching PRs: ${e.message}")
            e.printStackTrace()
            null
        }
    }

    /**
     * Get Pull Request by number using GitHub MCP
     */
    private suspend fun getPullRequestByNumber(number: Int): PullRequestInfo? {
        if (githubMcpService == null || githubOwner.isNullOrBlank() || githubRepo.isNullOrBlank()) {
            return null
        }

        return try {
            val result = githubMcpService.callTool(
                name = "get_pull_request",
                args = mapOf(
                    "owner" to githubOwner,
                    "repo" to githubRepo,
                    "pull_number" to number
                )
            )

            parsePullRequest(result)
        } catch (e: Exception) {
            println("Error fetching PR #$number: ${e.message}")
            e.printStackTrace()
            null
        }
    }

    /**
     * Get Pull Request diff using GitHub MCP tool: get_pull_request_files
     * This returns list of files with their patches, which we combine into unified diff
     */
    private suspend fun getPullRequestDiff(number: Int): String {
        if (githubMcpService == null || githubOwner.isNullOrBlank() || githubRepo.isNullOrBlank()) {
            return ""
        }

        return try {
            // Use GitHub MCP tool to get PR files
            val result = githubMcpService.callTool(
                name = "get_pull_request_files",
                args = mapOf(
                    "owner" to githubOwner,
                    "repo" to githubRepo,
                    "pull_number" to number
                )
            )

            // Parse result and extract patches
            val text = result.content.firstOrNull()?.text ?: return ""
            val json = Json { ignoreUnknownKeys = true }
            val filesArray = json.parseToJsonElement(text).jsonArray

            // Combine all patches into unified diff format
            buildString {
                filesArray.forEach { fileElement ->
                    val fileObj = fileElement.jsonObject
                    val filename = fileObj["filename"]?.jsonPrimitive?.content ?: return@forEach
                    val patch = fileObj["patch"]?.jsonPrimitive?.content
                    val status = fileObj["status"]?.jsonPrimitive?.content ?: "modified"

                    // If file has a patch (not binary, not too large)
                    if (patch != null) {
                        // Add git-style header
                        appendLine("diff --git a/$filename b/$filename")
                        when (status) {
                            "added" -> appendLine("new file mode 100644")
                            "removed" -> appendLine("deleted file mode 100644")
                        }
                        appendLine("--- a/$filename")
                        appendLine("+++ b/$filename")
                        appendLine(patch)
                        appendLine()
                    } else {
                        // File without patch (binary or too large)
                        appendLine("diff --git a/$filename b/$filename")
                        appendLine("Binary files differ or file too large")
                        appendLine()
                    }
                }
            }
        } catch (e: Exception) {
            println("Error fetching PR files #$number: ${e.message}")
            e.printStackTrace()
            ""
        }
    }

    /**
     * Parse Pull Request from MCP result
     */
    private fun parsePullRequest(result: ru.dikoresearch.infrastructure.mcp.StdioMcpService.CallToolResponse): PullRequestInfo? {
        return try {
            val text = result.content.firstOrNull()?.text ?: return null
            val json = Json { ignoreUnknownKeys = true }

            // GitHub MCP returns JSON with PR details
            val jsonElement = json.parseToJsonElement(text)
            val prObj = jsonElement.jsonObject

            PullRequestInfo(
                number = prObj["number"]?.jsonPrimitive?.int ?: 0,
                title = prObj["title"]?.jsonPrimitive?.content ?: "",
                description = prObj["body"]?.jsonPrimitive?.content ?: "",
                state = prObj["state"]?.jsonPrimitive?.content ?: "unknown",
                author = prObj["user"]?.jsonObject?.get("login")?.jsonPrimitive?.content,
                modifiedFiles = parseModifiedFiles(prObj)
            )
        } catch (e: Exception) {
            println("Error parsing PR: ${e.message}")
            null
        }
    }

    /**
     * Parse Pull Request from list result
     */
    private fun parsePullRequestFromList(result: ru.dikoresearch.infrastructure.mcp.StdioMcpService.CallToolResponse): PullRequestInfo? {
        return try {
            val text = result.content.firstOrNull()?.text ?: return null
            val json = Json { ignoreUnknownKeys = true }

            val jsonElement = json.parseToJsonElement(text)
            val prsArray = jsonElement.jsonArray

            if (prsArray.isEmpty()) return null

            // Get first PR
            val prObj = prsArray.first().jsonObject

            PullRequestInfo(
                number = prObj["number"]?.jsonPrimitive?.int ?: 0,
                title = prObj["title"]?.jsonPrimitive?.content ?: "",
                description = prObj["body"]?.jsonPrimitive?.content ?: "",
                state = prObj["state"]?.jsonPrimitive?.content ?: "unknown",
                author = prObj["user"]?.jsonObject?.get("login")?.jsonPrimitive?.content,
                modifiedFiles = emptyList() // Will be fetched later
            )
        } catch (e: Exception) {
            println("Error parsing PR list: ${e.message}")
            null
        }
    }

    /**
     * Parse modified files from PR object
     */
    private fun parseModifiedFiles(prObj: JsonObject): List<String> {
        return try {
            val filesArray = prObj["files"]?.jsonArray
            filesArray?.mapNotNull { fileElement ->
                fileElement.jsonObject["filename"]?.jsonPrimitive?.content
            } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }
}
