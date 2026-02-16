package ru.dikoresearch.infrastructure.persistence

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ru.dikoresearch.domain.valueobjects.ChatId
import ru.dikoresearch.domain.valueobjects.SystemPrompt
import ru.dikoresearch.domain.valueobjects.Temperature
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Упрощенные настройки чата для мульти-модельного режима
 */
@Serializable
data class ChatSettings(
    val chatId: ChatId,
    val temperature: Temperature = Temperature.DEFAULT,
    val systemPrompt: SystemPrompt? = null  // null означает "использовать дефолтный промпт"
)

/**
 * Менеджер для управления настройками чатов с файловым хранением
 */
class ChatSettingsManager(private val settingsDirectory: String = "chat_settings") {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    // Thread-safety: один Mutex на каждый chatId
    private val settingsLocks = ConcurrentHashMap<Long, Mutex>()

    init {
        // Создать директорию если не существует
        File(settingsDirectory).mkdirs()
        println("✅ ChatSettingsManager инициализирован (директория: $settingsDirectory)")
    }

    /**
     * Получить файл настроек для чата
     */
    private fun getSettingsFile(chatId: Long): File {
        return File("$settingsDirectory/${chatId}_settings.json")
    }

    /**
     * Получить Mutex для конкретного чата
     */
    private fun getLock(chatId: Long): Mutex {
        return settingsLocks.computeIfAbsent(chatId) { Mutex() }
    }

    /**
     * Загрузить настройки чата
     */
    suspend fun loadSettings(chatId: Long): ChatSettings = getLock(chatId).withLock {
        val file = getSettingsFile(chatId)

        if (!file.exists()) {
            // Файла нет - вернуть настройки по умолчанию
            return ChatSettings(chatId = ChatId(chatId))
        }

        try {
            val jsonText = file.readText()
            json.decodeFromString<ChatSettings>(jsonText)
        } catch (e: Exception) {
            println("⚠️ Ошибка загрузки настроек для чата $chatId: ${e.message}")
            println("   Используем настройки по умолчанию")
            ChatSettings(chatId = ChatId(chatId))
        }
    }

    /**
     * Сохранить настройки чата
     */
    suspend fun saveSettings(chatId: Long, settings: ChatSettings) = getLock(chatId).withLock {
        val file = getSettingsFile(chatId)

        try {
            val jsonText = json.encodeToString(settings)
            file.writeText(jsonText)
            println("💾 Настройки сохранены для чата $chatId")
        } catch (e: Exception) {
            println("❌ Ошибка сохранения настроек для чата $chatId: ${e.message}")
            throw e
        }
    }

    /**
     * Получить все chatId с сохраненными настройками
     */
    fun getAllChatIds(): List<Long> {
        return File(settingsDirectory).listFiles()
            ?.mapNotNull { file ->
                // Парсим имя файла: <chatId>_settings.json
                val fileName = file.name
                if (fileName.endsWith("_settings.json")) {
                    fileName.removeSuffix("_settings.json").toLongOrNull()
                } else {
                    null
                }
            }
            ?.sorted()
            ?: emptyList()
    }
}
