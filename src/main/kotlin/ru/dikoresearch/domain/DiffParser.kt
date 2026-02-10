package ru.dikoresearch.domain

/**
 * Parser for unified diff format from GitHub Pull Requests
 *
 * Unified diff format:
 * ```
 * diff --git a/file.kt b/file.kt
 * index abc123..def456 100644
 * --- a/file.kt
 * +++ b/file.kt
 * @@ -12,7 +12,10 @@ class Example {
 *  unchanged line
 * -removed line
 * +added line
 * ```
 */
object DiffParser {

    /**
     * Parse unified diff into individual file diffs
     *
     * @param diff Full unified diff string from GitHub
     * @return List of FileDiff objects, one per modified file
     */
    fun parseDiffIntoFiles(diff: String): List<FileDiff> {
        if (diff.isBlank()) {
            return emptyList()
        }

        val files = mutableListOf<FileDiff>()
        val lines = diff.lines()
        var currentFileName = ""
        val currentFileContent = StringBuilder()
        var insideFile = false

        for (line in lines) {
            when {
                // Start of a new file diff
                line.startsWith("diff --git") -> {
                    // Save previous file if exists
                    if (insideFile && currentFileName.isNotEmpty()) {
                        files.add(FileDiff(
                            fileName = currentFileName,
                            content = currentFileContent.toString().trim()
                        ))
                        currentFileContent.clear()
                    }

                    // Extract new file name
                    currentFileName = extractFileName(line)
                    insideFile = true
                    currentFileContent.appendLine(line)
                }

                // Skip binary files
                line.startsWith("Binary files") -> {
                    // Don't include binary files in analysis
                    insideFile = false
                    currentFileName = ""
                    currentFileContent.clear()
                }

                // Inside a file diff
                insideFile -> {
                    currentFileContent.appendLine(line)
                }
            }
        }

        // Add last file
        if (insideFile && currentFileName.isNotEmpty() && currentFileContent.isNotEmpty()) {
            files.add(FileDiff(
                fileName = currentFileName,
                content = currentFileContent.toString().trim()
            ))
        }

        return files
    }

    /**
     * Extract file name from diff header
     *
     * Example: "diff --git a/src/Main.kt b/src/Main.kt" -> "src/Main.kt"
     *
     * @param diffHeader The "diff --git" line
     * @return Extracted file name
     */
    fun extractFileName(diffHeader: String): String {
        // Format: "diff --git a/path/file.kt b/path/file.kt"
        // We want the "b/" path (new file path)
        val parts = diffHeader.split(" ")

        // Find "b/..." part
        val bPath = parts.firstOrNull { it.startsWith("b/") }
        if (bPath != null) {
            return bPath.removePrefix("b/")
        }

        // Fallback: find "a/..." part
        val aPath = parts.firstOrNull { it.startsWith("a/") }
        if (aPath != null) {
            return aPath.removePrefix("a/")
        }

        // Last resort: return the full line
        return diffHeader
    }

    /**
     * Extract hunks (change blocks) from file content
     *
     * Hunks start with "@@ -oldStart,oldCount +newStart,newCount @@"
     *
     * @param fileContent Full file diff content
     * @return List of hunks (each hunk is a string)
     */
    fun extractHunks(fileContent: String): List<String> {
        val hunks = mutableListOf<String>()
        val lines = fileContent.lines()
        val currentHunk = StringBuilder()
        var insideHunk = false

        for (line in lines) {
            when {
                // Start of a hunk
                line.startsWith("@@") -> {
                    // Save previous hunk
                    if (insideHunk && currentHunk.isNotEmpty()) {
                        hunks.add(currentHunk.toString().trim())
                        currentHunk.clear()
                    }

                    insideHunk = true
                    currentHunk.appendLine(line)
                }

                // Inside a hunk (lines starting with +, -, or space)
                insideHunk && (line.startsWith("+") || line.startsWith("-") ||
                               line.startsWith(" ") || line.isEmpty()) -> {
                    currentHunk.appendLine(line)
                }

                // End of hunk (metadata or next file)
                insideHunk && (line.startsWith("diff ") || line.startsWith("index ") ||
                               line.startsWith("---") || line.startsWith("+++")) -> {
                    // Don't include metadata in hunk
                    if (currentHunk.isNotEmpty()) {
                        hunks.add(currentHunk.toString().trim())
                        currentHunk.clear()
                    }
                    insideHunk = false
                }
            }
        }

        // Add last hunk
        if (insideHunk && currentHunk.isNotEmpty()) {
            hunks.add(currentHunk.toString().trim())
        }

        return hunks
    }

    /**
     * Check if file should be analyzed (skip binary files, images, etc.)
     *
     * @param fileName File name to check
     * @return true if file should be analyzed, false otherwise
     */
    fun shouldAnalyzeFile(fileName: String): Boolean {
        val binaryExtensions = setOf(
            ".png", ".jpg", ".jpeg", ".gif", ".ico", ".svg",
            ".pdf", ".zip", ".tar", ".gz", ".jar", ".war",
            ".class", ".o", ".so", ".dylib", ".dll",
            ".woff", ".woff2", ".ttf", ".eot"
        )

        val lowerFileName = fileName.lowercase()
        return !binaryExtensions.any { lowerFileName.endsWith(it) }
    }

    /**
     * Get statistics about the diff
     *
     * @param diff Full unified diff string
     * @return Map with statistics (files, additions, deletions)
     */
    fun getDiffStats(diff: String): Map<String, Int> {
        val files = parseDiffIntoFiles(diff)
        var additions = 0
        var deletions = 0

        files.forEach { file ->
            file.content.lines().forEach { line ->
                when {
                    line.startsWith("+") && !line.startsWith("+++") -> additions++
                    line.startsWith("-") && !line.startsWith("---") -> deletions++
                }
            }
        }

        return mapOf(
            "files" to files.size,
            "additions" to additions,
            "deletions" to deletions
        )
    }
}
