package ru.dikoresearch.domain.llm

import Choice
import GigaChatFunction
import GigaChatMessage
import GigaChatResponse
import Message
import Usage
import ru.dikoresearch.infrastructure.http.OllamaClient

/**
 * Adapter that wraps OllamaClient to implement LlmClient interface
 * Converts Ollama response format to GigaChat format for consistency
 */
class OllamaLlmClient(
    private val ollamaClient: OllamaClient
) : LlmClient {
    override suspend fun chatCompletion(
        model: String,
        messages: List<GigaChatMessage>,
        temperature: Float,
        functions: List<GigaChatFunction>?,
        functionCall: String?
    ): GigaChatResponse {
        // Ollama doesn't support function calling yet, so we ignore those parameters
        val ollamaResponse = ollamaClient.chatCompletion(messages = messages)

        // Convert Ollama response to GigaChat response format
        return GigaChatResponse(
            choices = listOf(
                Choice(
                    message = Message(
                        content = ollamaResponse.message.content,
                        role = ollamaResponse.message.role,
                        functionCall = null
                    ),
                    index = 0,
                    finishReason = "stop"
                )
            ),
            created = System.currentTimeMillis() / 1000,
            model = model,
            `object` = "chat.completion",
            usage = Usage(
                promptTokens = ollamaResponse.prompt_eval_count.toInt(),
                completionTokens = ollamaResponse.eval_count.toInt(),
                totalTokens = (ollamaResponse.prompt_eval_count + ollamaResponse.eval_count).toInt(),
                precachedPromptTokens = 0
            )
        )
    }

    override suspend fun embeddings(
        texts: List<String>,
        model: String
    ): List<Pair<String, List<Float>>> {
        return ollamaClient.embeddings(texts = texts, model = model)
    }
}
