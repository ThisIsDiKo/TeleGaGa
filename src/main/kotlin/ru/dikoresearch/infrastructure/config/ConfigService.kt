package ru.dikoresearch.infrastructure.config

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Properties

/**
 * Упрощенный сервис для управления конфигурацией приложения
 * Загружает параметры из файла config.properties
 *
 * Поддерживает только Ollama (без GigaChat, MCP, RAG, GitHub)
 */
class ConfigService private constructor(
    val telegramToken: String,
    val ollamaBaseUrl: String,
    val ollamaEmbeddingModel: String
) {
    companion object {
        private const val CONFIG_FILE = "config.properties"
        private const val TEMPLATE_FILE = "config.properties.template"

        /**
         * Загружает конфигурацию из файла config.properties
         * Поддерживает приоритеты:
         * 1. External config.properties (next to JAR)
         * 2. Bundled config.properties (inside JAR)
         * 3. Extract template and throw error
         */
        fun load(): ConfigService {
            val properties = loadPropertiesWithPriority()

            // Извлекаем параметры
            val telegramToken = properties.getProperty("telegram.token")?.trim()
            val ollamaBaseUrl = properties.getProperty("ollama.baseUrl")?.trim() ?: "http://localhost:11434"
            val ollamaEmbeddingModel = properties.getProperty("ollama.embeddingModel")?.trim() ?: "nomic-embed-text"

            // Валидация обязательных параметров
            val missingParams = mutableListOf<String>()
            if (telegramToken.isNullOrBlank()) missingParams.add("telegram.token")

            if (missingParams.isNotEmpty()) {
                throw IllegalStateException(
                    """
                    |Не заполнены обязательные параметры конфигурации в файле $CONFIG_FILE:
                    |${missingParams.joinToString("\n") { "- $it" }}
                    |
                    |Пожалуйста, откройте файл $CONFIG_FILE и заполните все параметры.
                    """.trimMargin()
                )
            }

            return ConfigService(
                telegramToken = telegramToken!!,
                ollamaBaseUrl = ollamaBaseUrl,
                ollamaEmbeddingModel = ollamaEmbeddingModel
            )
        }

        /**
         * Loads properties with priority system:
         * 1. External config.properties (next to JAR)
         * 2. Bundled config.properties (inside JAR resources)
         * 3. Extract template and throw error
         */
        private fun loadPropertiesWithPriority(): Properties {
            val externalConfig = File(CONFIG_FILE)

            // Priority 1: External config.properties
            if (externalConfig.exists()) {
                println("✅ Using external config: $CONFIG_FILE")
                val properties = Properties()
                FileInputStream(externalConfig).use { properties.load(it) }
                return properties
            }

            // Priority 2: Bundled config.properties (inside JAR)
            val classpathConfig = javaClass.classLoader.getResourceAsStream(CONFIG_FILE)
            if (classpathConfig != null) {
                println("⚠️ Using bundled config from JAR: $CONFIG_FILE")
                val properties = Properties()
                classpathConfig.use { properties.load(it) }
                return properties
            }

            // Priority 3: No config found - extract template and throw error
            extractTemplateFromJar()
            throw IllegalStateException(
                """
                |Configuration file not found!
                |
                |A template has been created: $CONFIG_FILE
                |
                |Instructions:
                |1. Edit the file $CONFIG_FILE and fill in your values:
                |   - telegram.token = your Telegram bot token
                |   - ollama.baseUrl = http://localhost:11434 (default)
                |   - ollama.embeddingModel = nomic-embed-text (default)
                |2. Run the application again
                |
                |Note: config.properties is in .gitignore and won't be committed
                """.trimMargin()
            )
        }

        /**
         * Extracts config template from JAR resources to external file
         */
        private fun extractTemplateFromJar() {
            val templateStream = javaClass.classLoader.getResourceAsStream("config.properties.template")
            if (templateStream != null) {
                File(CONFIG_FILE).outputStream().use { output ->
                    templateStream.use { input ->
                        input.copyTo(output)
                    }
                }
                println("📄 Template extracted to: $CONFIG_FILE")
            } else {
                // Fallback: create template manually if not in JAR
                createTemplate()
            }
        }

        /**
         * Создает файл-шаблон config.properties.template с пустыми значениями
         */
        private fun createTemplate() {
            val templateFile = File(TEMPLATE_FILE)
            val properties = Properties()

            properties.setProperty("telegram.token", "")
            properties.setProperty("ollama.baseUrl", "http://localhost:11434")
            properties.setProperty("ollama.embeddingModel", "nomic-embed-text")

            FileOutputStream(templateFile).use { output ->
                properties.store(output, "TeleGaGa Multi-Model Ollama Bot Configuration - заполните параметры и сохраните как config.properties")
            }

            println("Создан файл-шаблон конфигурации: $TEMPLATE_FILE")
        }
    }

    override fun toString(): String {
        return """
            |ConfigService:
            |  telegramToken: ${telegramToken.take(10)}...
            |  ollamaBaseUrl: $ollamaBaseUrl
            |  ollamaEmbeddingModel: $ollamaEmbeddingModel
        """.trimMargin()
    }
}
