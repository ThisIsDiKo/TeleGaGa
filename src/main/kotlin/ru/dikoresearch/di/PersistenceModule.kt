package ru.dikoresearch.di

import org.koin.dsl.module
import ru.dikoresearch.domain.TextChunker
import ru.dikoresearch.infrastructure.persistence.ChatHistoryManager
import ru.dikoresearch.infrastructure.persistence.ChatSettingsManager
import ru.dikoresearch.infrastructure.persistence.EmbeddingsManager

/**
 * Koin module for persistence layer (managers)
 */
val persistenceModule = module {
    single {
        ChatHistoryManager(historyDirectory = "chat_history")
    }

    single {
        ChatSettingsManager(settingsDirectory = "chat_settings")
    }

    single {
        EmbeddingsManager()
    }

    single {
        TextChunker(chunkSize = 500, overlap = 50)
    }
}
