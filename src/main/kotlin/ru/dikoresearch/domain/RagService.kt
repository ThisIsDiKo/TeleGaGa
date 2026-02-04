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
                "Файл embeddings не найден: ${embeddingsFile.absolutePath}\n" +
                "Создайте embeddings командой /createEmbeddings"
            )
        }

        val embeddingsDoc = json.decodeFromString<EmbeddingsDocument>(
            embeddingsFile.readText()
        )

        println("📚 Загружено ${embeddingsDoc.totalChunks} чанков из ${embeddingsDoc.fileName}")

        // Генерируем embedding для вопроса
        println("🔍 Генерация embedding для вопроса...")
        val questionEmbeddings = embeddingService.generateEmbeddings(question)

        if (questionEmbeddings.isEmpty()) {
            throw IllegalStateException("Не удалось сгенерировать embedding для вопроса")
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

        println("✅ Найдено ${topResults.size} релевантных чанков")
        topResults.forEachIndexed { i, (_, relevance, index) ->
            println("   ${i + 1}. Чанк #$index: релевантность = %.4f".format(relevance))
        }

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

        println("🔍 Результаты фильтрации:")
        println("   До фильтрации: ${topCandidates.size} чанков")
        println("   После фильтрации (≥${relevanceThreshold}): ${filteredChunks.size} чанков")
        if (filteredChunks.isNotEmpty()) {
            println("   Средняя релевантность: %.4f".format(avgRelevance))
        }

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
     * Форматирует найденные чанки в контекст для LLM
     *
     * @param chunks список троек (текст, релевантность, индекс)
     * @return отформатированная строка с контекстом
     */
    fun formatContext(chunks: List<Triple<String, Float, Int>>): String {
        return buildString {
            appendLine("=== РЕЛЕВАНТНАЯ ИНФОРМАЦИЯ ИЗ ДОКУМЕНТАЦИИ ===\n")

            chunks.forEachIndexed { i, (text, relevance, index) ->
                appendLine("--- Фрагмент ${i + 1} (релевантность: %.2f%%) ---".format(relevance * 100))
                appendLine(text)
                appendLine()
            }

            appendLine("=== КОНЕЦ ДОКУМЕНТАЦИИ ===")
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
