package ru.dikoresearch.application.commands.handlers

import ru.dikoresearch.application.commands.CommandContext
import ru.dikoresearch.application.commands.CommandHandler

/**
 * Handles the /start command - sends welcome message with bot capabilities and commands
 */
class StartCommandHandler : CommandHandler {
    override val commandName = "start"

    override suspend fun handle(context: CommandContext) {
        val welcomeMessage = buildString {
            appendLine("👋 Hello! I'm TeleGaGa - a project expert bot with RAG support.")
            appendLine()
            appendLine("🧠 How I work:")
            appendLine("• Answer questions about the TeleGaGa project")
            appendLine("• Use RAG to search documentation")
            appendLine("• Save chat history separately for each chat")
            appendLine("• Provide accurate answers based on documentation")
            appendLine()
            appendLine("💬 Just ask a question!")
            appendLine("Examples:")
            appendLine("• Which MCP servers are used?")
            appendLine("• How does RAG work in the project?")
            appendLine("• Where is temperature configured?")
            appendLine()
            appendLine("📋 Management Commands:")
            appendLine("/changeRole <text> - change system prompt")
            appendLine("/changeT <number> - change temperature (0.0-1.0)")
            appendLine("/clearChat - clear chat history")
            appendLine("/setThreshold <0.0-1.0> - RAG relevance threshold")
            appendLine()
            appendLine("🔧 Additional Commands:")
            appendLine("/createEmbeddings - create embeddings from rag_docs/")
            appendLine("/showPR [number] - analyze Pull Request")
            appendLine("/help <question> - search documentation")
            appendLine()
            appendLine("💡 Requirements:")
            appendLine("• Ollama with nomic-embed-text model")
            appendLine("• Embeddings created via /createEmbeddings")
        }

        context.sendMessage(welcomeMessage)
    }
}
