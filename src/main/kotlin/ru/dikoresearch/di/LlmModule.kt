package ru.dikoresearch.di

import org.koin.core.qualifier.named
import org.koin.dsl.module
import ru.dikoresearch.infrastructure.http.OllamaClient

/**
 * Упрощенный Koin module для трех Ollama клиентов
 *
 * Создает три отдельных клиента для:
 * - gemma3:1b
 * - qwen3:1.7b
 * - llama3.2:3b
 */
val llmModule = module {
    // Gemma3 1B client
    single(named("gemma3")) {
        val config = get<ru.dikoresearch.infrastructure.config.ConfigService>()
        OllamaClient(
            httpClient = get(),
            chatModel = "gemma3:1b",
            embeddingModel = config.ollamaEmbeddingModel,
            baseUrl = config.ollamaBaseUrl,
            verbose = false
        )
    }

    // Qwen3 1.7B client
    single(named("qwen3")) {
        val config = get<ru.dikoresearch.infrastructure.config.ConfigService>()
        OllamaClient(
            httpClient = get(),
            chatModel = "qwen3:1.7b",
            embeddingModel = config.ollamaEmbeddingModel,
            baseUrl = config.ollamaBaseUrl,
            verbose = false
        )
    }

    // Llama3.2 3B client
    single(named("llama3")) {
        val config = get<ru.dikoresearch.infrastructure.config.ConfigService>()
        OllamaClient(
            httpClient = get(),
            chatModel = "llama3.2:3b",
            embeddingModel = config.ollamaEmbeddingModel,
            baseUrl = config.ollamaBaseUrl,
            verbose = false
        )
    }
}
