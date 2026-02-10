# Diff Processing Strategy for PR Analysis

## Overview

This document explains how GitHub Pull Request diffs are processed, chunked, and analyzed by GigaChat for code review.

## 1. What is a Unified Diff?

When you call GitHub MCP tool `get_pull_request_diff`, you receive a **unified diff format** - a standard text format showing file changes.

### Example of Unified Diff:

```diff
diff --git a/src/main/kotlin/ConfigService.kt b/src/main/kotlin/ConfigService.kt
index abc123..def456 100644
--- a/src/main/kotlin/ConfigService.kt
+++ b/src/main/kotlin/ConfigService.kt
@@ -12,7 +12,10 @@ class ConfigService private constructor(
     val telegramToken: String,
     val gigaChatAuthKey: String,
     val gigaChatBaseUrl: String,
-    val gigaChatModel: String
+    val gigaChatModel: String,
+    val githubToken: String?,
+    val githubOwner: String?,
+    val githubRepo: String?
 ) {

@@ -80,6 +83,9 @@ class ConfigService private constructor(
                 telegramToken = telegramToken!!,
                 gigaChatAuthKey = gigaChatAuthKey!!,
                 gigaChatBaseUrl = gigaChatBaseUrl!!,
-                gigaChatModel = gigaChatModel!!
+                gigaChatModel = gigaChatModel!!,
+                githubToken = githubToken,
+                githubOwner = githubOwner,
+                githubRepo = githubRepo
             )

diff --git a/src/main/kotlin/Main.kt b/src/main/kotlin/Main.kt
index ghi789..jkl012 100644
--- a/src/main/kotlin/Main.kt
+++ b/src/main/kotlin/Main.kt
@@ -231,6 +231,41 @@ fun main() {
         }
         println()

+        // 7.5. Initialize GitHub MCP Service
+        println("7.5. Initializing GitHub MCP Service...")
+        val githubMcpService = if (!config.githubToken.isNullOrBlank()) {
+            try {
+                val npxPath = System.getenv("PATH")?.split(":")
+                    ?.map { "$it/npx" }
+                    ?.firstOrNull { File(it).exists() }
+                    ?: "/usr/local/bin/npx"
+
+                println("   Using npx at: $npxPath")
+
+                val githubMcpConfig = listOf(
+                    ru.dikoresearch.infrastructure.mcp.StdioMcpService.ServerConfig(
+                        name = "github",
+                        command = npxPath,
+                        args = listOf(
+                            "-y",
+                            "@modelcontextprotocol/server-github"
+                        ),
+                        envVars = mapOf(
+                            "GITHUB_PERSONAL_ACCESS_TOKEN" to config.githubToken
+                        )
+                    )
+                )
+                val service = ru.dikoresearch.infrastructure.mcp.StdioMcpService(githubMcpConfig)
+                runBlocking { service.initialize() }
+                println("   ✅ GitHub MCP Service initialized")
+                service
+            } catch (e: Exception) {
+                println("   ⚠️ GitHub MCP Service unavailable: ${e.message}")
+                e.printStackTrace()
+                null
+            }
+        } else {
+            println("   ⚠️ GitHub token not configured")
+            null
+        }
+        println()
+
         // 8. Create Domain Layer
```

### Key Components of Unified Diff:

1. **File header**: `diff --git a/file.kt b/file.kt`
2. **Metadata**: `index abc123..def456 100644`
3. **File markers**: `--- a/file.kt` (old) and `+++ b/file.kt` (new)
4. **Hunk header**: `@@ -12,7 +12,10 @@` (line numbers)
5. **Changes**:
   - Lines starting with `-` = removed
   - Lines starting with `+` = added
   - Lines with no prefix = unchanged context

## 2. The Problem: Size Limits

### GigaChat Constraints:
- **Context window**: 8,192 tokens (~32,000 characters)
- **Recommended chunk size**: 3,000 characters (safe margin)
- **Token-to-char ratio**: ~4 characters per token

### Real-world PR Sizes:
- **Small PR** (1-5 files): 500-2,000 chars ✅ Fits in one request
- **Medium PR** (5-20 files): 2,000-10,000 chars ⚠️ May need chunking
- **Large PR** (20-50 files): 10,000-50,000 chars ❌ MUST chunk

