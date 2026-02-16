package ru.dikoresearch.di

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.koin.dsl.module

/**
 * Упрощенный Koin module для domain services (без RAG и ChatOrchestrator)
 */
val domainModule = module {
    // Application-wide CoroutineScope
    single {
        CoroutineScope(SupervisorJob())
    }
}
