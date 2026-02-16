package ru.dikoresearch.di

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import org.koin.dsl.module
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

/**
 * Koin module for HTTP client configuration
 */
val httpModule = module {
    single {
        createHttpClient()
    }
}

/**
 * Creates HTTP client with extended timeouts for Ollama models
 *
 * Увеличенные таймауты необходимы для больших моделей (особенно llama3.2:3b):
 * - Первый запрос может занять до 60 секунд (загрузка модели в память)
 * - Последующие запросы обычно быстрее (модель уже в памяти)
 */
private fun createHttpClient(): HttpClient {
    val json = Json { ignoreUnknownKeys = true }

    return HttpClient(CIO) {
        engine {
            https {
                trustManager = object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                    override fun getAcceptedIssuers(): Array<X509Certificate>? = null
                }
            }

            // Увеличиваем таймауты для работы с Ollama
            requestTimeout = 120_000  // 120 секунд на весь запрос
        }

        install(HttpTimeout) {
            requestTimeoutMillis = 120_000  // 120 секунд на запрос
            connectTimeoutMillis = 30_000   // 30 секунд на подключение
            socketTimeoutMillis = 120_000   // 120 секунд на чтение/запись сокета
        }

        install(ContentNegotiation) {
            json(json)
        }

        install(Logging) {
            level = LogLevel.INFO
        }
    }
}
