package ru.dikoresearch.domain

import GigaChatMessage
import OllamaChatResponse
import ru.dikoresearch.infrastructure.http.OllamaClient

/**
 * CLI chat orchestrator with RAG integration and source citation
 * Manages conversation history in memory and automatically searches for relevant context
 * Uses Ollama LLM (llama3.2:3b) for response generation
 */
class CliChatOrchestrator(
    private val ollamaClient: OllamaClient,
    private val ragService: RagService,
    private val systemPrompt: String,
    private val ragEnabled: Boolean = true,
    private val ragTopK: Int = 5,
    private val ragRelevanceThreshold: Float = 0.5f
) {
    private val conversationHistory = mutableListOf<GigaChatMessage>()

    init {
        // Add system prompt to conversation history
        conversationHistory.add(
            GigaChatMessage(
                role = "system",
                content = systemPrompt
            )
        )
    }

    /**
     * Process user message with automatic RAG context retrieval
     *
     * @param userQuery user question
     * @return chat response with answer, sources, and statistics
     */
    suspend fun processMessage(userQuery: String): CliChatResponse {
        val sources = mutableListOf<Source>()
        var ragStats: RagStats? = null
        var finalQuery = userQuery

        // 1. RAG: Search for relevant chunks if enabled
        if (ragEnabled) {
            try {
                val ragResult = ragService.findRelevantChunksWithFilter(
                    question = userQuery,
                    fileName = "readme",
                    topK = ragTopK,
                    relevanceThreshold = ragRelevanceThreshold
                )

                ragStats = RagStats(
                    chunksFound = ragResult.filteredCount,
                    originalCount = ragResult.originalCount,
                    avgRelevance = ragResult.avgRelevance,
                    minRelevance = ragResult.minRelevance,
                    maxRelevance = ragResult.maxRelevance
                )

                // Extract sources from RAG results
                if (ragResult.filteredCount > 0) {
                    // Load embeddings document to get metadata
                    val embeddingsFile = java.io.File("embeddings_store/readme.embeddings.json")
                    if (embeddingsFile.exists()) {
                        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                        val embeddingsDoc = json.decodeFromString<ru.dikoresearch.infrastructure.persistence.EmbeddingsDocument>(
                            embeddingsFile.readText()
                        )

                        // Extract sources from chunks
                        ragResult.chunks.forEach { (_, relevance, index) ->
                            val record = embeddingsDoc.embeddings.find { it.index == index }
                            if (record != null) {
                                sources.add(
                                    Source(
                                        file = record.sourceFile,
                                        startLine = record.startLine,
                                        endLine = record.endLine,
                                        relevance = relevance
                                    )
                                )
                            }
                        }
                    }

                    // Format context with citations
                    val context = ragService.formatContextWithCitations(
                        chunks = ragResult.chunks,
                        fileName = "readme"
                    )

                    // Prepend RAG context to user query
                    finalQuery = "$context\n\n=== USER QUESTION ===\n$userQuery"
                }
            } catch (e: Exception) {
                println("Warning: RAG search failed: ${e.message}")
                // Continue without RAG context
            }
        }

        // 2. Add user message to conversation history
        conversationHistory.add(
            GigaChatMessage(
                role = "user",
                content = finalQuery
            )
        )

        // 3. Call Ollama LLM
        val llmResponse: OllamaChatResponse = ollamaClient.chatCompletion(
            messages = conversationHistory
        )

        // 4. Extract assistant's answer
        val assistantMessage = llmResponse.message.content

        // 5. Add assistant's response to history
        conversationHistory.add(
            GigaChatMessage(
                role = "assistant",
                content = assistantMessage
            )
        )

        // 6. Return response with sources and stats
        return CliChatResponse(
            answer = assistantMessage,
            sources = sources,
            tokenUsage = OllamaTokenUsage(
                promptTokens = llmResponse.prompt_eval_count.toInt(),
                completionTokens = llmResponse.eval_count.toInt(),
                totalTokens = (llmResponse.prompt_eval_count + llmResponse.eval_count).toInt()
            ),
            ragStats = ragStats
        )
    }

    /**
     * Clear conversation history (keeps system prompt)
     */
    fun clearHistory() {
        conversationHistory.clear()
        conversationHistory.add(
            GigaChatMessage(
                role = "system",
                content = systemPrompt
            )
        )
    }

    /**
     * Get current conversation history size (excluding system prompt)
     */
    fun getHistorySize(): Int {
        return conversationHistory.size - 1 // exclude system prompt
    }

    /**
     * Get conversation history
     */
    fun getHistory(): List<GigaChatMessage> {
        return conversationHistory.toList()
    }
}

/**
 * CLI chat response with answer, sources, and statistics
 */
data class CliChatResponse(
    val answer: String,
    val sources: List<Source>,
    val tokenUsage: OllamaTokenUsage,
    val ragStats: RagStats?
)

/**
 * Source citation from RAG
 */
data class Source(
    val file: String,
    val startLine: Int,
    val endLine: Int,
    val relevance: Float
)

/**
 * Token usage statistics from Ollama
 */
data class OllamaTokenUsage(
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int
)

/**
 * RAG search statistics
 */
data class RagStats(
    val chunksFound: Int,
    val originalCount: Int,
    val avgRelevance: Float,
    val minRelevance: Float,
    val maxRelevance: Float
)
