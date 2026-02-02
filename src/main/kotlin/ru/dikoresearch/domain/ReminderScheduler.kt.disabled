package ru.dikoresearch.domain

import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.entities.ChatId
import kotlinx.coroutines.*
import ru.dikoresearch.infrastructure.persistence.ChatSettings
import ru.dikoresearch.infrastructure.persistence.ChatSettingsManager
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Планировщик для автоматической отправки напоминаний пользователям.
 * Проверяет каждую минуту настройки всех чатов и отправляет напоминания в заданное время.
 */
class ReminderScheduler(
    private val settingsManager: ChatSettingsManager,
    private val chatOrchestrator: ChatOrchestrator,
    private val telegramBot: Bot,
    private val applicationScope: CoroutineScope
) {
    private var schedulerJob: Job? = null

    /**
     * Запускает фоновый планировщик
     */
    fun start() {
        println("🕐 ReminderScheduler запущен")

        schedulerJob = applicationScope.launch {
            while (isActive) {
                try {
                    checkAndSendReminders()
                } catch (e: Exception) {
                    println("❌ Ошибка в ReminderScheduler: ${e.message}")
                    e.printStackTrace()
                }

                // Проверка каждую минуту
                delay(60_000L)
            }
        }
    }

    /**
     * Останавливает планировщик
     */
    fun stop() {
        schedulerJob?.cancel()
        println("🛑 ReminderScheduler остановлен")
    }

    /**
     * Проверяет все чаты и отправляет напоминания если пришло время
     */
    private suspend fun checkAndSendReminders() {
        val currentTime = LocalTime.now()
        val currentDate = LocalDate.now()

        // Получить все чаты с включенными напоминаниями
        val chatsWithReminders = settingsManager.getAllChatIds()
            .mapNotNull { chatId ->
                val settings = settingsManager.loadSettings(chatId)
                if (settings.reminderEnabled && settings.reminderTime != null) {
                    chatId to settings
                } else {
                    null
                }
            }

        if (chatsWithReminders.isEmpty()) {
            return
        }

        // Проверить каждый чат
        for ((chatId, settings) in chatsWithReminders) {
            try {
                val reminderTime = LocalTime.parse(settings.reminderTime)

                if (shouldSendReminder(currentTime, reminderTime, settings.lastReminderSent, currentDate)) {
                    println("✅ Отправка напоминаний для чата $chatId")
                    sendDailyReminders(chatId, settings)
                }
            } catch (e: Exception) {
                println("❌ Ошибка при обработке напоминаний для чата $chatId: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    /**
     * Проверяет, нужно ли отправить напоминание
     */
    private fun shouldSendReminder(
        currentTime: LocalTime,
        reminderTime: LocalTime,
        lastSent: String?,
        currentDate: LocalDate
    ): Boolean {
        // Проверка времени (с точностью до минуты)
        val timeMatches = currentTime.hour == reminderTime.hour &&
                currentTime.minute == reminderTime.minute

        if (!timeMatches) {
            return false
        }

        // Проверка что сегодня еще не отправляли
        if (lastSent == null) {
            return true
        }

        val lastSentDate = try {
            LocalDateTime.parse(lastSent).toLocalDate()
        } catch (e: Exception) {
            // Если не удалось распарсить - считаем что еще не отправляли
            return true
        }

        // Отправляем только если последняя отправка была раньше чем сегодня
        return lastSentDate.isBefore(currentDate)
    }

    /**
     * Отправляет ежедневные напоминания для чата
     */
    private suspend fun sendDailyReminders(chatId: Long, settings: ChatSettings) {
        try {
            println("📤 Отправка напоминаний для чата $chatId")

            val today = LocalDate.now().toString()

            // Формируем промпт для LLM
            val promptMessage = buildString {
                append("Используй инструмент get_reminders для получения моих напоминаний на сегодня (")
                append(today)
                append("). ")
                append("Покажи ВСЕ напоминания пронумерованным списком с эмодзи. ")
                append("ВАЖНО: не пропускай ни одно напоминание! Если в ответе от инструмента несколько напоминаний - покажи их все!")
            }

            // Специальный системный промпт для напоминаний
            val systemRole = """
                Ты - помощник по напоминаниям.
                Используй MCP инструмент get_reminders, чтобы получить список дел на указанную дату.

                КРИТИЧЕСКИ ВАЖНО:
                - Покажи ВСЕ напоминания из массива reminders, который вернет инструмент
                - Каждое напоминание оформи отдельным пунктом с номером и эмодзи
                - Не пропускай ни одно напоминание!
                - Формат: "1. 📌 [текст напоминания]"

                Если дел нет - скажи об этом позитивно.
            """.trimIndent()

            // Вызываем ChatOrchestrator для получения напоминаний
            val remindersResponse = chatOrchestrator.processMessage(
                chatId = chatId,
                userMessage = promptMessage,
                systemRole = systemRole,
                temperature = 0.3F,  // Низкая температура для стабильного результата
                enableMcp = true     // Включить MCP для доступа к инструментам
            )

            // Системный промпт для погоды
            val weatherSystemRole = """
                Ты - ассистент для получения погоды.
                Используй get_weather для получения погоды в указанном городе.
                Сформируй краткую сводку погоды (температура, ощущается как, описание, влажность, ветер).
            """.trimIndent()

            // Получаем погоду для Санкт-Петербурга
            val weatherResponse = chatOrchestrator.processMessage(
                chatId = chatId,
                userMessage = "Используй get_weather для получения погоды в Санкт-Петербурге (lang=ru)",
                systemRole = weatherSystemRole,
                temperature = 0.3F,
                enableMcp = true
            )

            // Системный промпт для шутки
            val jokeSystemRole = """
                Ты - ассистент для получения и перевода шуток.
                Используй get_chuck_norris_joke для получения шутки на английском.
                ОБЯЗАТЕЛЬНО переведи полученную шутку на русский язык естественно и с юмором.
                Верни только переведенный текст шутки без лишних комментариев.
            """.trimIndent()

            // Получаем и переводим шутку про Чака Норриса
            val jokeResponse = chatOrchestrator.processMessage(
                chatId = chatId,
                userMessage = "Используй get_chuck_norris_joke для получения шутки и переведи её на русский",
                systemRole = jokeSystemRole,
                temperature = 0.7F,
                enableMcp = true
            )

            // Формируем финальное сообщение
            val messageText = buildString {
                appendLine("🌅 Доброе утро! Вот твои дела на сегодня:")
                appendLine()
                appendLine(remindersResponse.text)
                appendLine()
                appendLine("🌤️ Погода в Санкт-Петербурге:")
                appendLine(weatherResponse.text)
                appendLine()
                appendLine("😄 Шутка дня от Чака Норриса:")
                appendLine(jokeResponse.text)
            }

            // Отправляем в Telegram
            telegramBot.sendMessage(
                chatId = ChatId.fromId(chatId),
                text = messageText
            )

            // Обновляем lastReminderSent
            val updatedSettings = settings.copy(
                lastReminderSent = LocalDateTime.now().toString()
            )
            settingsManager.saveSettings(chatId, updatedSettings)

        } catch (e: Exception) {
            println("❌ Ошибка при отправке напоминаний для чата $chatId: ${e.message}")
            e.printStackTrace()
        }
    }
}
