package ru.dikoresearch.domain

import GigaChatMessage
import ru.dikoresearch.domain.valueobjects.ChatId
import ru.dikoresearch.domain.valueobjects.SystemPrompt
import ru.dikoresearch.domain.valueobjects.Temperature
import ru.dikoresearch.infrastructure.http.OllamaClient
import ru.dikoresearch.infrastructure.persistence.ChatHistoryManager
import ru.dikoresearch.infrastructure.persistence.OllamaModel

/**
 * Data class для ответа от одной модели
 */
data class ModelResponse(
    val modelName: String,
    val content: String,
    val generationTimeMs: Long
)

/**
 * Оркестратор для работы с моделью Gemma3
 */
class MultiModelChatOrchestrator(
    private val gemma3Client: OllamaClient,
    private val historyManager: ChatHistoryManager
) {
    /**
     * Обрабатывает сообщение пользователя через Gemma3
     *
     * @param chatId идентификатор чата
     * @param userMessage текст сообщения пользователя
     * @param systemRole системный промпт
     * @param temperature температура генерации (0.0 - 1.0)
     * @return список ответов от модели
     */
    suspend fun processMessage(
        chatId: ChatId,
        userMessage: String,
        systemRole: SystemPrompt,
        temperature: Temperature
    ): List<ModelResponse> {
        return listOf(processWithModel(
            model = OllamaModel.GEMMA3,
            client = gemma3Client,
            chatId = chatId,
            userMessage = userMessage,
            systemRole = systemRole,
            temperature = temperature
        ))
    }

    /**
     * Обрабатывает сообщение для конкретной модели
     */
    private suspend fun processWithModel(
        model: OllamaModel,
        client: OllamaClient,
        chatId: ChatId,
        userMessage: String,
        systemRole: SystemPrompt,
        temperature: Temperature
    ): ModelResponse {
        // 1. Загружаем историю для этой модели
        val history = historyManager.loadHistory(chatId.value, model)

        // 2. Обновляем системный промпт (если изменился или это первое сообщение)
        if (history.isEmpty() || history.first().role != "system") {
            history.add(0, GigaChatMessage(role = "system", content = systemRole.value))
        } else {
            history[0] = GigaChatMessage(role = "system", content = systemRole.value)
        }

        // 3. Добавляем сообщение пользователя
        history.add(GigaChatMessage(role = "user", content = userMessage))

        // 4. Засекаем время начала
        val startTime = System.currentTimeMillis()

        // 5. Вызываем модель
        val response = try {
            client.chatCompletion(messages = history)
        } catch (e: Exception) {
            println("Ошибка при обращении к модели ${model.displayName}: ${e.message}")
            e.printStackTrace()

            // Возвращаем ответ с ошибкой
            val duration = System.currentTimeMillis() - startTime
            return ModelResponse(
                modelName = model.displayName,
                content = "❌ Ошибка: ${e.message}",
                generationTimeMs = duration
            )
        }

        // 6. Вычисляем время генерации (конвертируем наносекунды в миллисекунды)
        val generationTimeMs = response.total_duration / 1_000_000

        // 7. Добавляем ответ модели в историю
        history.add(response.message)

        // 8. Сохраняем историю для этой модели
        historyManager.saveHistory(chatId.value, model, history)

        // 9. Возвращаем результат
        return ModelResponse(
            modelName = model.displayName,
            content = response.message.content ?: "Пустой ответ",
            generationTimeMs = generationTimeMs
        )
    }

    /**
     * Очищает историю Gemma3
     */
    fun clearHistory(chatId: ChatId): Boolean {
        return historyManager.clearAllHistories(chatId.value)
    }
}
