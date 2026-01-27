package ru.dikoresearch.infrastructure.persistence

import GigaChatMessage
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Менеджер для управления историей чатов с сохранением в JSON файлы.
 *
 * Каждый чат хранится в отдельном файле: chat_histories/<chatId>.json
 * Формат файла: массив объектов GigaChatMessage
 */
class ChatHistoryManager(
    private val historyDirectory: String = "chat_histories"
) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    init {
        // Создаем директорию для истории, если её нет
        val dir = File(historyDirectory)
        if (!dir.exists()) {
            dir.mkdirs()
            println("Создана директория для истории чатов: $historyDirectory")
        }
    }

    /**
     * Получает путь к файлу истории для конкретного чата
     */
    private fun getHistoryFilePath(chatId: Long): String {
        return "$historyDirectory/$chatId.json"
    }

    /**
     * Загружает историю чата из файла.
     * Возвращает пустой список, если файл не существует или произошла ошибка
     */
    fun loadHistory(chatId: Long): MutableList<GigaChatMessage> {
        val filePath = getHistoryFilePath(chatId)
        val file = File(filePath)

        return try {
            if (file.exists()) {
                val content = file.readText()
                val messages = json.decodeFromString<List<GigaChatMessage>>(content)
                println("Загружена история для чата $chatId: ${messages.size} сообщений")
                messages.toMutableList()
            } else {
                println("Файл истории для чата $chatId не найден, создается новая история")
                mutableListOf()
            }
        } catch (e: Exception) {
            println("Ошибка при загрузке истории чата $chatId: ${e.message}")
            e.printStackTrace()
            mutableListOf()
        }
    }

    /**
     * Сохраняет историю чата в файл.
     * Полностью перезаписывает существующий файл
     */
    fun saveHistory(chatId: Long, history: List<GigaChatMessage>) {
        val filePath = getHistoryFilePath(chatId)

        try {
            val content = json.encodeToString(history)
            File(filePath).writeText(content)
            println("Сохранена история для чата $chatId: ${history.size} сообщений")
        } catch (e: Exception) {
            println("Ошибка при сохранении истории чата $chatId: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * Очищает историю чата - удаляет файл полностью
     */
    fun clearHistory(chatId: Long): Boolean {
        val filePath = getHistoryFilePath(chatId)
        val file = File(filePath)

        return try {
            if (file.exists()) {
                val deleted = file.delete()
                if (deleted) {
                    println("История чата $chatId успешно удалена")
                } else {
                    println("Не удалось удалить файл истории чата $chatId")
                }
                deleted
            } else {
                println("Файл истории для чата $chatId не существует")
                true // Считаем успехом, так как цель достигнута - истории нет
            }
        } catch (e: Exception) {
            println("Ошибка при удалении истории чата $chatId: ${e.message}")
            e.printStackTrace()
            false
        }
    }

    /**
     * Проверяет, существует ли история для данного чата
     */
    fun hasHistory(chatId: Long): Boolean {
        return File(getHistoryFilePath(chatId)).exists()
    }

    /**
     * Получает размер файла истории в байтах
     */
    fun getHistoryFileSize(chatId: Long): Long {
        val file = File(getHistoryFilePath(chatId))
        return if (file.exists()) file.length() else 0L
    }

    /**
     * Возвращает список всех чатов, для которых есть сохраненная история
     */
    fun getAllChatIds(): List<Long> {
        val dir = File(historyDirectory)
        return dir.listFiles { file ->
            file.isFile && file.extension == "json"
        }?.mapNotNull { file ->
            file.nameWithoutExtension.toLongOrNull()
        } ?: emptyList()
    }
}
