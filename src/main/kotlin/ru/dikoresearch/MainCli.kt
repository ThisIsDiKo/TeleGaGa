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
 * CLI system prompt for hybrid RAG-enabled assistant
 * Using Ollama llama3.2:3b model, English language only
 *
 * Supports two modes:
 * 1. WITH DOCUMENTATION: When relevant documentation is provided, cite sources
 * 2. WITHOUT DOCUMENTATION: Use your knowledge to answer questions
 */
val CLI_RAG_SYSTEM_PROMPT = """
You are an AI assistant that helps answer questions in English.

TWO MODES OF OPERATION:

MODE 1: WITH DOCUMENTATION (when fragments are provided below)
- Use ONLY the provided documentation fragments
- EVERY fact must have a citation: [quoted text from documentation]
- Place exact quote in square brackets after each fact
- If answer is not in fragments, say so

MODE 2: WITHOUT DOCUMENTATION (when no fragments are provided)
- Use your general knowledge to answer
- Be helpful and informative
- Admit when you don't know something
- No citations needed in this mode

EXAMPLE WITH DOCUMENTATION:
Fragment: "The system uses Ollama with llama3.2:3b model"
Answer: The system uses Ollama with llama3.2:3b model [The system uses Ollama with llama3.2:3b model].

EXAMPLE WITHOUT DOCUMENTATION:
Question: What is machine learning?
Answer: Machine learning is a subset of artificial intelligence that enables systems to learn and improve from experience without being explicitly programmed.

Be concise and direct.
""".trimIndent()

/**
 * Main entry point for CLI chatbot
 */
fun main(args: Array<String>) = runBlocking {
    try {
        // Parse CLI arguments
        val cliArgs = parseCliArguments(args)

        // Create HTTP client
        val httpClient = createHttpClient()

        // Initialize Ollama client (verbose=false for clean CLI output)
        val ollamaClient = OllamaClient(httpClient = httpClient, verbose = false)

        // Initialize RAG services
        val markdownPreprocessor = MarkdownPreprocessor()
        val textChunker = TextChunker(chunkSize = 300, overlap = 50)
        val embeddingService = EmbeddingService(
            gigaChatClient = null,
            ollamaClient = ollamaClient,
            textChunker = textChunker,
            markdownPreprocessor = markdownPreprocessor,
            batchSize = 15,
            useOllama = true,
            verbose = false  // Silent mode for CLI
        )
        val ragService = RagService(embeddingService = embeddingService)

        // Check if embeddings exist
        val embeddingsDir = File("embeddings_store")
        if (!embeddingsDir.exists() || embeddingsDir.listFiles { it.name.endsWith(".embeddings.json") }?.isEmpty() == true) {
            println("No embeddings found. Use /createEmbeddings command to create them.")
        }

        // Create CLI orchestrator
        val orchestrator = CliChatOrchestrator(
            ollamaClient = ollamaClient,
            ragService = ragService,
            systemPrompt = CLI_RAG_SYSTEM_PROMPT,
            ragEnabled = true,
            ragTopK = cliArgs.topK,
            ragRelevanceThreshold = cliArgs.threshold
        )

        // Create and start CLI chatbot
        val chatbot = CliChatbot(
            orchestrator = orchestrator,
            embeddingService = embeddingService,
            ragService = ragService
        )

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
