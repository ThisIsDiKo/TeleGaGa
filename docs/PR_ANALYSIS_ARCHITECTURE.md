# PR Analysis Architecture

## Visual Overview

```
┌──────────────────────────────────────────────────────────────────────────┐
│                         /showPR Command Flow                              │
└──────────────────────────────────────────────────────────────────────────┘

User sends: /showPR 123
         │
         ▼
┌─────────────────────────┐
│ 1. Fetch PR Data        │  GitHub MCP: list_pull_requests
│    GitHub MCP Service   │             get_pull_request
└───────────┬─────────────┘
            │
            ├─► PR Title: "Add GitHub MCP integration"
            ├─► PR Description: "Integrates official GitHub MCP server..."
            ├─► Modified Files: ["ConfigService.kt", "Main.kt", ...]
            │
            ▼
   Send: "Found Pull Request #123: Add GitHub MCP integration"
            │
            ▼
┌─────────────────────────┐
│ 2. RAG Analysis         │  RagService.findRelevantChunksAcrossAllFiles()
│    Local Ollama         │
└───────────┬─────────────┘
            │
            ├─► Query: "Analyzing PR: Add GitHub MCP integration
            │           Modified: ConfigService.kt, Main.kt
            │           Question: What components are affected?"
            │
            ├─► Search embeddings_store/* with threshold=0.5
            │
            ├─► Results: Top 5 relevant chunks
            │   • CLAUDE.md lines 23-45: MCP Integration section
            │   • ConfigService.kt lines 1-30: Configuration loading
            │   • Main.kt lines 198-230: MCP initialization patterns
            │
            ▼
   Send: "Analyzing Pull Request with RAG and local documentation"
            │
   Ollama analyzes description + RAG context
            │
            ├─► Context summary: "This PR affects MCP initialization,
            │                     configuration management, and service
            │                     dependency injection patterns."
            │
            ▼
┌─────────────────────────┐
│ 3. Fetch Diff           │  GitHub MCP: get_pull_request_diff
│    GitHub MCP Service   │
└───────────┬─────────────┘
            │
            ├─► Full unified diff (25,000 chars)
            │
            ▼
   Send: "Getting diff from Pull Request and analyzing it"
            │
            ▼
┌─────────────────────────┐
│ 4. Parse & Chunk Diff   │  DiffParser + DiffChunker
│    (see strategy doc)   │
└───────────┬─────────────┘
            │
            ├─► Parse into 4 files
            │   • ConfigService.kt (800 chars)
            │   • Main.kt (1500 chars)
            │   • StdioMcpService.kt (600 chars)
            │   • TelegramBotService.kt (400 chars)
            │
            ├─► Group into 2 chunks
            │   • Chunk 1: ConfigService.kt + Main.kt (2300 chars)
            │   • Chunk 2: StdioMcpService.kt + TelegramBotService.kt (1000 chars)
            │
            ▼
┌─────────────────────────┐
│ 5. Analyze Chunk 1      │  GigaChat with RAG context
│    GigaChat (temp=0.3)  │
└───────────┬─────────────┘
            │
   Send: "Analyzing chunk 1/2..."
            │
            ├─► System Prompt: Code review expert criteria
            ├─► User Prompt: Diff + RAG context
            ├─► Response: JSON with issues
            │   {
            │     "files": [
            │       {
            │         "fileName": "ConfigService.kt",
            │         "issues": [
            │           {
            │             "severity": "low",
            │             "category": "quality",
            │             "line": 14,
            │             "description": "New nullable fields without validation",
            │             "suggestion": "Add token format validation"
            │           }
            │         ]
            │       }
            │     ]
            │   }
            │
            ▼
┌─────────────────────────┐
│ 6. Analyze Chunk 2      │  GigaChat with RAG context
│    GigaChat (temp=0.3)  │
└───────────┬─────────────┘
            │
   Send: "Analyzing chunk 2/2..."
            │
            ├─► Same process as Chunk 1
            ├─► Response: JSON with issues
            │
            ▼
┌─────────────────────────┐
│ 7. Synthesize Report    │  Aggregate all analyses
│    Combine Results      │
└───────────┬─────────────┘
            │
            ├─► Merge issues from all chunks
            ├─► Group by severity (high/medium/low)
            ├─► Remove duplicates
            ├─► Sort by priority
            │
            ▼
┌─────────────────────────┐
│ 8. Format & Send        │  Final report to user
│    Markdown Report      │
└───────────┬─────────────┘
            │
            ▼
   Send: "Pull Request Analysis Complete

          Files analyzed: 4
          Total issues: 3

          CRITICAL ISSUES (0):
          None

          WARNINGS (1):
          - ConfigService.kt:14 [security]
            New nullable fields without validation

          SUGGESTIONS (2):
          - Main.kt:245 [quality]
            Consider extracting initialization logic

          SUMMARY:
          Code quality is good. No critical issues.
          Minor improvements suggested."
```

