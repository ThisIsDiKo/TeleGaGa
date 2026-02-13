package ru.dikoresearch.application.commands.handlers

import ru.dikoresearch.application.commands.CommandContext
import ru.dikoresearch.application.commands.CommandHandler
import ru.dikoresearch.domain.valueobjects.RagThreshold
import ru.dikoresearch.infrastructure.persistence.ChatSettingsManager

/**
 * Handles the /setThreshold command - sets the RAG relevance threshold for filtering
 */
class SetThresholdCommandHandler(
    private val settingsManager: ChatSettingsManager
) : CommandHandler {
    override val commandName = "setThreshold"

    override suspend fun handle(context: CommandContext) {
        val thresholdStr = context.arguments.trim()

        if (thresholdStr.isEmpty()) {
            context.sendMessage(
                buildString {
                    appendLine("❌ Please specify threshold value")
                    appendLine()
                    appendLine("Usage: /setThreshold <0.0-1.0>")
                    appendLine()
                    appendLine("Recommended values:")
                    appendLine("• 0.3-0.4 (low) - more results, may include noise")
                    appendLine("• 0.5-0.6 (medium) - balanced approach")
                    appendLine("• 0.7-0.8 (high) - only highly relevant chunks")
                }
            )
            return
        }

        val threshold = try {
            thresholdStr.toFloat()
        } catch (e: Exception) {
            context.sendMessage("❌ Invalid threshold value. Must be a number between 0.0 and 1.0")
            return
        }

        if (threshold !in 0.0f..1.0f) {
            context.sendMessage("❌ Threshold must be between 0.0 and 1.0 (got: $threshold)")
            return
        }

        try {
            val settings = settingsManager.loadSettings(context.chatId)
            val updatedSettings = settings.copy(ragRelevanceThreshold = RagThreshold(threshold))
            settingsManager.saveSettings(context.chatId, updatedSettings)

            val category = when {
                threshold < 0.5 -> "low (more results)"
                threshold < 0.7 -> "medium (balanced)"
                else -> "high (strict)"
            }

            context.sendMessage(
                buildString {
                    appendLine("✅ RAG relevance threshold updated to: $threshold")
                    appendLine()
                    appendLine("Category: $category")
                    appendLine()
                    appendLine("This will be used for filtering search results in future queries.")
                }
            )

            println("RAG threshold updated for chat ${context.chatId}: $threshold ($category)")
        } catch (e: Exception) {
            context.sendMessage("❌ Error updating threshold: ${e.message}")
        }
    }
}
