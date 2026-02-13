package ru.dikoresearch.application.commands.handlers

import GigaChatMessage
import ru.dikoresearch.application.commands.CommandContext
import ru.dikoresearch.application.commands.CommandHandler
import ru.dikoresearch.domain.RagService
import ru.dikoresearch.infrastructure.http.OllamaClient
import ru.dikoresearch.infrastructure.persistence.ChatSettingsManager

/**
 * Handles the /compareRag command - compares 3 RAG approaches:
 * 1. Without RAG
 * 2. With RAG (top-5, no filter)
 * 3. With RAG + relevance filter
 */
class CompareRagCommandHandler(
    private val ragService: RagService,
    private val ollamaClient: OllamaClient,
    private val settingsManager: ChatSettingsManager
) : CommandHandler {
    override val commandName = "compareRag"

    override suspend fun handle(context: CommandContext) {
        val query = context.arguments

        if (query.isBlank()) {
            context.sendMessage(
                "Использование: /compareRag <ваш вопрос>\n\n" +
                "Сравнит 3 подхода:\n" +
                "1. БЕЗ RAG\n" +
                "2. С RAG (топ-5 без фильтра)\n" +
                "3. С RAG + фильтр релевантности\n\n" +
                "Пример: /compareRag Какие MCP серверы используются?"
            )
            return
        }

        try {
            // Получаем порог из настроек
            val settings = settingsManager.loadSettings(context.chatId)
            val threshold = settings.ragRelevanceThreshold

            context.sendMessage("🔍 Начинаю сравнение трех подходов RAG (порог: $threshold)...")

            val systemRole = "You are a helpful assistant. Answer concisely and to the point in English."

            // === APPROACH 1: WITHOUT RAG ===
            context.sendMessage("1/3 Generating answer WITHOUT RAG...")
            val startTimeNoRag = System.currentTimeMillis()
            val messagesNoRag = listOf(
                GigaChatMessage(role = "system", content = systemRole),
                GigaChatMessage(role = "user", content = query)
            )
            val responseNoRag = ollamaClient.chatCompletion(messagesNoRag)
            val timeNoRag = System.currentTimeMillis() - startTimeNoRag
            val answerNoRag = responseNoRag.message.content

            // === APPROACH 2: WITH RAG (top-5, no filter) ===
            context.sendMessage("2/3 Generating answer WITH RAG (no filter)...")
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
            context.sendMessage("3/3 Generating answer WITH RAG + filter (≥$threshold)...")
            val startTimeRagFiltered = System.currentTimeMillis()
            val searchResult = ragService.findRelevantChunksWithFilter(
                query, "readme", 5, threshold.value
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
                "⚠️ Не найдено релевантных чанков (все < $threshold). Отвечаю без RAG:\n\n$answerNoRag"
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
                appendLine("━━━ 3️⃣ RAG + ФИЛЬТР (≥$threshold) ━━━")
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
            val finalMessage = if (resultMessage.length > 3800) {
                resultMessage.take(3797) + "..."
            } else {
                resultMessage
            }

            context.sendMessage(finalMessage)

        } catch (e: Exception) {
            context.sendMessage("❌ Ошибка при сравнении RAG:\n${e.message}")
            e.printStackTrace()
        }
    }
}
