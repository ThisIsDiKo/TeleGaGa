package ru.dikoresearch.di

import org.koin.dsl.module
import ru.dikoresearch.infrastructure.config.ConfigService
import ru.dikoresearch.infrastructure.config.UserProfileService

/**
 * Koin module for configuration
 */
val configModule = module {
    single {
        ConfigService.load()
    }

    single {
        UserProfileService.load().also { profile ->
            println(profile)
        }
    }
}
