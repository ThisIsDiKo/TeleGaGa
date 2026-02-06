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
    private var ragRelevanceThreshold: Float = 0.5f
) {
    private val conversationHistory = mutableListOf<GigaChatMessage>()
    private var useRagForCurrentQuery: Boolean = false

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
        useRagForCurrentQuery = false

        // 1. RAG: Search across all embedding files
        if (ragEnabled) {
            try {
                val ragResult = ragService.findRelevantChunksAcrossAllFiles(
                    question = userQuery,
                    topK = ragTopK,
                    relevanceThreshold = 0.0f  // Get all top-K, we'll filter by threshold ourselves
                )

                ragStats = RagStats(
                    chunksFound = ragResult.originalCount,
                    originalCount = ragResult.originalCount,
                    avgRelevance = ragResult.avgRelevance,
                    minRelevance = ragResult.minRelevance,
                    maxRelevance = ragResult.maxRelevance
                )

                // Check if max relevance meets our threshold
                // Only use RAG if we have high-confidence matches
                if (ragResult.maxRelevance >= ragRelevanceThreshold && ragResult.chunks.isNotEmpty()) {
                    useRagForCurrentQuery = true

                    // Filter chunks by our threshold
                    val filteredChunks = ragResult.chunks.filter { it.relevance >= ragRelevanceThreshold }

                    filteredChunks.forEach { chunk ->
                        sources.add(
                            Source(
                                file = chunk.fileName,
                                startLine = chunk.startLine,
                                endLine = chunk.endLine,
                                relevance = chunk.relevance
                            )
                        )
                    }

                    // Format context with citations
                    val context = ragService.formatContextForMultipleFiles(filteredChunks)

                    // Prepend RAG context to user query
                    finalQuery = "$context\n\n=== USER QUESTION ===\n$userQuery"
                }
                // If max relevance < threshold, don't use RAG - let LLM think on its own
            } catch (e: Exception) {
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
            ragStats = ragStats,
            usedRag = useRagForCurrentQuery
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

    /**
     * Set RAG relevance threshold
     * @param threshold minimum relevance score (0.0-1.0)
     */
    fun setRelevanceThreshold(threshold: Float) {
        require(threshold in 0.0f..1.0f) { "Threshold must be between 0.0 and 1.0" }
        ragRelevanceThreshold = threshold
    }

    /**
     * Get current RAG relevance threshold
     */
    fun getRelevanceThreshold(): Float {
        return ragRelevanceThreshold
    }

    /**
     * Check if RAG was used for the last query
     */
    fun wasRagUsed(): Boolean {
        return useRagForCurrentQuery
    }
}

/**
 * CLI chat response with answer, sources, and statistics
 */
data class CliChatResponse(
    val answer: String,
    val sources: List<Source>,
    val tokenUsage: OllamaTokenUsage,
    val ragStats: RagStats?,
    val usedRag: Boolean
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
