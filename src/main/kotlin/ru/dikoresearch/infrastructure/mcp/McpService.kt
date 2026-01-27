package ru.dikoresearch.infrastructure.mcp

import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ListToolsResult
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Сервис для управления MCP (Model Context Protocol) клиентом и процессом.
 * Запускает Chuck Norris MCP сервер через Node.js и подключается к нему.
 */
class McpService(
    private val mcpServerPath: String = "mcp-chuck-server/index.js"
) {
    private var process: Process? = null
    private var client: Client? = null
    private val mutex = Mutex()

    @Volatile
    private var isInitialized = false

    /**
     * Инициализация MCP сервера и клиента.
     * Запускает node процесс и подключает клиента через StdioClientTransport.
     */
    suspend fun initialize() = mutex.withLock {
        if (isInitialized) {
            println("McpService уже инициализирован")
            return
        }

        try {
            println("Запуск Chuck Norris MCP сервера...")

            // Проверяем наличие файла сервера
            val serverFile = File(mcpServerPath)
            if (!serverFile.exists()) {
                throw IllegalStateException("MCP сервер не найден: ${serverFile.absolutePath}")
            }

            // Проверяем наличие node_modules
            val nodeModulesDir = File("mcp-chuck-server/node_modules")
            if (!nodeModulesDir.exists()) {
                println("⚠️ node_modules не найдены. Запустите: cd mcp-chuck-server && npm install")
                throw IllegalStateException("Зависимости MCP сервера не установлены")
            }

            // Запускаем node процесс
            process = ProcessBuilder("node", serverFile.absolutePath).start()

            val inputStream = process!!.inputStream.asSource().buffered()
            val outputStream = process!!.outputStream.asSink().buffered()

            // Создаем MCP клиента
            client = Client(
                clientInfo = Implementation(
                    name = "telegaga-bot",
                    version = "1.0.0"
                )
            )

            // Создаем transport и подключаемся
            val transport = StdioClientTransport(
                input = inputStream,
                output = outputStream
            )

            client!!.connect(transport)
            isInitialized = true

            println("✅ Chuck Norris MCP сервер успешно подключен")
        } catch (e: Exception) {
            println("Ошибка при инициализации Chuck Norris MCP сервера: ${e.message}")
            e.printStackTrace()

            // Очистка ресурсов при ошибке
            process?.destroyForcibly()
            process = null
            client = null
            isInitialized = false

            throw e
        }
    }

    /**
     * Проверяет, доступен ли MCP сервер для использования.
     */
    fun isAvailable(): Boolean {
        return isInitialized && process?.isAlive == true
    }

    /**
     * Получает список доступных инструментов от MCP сервера.
     * @return ListToolsResult с информацией о доступных инструментах
     * @throws IllegalStateException если сервис не инициализирован
     */
    suspend fun listTools(): ListToolsResult {
        checkInitialized()

        return try {
            val result = client!!.listTools()
            println("Получен список инструментов: ${result.tools.size} шт.")
            result
        } catch (e: Exception) {
            println("Ошибка при получении списка инструментов: ${e.message}")
            throw e
        }
    }

    /**
     * Вызывает указанный инструмент с заданными аргументами.
     * @param name имя инструмента для вызова
     * @param arguments аргументы для инструмента в формате Map
     * @return CallToolResult с результатом выполнения инструмента
     * @throws IllegalStateException если сервис не инициализирован
     */
    suspend fun callTool(name: String, arguments: Map<String, Any>): CallToolResult {
        checkInitialized()

        return try {
            println("Вызов MCP инструмента: $name с аргументами: $arguments")
            val result = client!!.callTool(name, arguments)
            println("Инструмент $name выполнен успешно")
            result
        } catch (e: Exception) {
            println("Ошибка при вызове инструмента $name: ${e.message}")
            throw e
        }
    }

    /**
     * Graceful shutdown MCP клиента и процесса.
     * Пытается корректно закрыть соединение перед уничтожением процесса.
     */
    suspend fun shutdown() = mutex.withLock {
        if (!isInitialized) {
            println("McpService уже остановлен")
            return
        }

        println("Остановка Chuck Norris MCP сервиса...")

        try {
            // Закрываем клиента
            client?.close()
            println("MCP клиент закрыт")
        } catch (e: Exception) {
            println("Ошибка при закрытии MCP клиента: ${e.message}")
        }

        try {
            // Пытаемся корректно завершить процесс
            process?.let { proc ->
                proc.destroy()
                val terminated = proc.waitFor(5, TimeUnit.SECONDS)

                if (!terminated) {
                    println("Процесс не завершился за 5 секунд, принудительное завершение...")
                    proc.destroyForcibly()
                    proc.waitFor(2, TimeUnit.SECONDS)
                }

                println("Chuck Norris MCP процесс завершен")
            }
        } catch (e: Exception) {
            println("Ошибка при завершении MCP процесса: ${e.message}")
            process?.destroyForcibly()
        } finally {
            process = null
            client = null
            isInitialized = false
        }

        println("Chuck Norris McpService успешно остановлен")
    }

    /**
     * Проверяет, что сервис инициализирован.
     * @throws IllegalStateException если сервис не инициализирован
     */
    private fun checkInitialized() {
        if (!isInitialized) {
            throw IllegalStateException("McpService не инициализирован. Вызовите initialize() перед использованием.")
        }

        if (process?.isAlive != true) {
            throw IllegalStateException("Chuck Norris MCP процесс не запущен или был завершен.")
        }
    }
}
