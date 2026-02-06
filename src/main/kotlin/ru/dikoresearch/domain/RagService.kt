package ru.dikoresearch.domain

import kotlinx.serialization.json.Json
import ru.dikoresearch.infrastructure.embeddings.EmbeddingService
import ru.dikoresearch.infrastructure.persistence.EmbeddingsDocument
import java.io.File
import kotlin.math.sqrt

/**
 * Сервис для Retrieval-Augmented Generation (RAG)
 * Выполняет векторный поиск релевантных чанков по запросу пользователя
 */
/**
 * Результат RAG поиска с метаданными
 */
data class RagSearchResult(
    val chunks: List<Triple<String, Float, Int>>,  // (текст, релевантность, индекс)
    val originalCount: Int,                         // Кол-во до фильтрации
    val filteredCount: Int,                         // Кол-во после фильтрации
    val avgRelevance: Float,                        // Средняя релевантность
    val minRelevance: Float,                        // Минимальная релевантность
    val maxRelevance: Float                         // Максимальная релевантность
)

/**
 * Чанк с информацией о файле-источнике
 */
data class ChunkWithSource(
    val text: String,
    val relevance: Float,
    val fileName: String,
    val startLine: Int,
    val endLine: Int
)

/**
 * Результат поиска по всем файлам
 */
data class MultiFileRagResult(
    val chunks: List<ChunkWithSource>,
    val originalCount: Int,
    val filteredCount: Int,
    val avgRelevance: Float,
    val minRelevance: Float,
    val maxRelevance: Float
)