### Example Problem:

```
PR with 30 files changed = ~25,000 characters
GigaChat safe limit = 3,000 characters
Result: Cannot send entire diff in one request!
```

## 3. Solution: Intelligent Diff Chunking

### Strategy Overview:

```
Full Diff (25,000 chars)
    ↓
Parse into File Chunks
    ↓
┌─────────────┬─────────────┬─────────────┐
│  Chunk 1    │  Chunk 2    │  Chunk 3    │
│  3000 chars │  3000 chars │  3000 chars │
│  Files 1-5  │  Files 6-12 │  Files 13-18│
└─────────────┴─────────────┴─────────────┘
    ↓             ↓             ↓
Analyze with   Analyze with  Analyze with
GigaChat       GigaChat      GigaChat
    ↓             ↓             ↓
┌─────────────┬─────────────┬─────────────┐
│  Result 1   │  Result 2   │  Result 3   │
└─────────────┴─────────────┴─────────────┘
    ↓
Synthesize Final Report
    ↓
Send to User
```

### Step-by-Step Process:

#### Step 1: Parse Diff into File Blocks

**Input**: Raw unified diff string

**Process**:
```kotlin
fun parseDiffIntoFiles(diff: String): List<FileDiff> {
    val files = mutableListOf<FileDiff>()
    val currentFile = StringBuilder()
    var fileName = ""

    diff.lines().forEach { line ->
        when {
            line.startsWith("diff --git") -> {
                // Save previous file
                if (fileName.isNotEmpty()) {
                    files.add(FileDiff(fileName, currentFile.toString()))
                    currentFile.clear()
                }
                // Extract new file name
                fileName = extractFileName(line)
            }
            else -> {
                currentFile.appendLine(line)
            }
        }
    }

    // Add last file
    if (fileName.isNotEmpty()) {
        files.add(FileDiff(fileName, currentFile.toString()))
    }

    return files
}

data class FileDiff(
    val fileName: String,
    val content: String
)
```

**Output**: List of file-level diffs

```kotlin
[
    FileDiff("ConfigService.kt", "diff --git...\n@@ -12,7..."),
    FileDiff("Main.kt", "diff --git...\n@@ -231,6..."),
    FileDiff("StdioMcpService.kt", "diff --git...\n@@ -30,3...")
]
```

#### Step 2: Group Files into Chunks

**Goal**: Create chunks ≤3,000 characters

**Algorithm**:
```kotlin
fun groupFilesIntoChunks(
    files: List<FileDiff>,
    maxChunkSize: Int = 3000
): List<DiffChunk> {
    val chunks = mutableListOf<DiffChunk>()
    var currentChunk = mutableListOf<FileDiff>()
    var currentSize = 0

    files.forEach { file ->
        val fileSize = file.content.length

        // If single file exceeds limit, split it separately
        if (fileSize > maxChunkSize) {
            // Save current chunk if not empty
            if (currentChunk.isNotEmpty()) {
                chunks.add(DiffChunk(currentChunk.toList()))
                currentChunk.clear()
                currentSize = 0
            }

            // Split large file into multiple chunks
            chunks.addAll(splitLargeFile(file, maxChunkSize))
        }
        // If adding this file would exceed limit
        else if (currentSize + fileSize > maxChunkSize) {
            // Save current chunk
            chunks.add(DiffChunk(currentChunk.toList()))

            // Start new chunk with this file
            currentChunk = mutableListOf(file)
            currentSize = fileSize
        }
        // Add file to current chunk
        else {
            currentChunk.add(file)
            currentSize += fileSize
        }
    }

    // Add final chunk
    if (currentChunk.isNotEmpty()) {
        chunks.add(DiffChunk(currentChunk.toList()))
    }

    return chunks
}

data class DiffChunk(
    val files: List<FileDiff>
) {
    val totalSize: Int = files.sumOf { it.content.length }
    val fileNames: List<String> = files.map { it.fileName }
}
```

**Output**: Grouped chunks

