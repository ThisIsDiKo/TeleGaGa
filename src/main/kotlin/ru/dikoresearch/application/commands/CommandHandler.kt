package ru.dikoresearch.application.commands

import com.github.kotlintelegrambot.Bot

/**
 * Interface for command handlers - implements Single Responsibility Principle
 * Each command handler is responsible for exactly one bot command
 */
interface CommandHandler {
    /**
     * The command name without the leading slash (e.g., "start", "help", "showPR")
     */
    val commandName: String

    /**
     * Handle the command with the given context
     *
     * @param context the command execution context
     */
    suspend fun handle(context: CommandContext)
}

/**
 * Context object that provides all dependencies needed by command handlers
 * Implements the Parameter Object pattern to avoid long parameter lists
 */
data class CommandContext(
    val bot: Bot,
    val chatId: Long,
    val messageId: Long,
    val userId: Long,
    val arguments: String, // Text after the command (e.g., "/changeRole <text>" -> arguments = "<text>")
    val sendMessage: suspend (String) -> Unit, // Helper to send messages to the chat
    val editMessage: suspend (String) -> Unit  // Helper to edit the current message
)
