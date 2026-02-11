# RAG Bot Refactoring - February 11, 2026

## Summary

Refactored TeleGaGa bot to work as a project expert assistant with automatic RAG (Retrieval-Augmented Generation) integration. All system prompts and user interactions are now in English.

## Changes Made

### 1. Character Encoding Fix (MarkdownPreprocessor.kt)

**Problem**: `MalformedInputException` when creating embeddings from markdown files containing invalid UTF-8 characters.

**Solution**: Added `sanitizeText()` function that removes:
- Control characters (0x00-0x1F, 0x7F-0x9F) except `\n`, `\r`, `\t`
- Invalid surrogate characters
- Other non-printable characters

**Files Changed**:
- `src/main/kotlin/ru/dikoresearch/domain/MarkdownPreprocessor.kt:14-47`

**Impact**: `/createEmbeddings` command now successfully processes all markdown files without encoding errors.

---

### 2. Automatic RAG Integration (TelegramBotService.kt)

**Problem**: RAG was not automatically used for answering questions. Users had to explicitly use `/help` command.

**Solution**: Message handler now automatically:
1. Searches relevant documentation using RAG
2. Injects found fragments into system prompt
3. Sends enriched context to GigaChat

**Files Changed**:
- `src/main/kotlin/ru/dikoresearch/infrastructure/telegram/TelegramBotService.kt:1290-1350`

**Workflow**:
```
User Question
    ↓
RAG Search (findRelevantChunksAcrossAllFiles)
    ↓
Format Context (formatContextForMultipleFiles)
    ↓
Build Enhanced System Prompt
    ↓
Send to GigaChat via ChatOrchestrator
    ↓
Return Answer
```

**Impact**: Every message now benefits from RAG without explicit commands.

---

### 3. System Prompt Update Fix (ChatOrchestrator.kt)

**Problem**: Chat history preserved old system prompts. When RAG added new context, it was ignored because the old Russian-language system prompt from history was used instead.

**Solution**: Always update system prompt (first message in history) before sending to GigaChat.

**Files Changed**:
- `src/main/kotlin/ru/dikoresearch/domain/ChatOrchestrator.kt:263-277`

**Key Code**:
```kotlin
// IMPORTANT: Always update system prompt (first message)
// This is needed for correct RAG operation with new context
if (history.isNotEmpty() && history[0].role == "system") {
    history[0] = GigaChatMessage(role = "system", content = systemRole)
    println("✏️ Updated system prompt in history")
}
```

**Impact**: RAG context is now correctly injected into every request, even with existing chat history.

---

### 4. Enhanced Logging

**Added logging in**:
- `ChatOrchestrator.kt:270-276` - Shows all messages sent to GigaChat with previews
- `TelegramBotService.kt:1303-1311` - Shows RAG search results and context size

**Console Output Example**:
```
🔍 Searching relevant documentation via RAG...
✅ Found 5 relevant fragments (avg relevance: 0.66)
📄 RAG context size: 2453 chars
📋 First 300 chars of context:
--- FRAGMENT 1 ---
Source: readme.md (lines 10-20)
...
📝 System prompt with RAG: 2850 chars
📋 Sending 3 messages to GigaChat:
  [0] system: You are a helpful assistant...
  [1] user: Which model is used for embeddings?
  [2] assistant: The project uses nomic-embed-text...
📤 Calling gigaClient.chatCompletion...
✅ Received response from ChatOrchestrator (149 chars)
```

**Impact**: Easy debugging of RAG and system prompt issues.

---

### 5. Full English Language Migration

**Changed**:
- System prompts (Main.kt:53-58)
- Bot commands (/start, /help, etc.)
- Console logging
- Error messages
- RAG context formatting

**Files Changed**:
- `src/main/kotlin/ru/dikoresearch/Main.kt:53-58`
- `src/main/kotlin/ru/dikoresearch/infrastructure/telegram/TelegramBotService.kt:161-192`
- `src/main/kotlin/ru/dikoresearch/infrastructure/telegram/TelegramBotService.kt:1290-1350`
- `src/main/kotlin/ru/dikoresearch/domain/RagService.kt:308-316`

