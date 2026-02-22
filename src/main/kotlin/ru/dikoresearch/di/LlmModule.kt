package ru.dikoresearch.di

import org.koin.core.qualifier.named
import org.koin.dsl.module
import ru.dikoresearch.infrastructure.http.OllamaClient

/**
 * Koin module для Ollama клиента Qwen 2.5 1.5B
 */
val llmModule = module {
    // Qwen 2.5 1.5B — поддерживает tool calling
    single(named("qwen25")) {
        val config = get<ru.dikoresearch.infrastructure.config.ConfigService>()
        OllamaClient(
            httpClient = get(),
            chatModel = "qwen2.5:1.5b",
            embeddingModel = config.ollamaEmbeddingModel,
            baseUrl = config.ollamaBaseUrl,
            verbose = false
        )
    }
}
