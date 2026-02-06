package ru.dikoresearch.domain

/**
 * Информация о чанке текста с метаданными
 */
data class TextChunk(
    val text: String,
    val startLine: Int,  // Номер начальной строки в исходном документе
    val endLine: Int     // Номер конечной строки в исходном документе
)

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

    /**
     * Разбивает текст на чанки с метаданными о позиции в документе
     * @param text Исходный текст
     * @return Список TextChunk с информацией о строках
     */
    fun chunkWithMetadata(text: String): List<TextChunk> {
        val lines = text.lines()

        if (text.length <= chunkSize) {
            return listOf(TextChunk(text, 1, lines.size))
        }

        val chunks = mutableListOf<TextChunk>()
        var currentText = ""
        var currentStartLine = 1
        var currentLine = 1

        for ((index, line) in lines.withIndex()) {
            val lineNumber = index + 1

            // Если добавление этой строки превысит размер чанка
            if (currentText.length + line.length + 1 > chunkSize && currentText.isNotEmpty()) {
                // Сохраняем текущий чанк
                chunks.add(TextChunk(
                    text = currentText.trim(),
                    startLine = currentStartLine,
                    endLine = currentLine
                ))

                // Начинаем новый чанк с учетом overlap
                // Берем последние несколько строк для перекрытия
                val overlapLines = currentText.takeLast(overlap).lines()
                currentText = overlapLines.joinToString("\n") + "\n" + line
                currentStartLine = maxOf(1, currentLine - overlapLines.size + 1)
            } else {
                // Добавляем строку к текущему чанку
                currentText += if (currentText.isEmpty()) line else "\n$line"
            }

            currentLine = lineNumber
        }

        // Добавляем последний чанк, если он не пустой
        if (currentText.isNotEmpty()) {
            chunks.add(TextChunk(
                text = currentText.trim(),
                startLine = currentStartLine,
                endLine = lines.size
            ))
        }

        return chunks
    }
}
