package ru.dikoresearch

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import ru.dikoresearch.domain.CliChatOrchestrator
import ru.dikoresearch.domain.MarkdownPreprocessor
import ru.dikoresearch.domain.RagService
import ru.dikoresearch.domain.TextChunker
import ru.dikoresearch.infrastructure.cli.CliChatbot
import ru.dikoresearch.infrastructure.embeddings.EmbeddingService
import ru.dikoresearch.infrastructure.http.OllamaClient
import java.io.File
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager
import kotlin.system.exitProcess

/**
 * CLI system prompt for RAG-enabled assistant with mandatory citations
 * Using Ollama llama3.2:3b model, English language only
 */
val CLI_RAG_SYSTEM_PROMPT = """
You are an AI assistant that answers questions ONLY using the provided documentation.
Answer in English language only.

MANDATORY CITATION RULES:

1. USE ONLY PROVIDED FRAGMENTS
   - Never invent information
   - If the answer is not in fragments, say: "The documentation does not contain information about this topic"

2. EVERY FACT MUST HAVE A CITATIO
   - Citation format: [quoted text from documentation]
   - Place exact quote in square brackets after each fact

3. FORBIDDEN ACTIONS
   - Answer without citations
   - Paraphrase without quoting

CORRECT EXAMPLE:
Q: What models are used?
Fragment: "The system uses Ollama with llama3.2:3b model"
Answer: The system uses Ollama with llama3.2:3b model [The system uses Ollama with llama3.2:3b model].

This is a CLI - be concise and direct.
""".trimIndent()

/**
 * Main entry point for CLI chatbot
 */
fun main(args: Array<String>) = runBlocking {
    try {
        println("=".repeat(60))
        println("TeleGaGa CLI Chatbot - Initializing")
        println("=".repeat(60))
        println()

        // Parse CLI arguments
        val cliArgs = parseCliArguments(args)
        println("Configuration:")
        println("  Model: llama3.2:3b (Ollama)")
        println("  RAG Threshold: ${cliArgs.threshold}")
        println("  RAG Top-K: ${cliArgs.topK}")
        println()

        // 1. Create HTTP client
        println("[1/6] Creating HTTP client...")
        val httpClient = createHttpClient()
        println("  HTTP client created")
        println()

        // 2. Initialize Ollama client
        println("[2/6] Initializing Ollama client...")
        val ollamaClient = OllamaClient(httpClient = httpClient)
        println("  Ollama client created (http://localhost:11434)")
        println("  Model: llama3.2:3b")
        println()

        // 3. Initialize RAG services
        println("[3/6] Initializing RAG services...")
        val markdownPreprocessor = MarkdownPreprocessor()
        val textChunker = TextChunker(chunkSize = 300, overlap = 50)
        val embeddingService = EmbeddingService(
            gigaChatClient = null,
            ollamaClient = ollamaClient,
            textChunker = textChunker,
            markdownPreprocessor = markdownPreprocessor,
            batchSize = 15,
            useOllama = true
        )
        val ragService = RagService(embeddingService = embeddingService)
        println("  RAG services initialized (Ollama nomic-embed-text)")
        println()

        // 4. Check embeddings file exists
        println("[4/6] Checking embeddings...")
        val embeddingsFile = File("embeddings_store/readme.embeddings.json")
        if (!embeddingsFile.exists()) {
            println("  ERROR: Embeddings file not found!")
            println("  Expected: ${embeddingsFile.absolutePath}")
            println("  Please create embeddings first using the Telegram bot's /createEmbeddings command")
            exitProcess(1)
        }
        println("  Embeddings file found: ${embeddingsFile.absolutePath}")
        println()

        // 5. Create CLI orchestrator
        println("[5/6] Creating CLI orchestrator...")
        val orchestrator = CliChatOrchestrator(
            ollamaClient = ollamaClient,
            ragService = ragService,
            systemPrompt = CLI_RAG_SYSTEM_PROMPT,
            ragEnabled = true,
            ragTopK = cliArgs.topK,
            ragRelevanceThreshold = cliArgs.threshold
        )
        println("  CLI orchestrator created")
        println()

        // 6. Create and start CLI chatbot
        println("[6/6] Starting CLI chatbot...")
        val chatbot = CliChatbot(orchestrator = orchestrator)
        println("  CLI chatbot ready")
        println()

        // Start REPL
        chatbot.start()

    } catch (e: Exception) {
        println("\nFATAL ERROR: ${e.message}")
        e.printStackTrace()
        exitProcess(1)
    }
}

/**
 * CLI arguments data class
 */
data class CliArguments(
    val threshold: Float = 0.5f,
    val topK: Int = 5
)

/**
 * Parse CLI arguments
 */
fun parseCliArguments(args: Array<String>): CliArguments {
    var threshold = 0.5f
    var topK = 5

    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--threshold" -> {
                if (i + 1 < args.size) {
                    threshold = args[i + 1].toFloatOrNull() ?: 0.5f
                    i++
                }
            }
            "--topK" -> {
                if (i + 1 < args.size) {
                    topK = args[i + 1].toIntOrNull() ?: 5
                    i++
                }
            }
        }
        i++
    }

    return CliArguments(
        threshold = threshold,
        topK = topK
    )
}

/**
 * Create HTTP client with SSL verification disabled (for GigaChat)
 */
private fun createHttpClient(): HttpClient {
    val json = Json { ignoreUnknownKeys = true }

    return HttpClient(CIO) {
        install(ContentNegotiation) {
            json(json)
        }
        install(Logging)

        // Increased timeouts for external APIs and RAG
        install(HttpTimeout) {
            requestTimeoutMillis = 180000  // 3 minutes
            connectTimeoutMillis = 10000   // 10 seconds
            socketTimeoutMillis = 180000   // 3 minutes
        }

        // SSL verification disabled due to GigaChat certificate issues
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
