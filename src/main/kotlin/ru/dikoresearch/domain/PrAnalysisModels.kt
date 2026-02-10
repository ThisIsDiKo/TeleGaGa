package ru.dikoresearch.domain

import kotlinx.serialization.Serializable

/**
 * Represents a single file's diff from a Pull Request
 */
data class FileDiff(
    val fileName: String,
    val content: String  // Unified diff content for this file
)

/**
 * Represents a chunk of files grouped together for analysis
 * Total size of all files should not exceed maxChunkSize
 */
data class DiffChunk(
    val files: List<FileDiff>
) {
    val totalSize: Int = files.sumOf { it.content.length }
    val fileNames: List<String> = files.map { it.fileName }

    override fun toString(): String {
        return "DiffChunk(files=${fileNames.size}, totalSize=$totalSize, fileNames=$fileNames)"
    }
}

/**
 * Represents a code issue found during analysis
 */
@Serializable
data class Issue(
    val severity: String,      // "high" | "medium" | "low"
    val category: String,      // "security" | "bug" | "quality" | "performance"
    val line: Int? = null,     // Line number in the file (if applicable)
    val description: String,   // What is the issue
    val suggestion: String     // How to fix it
)

/**
 * Analysis result for a single file
 */
@Serializable
data class FileAnalysis(
    val fileName: String,
    val issues: List<Issue>,
    val summary: String
)

/**
 * Analysis result for a chunk (may contain multiple files)
 */
@Serializable
data class ChunkAnalysis(
    val files: List<FileAnalysis>
)

/**
 * Issue with file context for final report
 */
data class IssueWithFile(
    val fileName: String,
    val issue: Issue
) {
    fun format(): String {
        val lineInfo = if (issue.line != null) ":${issue.line}" else ""
        return """
            - ${fileName}${lineInfo} [${issue.category}]
              ${issue.description}
              Suggestion: ${issue.suggestion}
        """.trimIndent()
    }
}

/**
 * Final PR analysis report
 */
data class FinalReport(
    val totalFiles: Int,
    val criticalIssues: List<IssueWithFile>,
    val warnings: List<IssueWithFile>,
    val suggestions: List<IssueWithFile>,
    val summary: String
) {
    val totalIssues: Int = criticalIssues.size + warnings.size + suggestions.size

    /**
     * Format report as Markdown for Telegram
     */
    fun format(): String = buildString {
        appendLine("*Pull Request Analysis Complete*")
        appendLine()
        appendLine("Files analyzed: $totalFiles")
        appendLine("Total issues: $totalIssues")
        appendLine()

        // Critical issues
        if (criticalIssues.isNotEmpty()) {
            appendLine("*CRITICAL ISSUES (${criticalIssues.size}):*")
            criticalIssues.forEach { issue ->
                appendLine(issue.format())
                appendLine()
            }
        } else {
            appendLine("*CRITICAL ISSUES (0):*")
            appendLine("None")
            appendLine()
        }

        // Warnings
        if (warnings.isNotEmpty()) {
            appendLine("*WARNINGS (${warnings.size}):*")
            warnings.forEach { issue ->
                appendLine(issue.format())
                appendLine()
            }
        } else {
            appendLine("*WARNINGS (0):*")
            appendLine("None")
            appendLine()
        }

        // Suggestions
        if (suggestions.isNotEmpty()) {
            appendLine("*SUGGESTIONS (${suggestions.size}):*")
            suggestions.forEach { issue ->
                appendLine(issue.format())
                appendLine()
            }
        }

        // Summary
        appendLine("*SUMMARY:*")
        appendLine(summary)
    }
}

/**
 * Pull Request metadata
 */
data class PullRequestInfo(
    val number: Int,
    val title: String,
    val description: String,
    val state: String,
    val author: String?,
    val modifiedFiles: List<String>
) {
    fun formatSummary(): String = buildString {
        appendLine("Found Pull Request #$number: $title")
        if (author != null) {
            appendLine("Author: $author")
        }
        appendLine("State: $state")
        appendLine("Modified files: ${modifiedFiles.size}")
    }
}
