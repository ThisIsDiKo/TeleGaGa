package ru.dikoresearch

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.plugins.*
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
import ru.dikoresearch.domain.ReminderScheduler
import ru.dikoresearch.infrastructure.config.ConfigService
import ru.dikoresearch.infrastructure.http.GigaChatClient
import ru.dikoresearch.infrastructure.http.OllamaClient
import ru.dikoresearch.infrastructure.mcp.HttpMcpService
import ru.dikoresearch.infrastructure.persistence.ChatHistoryManager
import ru.dikoresearch.infrastructure.persistence.ChatSettingsManager
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

val McpEnabledRole = """
Ты - умный AI ассистент с доступом к инструментам через Model Context Protocol (MCP).

Доступные инструменты:
1. get_weather - получить текущую погоду для указанного города (использует wttr.in)
2. create_reminder - создать напоминание (dueDate только YYYY-MM-DD)
3. get_reminders - получить список напоминаний
4. delete_reminder - удалить напоминание
5. get_chuck_norris_joke - получить шутку про Чака Норриса на английском (переводи на русский!)

ВАЖНО для работы с напоминаниями:
- Параметр dueDate должен содержать ТОЛЬКО дату в формате YYYY-MM-DD, БЕЗ времени
- Время включай в параметр text напоминания
- Текущая дата автоматически указана в системном сообщении выше

ВАЖНО для шуток про Чака:
- get_chuck_norris_joke возвращает шутку на английском языке
- ВСЕГДА переводи шутку на русский перед показом пользователю
- Переводи естественно и с юмором, сохраняя смысл

Примеры:
User: "Напомни мне завтра в 10:00 позвонить маме"
Ты: Вызови create_reminder(chatId="...", dueDate="2026-01-30", text="В 10:00 позвонить маме")

User: "Какая погода в Санкт-Петербурге?"
Ты: Вызови get_weather(city="Санкт-Петербург", lang="ru")

User: "Расскажи шутку про Чака"
Ты:
  1. Вызови get_chuck_norris_joke()
  2. Переведи полученную шутку на русский и покажи пользователю

Будь проактивным и полезным. Используй инструменты для точности.
""".trimIndent()

fun main() {
    // ApplicationScope для управления корутинами всего приложения
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    var httpMcpService: HttpMcpService? = null
    var botService: TelegramBotService? = null
    var reminderScheduler: ReminderScheduler? = null

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

        // 5. Инициализация ChatSettingsManager
        println("5. Инициализация ChatSettingsManager...")
        val settingsManager = ChatSettingsManager()
        println("   ChatSettingsManager инициализирован\n")

        // 6. Инициализация HTTP MCP сервисов
        println("6. Инициализация HTTP MCP сервисов...")
        val serverConfigs = listOf(
            HttpMcpService.ServerConfig("weather", "mcp-weather-server", 3001),
            HttpMcpService.ServerConfig("reminders", "mcp-reminders-server", 3002),
            HttpMcpService.ServerConfig("chuck", "mcp-chuck-server", 3003)
        )

        httpMcpService = HttpMcpService(httpClient, serverConfigs)
        try {
            runBlocking {
                httpMcpService!!.initialize()
            }
            println("   ✅ Все MCP серверы запущены и подключены\n")
        } catch (e: Exception) {
            println("   ⚠️ Не удалось запустить HTTP MCP серверы: ${e.message}")
            println("   💡 Для работы с MCP установите зависимости:")
            println("      cd mcp-weather-server && npm install")
            println("      cd mcp-reminders-server && npm install")
            println("      cd mcp-chuck-server && npm install")
            println("   Бот продолжит работу без MCP функций\n")
            httpMcpService = null
        }

        // 7. Создание Domain Layer
        println("7. Создание ChatOrchestrator...")
        val chatOrchestrator = ChatOrchestrator(
            gigaClient = gigaClient,
            historyManager = historyManager,
            mcpService = httpMcpService
        )
        println("   ChatOrchestrator создан\n")

        // 8. Создание Telegram Bot Service
        println("8. Инициализация Telegram Bot Service...")
        botService = TelegramBotService(
            telegramToken = config.telegramToken,
            chatOrchestrator = chatOrchestrator,
            mcpService = httpMcpService,
            settingsManager = settingsManager,
            applicationScope = applicationScope,
            defaultSystemRole = McpEnabledRole,
            defaultTemperature = 0.87F,
            gigaChatModel = config.gigaChatModel
        )
        println("   TelegramBotService создан\n")

        // 9. Запуск Health Check сервера
        println("9. Запуск Health Check сервера...")
        startHealthCheckServer()
        println("   Health Check сервер запущен на http://localhost:12222\n")

        // 10. Запуск Telegram бота
        println("10. Запуск Telegram бота...")
        botService.start()
        println("   TelegramBot запущен\n")

        // 11. Создание и запуск ReminderScheduler
        println("11. Создание ReminderScheduler...")
        reminderScheduler = ReminderScheduler(
            settingsManager = settingsManager,
            chatOrchestrator = chatOrchestrator,
            telegramBot = botService.bot,
            applicationScope = applicationScope
        )
        reminderScheduler.start()
        println("   ReminderScheduler запущен\n")

        println("=== TeleGaGa бот успешно запущен ===")
        println("Для остановки нажмите Enter\n")

        // Блокируем main поток до получения Enter
        readLine()

    } catch (e: Exception) {
        e.printStackTrace()
        println("\nОшибка в приложении: ${e.message}")
    } finally {
        println("\n=== Начинается graceful shutdown ===")

        // Останавливаем ReminderScheduler
        reminderScheduler?.stop()

        // Отменяем все корутины в applicationScope
        applicationScope.cancel()
        println("ApplicationScope отменен")

        // Останавливаем Telegram бота
        botService?.stop()

        // Останавливаем HTTP MCP сервисы
        try {
            runBlocking {
                httpMcpService?.shutdown()
            }
            println("HTTP MCP сервисы остановлены")
        } catch (e: Exception) {
            println("Ошибка при остановке HTTP MCP сервисов: ${e.message}")
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

        // Увеличенные таймауты для работы с внешними API (например, wttr.in)
        install(HttpTimeout) {
            requestTimeoutMillis = 60000  // 60 секунд на весь запрос
            connectTimeoutMillis = 10000  // 10 секунд на установку соединения
            socketTimeoutMillis = 60000   // 60 секунд на чтение/запись
        }

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