**New System Prompt**:
```
You are a helpful assistant for the TeleGaGa project.

Below is documentation extracted from the project. You MUST use this information to answer questions.

DOCUMENTATION:
<fragments>

CRITICAL INSTRUCTIONS:
1. Answer using ONLY the information from the documentation above
2. If the answer is in the documentation, provide it with specific details
3. Quote relevant parts when answering
4. Do NOT say information is not available if it exists in documentation
5. Answer in English
6. Be specific and cite exact information from the fragments
```

**Impact**: Consistent English interface for all interactions.

---

### 6. Improved RAG Context Formatting (RagService.kt)

**Before**:
```
Фрагмент 1:
<text>
```

**After**:
```
--- FRAGMENT 1 ---
Source: readme.md (lines 10-20)
Relevance: 66%

<text>
```

**Files Changed**:
- `src/main/kotlin/ru/dikoresearch/domain/RagService.kt:308-316`

**Impact**: GigaChat clearly sees documentation sources and relevance scores.

---

## Technical Details

### Key Classes Modified

1. **MarkdownPreprocessor** (`domain/MarkdownPreprocessor.kt`)
   - Added: `sanitizeText()` - removes invalid UTF-8 characters
   - Purpose: Fix encoding errors during embedding creation

2. **ChatOrchestrator** (`domain/ChatOrchestrator.kt`)
   - Modified: `processMessage()` - always updates system prompt in history
   - Added: Detailed logging of messages sent to GigaChat
   - Purpose: Ensure RAG context is correctly used

3. **TelegramBotService** (`infrastructure/telegram/TelegramBotService.kt`)
   - Modified: Message handler to automatically use RAG
   - Added: Enhanced logging of RAG search and context
   - Purpose: Automatic RAG integration for all messages

4. **RagService** (`domain/RagService.kt`)
   - Modified: `formatContextForMultipleFiles()` - improved formatting with sources
   - Purpose: Better context presentation to LLM

### RAG Workflow

```
┌─────────────────┐
│  User Message   │
└────────┬────────┘
         │
         v
┌─────────────────────────────┐
│  Load Chat Settings         │
│  (temperature, threshold)   │
└────────┬────────────────────┘
         │
         v
┌─────────────────────────────┐
│  RAG Search                 │
│  - Generate query embedding │
│  - Search all .md files     │
│  - Filter by threshold      │
│  - Return top-5 chunks      │
└────────┬────────────────────┘
         │
         v
┌─────────────────────────────┐
│  Format Context             │
│  - Add source info          │
│  - Add relevance scores     │
└────────┬────────────────────┘
         │
         v
┌─────────────────────────────┐
│  Build System Prompt        │
│  - Base prompt              │
│  - RAG context              │
│  - Critical instructions    │
└────────┬────────────────────┘
         │
         v
┌─────────────────────────────┐
│  Load/Update History        │
│  - Update system prompt [0] │
│  - Add user message         │
└────────┬────────────────────┘
         │
         v
┌─────────────────────────────┐
│  Send to GigaChat           │
│  - All history messages     │
│  - Temperature setting      │
└────────┬────────────────────┘
         │
         v
┌─────────────────────────────┐
│  Return Answer              │
│  - Clean response           │
│  - No token info            │
└─────────────────────────────┘
```

### Settings Per Chat

Each chat maintains:
- `temperature: Float` - GigaChat temperature (0.0-1.0)
- `ragRelevanceThreshold: Float` - Minimum relevance for RAG chunks (default: 0.5)

Stored in: `chat_settings/<chatId>_settings.json`

---

## Testing

### Test 1: Embeddings Creation

**Command**: `/createEmbeddings`

**Expected**:
- All .md files in `rag_docs/` processed without errors
- No `MalformedInputException`
- Embeddings saved to `embeddings_store/`

**Result**: ✅ Fixed with `sanitizeText()`

### Test 2: RAG Integration

**Question**: "Which local model do you use for create embeddings?"

**Expected Console Output**:
```
🔍 Searching relevant documentation via RAG...
✅ Found 5 relevant fragments (avg relevance: 0.66)
📄 RAG context size: 2453 chars
✏️ Updated system prompt in history (2850 chars)
📋 Sending 3 messages to GigaChat:
  [0] system: You are a helpful assistant...
```

**Expected Bot Answer**:
- Answer in English
- Mentions `nomic-embed-text`
- Mentions Ollama
- Cites documentation

**Result**: ✅ Working after system prompt update fix

### Test 3: Chat History Persistence

