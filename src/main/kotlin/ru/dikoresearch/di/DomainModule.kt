package ru.dikoresearch.di

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.koin.dsl.module
import ru.dikoresearch.domain.ChatOrchestrator
import ru.dikoresearch.domain.RagService
import ru.dikoresearch.infrastructure.embeddings.EmbeddingService

/**
 * Koin module for domain services
 */
val domainModule = module {
    // Application-wide CoroutineScope
    single {
        CoroutineScope(SupervisorJob())
    }

    // Markdown Preprocessor
    single {
        ru.dikoresearch.domain.MarkdownPreprocessor()
    }

    // Embedding Service
    single {
        EmbeddingService(
            gigaChatClient = null,
            ollamaClient = get(),
            textChunker = get(),
            markdownPreprocessor = get()
        )
    }

    // RAG Service
    single {
        RagService(
            embeddingService = get()
        )
    }

    // Chat Orchestrator
    single {
        ChatOrchestrator(
            llmClient = get(), // Uses default LlmClient (GigaChat)
            historyManager = get()
        )
    }
}
