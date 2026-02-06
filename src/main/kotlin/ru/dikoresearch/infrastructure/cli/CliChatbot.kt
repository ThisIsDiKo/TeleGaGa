package ru.dikoresearch.infrastructure.cli

import ru.dikoresearch.domain.CliChatOrchestrator
import ru.dikoresearch.domain.CliChatResponse
import ru.dikoresearch.domain.RagService
import ru.dikoresearch.infrastructure.embeddings.EmbeddingService
import ru.dikoresearch.infrastructure.persistence.EmbeddingsManager
import java.io.File
import kotlin.system.exitProcess

/**
 * CLI chatbot with REPL (Read-Eval-Print-Loop) interface
 * Provides command-line interface for interacting with the AI assistant
 */
class CliChatbot(
    private val orchestrator: CliChatOrchestrator,
    private val embeddingService: EmbeddingService,
    private val ragService: RagService
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
        val response: CliChatResponse = try {
            orchestrator.processMessage(query)
        } catch (e: Exception) {
            println("Error: ${e.message}")
            return
        }

        // Print answer
        println("\n${response.answer}")

        // Print mode indicator and sources
        if (response.usedRag) {
            // RAG mode - show sources
            if (response.sources.isNotEmpty()) {
                println("\n[Mode: Documentation-based answer]")
                println("Sources:")
                response.sources.forEachIndexed { index, source ->
                    val relevancePercent = (source.relevance * 100).toInt()
                    println("${index + 1}. ${source.file} (lines ${source.startLine}-${source.endLine}) - ${relevancePercent}% relevance")
                }
            }
        } else {
            // General knowledge mode
            println("\n[Mode: General knowledge - no relevant documentation found]")
            if (response.ragStats != null && response.ragStats.maxRelevance > 0) {
                val maxRelevancePercent = (response.ragStats.maxRelevance * 100).toInt()
                val thresholdPercent = (orchestrator.getRelevanceThreshold() * 100).toInt()
                println("(Best match: ${maxRelevancePercent}%, threshold: ${thresholdPercent}%)")
            }
        }

        // Update session stats
        totalTokensUsed += response.tokenUsage.totalTokens
        totalQuestionsAsked++
    }

    /**
     * Handle commands starting with /
     */
    private suspend fun handleCommand(command: String) {
        when {
            command == "/help" -> printHelp()
            command == "/clear" -> clearHistory()
            command == "/stats" -> printStats()
            command == "/threshold" -> showThreshold()
            command.startsWith("/setThreshold ") -> setThreshold(command)
            command == "/createEmbeddings" -> createEmbeddings()
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
        println("CLI Chat Bot with Hybrid RAG")
        println("=".repeat(60))
        val threshold = orchestrator.getRelevanceThreshold()
        println("RAG threshold: ${(threshold * 100).toInt()}% (use /threshold to view, /setThreshold to change)")
        println("Ask questions in English. Type /help for commands.")
        println("=".repeat(60))
    }

    /**
     * Print help message
     */
    private fun printHelp() {
        println("\nCommands:")
        println("  /help                    - Show this help")
        println("  /clear                   - Clear conversation history")
        println("  /stats                   - Show session statistics")
        println("  /threshold               - Show current RAG threshold")
        println("  /setThreshold <0.0-1.0>  - Set RAG relevance threshold")
        println("  /createEmbeddings        - Create embeddings from rag_docs folder")
        println("  /exit                    - Exit")
        println("\nRAG threshold determines when to use documentation vs general knowledge.")
        println("Higher threshold = stricter matching, more general answers.")
        println("Lower threshold = looser matching, more documentation-based answers.")
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

    /**
     * Create embeddings from all files in rag_docs folder
     */
    private suspend fun createEmbeddings() {
        val ragDocsDir = File("rag_docs")

        if (!ragDocsDir.exists() || !ragDocsDir.isDirectory) {
            println("Error: rag_docs folder not found")
            return
        }

        // Get all .md files
        val mdFiles = ragDocsDir.listFiles { file ->
            file.extension == "md"
        } ?: emptyArray()

        if (mdFiles.isEmpty()) {
            println("No .md files found in rag_docs")
            return
        }

        println("Found ${mdFiles.size} markdown files. Creating embeddings...")

        val embeddingsManager = EmbeddingsManager()

        mdFiles.forEach { file ->
            try {
                println("Processing ${file.name}...")

                val content = file.readText()
                val embeddings = embeddingService.generateEmbeddingsWithMetadata(
                    markdownText = content,
                    sourceFile = file.name
                )

                if (embeddings.isEmpty()) {
                    println("  Warning: No embeddings generated for ${file.name}")
                    return@forEach
                }

                embeddingsManager.saveEmbeddingsWithMetadata(
                    fileName = file.nameWithoutExtension,
                    embeddings = embeddings,
                    chunkSize = 300
                )

                println("  Created ${embeddings.size} embeddings for ${file.name}")
            } catch (e: Exception) {
                println("  Error processing ${file.name}: ${e.message}")
            }
        }

        println("Done. Embeddings saved to embeddings_store/")
    }

    /**
     * Show current RAG relevance threshold
     */
    private fun showThreshold() {
        val threshold = orchestrator.getRelevanceThreshold()
        println("\nCurrent RAG threshold: ${(threshold * 100).toInt()}%")
        println("RAG is used when document relevance >= ${(threshold * 100).toInt()}%")
        println("Below this threshold, the LLM uses its general knowledge.")
    }

    /**
     * Set new RAG relevance threshold
     */
    private fun setThreshold(command: String) {
        val parts = command.split(" ")
        if (parts.size != 2) {
            println("Usage: /setThreshold <0.0-1.0>")
            println("Example: /setThreshold 0.7")
            return
        }

        val thresholdValue = parts[1].toFloatOrNull()
        if (thresholdValue == null || thresholdValue < 0.0f || thresholdValue > 1.0f) {
            println("Error: Threshold must be a number between 0.0 and 1.0")
            return
        }

        try {
            orchestrator.setRelevanceThreshold(thresholdValue)
            println("RAG threshold set to ${(thresholdValue * 100).toInt()}%")
            println("RAG will be used when document relevance >= ${(thresholdValue * 100).toInt()}%")
        } catch (e: Exception) {
            println("Error setting threshold: ${e.message}")
        }
    }
}