## Component Breakdown

### 1. GitHub MCP Integration

**Location**: `Main.kt` lines 231-266 (after gitMcpService initialization)

**Required Tools**:
- `list_pull_requests(owner, repo, state)` → List of PRs
- `get_pull_request(owner, repo, pull_number)` → PR details
- `get_pull_request_diff(owner, repo, pull_number)` → Unified diff

**Configuration**: Reads from `config.properties`:
```properties
github.token=ghp_xxxxxxxxxxxxx
github.owner=dikoresearch
github.repo=TeleGaGa
```

### 2. RAG Integration

**Service**: `RagService.findRelevantChunksAcrossAllFiles()`

**Input**:
```kotlin
val query = """
Context: Analyzing pull request for code review

PR Title: ${pr.title}
PR Description: ${pr.description.take(500)}
Modified files: ${pr.modifiedFiles.joinToString(", ")}

Question: What project components, patterns, and best practices are relevant to this PR?
""".trimIndent()

val ragResult = ragService.findRelevantChunksAcrossAllFiles(
    question = query,
    topK = 5,
    relevanceThreshold = 0.5f
)
```

**Output**: Context string with relevant documentation chunks

**Purpose**: Provides project-specific context to GigaChat for better analysis

### 3. Diff Processing

**See detailed documentation**: `DIFF_PROCESSING_STRATEGY.md`

**Key Classes**:

```kotlin
// DiffParser.kt
object DiffParser {
    fun parseDiffIntoFiles(diff: String): List<FileDiff>
    fun extractFileName(diffHeader: String): String
    fun extractHunks(fileContent: String): List<String>
}

// DiffChunker.kt
object DiffChunker {
    fun groupFilesIntoChunks(
        files: List<FileDiff>,
        maxChunkSize: Int = 3000
    ): List<DiffChunk>

    fun splitLargeFile(
        file: FileDiff,
        maxSize: Int
    ): List<DiffChunk>
}

// DiffAnalyzer.kt
class DiffAnalyzer(
    private val gigaChatClient: GigaChatClient
) {
    suspend fun analyzeChunk(
        chunk: DiffChunk,
        ragContext: String,
        chunkIndex: Int,
        totalChunks: Int
    ): ChunkAnalysis

    fun synthesizeReport(
        analyses: List<ChunkAnalysis>
    ): FinalReport
}
```

**Data Models**:

```kotlin
data class FileDiff(
    val fileName: String,
    val content: String  // Unified diff for this file
)

data class DiffChunk(
    val files: List<FileDiff>
) {
    val totalSize: Int = files.sumOf { it.content.length }
    val fileNames: List<String> = files.map { it.fileName }
}

data class Issue(
    val severity: String,  // "high" | "medium" | "low"
    val category: String,  // "security" | "bug" | "quality" | "performance"
    val line: Int?,
    val description: String,
    val suggestion: String
)

data class FileAnalysis(
    val fileName: String,
    val issues: List<Issue>,
    val summary: String
)

data class ChunkAnalysis(
    val files: List<FileAnalysis>
)

data class FinalReport(
    val totalFiles: Int,
    val criticalIssues: List<IssueWithFile>,
    val warnings: List<IssueWithFile>,
    val suggestions: List<IssueWithFile>,
    val summary: String
) {
    fun format(): String {
        // Format as Markdown for Telegram
    }
}

data class IssueWithFile(
    val fileName: String,
    val issue: Issue
)
```

### 4. GigaChat Analysis

**System Prompt**:
```kotlin
val systemPrompt = """
You are an expert code reviewer analyzing a Pull Request diff.

REVIEW CRITERIA:
1. Code Quality - style, naming, organization, readability
2. Security - authentication, validation, injection risks, data exposure
3. Best Practices - design patterns, SOLID principles, error handling
4. Potential Bugs - null pointers, race conditions, logic errors, edge cases
5. Performance - algorithms, memory usage, resource leaks

CONTEXT:
${ragContext}

OUTPUT FORMAT (valid JSON only):
{
  "files": [
    {
      "fileName": "string",
      "issues": [
        {
          "severity": "high|medium|low",
          "category": "security|bug|quality|performance",
          "line": number,
          "description": "string",
          "suggestion": "string"
        }
      ],
      "summary": "string"
    }
  ]
}

Rules:
- Respond ONLY with valid JSON
- Focus on critical issues first
- Provide actionable suggestions
- Consider project context from RAG
- severity "high" = critical security/bugs only
- severity "medium" = potential bugs, bad practices
- severity "low" = style, minor improvements
""".trimIndent()
```

