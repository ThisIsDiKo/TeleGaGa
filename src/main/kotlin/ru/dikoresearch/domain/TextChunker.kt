package ru.dikoresearch.domain

/**
 * Разбивает текст на чанки для генерации embeddings
 */
class TextChunker(
    val chunkSize: Int = 500, // символов на чанк
    val overlap: Int = 50 // перекрытие между чанками
) {
    /**
     * Разбивает текст на чанки с перекрытием
     * @param text Исходный текст
     * @return Список чанков
     */
    fun chunk(text: String): List<String> {
        if (text.length <= chunkSize) {
            return listOf(text)
        }

        val chunks = mutableListOf<String>()
        var startIndex = 0

        while (startIndex < text.length) {
            val endIndex = minOf(startIndex + chunkSize, text.length)
            val chunk = text.substring(startIndex, endIndex)
            chunks.add(chunk.trim())

            // Следующий чанк начинается с overlap символов назад
            startIndex += chunkSize - overlap
        }

        return chunks
    }
}
