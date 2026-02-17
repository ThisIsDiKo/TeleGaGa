package ru.dikoresearch.di

import org.koin.core.qualifier.named
import org.koin.dsl.module
import ru.dikoresearch.infrastructure.http.OllamaClient

/**
 * Koin module для Ollama клиента Gemma3
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
}