class RagService(
    private val embeddingService: EmbeddingService
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val storageDir = File("embeddings_store")

    /**
     * Находит топ-K наиболее релевантных чанков по запросу пользователя
     *
     * @param question вопрос пользователя
     * @param fileName имя файла (без расширения), например "readme"
     * @param topK количество наиболее релевантных чанков для возврата
     * @return список троек (текст чанка, релевантность, индекс) отсортированных по убыванию релевантности
     */
    suspend fun findRelevantChunks(
        question: String,
        fileName: String = "readme",
        topK: Int = 5
    ): List<Triple<String, Float, Int>> {
        // Загружаем embeddings из файла
        val embeddingsFile = File(storageDir, "$fileName.embeddings.json")

        if (!embeddingsFile.exists()) {
            throw IllegalStateException(
                "Embeddings file not found: ${embeddingsFile.absolutePath}\n" +
                "Create embeddings using /createEmbeddings command"
            )
        }

        val embeddingsDoc = json.decodeFromString<EmbeddingsDocument>(
            embeddingsFile.readText()
        )

        // Генерируем embedding для вопроса
        val questionEmbeddings = embeddingService.generateEmbeddings(question)

        if (questionEmbeddings.isEmpty()) {
            throw IllegalStateException("Failed to generate embedding for question")
        }

        val questionVector = questionEmbeddings.first().second

        // Вычисляем косинусное сходство для каждого чанка
        val similarities = embeddingsDoc.embeddings.map { record ->
            val similarity = cosineSimilarity(questionVector, record.embedding)
            Triple(record.text, similarity, record.index)
        }

        // Сортируем по убыванию релевантности и берем топ-K
        val topResults = similarities
            .sortedByDescending { it.second }
            .take(topK)

        return topResults
    }

    /**
     * Находит релевантные чанки с фильтрацией по порогу
     *
     * @param question вопрос пользователя
     * @param fileName имя файла (без расширения)
     * @param topK максимальное количество кандидатов
     * @param relevanceThreshold порог косинусного сходства (0.0-1.0)
     * @return результат поиска с метаданными
     */
    suspend fun findRelevantChunksWithFilter(
        question: String,
        fileName: String = "readme",
        topK: Int = 5,
        relevanceThreshold: Float = 0.5f
    ): RagSearchResult {
        // 1. Получаем топ-K кандидатов (как раньше)
        val topCandidates = findRelevantChunks(question, fileName, topK)

        // 2. Фильтруем по порогу
        val filteredChunks = topCandidates.filter { (_, relevance, _) ->
            relevance >= relevanceThreshold
        }

        // 3. Собираем метаданные
        val avgRelevance = if (filteredChunks.isNotEmpty()) {
            filteredChunks.map { it.second }.average().toFloat()
        } else 0f

        val minRelevance = filteredChunks.minOfOrNull { it.second } ?: 0f
        val maxRelevance = filteredChunks.maxOfOrNull { it.second } ?: 0f

        return RagSearchResult(
            chunks = filteredChunks,
            originalCount = topCandidates.size,
            filteredCount = filteredChunks.size,
            avgRelevance = avgRelevance,
            minRelevance = minRelevance,
            maxRelevance = maxRelevance
        )
    }

    /**
     * Formats found chunks into context for LLM
     *
     * @param chunks list of triples (text, relevance, index)
     * @return formatted context string
     */
    fun formatContext(chunks: List<Triple<String, Float, Int>>): String {
        return buildString {
            appendLine("=== RELEVANT INFORMATION FROM DOCUMENTATION ===\n")

            chunks.forEachIndexed { i, (text, relevance, index) ->
                appendLine("--- Fragment ${i + 1} (relevance: %.2f%%) ---".format(relevance * 100))
                appendLine(text)
                appendLine()
            }

            appendLine("=== END OF DOCUMENTATION ===")
        }
    }

    /**
     * Formats found chunks into context for LLM with source citations
     *
     * @param chunks list of triples (text, relevance, index)
     * @param fileName file name to load full metadata
     * @return formatted string with context and citations
     */
    fun formatContextWithCitations(
        chunks: List<Triple<String, Float, Int>>,
        fileName: String = "readme"
    ): String {
        // Load embeddings to get metadata
        val embeddingsFile = File(storageDir, "$fileName.embeddings.json")

        if (!embeddingsFile.exists()) {
            // Fallback to regular format without citations
            return formatContext(chunks)
        }

        val embeddingsDoc = json.decodeFromString<EmbeddingsDocument>(
            embeddingsFile.readText()
        )

        return buildString {
            appendLine("=== DOCUMENTATION FOR ANSWER ===")
            appendLine()
            appendLine("IMPORTANT:")
            appendLine("- These fragments are relevant to the question")
            appendLine("- MUST use information from them")
            appendLine("- Each fact MUST have a citation in square brackets with quoted text")
            appendLine()

            chunks.forEachIndexed { i, (_, relevance, index) ->
                // Find corresponding record with metadata
                val record = embeddingsDoc.embeddings.find { it.index == index }

                if (record != null) {
                    appendLine("--- Fragment ${i + 1} ---")
                    appendLine("Source: ${record.sourceFile}, lines ${record.startLine}-${record.endLine}")
                    appendLine("Relevance: %.1f%%".format(relevance * 100))
                    appendLine("Text to quote:")
                    appendLine(record.text)
                    appendLine()
                } else {
                    // Fallback if metadata not found
                    appendLine("--- Fragment ${i + 1} ---")
                    appendLine("Relevance: %.1f%%".format(relevance * 100))
                    appendLine()
                }
            }

            appendLine("=== INSTRUCTION ===")
            appendLine("1. USE information from fragments above")
            appendLine("2. ADD citation in square brackets [quoted text] after each fact")
            appendLine("3. DO NOT write 'information not found' if it exists above")
            appendLine("4. Answer in English language")
        }
    }

    /**
     * Finds relevant chunks across ALL embedding files in embeddings_store
     *
     * @param question user question
     * @param topK maximum number of chunks to return
     * @param relevanceThreshold minimum relevance threshold (0.0-1.0)
     * @return search result with chunks from all files
     */
    suspend fun findRelevantChunksAcrossAllFiles(
        question: String,
        topK: Int = 5,
        relevanceThreshold: Float = 0.5f
    ): MultiFileRagResult {
        // Get all .embeddings.json files
        val embeddingFiles = storageDir.listFiles { file ->
            file.name.endsWith(".embeddings.json")
        } ?: emptyArray()

        if (embeddingFiles.isEmpty()) {
            throw IllegalStateException(
                "No embedding files found in ${storageDir.absolutePath}\n" +
                "Create embeddings using /createEmbeddings command"
            )
        }

        // Generate embedding for question once
        val questionEmbeddings = embeddingService.generateEmbeddings(question)
        if (questionEmbeddings.isEmpty()) {
            throw IllegalStateException("Failed to generate embedding for question")
        }
        val questionVector = questionEmbeddings.first().second

        // Collect all chunks from all files with their sources
        val allChunks = mutableListOf<ChunkWithSource>()

        embeddingFiles.forEach { file ->
            val embeddingsDoc = json.decodeFromString<EmbeddingsDocument>(
                file.readText()
            )

            embeddingsDoc.embeddings.forEach { record ->
                val similarity = cosineSimilarity(questionVector, record.embedding)

                allChunks.add(
                    ChunkWithSource(
                        text = record.text,
                        relevance = similarity,
                        fileName = record.sourceFile,
                        startLine = record.startLine,
                        endLine = record.endLine
                    )
                )
            }
        }

        // Sort by relevance and take top-K
        val topCandidates = allChunks
            .sortedByDescending { it.relevance }
            .take(topK)

        // Filter by threshold
        val filteredChunks = topCandidates.filter { it.relevance >= relevanceThreshold }

        // Calculate stats
        val avgRelevance = if (filteredChunks.isNotEmpty()) {
            filteredChunks.map { it.relevance }.average().toFloat()
        } else 0f

        val minRelevance = filteredChunks.minOfOrNull { it.relevance } ?: 0f
        val maxRelevance = filteredChunks.maxOfOrNull { it.relevance } ?: 0f

        return MultiFileRagResult(
            chunks = filteredChunks,
            originalCount = topCandidates.size,
            filteredCount = filteredChunks.size,
            avgRelevance = avgRelevance,
            minRelevance = minRelevance,
            maxRelevance = maxRelevance
        )
    }

    /**
     * Formats chunks from multiple files into context for LLM with citations
     */
    fun formatContextForMultipleFiles(chunks: List<ChunkWithSource>): String {
        return buildString {
            appendLine("=== DOCUMENTATION FOR ANSWER ===")
            appendLine()
            appendLine("IMPORTANT:")
            appendLine("- These fragments are relevant to the question")
            appendLine("- MUST use information from them")
            appendLine("- Each fact MUST have a citation in square brackets with quoted text")
            appendLine()

            chunks.forEachIndexed { i, chunk ->
                appendLine("--- Fragment ${i + 1} ---")
                appendLine("Source: ${chunk.fileName}, lines ${chunk.startLine}-${chunk.endLine}")
                appendLine("Relevance: %.1f%%".format(chunk.relevance * 100))
                appendLine("Text to quote:")
                appendLine(chunk.text)
                appendLine()
            }

            appendLine("=== INSTRUCTION ===")
            appendLine("1. USE information from fragments above")
            appendLine("2. ADD citation in square brackets [quoted text] after each fact")
            appendLine("3. DO NOT write 'information not found' if it exists above")
            appendLine("4. Answer in English language")
        }
    }

    /**
     * Вычисляет косинусное сходство между двумя векторами
     *
     * Формула: cosine_similarity(A, B) = (A · B) / (||A|| × ||B||)
     *
     * @param vec1 первый вектор
     * @param vec2 второй вектор
     * @return косинусное сходство в диапазоне [0, 1]
     */
    private fun cosineSimilarity(vec1: List<Float>, vec2: List<Float>): Float {
        require(vec1.size == vec2.size) {
            "Векторы должны иметь одинаковую размерность: ${vec1.size} != ${vec2.size}"
        }

        // Скалярное произведение (dot product)
        val dotProduct = vec1.zip(vec2)
            .sumOf { (a, b) -> (a * b).toDouble() }
            .toFloat()

        // Норма первого вектора
        val norm1 = sqrt(vec1.sumOf { (it * it).toDouble() }.toFloat())

        // Норма второго вектора
        val norm2 = sqrt(vec2.sumOf { (it * it).toDouble() }.toFloat())

        // Избегаем деления на ноль
        return if (norm1 > 0 && norm2 > 0) {
            dotProduct / (norm1 * norm2)
        } else {
            0f
        }
    }
}
