package ru.dikoresearch.di

import org.koin.dsl.module
import ru.dikoresearch.infrastructure.config.ConfigService

/**
 * Koin module for configuration
 */
val configModule = module {
    single {
        ConfigService.load()
    }
}
