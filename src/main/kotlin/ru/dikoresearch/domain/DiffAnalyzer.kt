package ru.dikoresearch.domain

import GigaChatMessage
import kotlinx.serialization.json.Json
import ru.dikoresearch.infrastructure.http.GigaChatClient

/**
 * Analyzes diff chunks using GigaChat for code review
 *
 * Provides structured analysis covering:
 * - Code quality (style, naming, organization)
 * - Security (auth, validation, injection risks)
 * - Best practices (patterns, SOLID, error handling)
 * - Potential bugs (null pointers, race conditions)
 * - Performance (algorithms, resource usage)
 */
class DiffAnalyzer(
    private val gigaChatClient: GigaChatClient,
    private val model: String = "GigaChat"
) {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
    }

    /**
     * System prompt for code review
     */
    private fun buildSystemPrompt(ragContext: String): String = """
You are an expert code reviewer analyzing a Pull Request diff.

REVIEW CRITERIA:
1. Code Quality - style, naming, organization, readability
2. Security - authentication, validation, injection risks, data exposure
3. Best Practices - design patterns, SOLID principles, error handling
4. Potential Bugs - null pointers, race conditions, logic errors, edge cases
5. Performance - algorithms, memory usage, resource leaks

${if (ragContext.isNotBlank()) "PROJECT CONTEXT:\n$ragContext\n" else ""}

OUTPUT FORMAT (valid JSON only):
{
  "files": [
    {
      "fileName": "string",
      "issues": [
        {
          "severity": "high|medium|low",
          "category": "security|bug|quality|performance",
          "line": number or null,
          "description": "string",
          "suggestion": "string"
        }
      ],
      "summary": "string"
    }
  ]
}

RULES:
- Respond ONLY with valid JSON (no markdown, no explanations)
- Focus on critical issues first
- Provide actionable suggestions
- severity "high" = critical security issues or bugs that break functionality
- severity "medium" = potential bugs, bad practices, security concerns
- severity "low" = style issues, minor improvements
- If no issues found in a file, return empty issues array with positive summary
- Consider project context from documentation when available
    """.trimIndent()

    /**
     * Analyze a single chunk of diff
     *
     * @param chunk DiffChunk to analyze
     * @param ragContext Context from RAG (project documentation)
     * @param chunkIndex Index of this chunk (0-based)
     * @param totalChunks Total number of chunks
     * @return ChunkAnalysis with issues found
     */
    suspend fun analyzeChunk(
        chunk: DiffChunk,
        ragContext: String = "",
        chunkIndex: Int = 0,
        totalChunks: Int = 1
    ): ChunkAnalysis {
        val systemPrompt = buildSystemPrompt(ragContext)
        val userPrompt = buildUserPrompt(chunk, chunkIndex, totalChunks)

        return try {
            val response = gigaChatClient.chatCompletion(
                messages = listOf(
                    GigaChatMessage(role = "system", content = systemPrompt),
                    GigaChatMessage(role = "user", content = userPrompt)
                ),
                temperature = 0.3f,  // Low temperature for consistency
                model = model,
                functions = null  // No function calling for analysis
            )

            parseAnalysisResponse(response.choices.first().message.content)
        } catch (e: Exception) {
            // Fallback: create analysis from error
            ChunkAnalysis(
                files = chunk.files.map { file ->
                    FileAnalysis(
                        fileName = file.fileName,
                        issues = listOf(
                            Issue(
                                severity = "low",
                                category = "quality",
                                line = null,
                                description = "Analysis failed: ${e.message}",
                                suggestion = "Manual review recommended"
                            )
                        ),
                        summary = "Automatic analysis failed, manual review needed"
                    )
                }
            )
        }
    }

    /**
     * Build user prompt for a chunk
     */
    private fun buildUserPrompt(
        chunk: DiffChunk,
        chunkIndex: Int,
        totalChunks: Int
    ): String = buildString {
        appendLine("Analyze this Pull Request diff:")
        appendLine()

        if (totalChunks > 1) {
            appendLine("Chunk ${chunkIndex + 1} of $totalChunks")
            appendLine()
        }

        appendLine("Files in this chunk: ${chunk.fileNames.joinToString(", ")}")
        appendLine()
        appendLine("DIFF:")
        appendLine("---")

        chunk.files.forEach { file ->
            appendLine()
            appendLine("FILE: ${file.fileName}")
            appendLine(file.content)
            appendLine()
        }

        appendLine("---")
        appendLine()
        appendLine("Provide structured JSON analysis.")
    }

    /**
     * Parse GigaChat response into ChunkAnalysis
     */
    private fun parseAnalysisResponse(response: String): ChunkAnalysis {
        // Remove markdown code blocks if present
        val cleanedResponse = response
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        return try {
            json.decodeFromString<ChunkAnalysis>(cleanedResponse)
        } catch (e: Exception) {
            // Fallback: return empty analysis
            ChunkAnalysis(
                files = listOf(
                    FileAnalysis(
                        fileName = "unknown",
                        issues = emptyList(),
                        summary = "Could not parse analysis response: ${e.message}"
                    )
                )
            )
        }
    }

    /**
     * Synthesize final report from multiple chunk analyses
     *
     * @param analyses List of ChunkAnalysis from all chunks
     * @return FinalReport with aggregated issues
     */
    fun synthesizeReport(analyses: List<ChunkAnalysis>): FinalReport {
        // Collect all issues with file context
        val allIssues = analyses.flatMap { analysis ->
            analysis.files.flatMap { file ->
                file.issues.map { issue ->
                    IssueWithFile(
                        fileName = file.fileName,
                        issue = issue
                    )
                }
            }
        }

        // Group by severity
        val critical = allIssues.filter { it.issue.severity == "high" }
        val warnings = allIssues.filter { it.issue.severity == "medium" }
        val suggestions = allIssues.filter { it.issue.severity == "low" }

        // Count total files analyzed
        val totalFiles = analyses.flatMap { it.files }.distinctBy { it.fileName }.size

        // Generate summary
        val summary = generateSummary(
            totalFiles = totalFiles,
            critical = critical,
            warnings = warnings,
            suggestions = suggestions
        )

        return FinalReport(
            totalFiles = totalFiles,
            criticalIssues = critical,
            warnings = warnings,
            suggestions = suggestions,
            summary = summary
        )
    }

    /**
     * Generate human-readable summary
     */
    private fun generateSummary(
        totalFiles: Int,
        critical: List<IssueWithFile>,
        warnings: List<IssueWithFile>,
        suggestions: List<IssueWithFile>
    ): String = buildString {
        when {
            critical.isNotEmpty() -> {
                append("The Pull Request contains ${critical.size} critical issue(s) ")
                append("that must be addressed before merging. ")
            }
            warnings.isNotEmpty() -> {
                append("The Pull Request contains ${warnings.size} warning(s) ")
                append("that should be reviewed. ")
            }
            suggestions.isNotEmpty() -> {
                append("The Pull Request looks good overall with ${suggestions.size} minor suggestion(s) ")
                append("for improvement. ")
            }
            else -> {
                append("The Pull Request looks excellent! ")
                append("No issues found during automated review. ")
            }
        }

        // Add category breakdown
        val categories = (critical + warnings + suggestions)
            .groupBy { it.issue.category }
            .mapValues { it.value.size }

        if (categories.isNotEmpty()) {
            append("\n\nIssue breakdown: ")
            append(categories.entries.joinToString(", ") { (category, count) ->
                "$category ($count)"
            })
            append(".")
        }

        // Add recommendation
        append("\n\n")
        when {
            critical.isNotEmpty() -> {
                append("Recommendation: Address critical issues before merging.")
            }
            warnings.isNotEmpty() -> {
                append("Recommendation: Review warnings and address as needed.")
            }
            suggestions.isNotEmpty() -> {
                append("Recommendation: Consider addressing suggestions for code quality improvement.")
            }
            else -> {
                append("Recommendation: Ready for merge after additional manual review.")
            }
        }
    }
}
