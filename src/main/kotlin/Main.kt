package ru.dikoresearch

import GigaChatClient
import com.github.kotlintelegrambot.bot
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.dispatch
import com.github.kotlintelegrambot.dispatcher.text
import handleTextUpdate

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import java.lang.IllegalStateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

fun main() {
    val json = Json { ignoreUnknownKeys = true }

    val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(json)
        }
        install(Logging) {
        }

        // Из-за проблем с сертификатами минцифры, пришлось отключить их проверку.
        engine {
            https {
                trustManager = object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                }
            }
        }

    }

    val telegramToken = "" ?: throw IllegalStateException("Telegram bot key is empty")
    val gigaBaseUrl = "https://gigachat.devices.sberbank.ru"
    val gigaAuthKey = "" ?: throw IllegalStateException("GigaChat key is empty")
    val gigaModel = "GigaChat"

    val gigaClient = GigaChatClient(
        httpClient = httpClient,
        baseUrl = gigaBaseUrl,
        authorizationKey = gigaAuthKey
    )


    val bot = bot {
        token = telegramToken

        dispatch {
            text {
                handleTextUpdate(
                    gigaClient = gigaClient,
                    gigaModel = gigaModel,
                    update = update,
                    reply = { chatId, text ->
                        val result = bot.sendMessage(
                            chatId = ChatId.fromId(chatId),
                            text = text
                        )
                        result.fold({}, { error ->
                            println("Telegram sendMessage error: $error")
                        })
                    }
                )
            }
        }
    }

    // Опциональный Ktor сервер (healthcheck)
    embeddedServer(Netty, port = 12222) {
        routing {
            get("/") {
                call.respondText("Bot OK")
            }
        }
    }.start(wait = false)

    // Запуск polling
    bot.startPolling()
}

@Suppress("unused")
fun Application.module() {
}
