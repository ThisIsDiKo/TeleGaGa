package ru.dikoresearch

import kotlinx.coroutines.runBlocking
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.qualifier.named
import org.koin.java.KoinJavaComponent.get
import ru.dikoresearch.di.*
import ru.dikoresearch.infrastructure.mcp.StdioMcpService
import ru.dikoresearch.infrastructure.telegram.TelegramBotService

/**
 * Main entry point for TeleGaGa Bot with Qwen 2.5 1.5B + MCP artifacts analysis
 */
fun main() {
    println("=== Starting TeleGaGa Qwen2.5 + Artifacts Bot ===\n")

    var botService: TelegramBotService? = null
    var artifactsMcp: StdioMcpService? = null

    try {
        // 1. Initialize Koin DI container
        println("1. Initializing Koin DI container...")
        startKoin {
            modules(
                configModule,
                httpModule,
                llmModule,
                multiModelModule,
                persistenceModule,
                domainModule,
                commandModule,
                telegramModule
            )
        }
        println("   ✅ Koin DI container initialized\n")

        // 2. Initialize MCP artifacts server
        println("2. Initializing MCP artifacts server...")
        artifactsMcp = get<StdioMcpService>(StdioMcpService::class.java, named("artifactsMcp"))
        runBlocking { artifactsMcp.initialize() }
        println("   ✅ MCP artifacts server ready\n")

        // 3. Start Telegram Bot
        println("3. Starting Telegram Bot...")
        botService = get(TelegramBotService::class.java)
        botService?.start()
        println("   ✅ Telegram Bot started\n")

        println("=== TeleGaGa Bot is running ===")
        println("Press Enter to stop (or Ctrl+C)\n")

        // Add shutdown hook for Ctrl+C
        Runtime.getRuntime().addShutdownHook(Thread {
            println("\n(Received shutdown signal)")
        })

        // Block main thread until Enter pressed
        val input = readLine()
        if (input == null) {
            // Stdin closed (e.g., running from IDE), wait indefinitely
            println("⚠️ Stdin not available, use Ctrl+C to stop")
            try {
                while (true) {
                    Thread.sleep(1000)
                }
            } catch (e: InterruptedException) {
                println("\n⚠️ Received interrupt signal")
            }
        }

    } catch (e: Exception) {
        e.printStackTrace()
        println("\n❌ Application error: ${e.message}")
    } finally {
        println("\n=== Graceful shutdown ===")

        // Stop MCP servers
        artifactsMcp?.let { runBlocking { it.shutdown() } }
        println("✅ MCP artifacts server stopped")

        // Stop Telegram bot
        botService?.stop()
        println("✅ Telegram bot stopped")

        // Stop Koin
        stopKoin()
        println("✅ Koin DI container stopped")

        println("=== Application terminated ===")
    }
}
