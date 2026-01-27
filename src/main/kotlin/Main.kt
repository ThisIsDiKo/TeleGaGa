package ru.dikoresearch

import ChatHistoryManager
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import ru.dikoresearch.infrastructure.mcp.McpService
import kotlinx.serialization.json.Json
import java.lang.IllegalStateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

fun main() {
    // Создаем ApplicationScope для управления корутинами всего приложения
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    var mcpService: McpService? = null

    try {
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

        // Инициализируем менеджер истории чатов
        val historyManager = ChatHistoryManager()

        // Хранилище истории для каждого чата (chatId -> история)
        val chatHistories = mutableMapOf<Long, MutableList<GigaChatMessage>>()

        val modelTemperature = 0.87F

        // Инициализация MCP сервиса
        mcpService = McpService()
        runBlocking {
            mcpService!!.initialize()
        }
        println("MCP сервис инициализирован и готов к работе")

        val bot = bot {
            token = telegramToken


            dispatch {
                command("start") {

                }
                command("destroyContext"){

                    bot.sendMessage(
                        chatId = ChatId.fromId(message.chat.id),
                        text = "Раньше я отправлял примерно 160к токенов (максимум заявлен в 128к)"
                    )
                }
                command("listTools"){
                    val chatId = message.chat.id

                    // Используем applicationScope.launch для вызова suspend функции из синхронного контекста
                    applicationScope.launch {
                        try {
                            val service = mcpService
                            if (service == null) {
                                bot.sendMessage(
                                    chatId = ChatId.fromId(chatId),
                                    text = "MCP сервис не инициализирован"
                                )
                                return@launch
                            }

                            val mcpTools = service.listTools()
                            val toolNames = mcpTools.tools.map { it.name }
                            val message = "Доступные MCP инструменты (${toolNames.size}):\n" +
                                    toolNames.joinToString("\n") { "• $it" }

                            bot.sendMessage(
                                chatId = ChatId.fromId(chatId),
                                text = message
                            )
                        } catch (e: Exception) {
                            bot.sendMessage(
                                chatId = ChatId.fromId(chatId),
                                text = "Ошибка при получении списка инструментов: ${e.message}"
                            )
                        }
                    }
                }
                command("changeRole") {
                    val chatId = message.chat.id
                    val joinedArgs = args.joinToString(separator = " ")
                    val response = if (joinedArgs.isNotBlank()) joinedArgs else return@command
                    println("Got response from changeSystemPromt $response")
                    val newSystemPromt = response

                    // Получаем или создаем историю для текущего чата
                    val history = chatHistories.getOrPut(chatId) {
                        historyManager.loadHistory(chatId).takeIf { it.isNotEmpty() }
                            ?: mutableListOf(GigaChatMessage(role = "system", content = SingleRole))
                    }

                    // Обновляем системный промпт
                    history[0] = GigaChatMessage(role = "system", content = newSystemPromt)
                    historyManager.saveHistory(chatId, history)

                    bot.sendMessage(
                        chatId = ChatId.fromId(chatId),
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
                command("clearChat") {
                    val chatId = message.chat.id

                    // Удаляем историю из файла
                    val deleted = historyManager.clearHistory(chatId)

                    // Удаляем историю из памяти
                    chatHistories.remove(chatId)

                    val responseText = if (deleted) {
                        "История чата успешно удалена. Начинаем с чистого листа!"
                    } else {
                        "Произошла ошибка при удалении истории чата"
                    }

                    bot.sendMessage(
                        chatId = ChatId.fromId(chatId),
                        text = responseText
                    )

                    println("Команда clearChat выполнена для чата $chatId: deleted=$deleted")
                }
                message(filter = Filter.Text) {
                    val chatId = message.chat.id

                    // Получаем или создаем историю для текущего чата
                    val history = chatHistories.getOrPut(chatId) {
                        val loadedHistory = historyManager.loadHistory(chatId)
                        if (loadedHistory.isNotEmpty()) {
                            println("Загружена существующая история для чата $chatId")
                            loadedHistory
                        } else {
                            println("Создана новая история для чата $chatId")
                            mutableListOf(GigaChatMessage(role = "system", content = SingleRole))
                        }
                    }

                    handleTextUpdate(
                        systemRole = SingleRole,
                        gigaClient = gigaClient,
                        ollamaClient = ollamaClient,
                        gigaModel = gigaModel,
                        update = update,
                        gigaChatHistory = history,
                        temperature = modelTemperature,
                        historyManager = historyManager,
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

        // Блокируем main поток, чтобы бот продолжал работать
        println("Бот запущен. Нажмите Enter для остановки...")
        readLine()
    }
    catch (e: Exception){
        e.printStackTrace()
        println("Ошибка в приложении: ${e.message}")
    }
    finally {
        println("Начинается graceful shutdown...")

        // Отменяем все корутины в applicationScope
        applicationScope.cancel()
        println("ApplicationScope отменен")

        // Останавливаем MCP сервис
        try {
            runBlocking {
                mcpService?.shutdown()
            }
        } catch (e: Exception) {
            println("Ошибка при остановке MCP сервиса: ${e.message}")
        }

        println("Приложение завершено")
    }

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

val SingleRole = "Ты эксперт в области построения систем на основе семейства микроконтроллеров ESP32\n"
