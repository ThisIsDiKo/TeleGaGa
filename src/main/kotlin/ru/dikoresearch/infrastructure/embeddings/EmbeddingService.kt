package ru.dikoresearch.infrastructure.embeddings

import ru.dikoresearch.domain.MarkdownPreprocessor
import ru.dikoresearch.domain.TextChunk
import ru.dikoresearch.domain.TextChunker
import ru.dikoresearch.infrastructure.http.GigaChatClient
import ru.dikoresearch.infrastructure.http.OllamaClient

/**
 * Результат генерации embeddings с метаданными
 */
data class EmbeddingWithMetadata(
    val text: String,
    val embedding: List<Float>,
    val startLine: Int,
    val endLine: Int
)

/**
 * Сервис для генерации embeddings с батчингом
 * Поддерживает два провайдера: GigaChat (платный) и Ollama (бесплатный локальный)
 */
class EmbeddingService(
    private val gigaChatClient: GigaChatClient? = null,
    private val ollamaClient: OllamaClient? = null,
    private val textChunker: TextChunker,
    private val markdownPreprocessor: MarkdownPreprocessor,
    private val batchSize: Int = 15, // 10-20 чанков за запрос для GigaChat
    private val useOllama: Boolean = true // По умолчанию используем Ollama
) {
    /**
     * Генерирует embeddings для текста с автоматическим чанкингом и батчингом
     * @param text Исходный текст
     * @return Список пар (текст чанка, embedding вектор)
     */
    suspend fun generateEmbeddings(text: String): List<Pair<String, List<Float>>> {
        // Разбиваем текст на чанки
        val chunks = textChunker.chunk(text)

        return if (useOllama) {
            // Используем Ollama (бесплатно, локально)
            if (ollamaClient == null) {
                throw IllegalStateException("Ollama client не инициализирован")
            }
            println("🦙 Используется Ollama для генерации embeddings")
            ollamaClient.embeddings(chunks)
        } else {
            // Используем GigaChat (требует пакеты)
            if (gigaChatClient == null) {
                throw IllegalStateException("GigaChat client не инициализирован")
            }
            println("🤖 Используется GigaChat для генерации embeddings")

            // Батчим чанки для GigaChat
            val results = mutableListOf<Pair<String, List<Float>>>()

            chunks.chunked(batchSize).forEach { batch ->
                val response = gigaChatClient.embeddings(batch)
                response.data.forEach { embedding ->
                    val chunkText = batch[embedding.index]
                    results.add(chunkText to embedding.embedding)
                }
            }

            results
        }
    }

    /**
     * Генерирует embeddings для Markdown документа с предобработкой:
     * - Удаляет код-блоки
     * - Удаляет инлайн код
     * - Разбивает на абзацы
     * - Применяет чанкинг и батчинг
     *
     * @param markdownText исходный Markdown текст
     * @return Список пар (текст чанка, embedding вектор)
     */
    suspend fun generateEmbeddingsForMarkdown(markdownText: String): List<Pair<String, List<Float>>> {
        println("📄 Начинаю предобработку Markdown...")

        // Предобработка: удаление код-блоков и разбиение на абзацы
        val preprocessedText = markdownPreprocessor.preprocess(markdownText)

        println("✂️ Предобработка завершена:")
        println("   Исходный размер: ${markdownText.length} символов")
        println("   После обработки: ${preprocessedText.length} символов")

        // Генерируем embeddings для очищенного текста
        return generateEmbeddings(preprocessedText)
    }

    /**
     * Альтернативный метод: разбивает Markdown на предложения перед генерацией embeddings
     * Полезно для более детального поиска
     *
     * @param markdownText исходный Markdown текст
     * @return Список пар (текст предложения, embedding вектор)
     */
    suspend fun generateEmbeddingsForMarkdownBySentences(markdownText: String): List<Pair<String, List<Float>>> {
        println("📄 Начинаю предобработку Markdown по предложениям...")

        // Получаем список предложений без кода
        val sentences = markdownPreprocessor.splitIntoParagraphsAndSentences(markdownText)

        println("✂️ Предобработка завершена:")
        println("   Найдено предложений: ${sentences.size}")

        return if (useOllama) {
            // Используем Ollama
            if (ollamaClient == null) {
                throw IllegalStateException("Ollama client не инициализирован")
            }
            println("🦙 Используется Ollama для генерации embeddings по предложениям")
            ollamaClient.embeddings(sentences)
        } else {
            // Используем GigaChat
            if (gigaChatClient == null) {
                throw IllegalStateException("GigaChat client не инициализирован")
            }
            println("🤖 Используется GigaChat для генерации embeddings по предложениям")

            val results = mutableListOf<Pair<String, List<Float>>>()

            sentences.chunked(batchSize).forEach { batch ->
                val response = gigaChatClient.embeddings(batch)
                response.data.forEach { embedding ->
                    val sentenceText = batch[embedding.index]
                    results.add(sentenceText to embedding.embedding)
                }
            }

            results
        }
    }

    /**
     * Генерирует embeddings для Markdown документа с метаданными о строках
     * @param markdownText исходный Markdown текст
     * @param sourceFile имя исходного файла
     * @return Список EmbeddingWithMetadata с информацией о строках
     */
    suspend fun generateEmbeddingsWithMetadata(
        markdownText: String,
        sourceFile: String = "readme.md"
    ): List<EmbeddingWithMetadata> {
        println("📄 Начинаю предобработку Markdown с метаданными...")

        // Предобработка: удаление код-блоков и разбиение на абзацы
        val preprocessedText = markdownPreprocessor.preprocess(markdownText)

        println("✂️ Предобработка завершена:")
        println("   Исходный размер: ${markdownText.length} символов")
        println("   После обработки: ${preprocessedText.length} символов")

        // Разбиваем на чанки с метаданными
        val chunks: List<TextChunk> = textChunker.chunkWithMetadata(preprocessedText)

        println("📦 Создано ${chunks.size} чанков с метаданными")

        // Генерируем embeddings
        val texts = chunks.map { it.text }

        val embeddings = if (useOllama) {
            if (ollamaClient == null) {
                throw IllegalStateException("Ollama client не инициализирован")
            }
            println("🦙 Используется Ollama для генерации embeddings")
            ollamaClient.embeddings(texts)
        } else {
            if (gigaChatClient == null) {
                throw IllegalStateException("GigaChat client не инициализирован")
            }
            println("🤖 Используется GigaChat для генерации embeddings")

            val results = mutableListOf<Pair<String, List<Float>>>()
            texts.chunked(batchSize).forEach { batch ->
                val response = gigaChatClient.embeddings(batch)
                response.data.forEach { embedding ->
                    val chunkText = batch[embedding.index]
                    results.add(chunkText to embedding.embedding)
                }
            }
            results
        }

        // Объединяем embeddings с метаданными
        return embeddings.mapIndexed { index, (text, embedding) ->
            val chunk = chunks[index]
            EmbeddingWithMetadata(
                text = text,
                embedding = embedding,
                startLine = chunk.startLine,
                endLine = chunk.endLine
            )
        }
    }
}
