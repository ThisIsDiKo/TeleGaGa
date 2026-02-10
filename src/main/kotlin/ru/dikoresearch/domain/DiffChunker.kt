package ru.dikoresearch.domain

/**
 * Groups file diffs into optimal chunks for analysis
 *
 * Strategy:
 * 1. Group multiple small files into single chunk (up to maxChunkSize)
 * 2. Split large files into multiple chunks if needed
 * 3. Ensure each chunk stays under maxChunkSize for LLM context limits
 */
object DiffChunker {

    /**
     * Group files into chunks suitable for LLM analysis
     *
     * @param files List of file diffs to group
     * @param maxChunkSize Maximum size of a chunk in characters (default 3000)
     * @return List of DiffChunk objects
     */
    fun groupFilesIntoChunks(
        files: List<FileDiff>,
        maxChunkSize: Int = 3000
    ): List<DiffChunk> {
        if (files.isEmpty()) {
            return emptyList()
        }

        val chunks = mutableListOf<DiffChunk>()
        var currentChunk = mutableListOf<FileDiff>()
        var currentSize = 0

        files.forEach { file ->
            val fileSize = file.content.length

            when {
                // Single file exceeds max size - split it separately
                fileSize > maxChunkSize -> {
                    // Save current chunk if not empty
                    if (currentChunk.isNotEmpty()) {
                        chunks.add(DiffChunk(currentChunk.toList()))
                        currentChunk.clear()
                        currentSize = 0
                    }

                    // Split large file into multiple chunks
                    chunks.addAll(splitLargeFile(file, maxChunkSize))
                }

                // Adding this file would exceed limit
                currentSize + fileSize > maxChunkSize -> {
                    // Save current chunk
                    chunks.add(DiffChunk(currentChunk.toList()))

                    // Start new chunk with this file
                    currentChunk = mutableListOf(file)
                    currentSize = fileSize
                }

                // Add file to current chunk
                else -> {
                    currentChunk.add(file)
                    currentSize += fileSize
                }
            }
        }

        // Add final chunk if not empty
        if (currentChunk.isNotEmpty()) {
            chunks.add(DiffChunk(currentChunk.toList()))
        }

        return chunks
    }

    /**
     * Split a large file diff into multiple chunks
     *
     * Strategy: Split by hunks (change blocks marked with @@)
     *
     * @param file Large file diff to split
     * @param maxSize Maximum size per chunk
     * @return List of DiffChunk objects, each containing part of the file
     */
    fun splitLargeFile(file: FileDiff, maxSize: Int): List<DiffChunk> {
        val hunks = DiffParser.extractHunks(file.content)

        if (hunks.isEmpty()) {
            // No hunks found, split by raw lines
            return splitByLines(file, maxSize)
        }

        val chunks = mutableListOf<DiffChunk>()
        val currentHunks = mutableListOf<String>()
        var currentSize = 0

        // Add file header to first chunk
        val header = extractFileHeader(file.content)
        val headerSize = header.length

        hunks.forEach { hunk ->
            val hunkSize = hunk.length

            if (currentSize + hunkSize > maxSize - headerSize) {
                // Save current chunk
                if (currentHunks.isNotEmpty()) {
                    val chunkContent = buildChunkContent(
                        header = header,
                        hunks = currentHunks,
                        partNumber = chunks.size + 1
                    )

                    chunks.add(DiffChunk(listOf(
                        FileDiff("${file.fileName} (part ${chunks.size + 1})", chunkContent)
                    )))

                    currentHunks.clear()
                    currentSize = 0
                }
            }

            currentHunks.add(hunk)
            currentSize += hunkSize
        }

        // Add final chunk
        if (currentHunks.isNotEmpty()) {
            val chunkContent = buildChunkContent(
                header = header,
                hunks = currentHunks,
                partNumber = chunks.size + 1
            )

            chunks.add(DiffChunk(listOf(
                FileDiff("${file.fileName} (part ${chunks.size + 1})", chunkContent)
            )))
        }

        return chunks
    }

    /**
     * Extract file header from diff (metadata before first hunk)
     */
    private fun extractFileHeader(content: String): String {
        val lines = content.lines()
        val headerLines = mutableListOf<String>()

        for (line in lines) {
            if (line.startsWith("@@")) {
                break
            }
            headerLines.add(line)
        }

        return headerLines.joinToString("\n")
    }

    /**
     * Build chunk content with header and hunks
     */
    private fun buildChunkContent(
        header: String,
        hunks: List<String>,
        partNumber: Int
    ): String = buildString {
        appendLine(header)
        appendLine("--- Part $partNumber ---")
        hunks.forEach { hunk ->
            appendLine(hunk)
            appendLine()
        }
    }

    /**
     * Fallback: Split file by lines if hunks can't be extracted
     */
    private fun splitByLines(file: FileDiff, maxSize: Int): List<DiffChunk> {
        val lines = file.content.lines()
        val chunks = mutableListOf<DiffChunk>()
        val currentLines = mutableListOf<String>()
        var currentSize = 0

        lines.forEach { line ->
            val lineSize = line.length + 1 // +1 for newline

            if (currentSize + lineSize > maxSize && currentLines.isNotEmpty()) {
                // Save current chunk
                chunks.add(DiffChunk(listOf(
                    FileDiff(
                        "${file.fileName} (part ${chunks.size + 1})",
                        currentLines.joinToString("\n")
                    )
                )))

                currentLines.clear()
                currentSize = 0
            }

            currentLines.add(line)
            currentSize += lineSize
        }

        // Add final chunk
        if (currentLines.isNotEmpty()) {
            chunks.add(DiffChunk(listOf(
                FileDiff(
                    "${file.fileName} (part ${chunks.size + 1})",
                    currentLines.joinToString("\n")
                )
            )))
        }

        return chunks
    }

    /**
     * Calculate optimal chunk size based on context window
     *
     * @param contextWindow Total context window size in characters
     * @param reserveForPrompt Characters to reserve for system/user prompt
     * @return Recommended max chunk size
     */
    fun calculateOptimalChunkSize(
        contextWindow: Int = 32000,  // GigaChat ~8192 tokens * 4 chars/token
        reserveForPrompt: Int = 2000  // Reserve for system prompt + instructions
    ): Int {
        return (contextWindow - reserveForPrompt) / 2  // Conservative estimate
    }

    /**
     * Get statistics about chunking result
     */
    fun getChunkingStats(chunks: List<DiffChunk>): Map<String, Any> {
        val totalFiles = chunks.sumOf { it.files.size }
        val totalSize = chunks.sumOf { it.totalSize }
        val avgChunkSize = if (chunks.isNotEmpty()) totalSize / chunks.size else 0
        val maxChunkSize = chunks.maxOfOrNull { it.totalSize } ?: 0
        val minChunkSize = chunks.minOfOrNull { it.totalSize } ?: 0

        return mapOf(
            "totalChunks" to chunks.size,
            "totalFiles" to totalFiles,
            "totalSize" to totalSize,
            "avgChunkSize" to avgChunkSize,
            "maxChunkSize" to maxChunkSize,
            "minChunkSize" to minChunkSize
        )
    }
}
