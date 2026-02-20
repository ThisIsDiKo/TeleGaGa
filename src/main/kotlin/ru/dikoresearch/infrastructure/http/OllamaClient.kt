package ru.dikoresearch.infrastructure.http

import GigaChatMessage
import OllamaChatRequest
import OllamaChatResponse
import OllamaEmbeddingRequest
import OllamaEmbeddingResponse
import OllamaOptions
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json

class OllamaClient(
    private val httpClient: HttpClient,
    private val chatModel: String,
    private val embeddingModel: String,
    private val baseUrl: String = "http://localhost:11434",
    private val verbose: Boolean = false
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun chatCompletion(
        messages: List<GigaChatMessage>,
        temperature: Float? = null
    ): OllamaChatResponse {

        val requestBody = OllamaChatRequest(
            model = chatModel,
            messages = messages,
            stream = false,
            options = if (temperature != null) OllamaOptions(temperature = temperature) else null
        )

        if (verbose) {
            println("Request for ollama is $requestBody")
            println("Request for ollama is ${Json { prettyPrint = true }.encodeToString(requestBody)}")
        }

        val response = httpClient.post("$baseUrl/api/chat") {
            contentType(ContentType.Application.Json)
            setBody(requestBody)
        }

        val bodyText = response.bodyAsText()

        if (verbose) {
            println("Response from ollama is $response -> $bodyText")
        }

        if (!response.status.isSuccess()) {
            throw IllegalStateException("Ollama error: ${response.status}: $bodyText")
        }

        val parsed: OllamaChatResponse = json.decodeFromString(bodyText)

        if (verbose) {
            println("Parsed ollama response: $parsed")
        }

        return parsed
    }

    /**
     * Генерирует embeddings для списка текстов через Ollama
     * Ollama обрабатывает по одному тексту за раз, поэтому делаем батчинг здесь
     *
     * @param texts список текстов для обработки
     * @param model модель для embeddings
     * @return список пар (текст, embedding)
     */
    suspend fun embeddings(
        texts: List<String>,
        model: String = embeddingModel
    ): List<Pair<String, List<Float>>> {
        if (verbose) {
            println("📤 Ollama embeddings запрос для ${texts.size} текстов (модель: $model)")
        }

        val results = mutableListOf<Pair<String, List<Float>>>()

        texts.forEachIndexed { index, text ->
            val requestBody = OllamaEmbeddingRequest(
                model = model,
                prompt = text
            )

            val response = httpClient.post("$baseUrl/api/embeddings") {
                contentType(ContentType.Application.Json)
                setBody(requestBody)
            }

            val bodyText = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw IllegalStateException("Ollama embeddings error: ${response.status}: $bodyText")
            }

            val parsed: OllamaEmbeddingResponse = json.decodeFromString(bodyText)

            // Конвертируем Double в Float для совместимости
            val embeddingFloats = parsed.embedding.map { it.toFloat() }

            results.add(text to embeddingFloats)

            if (verbose && (index + 1) % 5 == 0) {
                println("   Обработано ${index + 1}/${texts.size} текстов")
            }
        }

        if (verbose) {
            println("📥 Ollama embeddings завершен (${results.size} embeddings)")
        }
        return results
    }
}
