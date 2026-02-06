package ru.dikoresearch.infrastructure.cli

import ru.dikoresearch.domain.CliChatOrchestrator
import ru.dikoresearch.domain.CliChatResponse
import kotlin.system.exitProcess

/**
 * CLI chatbot with REPL (Read-Eval-Print-Loop) interface
 * Provides command-line interface for interacting with the AI assistant
 */
class CliChatbot(
    private val orchestrator: CliChatOrchestrator
) {
    private var isRunning = true
    private var totalTokensUsed = 0
    private var totalQuestionsAsked = 0

    /**
     * Start the REPL loop
     */
    suspend fun start() {
        printWelcomeMessage()

        while (isRunning) {
            try {
                print("\nYou: ")
                val input = readLine()?.trim() ?: continue

                if (input.isEmpty()) continue

                // Handle commands
                if (input.startsWith("/")) {
                    handleCommand(input)
                    continue
                }

                // Process user query
                processQuery(input)
            } catch (e: Exception) {
                println("Error: ${e.message}")
                println("Stack trace: ${e.stackTraceToString()}")
            }
        }

        println("\nGoodbye!")
        exitProcess(0)
    }

    /**
     * Process user query and print response
     */
    private suspend fun processQuery(query: String) {
        println("\n[Searching knowledge base...]")

        val response: CliChatResponse = try {
            orchestrator.processMessage(query)
        } catch (e: Exception) {
            println("Error processing query: ${e.message}")
            return
        }

        println("\n[Generating answer...]\n")

        // Print answer
        println("Answer:")
        println(response.answer)

        // Print sources if available
        if (response.sources.isNotEmpty()) {
            println("\nSources:")
            response.sources.forEachIndexed { index, source ->
                val relevancePercent = (source.relevance * 100).toInt()
                println("- ${source.file} (lines ${source.startLine}-${source.endLine}): $relevancePercent% relevance")
            }
        }

        // Print statistics
        println("\nStatistics:")
        println("- Tokens used: ${response.tokenUsage.totalTokens} " +
                "(prompt: ${response.tokenUsage.promptTokens}, " +
                "completion: ${response.tokenUsage.completionTokens})")

        if (response.ragStats != null) {
            println("- RAG chunks found: ${response.ragStats.chunksFound}")
            if (response.ragStats.chunksFound > 0) {
                println("- Average relevance: ${(response.ragStats.avgRelevance * 100).toInt()}%")
            }
        }

        // Update session stats
        totalTokensUsed += response.tokenUsage.totalTokens
        totalQuestionsAsked++
    }

    /**
     * Handle commands starting with /
     */
    private fun handleCommand(command: String) {
        when {
            command == "/help" -> printHelp()
            command == "/clear" -> clearHistory()
            command == "/stats" -> printStats()
            command == "/exit" || command == "/quit" -> {
                isRunning = false
            }
            else -> println("Unknown command: $command. Type /help for available commands.")
        }
    }

    /**
     * Print welcome message
     */
    private fun printWelcomeMessage() {
        println("=".repeat(60))
        println("CLI Chat Bot with RAG Memory")
        println("=".repeat(60))
        println("Type your questions in English.")
        println("Type /help to see available commands.")
        println("Type /exit to quit.")
        println("=".repeat(60))
    }

    /**
     * Print help message
     */
    private fun printHelp() {
        println("\nAvailable commands:")
        println("  /help   - Show this help message")
        println("  /clear  - Clear conversation history")
        println("  /stats  - Show session statistics")
        println("  /exit   - Exit the chatbot")
        println("\nJust type your question to get an answer with source citations.")
    }

    /**
     * Clear conversation history
     */
    private fun clearHistory() {
        orchestrator.clearHistory()
        println("\nConversation history cleared.")
    }

    /**
     * Print session statistics
     */
    private fun printStats() {
        println("\nSession Statistics:")
        println("- Questions asked: $totalQuestionsAsked")
        println("- Total tokens used: $totalTokensUsed")
        println("- Conversation history size: ${orchestrator.getHistorySize()} messages")

        if (totalQuestionsAsked > 0) {
            val avgTokensPerQuestion = totalTokensUsed / totalQuestionsAsked
            println("- Average tokens per question: $avgTokensPerQuestion")
        }
    }
}
