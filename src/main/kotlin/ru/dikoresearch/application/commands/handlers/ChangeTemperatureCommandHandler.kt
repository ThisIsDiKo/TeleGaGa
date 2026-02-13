package ru.dikoresearch.application.commands.handlers

import ru.dikoresearch.application.commands.CommandContext
import ru.dikoresearch.application.commands.CommandHandler
import ru.dikoresearch.domain.valueobjects.Temperature
import ru.dikoresearch.infrastructure.persistence.ChatSettingsManager

/**
 * Handles the /changeT command - changes the LLM temperature parameter for a chat
 */
class ChangeTemperatureCommandHandler(
    private val settingsManager: ChatSettingsManager
) : CommandHandler {
    override val commandName = "changeT"

    override suspend fun handle(context: CommandContext) {
        val temperatureStr = context.arguments

        val newTemperature = try {
            temperatureStr.toFloat()
        } catch (e: Exception) {
            context.sendMessage("Температура должна быть числом от 0.0 до 1.0")
            return
        }

        if (newTemperature !in 0.0f..1.0f) {
            context.sendMessage("Температура должна быть в диапазоне от 0.0 до 1.0")
            return
        }

        try {
            val settings = settingsManager.loadSettings(context.chatId)
            val updatedSettings = settings.copy(temperature = Temperature(newTemperature))
            settingsManager.saveSettings(context.chatId, updatedSettings)
            context.sendMessage("Температура изменена на: $newTemperature")
            println("Температура изменена для чата ${context.chatId}: $newTemperature")
        } catch (e: Exception) {
            context.sendMessage("Ошибка при изменении температуры: ${e.message}")
        }
    }
}
