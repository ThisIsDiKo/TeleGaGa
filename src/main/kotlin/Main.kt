package ru.dikoresearch

import GigaChatClient
import GigaChatMessage
import OllamaClient
import com.github.kotlintelegrambot.bot
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.dispatch
import com.github.kotlintelegrambot.dispatcher.command
import com.github.kotlintelegrambot.dispatcher.message
import com.github.kotlintelegrambot.dispatcher.text
import com.github.kotlintelegrambot.extensions.filters.Filter
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

    val ollamaClient = OllamaClient(
        httpClient = httpClient,
    )

    val gigaChatHistory = mutableListOf<GigaChatMessage>()
    gigaChatHistory.add(
        GigaChatMessage(role = "system", content = SingleRole)
    )

    val modelTemperature = 0.87F


    val bot = bot {
        token = telegramToken

        dispatch {
            command("start") {

            }
            command("destroyContext"){

                bot.sendMessage(
                    chatId = ChatId.fromId(message.chat.id),
                    text = "Отправляю примерно 160к токенов (максимум заявлен в 128к)"
                )

                handleTextUpdate(
                    gigaClient = gigaClient,
                    ollamaClient = ollamaClient,
                    gigaModel = gigaModel,
                    update = update,
                    gigaChatHistory = gigaChatHistory,
                    temperature = modelTemperature,
                    destroy = true,
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
            command("changeRole") {
                val joinedArgs = args.joinToString(separator = " ")
                val response = if (joinedArgs.isNotBlank()) joinedArgs else return@command
                println("Got response from changeSystemPromt $response")
                val newSystemPromt = response

                gigaChatHistory[0] = GigaChatMessage(role = "system", content = newSystemPromt)

                bot.sendMessage(
                    chatId = ChatId.fromId(message.chat.id),
                    text = "Изменил системный промнт на $newSystemPromt"
                )
            }
            command("changeT") {
                val joinedArgs = args.joinToString(separator = " ")

                val newTemperature = try {
                    joinedArgs.toFloat()
                }
                catch (e: Exception){
                    bot.sendMessage(
                        chatId = ChatId.fromId(message.chat.id),
                        text = "Температура может быть указана только числом"
                    )
                    return@command
                }

                println("Новая температура ответов: $newTemperature")
                bot.sendMessage(
                    chatId = ChatId.fromId(message.chat.id),
                    text = "Новая температура ответов: $newTemperature"
                )
            }
            message(filter = Filter.Text) {
                handleTextUpdate(
                    gigaClient = gigaClient,
                    ollamaClient = ollamaClient,
                    gigaModel = gigaModel,
                    update = update,
                    gigaChatHistory = gigaChatHistory,
                    temperature = modelTemperature,
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


val JsonRole = "Ты — сервис, который отвечает ТОЛЬКО валидным JSON-объектом без пояснений и форматирования Markdown.\n" +
        "Всегда используй ровно такой формат:\n" +
        "\n" +
        "{\n" +
        "  \"datetime\": \"ISO 8601 строка с датой и временем запроса, например 2026-01-13T20:54:00+03:00\",\n" +
        "  \"model\": \"строка с названием модели, к которой был сделан запрос, например GigaChat\",\n" +
        "  \"question\": \"строка с исходным вопросом пользователя\",\n" +
        "  \"answer\": \"строка с ответом на вопрос\"\n" +
        "}\n" +
        "\n" +
        "Требования:\n" +
        "- Не добавляй никакого текста вне JSON.\n" +
        "- Всегда заполняй все поля.\n" +
        "- В поле dateime не должно быть лишних слов, только представление даты и времени\n" +
        "- Поле \"question\" копируй дословно из сообщения пользователя.\n" +
        "- Поле \"datetime\" указывай в часовом поясе пользователя (если известен)."

val AssistantRole = "Ты — эксперт \n" +
        "\n" +
        "1. Если вопрос неясен, сначала задай несколько коротких уточняющих вопроса (без предположений).\n" +
        "2. Нужна максимальная конкретика\n" +
        "3. После получения ответов дай структурированный совет или ТЗ\n" +
        "4. Будь конкретным, используй примеры. Отвечай только по теме.\n" +
        "5. Я хочу, чтобы ты задавал уточняющие вопросы последовательно, а не списком в 1 сообщение."

val SingleRole = "Ты эксперт в области построения систем на основе семейства микроконтроллеров ESP32"
