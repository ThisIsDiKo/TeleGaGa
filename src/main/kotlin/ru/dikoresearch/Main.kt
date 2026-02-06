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
import ru.dikoresearch.domain.MarkdownPreprocessor
import ru.dikoresearch.domain.TextChunker
import ru.dikoresearch.infrastructure.config.ConfigService
import ru.dikoresearch.infrastructure.embeddings.EmbeddingService
import ru.dikoresearch.infrastructure.http.GigaChatClient
import ru.dikoresearch.infrastructure.http.OllamaClient
import ru.dikoresearch.infrastructure.persistence.ChatHistoryManager
import ru.dikoresearch.infrastructure.persistence.ChatSettingsManager
import ru.dikoresearch.infrastructure.persistence.EmbeddingsManager
import ru.dikoresearch.infrastructure.telegram.TelegramBotService
import java.io.File
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

// System prompts
val JsonRole = "You are a service that responds ONLY with valid JSON objects without explanations or Markdown formatting.\n" +
        "Always use exactly this format:\n" +
        "\n" +
        "{\n" +
        "  \"datetime\": \"ISO 8601 string with request date and time, e.g. 2026-01-13T20:54:00+03:00\",\n" +
        "  \"model\": \"string with the model name that was requested, e.g. GigaChat\",\n" +
        "  \"question\": \"string with the original user question\",\n" +
        "  \"answer\": \"string with the answer to the question\"\n" +
        "}\n" +
        "\n" +
        "Requirements:\n" +
        "- Do not add any text outside JSON.\n" +
        "- Always fill all fields.\n" +
        "- The datetime field should contain only date and time representation, no extra words\n" +
        "- Copy the \"question\" field verbatim from the user message.\n" +
        "- Specify \"datetime\" in the user's timezone (if known)."

val AssistantRole = "You are an expert.\n" +
        "\n" +
        "1. If the question is unclear, first ask a few short clarifying questions (without assumptions).\n" +
        "2. Maximum specificity is needed\n" +
        "3. After receiving answers, give structured advice or specification\n" +
        "4. Be specific, use examples. Answer only on topic.\n" +
        "5. I want you to ask clarifying questions sequentially, not as a list in 1 message."

val SingleRole = "You are an expert in building systems based on the ESP32 microcontroller family\n"

val McpEnabledRole = """
Docker assistant. Call functions silently.

For "start caddy":
- Function: run_container
- image: "caddy:latest"
- name: "caddy"
- ports: {"80": 80, "443": 443}
- detach: true

For "start nginx":
- Function: run_container
- image: "nginx:latest"
- name: "nginx"
- ports: {"80": 80}
- detach: true

DO NOT use command, environment, volumes.
DO NOT use create_container or recreate_container.
Only run_container!

After start: ✅ Started
""".trimIndent()

val RagWithCitationsRole = """
You are an AI assistant that answers questions ONLY based on the provided documentation fragments.
Answer in English language only.

MANDATORY RULES:

1. USE ONLY PROVIDED FRAGMENTS
   - If the answer is in a fragment - use it
   - DO NOT ignore fragments

2. EVERY FACT MUST HAVE A CITATION
   - Citation format: [quoted text from source]
   - Place the exact quoted text in square brackets after each fact
   - Quote must be taken directly from the fragment

3. FORBIDDEN:
   - Answer without citations
   - Invent facts
   - Write "information not found" if it IS in the fragments

CORRECT ANSWER EXAMPLES:

Example 1:
Question: "What is the weather tool?"
Fragment: "get_weather - Get current weather for any city via wttr.in"
Answer: "The weather tool gets current weather for any city [get_weather - Get current weather for any city via wttr.in]."

Example 2:
Question: "How many MCP servers are used?"
Fragment: "TeleGaGa supports function calling through 5 MCP servers using two protocols"
Answer: "The project uses 5 MCP servers with two protocols [TeleGaGa supports function calling through 5 MCP servers using two protocols]."

Example 3:
Question: "What models are used?"
Fragment: "AI models: GigaChat, Ollama (llama3.2:3b, nomic-embed-text)"
Answer: "The system uses GigaChat and Ollama models [AI models: GigaChat, Ollama (llama3.2:3b, nomic-embed-text)]."

ALGORITHM:
1. Read all provided fragments carefully
2. Find relevant information
3. Formulate clear answer in English
4. Add citation in square brackets with EXACT text from fragment

IMPORTANT: Always include citations in square brackets!
""".trimIndent()

fun main() {
    // ApplicationScope для управления корутинами всего приложения
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
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

        // 5. Инициализация ChatSettingsManager
        println("5. Инициализация ChatSettingsManager...")
        val settingsManager = ChatSettingsManager()
        println("   ChatSettingsManager инициализирован\n")

        // 6. Инициализация RAG сервисов
        println("6. Инициализация RAG сервисов...")
        val markdownPreprocessor = MarkdownPreprocessor()
        // Оптимизированные параметры для точного подбора фрагментов с цитатами
        // chunkSize: 300 символов = более полные фрагменты для корректного цитирования
        // overlap: 50 символов = ~17% (сохраняет контекст на границах)
        val textChunker = TextChunker(chunkSize = 300, overlap = 50)
        val embeddingService = EmbeddingService(
            gigaChatClient = null, // GigaChat требует пакеты
            ollamaClient = ollamaClient, // Используем Ollama (бесплатно)
            textChunker = textChunker,
            markdownPreprocessor = markdownPreprocessor,
            batchSize = 15,
            useOllama = true // По умолчанию используем Ollama
        )
        val embeddingsManager = EmbeddingsManager()

        // Создаем папку для RAG документов
        val ragDocsDir = File("rag_docs")
        if (!ragDocsDir.exists()) {
            ragDocsDir.mkdirs()
            println("   📁 Создана папка rag_docs")
        }
        println("   ✅ RAG сервисы инициализированы (Ollama embeddings + Markdown preprocessor)\n")

        // 7. Создание Domain Layer
        println("7. Создание ChatOrchestrator...")
        val chatOrchestrator = ChatOrchestrator(
            gigaClient = gigaClient,
            historyManager = historyManager
        )
        println("   ChatOrchestrator создан\n")

        // 8. Создание Telegram Bot Service
        println("8. Инициализация Telegram Bot Service...")
        botService = TelegramBotService(
            telegramToken = config.telegramToken,
            chatOrchestrator = chatOrchestrator,
            settingsManager = settingsManager,
            embeddingService = embeddingService,
            embeddingsManager = embeddingsManager,
            textChunker = textChunker,
            ollamaClient = ollamaClient,
            applicationScope = applicationScope,
            defaultSystemRole = AssistantRole,
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

        println("=== TeleGaGa бот с RAG успешно запущен ===")
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

        // Увеличенные таймауты для работы с внешними API и RAG (qwen3 может быть медленным)
        install(HttpTimeout) {
            requestTimeoutMillis = 180000  // 3 минуты на весь запрос
            connectTimeoutMillis = 10000   // 10 секунд на установку соединения
            socketTimeoutMillis = 180000   // 3 минуты на чтение/запись
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
