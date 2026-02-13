package ru.dikoresearch.di

import org.koin.core.qualifier.named
import org.koin.dsl.module
import ru.dikoresearch.domain.llm.GigaChatLlmClient
import ru.dikoresearch.domain.llm.LlmClient
import ru.dikoresearch.domain.llm.OllamaLlmClient
import ru.dikoresearch.infrastructure.http.GigaChatClient
import ru.dikoresearch.infrastructure.http.OllamaClient

/**
 * Koin module for LLM clients
 */
val llmModule = module {
    // GigaChat concrete client
    single {
        val config = get<ru.dikoresearch.infrastructure.config.ConfigService>()
        GigaChatClient(
            httpClient = get(),
            baseUrl = config.gigaChatBaseUrl,
            authorizationKey = config.gigaChatAuthKey,
            scope = "GIGACHAT_API_PERS"
        )
    }

    // Ollama concrete client
    single {
        val config = get<ru.dikoresearch.infrastructure.config.ConfigService>()
        OllamaClient(
            httpClient = get(),
            chatModel = config.ollamaChatModel,
            embeddingModel = config.ollamaEmbeddingModel,
            baseUrl = "http://localhost:11434",
            verbose = false
        )
    }

    // LlmClient abstraction for GigaChat (default)
    single<LlmClient>(named("gigachat")) {
        GigaChatLlmClient(get())
    }

    // LlmClient abstraction for Ollama
    single<LlmClient>(named("ollama")) {
        OllamaLlmClient(get())
    }

    // Default LlmClient (GigaChat)
    single<LlmClient> {
        get(named("gigachat"))
    }
}
