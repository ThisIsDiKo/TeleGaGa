package ru.dikoresearch.application.commands.handlers

import GigaChatMessage
import ru.dikoresearch.application.commands.CommandContext
import ru.dikoresearch.application.commands.CommandHandler
import ru.dikoresearch.domain.RagService
import ru.dikoresearch.infrastructure.http.OllamaClient

/**
 * Handles the /testRag command - compares answers with and without RAG
 */
class TestRagCommandHandler(
    private val ragService: RagService,
    private val ollamaClient: OllamaClient
) : CommandHandler {
    override val commandName = "testRag"

    override suspend fun handle(context: CommandContext) {
        val query = context.arguments

        if (query.isBlank()) {
            context.sendMessage(
                "Использование: /testRag <ваш вопрос>\n\n" +
                "Пример: /testRag Какие MCP серверы используются в проекте?"
            )
            return
        }

        try {
            context.sendMessage("🔍 Поиск релевантных чанков...")

            // 1. Создаем RagService и ищем релевантные чанки
            val topChunks = ragService.findRelevantChunks(query, "readme", 5)

            // 2. System prompt for RAG
            val systemRole = "You are a helpful assistant. Answer concisely and to the point in English."

            // 3. Generate answer WITHOUT RAG (using Ollama)
            context.sendMessage("🤖 Generating answer WITHOUT RAG (Ollama llama3.2:3b)...")
            val messagesWithoutRag = listOf(
                GigaChatMessage(role = "system", content = systemRole),
                GigaChatMessage(role = "user", content = query)
            )
            val responseWithoutRag = ollamaClient.chatCompletion(messagesWithoutRag)
            val answerWithoutRag = responseWithoutRag.message.content

            // 4. Generate answer WITH RAG (using Ollama)
            context.sendMessage("🧠 Generating answer WITH RAG (Ollama llama3.2:3b + top-5 chunks)...")
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
            val finalMessage = if (resultMessage.length > 3800) {
                resultMessage.take(3797) + "..."
            } else {
                resultMessage
            }

            context.sendMessage(finalMessage)

        } catch (e: Exception) {
            context.sendMessage(
                "❌ Ошибка при RAG-тестировании:\n${e.message}\n\n" +
                "💡 Убедитесь, что:\n" +
                "1. Создали embeddings командой /createEmbeddings\n" +
                "2. Ollama запущена и доступна"
            )
            e.printStackTrace()
        }
    }
}