**User Prompt**:
```kotlin
val userPrompt = """
Analyze this Pull Request diff (chunk ${chunkIndex + 1} of ${totalChunks}):

Files in this chunk: ${chunk.fileNames.joinToString(", ")}

DIFF:
${chunk.files.joinToString("\n\n") { "--- ${it.fileName} ---\n${it.content}" }}

Provide structured JSON analysis.
""".trimIndent()
```

**Request Parameters**:
```kotlin
gigaChatClient.chatCompletion(
    messages = listOf(
        GigaChatMessage(role = "system", content = systemPrompt),
        GigaChatMessage(role = "user", content = userPrompt)
    ),
    temperature = 0.3f,  // Low for consistency
    model = "GigaChat",
    functions = null  // No function calling for analysis
)
```

### 5. Progress Messages

**User Experience Flow**:

```
[User] /showPR 123

[Bot] Fetching Pull Request #123...

[Bot] Found Pull Request #123: Add GitHub MCP integration
      Modified files: 4
      Changed lines: +127 -12

[Bot] Analyzing Pull Request with RAG and local documentation...

[Bot] Getting diff from Pull Request and analyzing it...

[Bot] Analyzing chunk 1 of 2...

[Bot] Analyzing chunk 2 of 2...

[Bot] Pull Request Analysis Complete

      Files analyzed: 4
      Total issues: 3

      [... detailed report ...]
```

## Implementation Priority

### Phase 2: Command Structure (2-3 days)
1. ✅ Add `/showPR` command handler
2. ✅ Implement PR fetching
3. ✅ Add progress messages
4. ✅ Basic error handling

### Phase 3: Analysis Logic (3-4 days)
1. ✅ Create `DiffParser.kt`
2. ✅ Create `DiffChunker.kt`
3. ✅ Create `DiffAnalyzer.kt`
4. ✅ Implement RAG query generation
5. ✅ Integrate GigaChat analysis
6. ✅ Implement report synthesis

### Phase 4: Testing & Documentation (1-2 days)
1. ✅ Test with small PR (1-5 files)
2. ✅ Test with medium PR (5-20 files)
3. ✅ Test with large PR (20-50 files)
4. ✅ Test error scenarios
5. ✅ Write user documentation

## Error Handling Scenarios

| Error | Handling Strategy |
|-------|------------------|
| GitHub token missing | Show setup instructions |
| GitHub API rate limit | Wait + retry with exponential backoff |
| PR not found | Clear error message |
| No diff available | Show file list only |
| GigaChat timeout | Retry up to 3 times |
| JSON parse error | Return raw text analysis |
| Large diff (>50 files) | Warn user, analyze anyway |
| Binary files in diff | Skip and notify |
| Network failure | Timeout with clear message |

## Performance Expectations

| PR Size | Files | Diff Size | Chunks | Time | Cost |
|---------|-------|-----------|--------|------|------|
| Small | 1-5 | <3K | 1 | 15s | Low |
| Medium | 5-20 | 3-10K | 2-3 | 40s | Medium |
| Large | 20-50 | 10-50K | 5-10 | 120s | High |
| Very Large | 50+ | >50K | 10+ | 180s+ | Very High |

## Security Considerations

1. **GitHub Token**: Never log or expose token
2. **Private Repos**: User must have access rights
3. **Rate Limits**: Respect GitHub API limits (5000/hour)
4. **Input Validation**: Sanitize PR numbers and repo names
5. **Error Messages**: Don't leak sensitive information

## Future Enhancements

1. **Caching**: Cache PR analyses for 1 hour
2. **Incremental Analysis**: Only analyze changed files since last review
3. **Multiple Models**: Let user choose Ollama for free analysis
4. **Custom Rules**: Allow project-specific review rules
5. **Auto-comments**: Post analysis as GitHub PR comment
6. **Comparison**: Compare with previous PR versions

---

**Related Documents**:
- `DIFF_PROCESSING_STRATEGY.md` - Detailed diff chunking algorithm
- `GITHUB_MCP_SETUP.md` - GitHub token setup guide
- `CLAUDE.md` - Project overview and MCP integration