**Scenario**:
1. Clear chat: `/clearChat`
2. Ask question: "What is RAG?"
3. Restart bot
4. Ask follow-up: "How does it work?"

**Expected**:
- Both answers use RAG context
- Both answers in English
- Follow-up understands context

**Result**: ✅ System prompt correctly updated in loaded history

---

## Migration Guide

### For Existing Chats

If bot answers in Russian or ignores RAG:

**Option 1**: Clear chat history
```
/clearChat
```

**Option 2**: Just continue - system prompt auto-updates
- Next message will use new English prompt
- RAG context will be injected automatically

### For Developers

1. **Embeddings must be recreated** if you had encoding issues before:
   ```
   /createEmbeddings
   ```

2. **Check console logs** to verify RAG is working:
   - Should see "Found X relevant fragments"
   - Should see "Updated system prompt in history"

3. **Adjust RAG threshold** per chat if needed:
   ```
   /setThreshold 0.6
   ```

---

## Configuration

### Default Settings

```kotlin
// Main.kt
defaultSystemRole = "You are an expert assistant for the TeleGaGa project..."
defaultTemperature = 0.87F

// ChatSettingsManager.kt (defaults)
temperature = 0.87F
ragRelevanceThreshold = 0.5F
ragEnabled = true
ragTopK = 5
```

### RAG Thresholds

- **0.3-0.4**: Low - more results, may include noise
- **0.5-0.6**: Medium - balanced (recommended)
- **0.7-0.8**: High - only highly relevant chunks

---

## Known Issues & Limitations

1. **First message without history** may not benefit from RAG if embeddings not created
   - Solution: Run `/createEmbeddings` after bot start

2. **Large documentation files** (>100KB) may slow embedding creation
   - Current: All files process in ~30 seconds total

3. **GigaChat may occasionally ignore RAG context** even with strong instructions
   - Workaround: Rephrase question or clear chat history

---

## Future Improvements

1. **Reranker**: Add reranking step after RAG search for better relevance
2. **Streaming responses**: Support streaming for long answers
3. **Multi-language**: Support Russian alongside English
4. **Embedding cache**: Cache embeddings for queries to speed up repeated questions

---

## Files Modified

### Core Changes
- `src/main/kotlin/ru/dikoresearch/Main.kt` - System prompts
- `src/main/kotlin/ru/dikoresearch/domain/ChatOrchestrator.kt` - System prompt update fix
- `src/main/kotlin/ru/dikoresearch/domain/MarkdownPreprocessor.kt` - Character sanitization
- `src/main/kotlin/ru/dikoresearch/infrastructure/telegram/TelegramBotService.kt` - Automatic RAG
- `src/main/kotlin/ru/dikoresearch/domain/RagService.kt` - Context formatting

### Build
- `build.gradle.kts` - No changes
- Java version: OpenJDK 17.0.1

---

## Commit Message

```
Refactor: Convert bot to English RAG expert assistant

- Fix: Character encoding in markdown preprocessing (sanitizeText)
- Fix: System prompt not updating in chat history
- Feature: Automatic RAG integration for all messages
- Feature: Enhanced logging for RAG and GigaChat requests
- Refactor: Full English language migration (prompts, commands, logs)
- Improve: RAG context formatting with sources and relevance

All user interactions now in English.
RAG automatically used for every message.
Chat history correctly updates system prompts.

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>
```

---

## Changelog

### [2026-02-11] - RAG Bot Refactoring

#### Added
- Character sanitization in MarkdownPreprocessor (`sanitizeText()`)
- Automatic RAG integration in message handler
- System prompt update mechanism in ChatOrchestrator
- Enhanced logging for RAG search and GigaChat requests
- Source and relevance info in RAG context formatting

#### Changed
- All system prompts → English
- All bot commands → English
- All console logging → English
- RAG context formatting → Include sources and relevance
- System prompt → Stronger RAG usage instructions

#### Fixed
- `MalformedInputException` during embeddings creation
- RAG context ignored due to old system prompt in history
- Russian language responses despite English prompts
- Missing RAG integration in normal message flow

#### Removed
- Token usage display in bot responses
- Decorative formatting in responses
- Git MCP integration from message handler (not needed for RAG bot)

---

## Contributors

- Claude Sonnet 4.5
- Dmitrii Konovalov (@dikoresearch)

---

**Date**: February 11, 2026
**Version**: After RAG refactoring
**Status**: ✅ Tested and Working
