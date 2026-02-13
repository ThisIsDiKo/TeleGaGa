package ru.dikoresearch.application.commands.handlers

import ru.dikoresearch.application.commands.CommandContext
import ru.dikoresearch.application.commands.CommandHandler
import ru.dikoresearch.domain.ChatOrchestrator
import ru.dikoresearch.domain.RagService
import ru.dikoresearch.domain.valueobjects.SystemPrompt
import ru.dikoresearch.domain.valueobjects.Temperature
import ru.dikoresearch.infrastructure.persistence.ChatSettingsManager

/**
 * Handles the /help command - searches documentation using RAG and provides answers
 */
class HelpCommandHandler(
    private val ragService: RagService,
    private val chatOrchestrator: ChatOrchestrator,
    private val settingsManager: ChatSettingsManager,
    private val gigaChatModel: String
) : CommandHandler {
    override val commandName = "help"

    override suspend fun handle(context: CommandContext) {
        val question = context.arguments

        if (question.isBlank()) {
            context.sendMessage(
                """
                🔍 PROJECT HELP

                Usage: /help <your question>

                Examples:
                • /help What MCP servers are available?
                • /help How do I change temperature?
                • /help How to add a new command?

                This command uses RAG to search all project documentation.
                """.trimIndent()
            )
            return
        }

        try {
            context.sendMessage("🔍 Searching documentation...")

            // Step 1: Load RAG settings
            val settings = settingsManager.loadSettings(context.chatId)

            // Step 2: Search across all documentation files
            val searchResult = ragService.findRelevantChunksAcrossAllFiles(
                question = question,
                topK = 5,
                relevanceThreshold = settings.ragRelevanceThreshold.value
            )

            if (searchResult.chunks.isEmpty()) {
                context.sendMessage(
                    """
                    ❌ No relevant documentation found.

                    Try:
                    • Rephrasing your question
                    • Running /createEmbeddings to refresh docs
                    • Checking if embeddings exist
                    """.trimIndent()
                )
                return
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
                systemRole = SystemPrompt(systemRole),
                userMessage = userPrompt,
                temperature = Temperature(0.7f),
                model = gigaChatModel
            )

            // Step 6: Format final response
            val formattedAnswer = buildString {
                appendLine(answer)
                appendLine()
                appendLine("━━━ Sources ━━━")
                searchResult.chunks.forEach { chunk ->
                    appendLine("• ${chunk.fileName} (lines ${chunk.startLine}-${chunk.endLine})")
                }
            }

            // Truncate if too long (Telegram limit: 4096 chars)
            val finalAnswer = if (formattedAnswer.length > 3800) {
                formattedAnswer.take(3800) + "\n\n... (truncated)"
            } else {
                formattedAnswer
            }

            context.sendMessage(finalAnswer)

        } catch (e: IllegalStateException) {
            // No embeddings found
            context.sendMessage(
                """
                ❌ ${e.message}

                Please run: /createEmbeddings
                """.trimIndent()
            )
        } catch (e: Exception) {
            context.sendMessage("❌ Error: ${e.message}")
            e.printStackTrace()
        }
    }
}
