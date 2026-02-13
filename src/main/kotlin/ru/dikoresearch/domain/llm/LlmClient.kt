package ru.dikoresearch.domain.llm

import GigaChatFunction
import GigaChatMessage
import GigaChatResponse

/**
 * Abstraction for LLM providers (GigaChat, Ollama, etc.)
 * Enables dependency inversion and easier testing
 */
interface LlmClient {
    /**
     * Send chat completion request
     *
     * @param model model name
     * @param messages conversation history
     * @param temperature sampling temperature (0.0 - 1.0)
     * @param functions optional list of functions for function calling
     * @param functionCall optional function calling mode ("auto", "none", or specific function name)
     * @return chat completion response
     */
    suspend fun chatCompletion(
        model: String,
        messages: List<GigaChatMessage>,
        temperature: Float,
        functions: List<GigaChatFunction>? = null,
        functionCall: String? = null
    ): GigaChatResponse

    /**
     * Generate embeddings for texts
     *
     * @param texts list of texts to embed
     * @param model embedding model name
     * @return list of (text, embedding vector) pairs
     */
    suspend fun embeddings(
        texts: List<String>,
        model: String
    ): List<Pair<String, List<Float>>>
}
