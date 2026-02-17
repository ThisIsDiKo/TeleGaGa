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
            historyManager = get()
        )
    }
}