```kotlin
[
    DiffChunk(
        files = [ConfigService.kt, Main.kt],
        totalSize = 2800,
        fileNames = ["ConfigService.kt", "Main.kt"]
    ),
    DiffChunk(
        files = [StdioMcpService.kt, TelegramBotService.kt],
        totalSize = 2900,
        fileNames = ["StdioMcpService.kt", "TelegramBotService.kt"]
    )
]
```

#### Step 3: Analyze Each Chunk with GigaChat

**For each chunk**:

1. **Build system prompt**:
```kotlin
val systemPrompt = """
You are an expert code reviewer analyzing a Pull Request.

REVIEW CRITERIA:
1. Code Quality - style, naming, organization
2. Security - authentication, validation, injection risks
3. Best Practices - design patterns, SOLID principles
4. Potential Bugs - null pointers, race conditions, logic errors
5. Performance - algorithms, resource usage

OUTPUT FORMAT (JSON):
{
  "files": [
    {
      "fileName": "ConfigService.kt",
      "issues": [
        {
          "severity": "high|medium|low",
          "category": "security|bug|quality|performance",
          "line": 15,
          "description": "Brief issue description",
          "suggestion": "How to fix it"
        }
      ],
      "summary": "Overall file assessment"
    }
  ]
}

Respond ONLY with valid JSON.
""".trimIndent()
```

2. **Build user prompt**:
```kotlin
val userPrompt = """
Analyze this Pull Request diff (chunk ${chunkIndex + 1} of ${totalChunks}):

Files in this chunk: ${chunk.fileNames.joinToString(", ")}

${ragContext}  // Context from RAG if available

DIFF TO ANALYZE:
${chunk.files.joinToString("\n\n") { it.content }}

Provide structured JSON analysis focusing on critical issues.
""".trimIndent()
```

3. **Call GigaChat**:
```kotlin
val response = gigaChatClient.chatCompletion(
    messages = listOf(
        GigaChatMessage(role = "system", content = systemPrompt),
        GigaChatMessage(role = "user", content = userPrompt)
    ),
    temperature = 0.3f,  // Low temperature for consistency
    model = "GigaChat"
)
```

4. **Parse JSON response**:
```kotlin
val analysis = json.decodeFromString<ChunkAnalysis>(response.content)
```

**Output per chunk**:
```json
{
  "files": [
    {
      "fileName": "ConfigService.kt",
      "issues": [
        {
          "severity": "low",
          "category": "quality",
          "line": 14,
          "description": "New nullable fields added without validation",
          "suggestion": "Consider adding validation for githubToken format"
        }
      ],
      "summary": "Added GitHub configuration fields. Implementation looks correct."
    }
  ]
}
```

#### Step 4: Synthesize Final Report

If multiple chunks were analyzed:

1. **Collect all chunk analyses**
2. **Aggregate issues by severity**
3. **Remove duplicates**
4. **Sort by priority**

```kotlin
fun synthesizeReport(chunkAnalyses: List<ChunkAnalysis>): FinalReport {
    val allIssues = chunkAnalyses.flatMap { chunk ->
        chunk.files.flatMap { file ->
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

    return FinalReport(
        totalFiles = chunkAnalyses.sumOf { it.files.size },
        criticalIssues = critical,
        warnings = warnings,
        suggestions = suggestions,
        summary = generateSummary(critical, warnings, suggestions)
    )
}
```

**Final Output to User**:
```
Pull Request Analysis Complete

Files analyzed: 4
Total issues found: 3

CRITICAL ISSUES (0):
None

WARNINGS (1):
- ConfigService.kt:14 [security]
  New nullable fields without validation
  Suggestion: Add validation for githubToken format

SUGGESTIONS (2):
- Main.kt:245 [quality]
  Consider extracting GitHub MCP initialization to separate function

- StdioMcpService.kt:35 [quality]
  Add documentation for envVars parameter

SUMMARY:
The Pull Request adds GitHub MCP integration with proper configuration management.
Code quality is good, with minor suggestions for improvement. No critical security
issues detected. Recommended to add token format validation before merging.
```

## 4. Special Cases

### Case 1: Single Large File (>3,000 chars)

If a single file diff exceeds 3,000 characters:

