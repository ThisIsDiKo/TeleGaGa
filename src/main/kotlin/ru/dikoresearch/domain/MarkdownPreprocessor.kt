package ru.dikoresearch.domain

/**
 * Препроцессор для очистки Markdown документов перед генерацией embeddings
 * Удаляет код-блоки и разбивает текст на семантические единицы
 */
class MarkdownPreprocessor {

    /**
     * Обрабатывает Markdown документ: удаляет код-блоки и разбивает на абзацы
     * @param markdownText исходный Markdown текст
     * @return очищенный и разбитый на абзацы текст
     */
    fun preprocess(markdownText: String): String {
        // 1. Удаляем код-блоки (тройные обратные кавычки)
        val withoutCodeBlocks = removeCodeBlocks(markdownText)

        // 2. Удаляем инлайн код (одинарные обратные кавычки)
        val withoutInlineCode = removeInlineCode(withoutCodeBlocks)

        // 3. Разбиваем на абзацы и очищаем
        val paragraphs = splitIntoParagraphs(withoutInlineCode)

        // 4. Объединяем обратно в текст с двойными переносами
        return paragraphs.joinToString("\n\n")
    }

    /**
     * Удаляет блоки кода вида ```...```
     */
    private fun removeCodeBlocks(text: String): String {
        // Регулярное выражение для поиска код-блоков
        // Ищем ```язык\nкод\n``` или просто ```\nкод\n```
        val codeBlockPattern = Regex("""```[\w]*\n[\s\S]*?\n```""", RegexOption.MULTILINE)
        return codeBlockPattern.replace(text, "")
    }

    /**
     * Удаляет инлайн код вида `...`
     */
    private fun removeInlineCode(text: String): String {
        val inlineCodePattern = Regex("""`[^`]+`""")
        return inlineCodePattern.replace(text, "")
    }

    /**
     * Разбивает текст на абзацы и очищает их
     * @return список непустых абзацев
     */
    private fun splitIntoParagraphs(text: String): List<String> {
        return text
            .split(Regex("\n\n+")) // Разбиваем по двойным и более переносам
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { normalizeWhitespace(it) }
    }

    /**
     * Нормализует пробелы: убирает множественные пробелы и переносы внутри абзаца
     */
    private fun normalizeWhitespace(text: String): String {
        return text
            .replace(Regex("\\s+"), " ") // Заменяем множественные пробелы на один
            .trim()
    }

    /**
     * Разбивает текст на предложения (альтернативный метод)
     * Можно использовать вместо splitIntoParagraphs для более мелкого разбиения
     */
    fun splitIntoSentences(text: String): List<String> {
        // Разбиваем по точке, восклицательному и вопросительному знаку с пробелом/переносом
        return text
            .split(Regex("[.!?]\\s+"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { "$it." } // Добавляем точку обратно
    }

    /**
     * Альтернативный метод: разбивает на абзацы И внутри каждого на предложения
     * Возвращает плоский список всех предложений
     */
    fun splitIntoParagraphsAndSentences(markdownText: String): List<String> {
        val withoutCodeBlocks = removeCodeBlocks(markdownText)
        val withoutInlineCode = removeInlineCode(withoutCodeBlocks)
        val paragraphs = splitIntoParagraphs(withoutInlineCode)

        return paragraphs.flatMap { paragraph ->
            splitIntoSentences(paragraph)
        }
    }
}
