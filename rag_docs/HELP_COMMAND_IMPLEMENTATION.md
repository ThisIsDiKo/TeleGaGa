# /help Command Implementation

## Overview

This document describes the implementation of the `/help` command which provides RAG-based assistance for the TeleGaGa project.

## Implementation Date

February 9, 2026

## Key Features

### 1. Multi-File RAG Search

The `/help` command searches across ALL documentation files in `rag_docs/` using the existing `findRelevantChunksAcrossAllFiles()` method from RagService.

**Code location:** `src/main/kotlin/ru/dikoresearch/infrastructure/telegram/TelegramBotService.kt`

**Usage:**
```
/help What MCP servers are available?
/help How do I change temperature?
/help How to add a new command?
```

### 2. Enhanced /createEmbeddings Command

The `/createEmbeddings` command was updated to process ALL .md files in `rag_docs/` automatically.

**Previous behavior:** Only processed `readme.md`
**New behavior:** Processes all `.md` files in `rag_docs/` directory

**Usage:**
```
/createEmbeddings              # Process all .md files
/createEmbeddings readme.md    # Process specific file
```

### 3. Response Format

The `/help` command returns:
- Question
- Answer from Ollama (llama3.2:3b)
- Sources with file names and line numbers
- Statistics (chunks found, average relevance, threshold)

**Example response:**
```
━━━ PROJECT HELP ━━━

🔍 Question: What MCP servers are available?

📖 Answer:
The project uses 5 MCP servers...

━━━ SOURCES ━━━
• MCP_INTEGRATION.md (lines 45-67)
• CLAUDE.md (lines 89-112)

📊 Statistics:
• Chunks found: 3
• Avg relevance: 78%
• Threshold: 0.5
```

### 4. Git MCP Server Setup

**Installed:** `mcp-server-git` via `uvx` (Python-based MCP server)

**Purpose:** Provides git operations via MCP protocol (intended for future use)

**Installation:**
```bash
pipx install uv
uvx mcp-server-git --repository .
```

### 5. CLI Interface

**Already implemented** in `MainCli.kt` and `CliChatbot.kt`

**Run CLI mode:**
```bash
./gradlew run --args="--threshold 0.5 --topK 5"
```

**CLI commands:**
- `/help` - Show help
- `/createEmbeddings` - Create embeddings for all docs
- `/threshold` - Show current RAG threshold
- `/setThreshold <0.0-1.0>` - Set threshold
- `/clear` - Clear history
- `/stats` - Show statistics
- `/exit` - Exit

## Technical Details

### RAG Service Methods Used

1. **findRelevantChunksAcrossAllFiles()** - Searches all embedding files
   - Returns: `MultiFileRagResult` with chunks from multiple files
   - Parameters: question, topK, relevanceThreshold

2. **formatContextForMultipleFiles()** - Formats chunks with citations
   - Input: `List<ChunkWithSource>`
   - Output: Formatted context string for LLM

### Dependencies

- **Ollama:** llama3.2:3b for inference, nomic-embed-text for embeddings
- **RagService:** Multi-file search with relevance filtering
- **EmbeddingService:** Generates embeddings for markdown files
- **TextChunker:** Splits text into 300-character chunks with 50-char overlap

### Data Models

**ChunkWithSource:**
```kotlin
data class ChunkWithSource(
    val text: String,
    val relevance: Float,
    val fileName: String,
    val startLine: Int,
    val endLine: Int
)
```

**MultiFileRagResult:**
```kotlin
data class MultiFileRagResult(
    val chunks: List<ChunkWithSource>,
    val originalCount: Int,
    val filteredCount: Int,
    val avgRelevance: Float,
    val minRelevance: Float,
    val maxRelevance: Float
)
```

## Testing

### Test 1: /help Without Embeddings
```
/help What is TeleGaGa?
Expected: Error message asking to run /createEmbeddings
```

### Test 2: /help With Embeddings
```
/help What MCP servers are available?
Expected: Answer with citations from MCP_INTEGRATION.md
```

### Test 3: /createEmbeddings All Files
```
/createEmbeddings
Expected: Processes all 17 .md files, shows progress
```

### Test 4: CLI Mode
```bash
./gradlew run
# Then in CLI:
> help What is RAG?
# Expected: Answer with documentation sources
```

## Success Criteria Met

✅ 1. `/createEmbeddings` processes all .md files in rag_docs/
✅ 2. Git MCP server (mcp-server-git) installed via uvx
✅ 3. `/help <question>` returns answer with RAG citations
✅ 4. CLI interface already implemented (MainCli.kt)
✅ 5. /start command updated with /help documentation
✅ 6. Build succeeds without errors

## Files Modified

1. **TelegramBotService.kt** (lines 71-106, 740-980)
   - Added `createEmbeddingsForFile()` helper function
   - Updated `/createEmbeddings` to process all files
   - Added `/help` command with RAG search
   - Updated `/start` command help text

2. **Main.kt** (no changes needed)
   - Configuration already supports RAG
   - CLI mode already implemented in MainCli.kt

## Future Enhancements

1. **Git MCP Integration:** Add current branch to /help response
2. **Code Syntax Highlighting:** Format code snippets in responses
3. **Caching:** Cache embeddings in memory for faster search
4. **Streaming:** Support streaming responses for long answers
5. **Multi-language:** Support Russian language questions

## Related Documentation

- **RAG System:** See rag_docs/RAG_SYSTEM.md
- **MCP Integration:** See rag_docs/MCP_INTEGRATION.md
- **Architecture:** See rag_docs/ARCHITECTURE.md
- **Project Guide:** See rag_docs/CLAUDE.md

## Version Information

- **Kotlin:** 1.9.x
- **Gradle:** 8.14
- **Ollama Models:** llama3.2:3b, nomic-embed-text
- **MCP Protocol:** 2024-11-05
