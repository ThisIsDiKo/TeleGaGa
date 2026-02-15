package ru.dikoresearch

import kotlinx.coroutines.runBlocking
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.java.KoinJavaComponent.get
import ru.dikoresearch.di.*
import ru.dikoresearch.infrastructure.mcp.StdioMcpService
import ru.dikoresearch.infrastructure.telegram.TelegramBotService
import org.koin.core.qualifier.named

/**
 * Main entry point for TeleGaGa Telegram bot with Koin DI
 * Refactored from 448 lines to <100 lines using dependency injection
 */
fun main() {
    println("=== Starting TeleGaGa Bot with Koin DI ===\n")

    var botService: TelegramBotService? = null

    try {
        // 1. Initialize Koin DI container
        println("1. Initializing Koin DI container...")
        startKoin {
            modules(
                configModule,
                httpModule,
                llmModule,
                mcpModule,
                persistenceModule,
                domainModule,
                commandModule,
                telegramModule
            )
        }
        println("   ✅ Koin DI container initialized\n")

        // 2. Initialize MCP services
        println("2. Initializing MCP services...")
        runBlocking {
            // Git MCP (Docker)
            val gitMcp: StdioMcpService = get(StdioMcpService::class.java, named("gitMcp"))
            try {
                gitMcp.initialize()
                println("   ✅ Git MCP Service initialized")
            } catch (e: Exception) {
                println("   ⚠️ Git MCP Service failed: ${e.message}")
                e.printStackTrace()
            }

            // GitHub MCP (optional)
            val githubMcp: StdioMcpService? = get(StdioMcpService::class.java, named("githubMcp"))
            if (githubMcp != null) {
                try {
                    githubMcp.initialize()
                    println("   ✅ GitHub MCP Service initialized")
                } catch (e: Exception) {
                    println("   ⚠️ GitHub MCP Service failed: ${e.message}")
                    e.printStackTrace()
                }
            } else {
                println("   ⚠️ GitHub MCP Service not configured (missing github.token)")
            }
        }
        println()

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

        // Stop Telegram bot
        botService?.stop()
        println("✅ Telegram bot stopped")

        // Stop Koin
        stopKoin()
        println("✅ Koin DI container stopped")

        println("=== Application terminated ===")
    }
}