```kotlin
fun splitLargeFile(file: FileDiff, maxSize: Int): List<DiffChunk> {
    val hunks = extractHunks(file.content)  // Split by @@ markers
    val chunks = mutableListOf<DiffChunk>()

    var currentHunks = mutableListOf<String>()
    var currentSize = 0

    hunks.forEach { hunk ->
        if (currentSize + hunk.length > maxSize) {
            // Save current group
            chunks.add(DiffChunk(listOf(
                FileDiff("${file.fileName} (part ${chunks.size + 1})",
                        currentHunks.joinToString("\n"))
            )))

            // Start new group
            currentHunks = mutableListOf(hunk)
            currentSize = hunk.length
        } else {
            currentHunks.add(hunk)
            currentSize += hunk.length
        }
    }

    if (currentHunks.isNotEmpty()) {
        chunks.add(DiffChunk(listOf(
            FileDiff("${file.fileName} (part ${chunks.size + 1})",
                    currentHunks.joinToString("\n"))
        )))
    }

    return chunks
}
```

### Case 2: Binary Files

Skip binary files (images, PDFs, etc.):

```kotlin
fun shouldAnalyzeFile(fileName: String): Boolean {
    val binaryExtensions = setOf(".png", ".jpg", ".pdf", ".zip", ".jar")
    return !binaryExtensions.any { fileName.endsWith(it) }
}
```

### Case 3: No Diff Available

Fallback to file list only:

```kotlin
if (diff.isBlank()) {
    return "No diff available. PR modifies: ${fileList.joinToString(", ")}"
}
```

## 5. Performance Metrics

**Expected Analysis Times**:
- Small PR (1-5 files, <3,000 chars): 10-15 seconds
- Medium PR (5-20 files, 3,000-10,000 chars): 20-40 seconds
- Large PR (20-50 files, 10,000-50,000 chars): 60-120 seconds

**Cost per Analysis**:
- Small: ~2,000 tokens = minimal cost
- Medium: ~6,000 tokens = low cost
- Large: ~15,000 tokens = moderate cost

## 6. Error Handling

1. **GigaChat timeout**: Retry with exponential backoff
2. **JSON parse error**: Return raw text analysis
3. **Rate limit**: Wait and retry with user notification
4. **Empty analysis**: Provide generic summary

## 7. Implementation Checklist

- [ ] Create `DiffParser.kt` for parsing unified diff
- [ ] Create `DiffChunker.kt` for intelligent chunking
- [ ] Create `DiffAnalyzer.kt` for GigaChat integration
- [ ] Create data classes: `FileDiff`, `DiffChunk`, `ChunkAnalysis`, `FinalReport`
- [ ] Add unit tests for diff parsing
- [ ] Add integration tests with sample PRs
- [ ] Document error handling scenarios

## 8. Example Usage in /showPR Command

```kotlin
// In TelegramBotService.kt
command("showPR") {
    applicationScope.launch {
        try {
            // 1. Get PR diff from GitHub MCP
            val diff = githubMcpService.callTool("get_pull_request_diff", ...)

            // 2. Parse into files
            val files = DiffParser.parseDiffIntoFiles(diff)
            bot.sendMessage(chatId, "Getting diff (${files.size} files)...")

            // 3. Chunk for analysis
            val chunks = DiffChunker.groupFilesIntoChunks(files, maxSize = 3000)
            bot.sendMessage(chatId, "Analyzing ${chunks.size} chunks...")

            // 4. Analyze each chunk
            val analyses = chunks.mapIndexed { i, chunk ->
                bot.sendMessage(chatId, "Analyzing chunk ${i+1}/${chunks.size}...")
                DiffAnalyzer.analyzeChunk(chunk, ragContext, i, chunks.size)
            }

            // 5. Synthesize report
            val report = DiffAnalyzer.synthesizeReport(analyses)

            // 6. Send final report
            bot.sendMessage(chatId, report.format(), parseMode = ParseMode.MARKDOWN)
        } catch (e: Exception) {
            bot.sendMessage(chatId, "Analysis failed: ${e.message}")
        }
    }
}
```

---

**Next Steps**: Implement these components in Phase 2 and Phase 3 of the main plan.
