package ru.dikoresearch.di

import org.koin.core.qualifier.named
import org.koin.dsl.module
import ru.dikoresearch.domain.MultiModelChatOrchestrator
import ru.dikoresearch.infrastructure.http.OllamaClient

/**
 * Koin module для MultiModelChatOrchestrator
 */
val multiModelModule = module {
    single {
        MultiModelChatOrchestrator(
            gemma3Client = get<OllamaClient>(named("gemma3")),
            qwen3Client = get<OllamaClient>(named("qwen3")),
            llama3Client = get<OllamaClient>(named("llama3")),
            historyManager = get()
        )
    }
}
