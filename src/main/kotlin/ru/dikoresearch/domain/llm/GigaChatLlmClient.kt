package ru.dikoresearch.domain.llm

import GigaChatFunction
import GigaChatMessage
import GigaChatResponse
import ru.dikoresearch.infrastructure.http.GigaChatClient

/**
 * Adapter that wraps GigaChatClient to implement LlmClient interface
 * Enables dependency inversion for GigaChat integration
 */
class GigaChatLlmClient(
    private val gigaChatClient: GigaChatClient
) : LlmClient {
    override suspend fun chatCompletion(
        model: String,
        messages: List<GigaChatMessage>,
        temperature: Float,
        functions: List<GigaChatFunction>?,
        functionCall: String?
    ): GigaChatResponse {
        return gigaChatClient.chatCompletion(
            model = model,
            messages = messages,
            temperature = temperature,
            functions = functions,
            functionCall = functionCall
        )
    }

    override suspend fun embeddings(
        texts: List<String>,
        model: String
    ): List<Pair<String, List<Float>>> {
        val response = gigaChatClient.embeddings(texts = texts, model = model)

        // Convert GigaChat embeddings format to standard format
        return response.data.mapIndexed { index, embedding ->
            val text = texts.getOrNull(index) ?: ""
            text to embedding.embedding
        }
    }
}
