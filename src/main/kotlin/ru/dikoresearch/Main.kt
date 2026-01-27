package ru.dikoresearch

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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import ru.dikoresearch.domain.ChatOrchestrator
import ru.dikoresearch.infrastructure.config.ConfigService
import ru.dikoresearch.infrastructure.http.GigaChatClient
import ru.dikoresearch.infrastructure.http.OllamaClient
import ru.dikoresearch.infrastructure.mcp.McpService
import ru.dikoresearch.infrastructure.persistence.ChatHistoryManager
import ru.dikoresearch.infrastructure.telegram.TelegramBotService
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

// Системные промпты
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

fun main() {
    // ApplicationScope для управления корутинами всего приложения
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    var mcpService: McpService? = null
    var botService: TelegramBotService? = null

    try {
        println("=== Запуск TeleGaGa бота ===\n")

        // 1. Загрузка конфигурации
        println("1. Загрузка конфигурации...")
        val config = ConfigService.load()
        println("   Конфигурация загружена успешно")
        println("   $config\n")

        // 2. Создание HTTP Client
        println("2. Создание HTTP клиента...")
        val httpClient = createHttpClient()
        println("   HTTP клиент создан\n")

        // 3. Инициализация AI клиентов
        println("3. Инициализация AI клиентов...")
        val gigaClient = GigaChatClient(
            httpClient = httpClient,
            baseUrl = config.gigaChatBaseUrl,
            authorizationKey = config.gigaChatAuthKey
        )
        val ollamaClient = OllamaClient(httpClient = httpClient)
        println("   GigaChat клиент создан: ${config.gigaChatBaseUrl}")
        println("   Ollama клиент создан\n")

        // 4. Инициализация Persistence
        println("4. Инициализация менеджера истории...")
        val historyManager = ChatHistoryManager()
        println("   ChatHistoryManager инициализирован\n")

        // 5. Инициализация MCP сервиса
        println("5. Инициализация MCP сервиса...")
        mcpService = McpService()
        runBlocking {
            mcpService!!.initialize()
        }
        println("   MCP сервис инициализирован и готов к работе\n")

        // 6. Создание Domain Layer
        println("6. Создание ChatOrchestrator...")
        val chatOrchestrator = ChatOrchestrator(
            gigaClient = gigaClient,
            historyManager = historyManager
        )
        println("   ChatOrchestrator создан\n")

        // 7. Создание Telegram Bot Service
        println("7. Инициализация Telegram Bot Service...")
        botService = TelegramBotService(
            telegramToken = config.telegramToken,
            chatOrchestrator = chatOrchestrator,
            mcpService = mcpService!!,
            applicationScope = applicationScope,
            defaultSystemRole = SingleRole,
            defaultTemperature = 0.87F,
            gigaChatModel = config.gigaChatModel
        )
        println("   TelegramBotService создан\n")

        // 8. Запуск Health Check сервера
        println("8. Запуск Health Check сервера...")
        startHealthCheckServer()
        println("   Health Check сервер запущен на http://localhost:12222\n")

        // 9. Запуск бота
        println("9. Запуск Telegram бота...")
        botService.start()
        println("\n=== TeleGaGa бот успешно запущен ===")
        println("Для остановки нажмите Enter\n")

        // Блокируем main поток до получения Enter
        readLine()

    } catch (e: Exception) {
        e.printStackTrace()
        println("\nОшибка в приложении: ${e.message}")
    } finally {
        println("\n=== Начинается graceful shutdown ===")

        // Отменяем все корутины в applicationScope
        applicationScope.cancel()
        println("ApplicationScope отменен")

        // Останавливаем Telegram бота
        botService?.stop()

        // Останавливаем MCP сервис
        try {
            runBlocking {
                mcpService?.shutdown()
            }
            println("MCP сервис остановлен")
        } catch (e: Exception) {
            println("Ошибка при остановке MCP сервиса: ${e.message}")
        }

        println("=== Приложение завершено ===")
    }
}

/**
 * Создает HTTP клиент с отключенной проверкой SSL сертификатов
 * (требуется для работы с GigaChat из-за проблем с сертификатами)
 */
private fun createHttpClient(): HttpClient {
    val json = Json { ignoreUnknownKeys = true }

    return HttpClient(CIO) {
        install(ContentNegotiation) {
            json(json)
        }
        install(Logging)

        // Из-за проблем с сертификатами минцифры, пришлось отключить их проверку
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
}

/**
 * Запускает простой HTTP сервер для health check на порту 12222
 */
private fun startHealthCheckServer() {
    embeddedServer(Netty, port = 12222) {
        routing {
            get("/") {
                call.respondText("Bot OK")
            }
        }
    }.start(wait = false)
}

@Suppress("unused")
fun Application.module() {
    // Для совместимости с Ktor, если потребуется
}
