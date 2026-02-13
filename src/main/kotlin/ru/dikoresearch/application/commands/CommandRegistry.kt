package ru.dikoresearch.application.commands

/**
 * Registry that manages all command handlers
 * Implements the Registry pattern for command handler lookup
 */
class CommandRegistry(
    private val handlers: List<CommandHandler>
) {
    private val handlerMap: Map<String, CommandHandler> = handlers.associateBy { it.commandName }

    /**
     * Get handler for a specific command
     *
     * @param command the command name (without leading slash)
     * @return the handler if found, null otherwise
     */
    fun getHandler(command: String): CommandHandler? {
        return handlerMap[command]
    }

    /**
     * Get all registered command names
     *
     * @return list of all command names
     */
    fun getAllCommands(): List<String> {
        return handlers.map { it.commandName }.sorted()
    }

    /**
     * Check if a command is registered
     *
     * @param command the command name (without leading slash)
     * @return true if the command is registered
     */
    fun hasCommand(command: String): Boolean {
        return handlerMap.containsKey(command)
    }

    /**
     * Get total number of registered commands
     */
    fun size(): Int {
        return handlers.size
    }

    override fun toString(): String {
        return "CommandRegistry(${handlers.size} commands: ${getAllCommands().joinToString(", ")})"
    }
}
