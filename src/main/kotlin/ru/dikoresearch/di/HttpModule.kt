package ru.dikoresearch.di

import io.ktor.client.*
import io.ktor.client.engine.cio.*
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
 * Creates HTTP client with SSL certificate verification disabled
 * (required for working with GigaChat due to certificate issues)
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
        }

        install(ContentNegotiation) {
            json(json)
        }

        install(Logging) {
            level = LogLevel.INFO
        }
    }
}
