package ru.dikoresearch.infrastructure.persistence

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ru.dikoresearch.infrastructure.embeddings.EmbeddingWithMetadata
import java.io.File

/**
 * Модель для сохранения embeddings в JSON
 */
@Serializable
data class EmbeddingRecord(
    val text: String,
    val embedding: List<Float>,
    val index: Int,
    val startLine: Int = 0,  // Номер начальной строки в исходном документе
    val endLine: Int = 0,    // Номер конечной строки в исходном документе
    val sourceFile: String = "readme.md"  // Имя исходного файла
)

@Serializable
data class EmbeddingsDocument(
    val fileName: String,
    val totalChunks: Int,
    val chunkSize: Int,
    val embeddings: List<EmbeddingRecord>
)

/**
 * Менеджер для сохранения embeddings в JSON
 */
class EmbeddingsManager {
    private val json = Json { prettyPrint = true }
    private val storageDir = File("embeddings_store")

    init {
        if (!storageDir.exists()) {
            storageDir.mkdirs()
        }
    }

    /**
     * Сохраняет embeddings в JSON файл
     * @param fileName Имя исходного файла
     * @param embeddings Список пар (текст, embedding)
     * @return Путь к сохраненному файлу
     */
    fun saveEmbeddings(
        fileName: String,
        embeddings: List<Pair<String, List<Float>>>,
        chunkSize: Int
    ): String {
        val records = embeddings.mapIndexed { index, (text, embedding) ->
            EmbeddingRecord(
                text = text,
                embedding = embedding,
                index = index
            )
        }

        val document = EmbeddingsDocument(
            fileName = fileName,
            totalChunks = embeddings.size,
            chunkSize = chunkSize,
            embeddings = records
        )

        val outputFile = File(storageDir, "${fileName}.embeddings.json")
        val jsonContent = json.encodeToString(document)
        outputFile.writeText(jsonContent)

        return outputFile.absolutePath
    }

    /**
     * Сохраняет embeddings с метаданными источников в JSON файл
     * @param fileName Имя исходного файла
     * @param embeddings Список EmbeddingWithMetadata
     * @param chunkSize Размер чанка
     * @return Путь к сохраненному файлу
     */
    fun saveEmbeddingsWithMetadata(
        fileName: String,
        embeddings: List<EmbeddingWithMetadata>,
        chunkSize: Int
    ): String {
        val records = embeddings.mapIndexed { index, emb ->
            EmbeddingRecord(
                text = emb.text,
                embedding = emb.embedding,
                index = index,
                startLine = emb.startLine,
                endLine = emb.endLine,
                sourceFile = fileName
            )
        }

        val document = EmbeddingsDocument(
            fileName = fileName,
            totalChunks = embeddings.size,
            chunkSize = chunkSize,
            embeddings = records
        )

        val outputFile = File(storageDir, "${fileName}.embeddings.json")
        val jsonContent = json.encodeToString(document)
        outputFile.writeText(jsonContent)

        return outputFile.absolutePath
    }

    /**
     * Возвращает JSON строку с ограничением по размеру
     * @param filePath Путь к JSON файлу
     * @param maxLength Максимальная длина (для Telegram лимита)
     * @return JSON строка
     */
    fun getJsonPreview(filePath: String, maxLength: Int = 3800): String {
        val content = File(filePath).readText()

        return if (content.length <= maxLength) {
            content
        } else {
            content.take(maxLength) + "\n\n... (truncated)"
        }
    }
}
