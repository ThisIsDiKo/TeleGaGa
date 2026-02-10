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
    val githubToken: String?,
    val githubOwner: String?,
    val githubRepo: String?
) {
    companion object {
        private const val CONFIG_FILE = "config.properties"
        private const val TEMPLATE_FILE = "config.properties.template"

        /**
         * Загружает конфигурацию из файла config.properties
         * Если файл не существует, создает шаблон и выбрасывает исключение с инструкцией
         */
        fun load(): ConfigService {
            val configFile = File(CONFIG_FILE)

            // Если файл не существует, создаем шаблон
            if (!configFile.exists()) {
                createTemplate()
                throw IllegalStateException(
                    """
                    |Конфигурационный файл не найден!
                    |
                    |Был создан файл-шаблон: $TEMPLATE_FILE
                    |
                    |Инструкция:
                    |1. Скопируйте файл $TEMPLATE_FILE в $CONFIG_FILE
                    |2. Заполните все параметры в файле $CONFIG_FILE:
                    |   - telegram.token = ваш токен Telegram бота
                    |   - gigachat.authKey = ваш ключ авторизации GigaChat
                    |   - gigachat.baseUrl = https://gigachat.devices.sberbank.ru
                    |   - gigachat.model = GigaChat
                    |3. Запустите приложение снова
                    |
                    |Примечание: Файл $CONFIG_FILE не будет сохранен в git (добавлен в .gitignore)
                    """.trimMargin()
                )
            }

            // Загружаем параметры
            val properties = Properties()
            FileInputStream(configFile).use { properties.load(it) }

            // Извлекаем параметры
            val telegramToken = properties.getProperty("telegram.token")?.trim()
            val gigaChatAuthKey = properties.getProperty("gigachat.authKey")?.trim()
            val gigaChatBaseUrl = properties.getProperty("gigachat.baseUrl")?.trim()
            val gigaChatModel = properties.getProperty("gigachat.model")?.trim()
            val githubToken = properties.getProperty("github.token")?.trim()
            val githubOwner = properties.getProperty("github.owner")?.trim()
            val githubRepo = properties.getProperty("github.repo")?.trim()

            // Валидация обязательных параметров
            val missingParams = mutableListOf<String>()
            if (telegramToken.isNullOrBlank()) missingParams.add("telegram.token")
            if (gigaChatAuthKey.isNullOrBlank()) missingParams.add("gigachat.authKey")
            if (gigaChatBaseUrl.isNullOrBlank()) missingParams.add("gigachat.baseUrl")
            if (gigaChatModel.isNullOrBlank()) missingParams.add("gigachat.model")

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
                githubToken = githubToken,
                githubOwner = githubOwner,
                githubRepo = githubRepo
            )
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
            |  githubToken: ${if (githubToken != null) "${githubToken.take(10)}..." else "not set"}
            |  githubOwner: ${githubOwner ?: "not set"}
            |  githubRepo: ${githubRepo ?: "not set"}
        """.trimMargin()
    }
}
