package ru.dikoresearch.infrastructure.http

import GigaChatMessage
import OllamaChatRequest
import OllamaChatResponse
import OllamaEmbeddingRequest
import OllamaEmbeddingResponse
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
    private val baseUrl: String = "http://localhost:11434"
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun chatCompletion(
        messages: List<GigaChatMessage>,
    ): OllamaChatResponse {

        val requestBody = OllamaChatRequest(
            model = "llama3.2:1b",
            messages = messages,
            stream = false
        )

        println("Request for ollama is $requestBody")
        println("Request for ollama is ${Json { prettyPrint = true }.encodeToString(requestBody)}")

        val response = httpClient.post("$baseUrl/api/chat") {
            contentType(ContentType.Application.Json)
            setBody(requestBody)
        }

        println("Response from ollama is $response -> ${response.bodyAsText()}")

        val bodyText = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw IllegalStateException("Ollama error: ${response.status}: $bodyText")
        }

        val parsed: OllamaChatResponse = json.decodeFromString(bodyText)

        println("Parsed ollama response: $parsed")

        return parsed
    }

    /**
     * Генерирует embeddings для списка текстов через Ollama
     * Ollama обрабатывает по одному тексту за раз, поэтому делаем батчинг здесь
     *
     * @param texts список текстов для обработки
     * @param model модель для embeddings (рекомендуется "nomic-embed-text")
     * @return список пар (текст, embedding)
     */
    suspend fun embeddings(
        texts: List<String>,
        model: String = "nomic-embed-text"
    ): List<Pair<String, List<Float>>> {
        println("📤 Ollama embeddings запрос для ${texts.size} текстов (модель: $model)")

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

            if ((index + 1) % 5 == 0) {
                println("   Обработано ${index + 1}/${texts.size} текстов")
            }
        }

        println("📥 Ollama embeddings завершен (${results.size} embeddings)")
        return results
    }
}
