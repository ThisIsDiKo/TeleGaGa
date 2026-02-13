package ru.dikoresearch.infrastructure.config

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Properties

/**
 * Сервис для управления конфигурацией приложения
 * Загружает параметры из файла config.properties
 */
class ConfigService private constructor(
    val telegramToken: String,
    val gigaChatAuthKey: String,
    val gigaChatBaseUrl: String,
    val gigaChatModel: String,
    val ollamaChatModel: String,
    val ollamaEmbeddingModel: String,
    val githubToken: String?,
    val githubOwner: String?,
    val githubRepo: String?
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

            // Загружаем параметры
            val externalConfig = File(CONFIG_FILE)

            // Извлекаем параметры
            val telegramToken = properties.getProperty("telegram.token")?.trim()
            val gigaChatAuthKey = properties.getProperty("gigachat.authKey")?.trim()
            val gigaChatBaseUrl = properties.getProperty("gigachat.baseUrl")?.trim()
            val gigaChatModel = properties.getProperty("gigachat.model")?.trim()
            val ollamaChatModel = properties.getProperty("ollama.chatModel")?.trim()
            val ollamaEmbeddingModel = properties.getProperty("ollama.embeddingModel")?.trim()
            val githubToken = properties.getProperty("github.token")?.trim()
            val githubOwner = properties.getProperty("github.owner")?.trim()
            val githubRepo = properties.getProperty("github.repo")?.trim()

            // Валидация обязательных параметров
            val missingParams = mutableListOf<String>()
            if (telegramToken.isNullOrBlank()) missingParams.add("telegram.token")
            if (gigaChatAuthKey.isNullOrBlank()) missingParams.add("gigachat.authKey")
            if (gigaChatBaseUrl.isNullOrBlank()) missingParams.add("gigachat.baseUrl")
            if (gigaChatModel.isNullOrBlank()) missingParams.add("gigachat.model")
            if (ollamaChatModel.isNullOrBlank()) missingParams.add("ollama.chatModel")
            if (ollamaEmbeddingModel.isNullOrBlank()) missingParams.add("ollama.embeddingModel")

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
                gigaChatAuthKey = gigaChatAuthKey!!,
                gigaChatBaseUrl = gigaChatBaseUrl!!,
                gigaChatModel = gigaChatModel!!,
                ollamaChatModel = ollamaChatModel!!,
                ollamaEmbeddingModel = ollamaEmbeddingModel!!,
                githubToken = githubToken,
                githubOwner = githubOwner,
                githubRepo = githubRepo
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
                |   - gigachat.authKey = your GigaChat auth key
                |   - gigachat.baseUrl = https://gigachat.devices.sberbank.ru
                |   - gigachat.model = GigaChat
                |   - github.token = (optional) your GitHub token
                |   - github.owner = (optional) your GitHub username
                |   - github.repo = (optional) your repository name
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
            properties.setProperty("gigachat.authKey", "")
            properties.setProperty("gigachat.baseUrl", "https://gigachat.devices.sberbank.ru")
            properties.setProperty("gigachat.model", "GigaChat")
            properties.setProperty("ollama.chatModel", "llama3.2:3b")
            properties.setProperty("ollama.embeddingModel", "nomic-embed-text")
            properties.setProperty("github.token", "")
            properties.setProperty("github.owner", "")
            properties.setProperty("github.repo", "")

            FileOutputStream(templateFile).use { output ->
                properties.store(output, "TeleGaGa Configuration Template - заполните параметры и сохраните как config.properties")
            }

            println("Создан файл-шаблон конфигурации: $TEMPLATE_FILE")
        }
    }

    override fun toString(): String {
        return """
            |ConfigService:
            |  telegramToken: ${telegramToken.take(10)}...
            |  gigaChatAuthKey: ${gigaChatAuthKey.take(10)}...
            |  gigaChatBaseUrl: $gigaChatBaseUrl
            |  gigaChatModel: $gigaChatModel
            |  ollamaChatModel: $ollamaChatModel
            |  ollamaEmbeddingModel: $ollamaEmbeddingModel
            |  githubToken: ${if (githubToken != null) "${githubToken.take(10)}..." else "not set"}
            |  githubOwner: ${githubOwner ?: "not set"}
            |  githubRepo: ${githubRepo ?: "not set"}
        """.trimMargin()
    }
}
