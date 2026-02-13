package ru.dikoresearch.application.commands.handlers

import com.github.kotlintelegrambot.entities.ParseMode
import ru.dikoresearch.application.commands.CommandContext
import ru.dikoresearch.application.commands.CommandHandler
import ru.dikoresearch.application.github.PullRequestAnalysisService

/**
 * Handles the /showPR command - analyzes Pull Requests using GitHub MCP and RAG
 */
class ShowPRCommandHandler(
    private val analysisService: PullRequestAnalysisService?,
    private val githubOwner: String?,
    private val githubRepo: String?
) : CommandHandler {
    override val commandName = "showPR"

    override suspend fun handle(context: CommandContext) {
        // Check if GitHub MCP is available
        if (analysisService == null) {
            context.sendMessage(
                """
                GitHub MCP service is not available.

                Setup:
                1. Add GitHub token to config.properties:
                   github.token=ghp_your_token_here
                   github.owner=your_username
                   github.repo=your_repo

                2. Restart the bot

                Token scopes needed:
                - public_repo (for public repos)
                - repo (for private repos)
                """.trimIndent()
            )
            return
        }

        // Check if owner and repo are configured
        if (githubOwner.isNullOrBlank() || githubRepo.isNullOrBlank()) {
            context.sendMessage(
                """
                GitHub owner and repo not configured.

                Please add to config.properties:
                github.owner=your_username
                github.repo=your_repo

                Then restart the bot.
                """.trimIndent()
            )
            return
        }

        try {
            val prNumberArg = context.arguments.trim().toIntOrNull()

            // Step 1: Fetch PR information
            context.sendMessage(
                if (prNumberArg != null) {
                    "Fetching Pull Request #$prNumberArg..."
                } else {
                    "Fetching latest open Pull Request..."
                }
            )

            val prInfo = if (prNumberArg != null) {
                analysisService.getPullRequestByNumber(prNumberArg)
            } else {
                analysisService.getLatestOpenPullRequest()
            }

            if (prInfo == null) {
                context.sendMessage("No Pull Request found.")
                return
            }

            // Send PR summary
            context.sendMessage(prInfo.formatSummary())

            // Step 2: RAG Analysis
            context.sendMessage("Analyzing Pull Request with RAG and local documentation...")

            val ragContext = analysisService.analyzeWithRAG(prInfo)

            // Step 3: Get diff and analyze
            context.sendMessage("Getting diff from Pull Request and analyzing it...")

            val diff = analysisService.getPullRequestDiff(prInfo.number)

            if (diff.isBlank()) {
                context.sendMessage("No diff available for this Pull Request.")
                return
            }

            // Step 4: Analyze diff with progress updates
            val report = analysisService.analyzeDiff(
                diff = diff,
                ragContext = ragContext,
                prInfo = prInfo,
                onProgress = { message ->
                    context.sendMessage(message)
                }
            )

            val reportText = report.format()

            // Split report if too long (Telegram limit: 4096 chars)
            if (reportText.length <= 4000) {
                // Send as single message with Markdown formatting
                sendMarkdownMessage(context, reportText)
            } else {
                // Send in multiple messages
                val parts = reportText.chunked(4000)
                parts.forEachIndexed { index, part ->
                    sendMarkdownMessage(
                        context,
                        if (index == 0) part else "...$part"
                    )
                }
            }

        } catch (e: Exception) {
            context.sendMessage("Error analyzing PR: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * Send message with Markdown formatting
     * Note: We can't actually set ParseMode through CommandContext,
     * so this is a placeholder for future enhancement when we add ParseMode support to CommandContext
     */
    private suspend fun sendMarkdownMessage(context: CommandContext, text: String) {
        // For now, just send as plain text
        // In a full implementation, CommandContext would support ParseMode
        context.sendMessage(text)
    }
}
