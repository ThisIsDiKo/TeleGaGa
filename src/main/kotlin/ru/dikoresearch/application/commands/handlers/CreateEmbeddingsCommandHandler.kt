package ru.dikoresearch.application.commands.handlers

import ru.dikoresearch.application.commands.CommandContext
import ru.dikoresearch.application.commands.CommandHandler
import ru.dikoresearch.domain.TextChunker
import ru.dikoresearch.infrastructure.embeddings.EmbeddingService
import ru.dikoresearch.infrastructure.persistence.EmbeddingsManager
import java.io.File

/**
 * Handles the /createEmbeddings command - generates embeddings for documentation files
 * Supports single file or batch processing of all .md files in rag_docs/
 */
class CreateEmbeddingsCommandHandler(
    private val embeddingService: EmbeddingService,
    private val embeddingsManager: EmbeddingsManager,
    private val textChunker: TextChunker
) : CommandHandler {
    override val commandName = "createEmbeddings"

    override suspend fun handle(context: CommandContext) {
        val fileName = context.arguments.trim().ifBlank { null }

        try {
            if (fileName != null) {
                // Single file mode
                context.sendMessage("🦙 Generating embeddings for $fileName...\n(using Ollama local model)")

                val result = createEmbeddingsForFile(
                    filePath = "rag_docs/$fileName"
                )

                context.sendMessage(result)
            } else {
                // Process ALL .md files in rag_docs/
                val ragDir = File("rag_docs")
                if (!ragDir.exists()) {
                    context.sendMessage("❌ Directory rag_docs/ not found")
                    return
                }

                val mdFiles = ragDir.listFiles { file ->
                    file.extension == "md"
                }?.toList() ?: emptyList()

                if (mdFiles.isEmpty()) {
                    context.sendMessage("❌ No .md files found in rag_docs/")
                    return
                }

                context.sendMessage(
                    "🦙 Found ${mdFiles.size} markdown files. Processing...\n(using Ollama local model)"
                )

                val results = mutableListOf<String>()
                mdFiles.forEachIndexed { i, file ->
                    context.sendMessage("[${i + 1}/${mdFiles.size}] Processing ${file.name}...")

                    val result = createEmbeddingsForFile(
                        filePath = file.absolutePath
                    )
                    results.add(result)
                }

                val summary = buildString {
                    appendLine("✅ Embeddings created for ${mdFiles.size} files!")
                    appendLine()
                    results.forEach { appendLine(it) }
                }

                context.sendMessage(summary)
            }
        } catch (e: Exception) {
            context.sendMessage(
                "❌ Error: ${e.message}\n\n" +
                "💡 Make sure Ollama is running:\n" +
                "ollama pull nomic-embed-text"
            )
            e.printStackTrace()
        }
    }

    /**
     * Helper function to create embeddings for a single file
     */
    private suspend fun createEmbeddingsForFile(filePath: String): String {
        val file = File(filePath)
        if (!file.exists()) {
            return "❌ File not found: $filePath"
        }

        val originalText = file.readText()
        val startTime = System.currentTimeMillis()

        val embeddingsWithMetadata = embeddingService.generateEmbeddingsWithMetadata(
            originalText,
            file.name
        )

        val endTime = System.currentTimeMillis()
        val durationSeconds = (endTime - startTime) / 1000.0

        val fileNameWithoutExtension = file.nameWithoutExtension
        embeddingsManager.saveEmbeddingsWithMetadata(
            fileName = fileNameWithoutExtension,
            embeddings = embeddingsWithMetadata,
            chunkSize = textChunker.chunkSize
        )

        return "✅ ${file.name}: ${embeddingsWithMetadata.size} chunks in %.1f sec".format(durationSeconds)
    }
}
