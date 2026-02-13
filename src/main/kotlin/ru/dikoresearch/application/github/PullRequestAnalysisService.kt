package ru.dikoresearch.application.github

import kotlinx.serialization.json.*
import ru.dikoresearch.domain.*
import ru.dikoresearch.domain.mcp.McpService
import ru.dikoresearch.infrastructure.embeddings.EmbeddingService
import ru.dikoresearch.infrastructure.http.GigaChatClient

/**
 * Service for analyzing GitHub Pull Requests using RAG and LLM
 * Extracts business logic from /showPR command handler
 */
class PullRequestAnalysisService(
    private val githubMcpService: McpService,
    private val ragService: RagService,
    private val gigaChatClient: GigaChatClient,
    private val gigaChatModel: String,
    private val githubOwner: String,
    private val githubRepo: String
) {
    /**
     * Fetch Pull Request by number
     */
    suspend fun getPullRequestByNumber(number: Int): PullRequestInfo? {
        return try {
            val result = githubMcpService.callTool(
                name = "get_pull_request",
                args = mapOf(
                    "owner" to githubOwner,
                    "repo" to githubRepo,
                    "pull_number" to number
                )
            )

            parsePullRequest(result.content.firstOrNull()?.text ?: return null)
        } catch (e: Exception) {
            println("Error fetching PR #$number: ${e.message}")
            e.printStackTrace()
            null
        }
    }

    /**
     * Fetch latest open Pull Request
     */
    suspend fun getLatestOpenPullRequest(): PullRequestInfo? {
        return try {
            // List open PRs
            val result = githubMcpService.callTool(
                name = "list_pull_requests",
                args = mapOf(
                    "owner" to githubOwner,
                    "repo" to githubRepo,
                    "state" to "open"
                )
            )

            // Parse first PR from result
            parsePullRequestFromList(result.content.firstOrNull()?.text ?: return null)
        } catch (e: Exception) {
            println("Error fetching PRs: ${e.message}")
            e.printStackTrace()
            null
        }
    }

    /**
     * Get diff for a Pull Request
     */
    suspend fun getPullRequestDiff(number: Int): String {
        return try {
            // Use GitHub MCP tool to get PR files
            val result = githubMcpService.callTool(
                name = "get_pull_request_files",
                args = mapOf(
                    "owner" to githubOwner,
                    "repo" to githubRepo,
                    "pull_number" to number
                )
            )

            // Parse result and extract patches
            val text = result.content.firstOrNull()?.text ?: return ""
            val json = Json { ignoreUnknownKeys = true }
            val filesArray = json.parseToJsonElement(text).jsonArray

            // Combine all patches into unified diff
            filesArray.joinToString("\n\n") { fileElement ->
                val fileObj = fileElement.jsonObject
                val fileName = fileObj["filename"]?.jsonPrimitive?.content ?: "unknown"
                val patch = fileObj["patch"]?.jsonPrimitive?.content ?: ""

                if (patch.isNotBlank()) {
                    "diff --git a/$fileName b/$fileName\n$patch"
                } else {
                    ""
                }
            }.trim()
        } catch (e: Exception) {
            println("Error getting diff: ${e.message}")
            e.printStackTrace()
            ""
        }
    }

    /**
     * Perform RAG analysis on Pull Request
     */
    suspend fun analyzeWithRAG(prInfo: PullRequestInfo): String {
        return try {
            val query = buildString {
                appendLine("Context: Analyzing pull request for code review")
                appendLine()
                appendLine("PR Title: ${prInfo.title}")
                appendLine("PR Description: ${prInfo.description.take(500)}")
                appendLine("Modified files: ${prInfo.modifiedFiles.joinToString(", ")}")
                appendLine()
                appendLine("Question: What project components, patterns, and best practices are relevant to this PR?")
            }

            val searchResult = ragService.findRelevantChunksAcrossAllFiles(
                question = query,
                topK = 5,
                relevanceThreshold = 0.5f
            )

            ragService.formatContextForMultipleFiles(searchResult.chunks)
        } catch (e: Exception) {
            // RAG is optional, continue without it
            ""
        }
    }

    /**
     * Analyze Pull Request diff using LLM
     */
    suspend fun analyzeDiff(
        diff: String,
        ragContext: String,
        prInfo: PullRequestInfo,
        onProgress: suspend (String) -> Unit
    ): FinalReport {
        // Parse diff
        val files = DiffParser.parseDiffIntoFiles(diff)
        val analyzableFiles = files.filter { DiffParser.shouldAnalyzeFile(it.fileName) }

        if (analyzableFiles.isEmpty()) {
            return FinalReport(
                totalFiles = 0,
                criticalIssues = emptyList(),
                warnings = emptyList(),
                suggestions = emptyList(),
                summary = "No code files to analyze (only binary files or no changes)."
            )
        }

        // Chunk files
        val chunks = DiffChunker.groupFilesIntoChunks(analyzableFiles, maxChunkSize = 3000)

        // Analyze each chunk
        val analyzer = DiffAnalyzer(
            gigaChatClient = gigaChatClient,
            model = gigaChatModel
        )

        val analyses = chunks.mapIndexed { index, chunk ->
            onProgress("Analyzing chunk ${index + 1} of ${chunks.size}...")

            analyzer.analyzeChunk(
                chunk = chunk,
                ragContext = ragContext,
                chunkIndex = index,
                totalChunks = chunks.size
            )
        }

        // Synthesize and return report
        return analyzer.synthesizeReport(analyses)
    }

    /**
     * Parse Pull Request from API response
     */
    private fun parsePullRequest(text: String): PullRequestInfo? {
        return try {
            val json = Json { ignoreUnknownKeys = true }
            val prJson = json.parseToJsonElement(text).jsonObject

            PullRequestInfo(
                number = prJson["number"]?.jsonPrimitive?.int ?: return null,
                title = prJson["title"]?.jsonPrimitive?.content ?: "",
                description = prJson["body"]?.jsonPrimitive?.contentOrNull ?: "",
                author = prJson["user"]?.jsonObject?.get("login")?.jsonPrimitive?.content ?: "unknown",
                state = prJson["state"]?.jsonPrimitive?.content ?: "unknown",
                modifiedFiles = emptyList() // Not included in basic PR info
            )
        } catch (e: Exception) {
            println("Error parsing PR: ${e.message}")
            null
        }
    }

    /**
     * Parse first Pull Request from list response
     */
    private fun parsePullRequestFromList(text: String): PullRequestInfo? {
        return try {
            val json = Json { ignoreUnknownKeys = true }
            val prsArray = json.parseToJsonElement(text).jsonArray

            if (prsArray.isEmpty()) return null

            val prJson = prsArray.first().jsonObject

            PullRequestInfo(
                number = prJson["number"]?.jsonPrimitive?.int ?: return null,
                title = prJson["title"]?.jsonPrimitive?.content ?: "",
                description = prJson["body"]?.jsonPrimitive?.contentOrNull ?: "",
                author = prJson["user"]?.jsonObject?.get("login")?.jsonPrimitive?.content ?: "unknown",
                state = prJson["state"]?.jsonPrimitive?.content ?: "unknown",
                modifiedFiles = emptyList()
            )
        } catch (e: Exception) {
            println("Error parsing PR list: ${e.message}")
            null
        }
    }
}
