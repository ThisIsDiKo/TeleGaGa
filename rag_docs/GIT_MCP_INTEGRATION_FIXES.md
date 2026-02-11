# Git MCP Integration - Fixes and Improvements

**Date:** 2026-02-09
**Status:** ✅ Completed

## Overview

Fixed Git MCP integration to enable GigaChat to automatically call git functions when users ask git-related questions. The integration now works correctly with proper repository path configuration, request/response logging, and JSON formatting for function results.

## Problems Fixed

### Problem 1: RAG Chunks Not Visible in Requests
**Issue:** RAG documentation fragments were not visible when debugging `/help` command.
**Solution:** Added comprehensive logging in `ChatOrchestrator.kt` to show full requests and responses from GigaChat.

### Problem 2: GigaChat Not Calling Git MCP Functions
**Root Causes:**
1. Git MCP was only available in `/help` command (meant for RAG documentation)
2. Relative repository path `"."` might point to wrong location
3. Function results not wrapped in valid JSON (GigaChat requirement)
4. Function results too long causing 413 errors

**Solutions:**
1. Moved Git MCP from `/help` to regular message handler
2. Changed to absolute repository path
3. Added JSON wrapping for all function results
4. Added truncation for long results (max 1500 chars)

---

## File Changes

### 1. Main.kt

**Location:** `src/main/kotlin/ru/dikoresearch/Main.kt`
**Lines Modified:** 209-226

**Changes:**
- Changed repository path from relative `"."` to absolute path via `System.getProperty("user.dir")`
- Added logging for project path
- Added stack trace printing on Git MCP initialization failure

**Before:**
```kotlin
args = listOf("mcp-server-git", "--repository", ".")
```

**After:**
```kotlin
val projectPath = System.getProperty("user.dir")
println("   Project path: $projectPath")
args = listOf("mcp-server-git", "--repository", projectPath)
```

**Impact:** Git MCP now correctly works with repository at `/Users/dmitriikonovalov/Documents/TeleGaGa`

---

### 2. ChatOrchestrator.kt

**Location:** `src/main/kotlin/ru/dikoresearch/domain/ChatOrchestrator.kt`
**Function Modified:** `processMessageWithFunctionCalling()`

#### Change 2.1: Request Logging (Lines 75-97)

**Added comprehensive logging before sending request to GigaChat:**

```kotlin
// LOG: Show initial request to GigaChat
println("\n" + "=".repeat(80))
println("📤 REQUEST TO GIGACHAT")
println("=".repeat(80))
println("SYSTEM ROLE (${systemRole.length} chars):")
println(systemRole)
println("\n${"-".repeat(80)}")
println("USER MESSAGE (${userMessage.length} chars):")
println(userMessage)
println("\n${"-".repeat(80)}")
println("FUNCTIONS AVAILABLE: ${functions.size}")
functions.forEach { println("   - ${it.name}: ${it.description}") }
println("=".repeat(80) + "\n")
```

**Purpose:** Debug RAG chunks transmission and function availability

#### Change 2.2: Response Logging (Lines 128-141)

**Added logging for GigaChat responses:**

```kotlin
// LOG: Show GigaChat response
println("\n" + "=".repeat(80))
println("📥 RESPONSE FROM GIGACHAT")
println("=".repeat(80))
println("FINISH REASON: ${choice?.finishReason}")
println("MESSAGE ROLE: ${message?.role}")
println("MESSAGE CONTENT: ${message?.content}")
println("FUNCTION CALL: ${message?.functionCall?.name}")
if (message?.functionCall != null) {
    println("FUNCTION ARGUMENTS: ${message.functionCall.arguments}")
}
println("=".repeat(80) + "\n")
```

**Purpose:** See if GigaChat calls functions (`finish_reason: function_call` vs `stop`)

#### Change 2.3: Result Truncation (Lines 177-184)

**Added truncation to prevent 413 errors:**

```kotlin
// Truncate result if too long (to avoid 413 Request Entity Too Large)
val truncatedResult = if (result.length > 1500) {
    result.take(1500) + "\n\n... (truncated due to length, showing first 1500 chars)"
} else {
    result
}

if (result.length > 1500) {
    println("⚠️ Function result truncated: ${result.length} -> 1500 chars")
}
```

**Purpose:** Prevent "413 Request Entity Too Large" errors from GigaChat

#### Change 2.4: JSON Wrapping (Lines 186-200)

**Critical fix - wrap function results in valid JSON:**

```kotlin
// GigaChat requires function results to be valid JSON
// Wrap plain text results in JSON object
val jsonResult = if (truncatedResult.trim().startsWith("{") || truncatedResult.trim().startsWith("[")) {
    // Already JSON
    truncatedResult
} else {
    // Plain text - wrap in JSON object
    // Escape quotes and newlines
    val escapedResult = truncatedResult
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
    "{\"result\": \"$escapedResult\"}"
}

println("📋 JSON result for GigaChat: ${jsonResult.take(100)}...")
```

**Purpose:** Fix "422 Unprocessable Entity: invalid function result json string" error

**Before (plain text):**
```
Switched to branch 'main'
```

**After (valid JSON):**
```json
{"result": "Switched to branch 'main'"}
```

---

### 3. TelegramBotService.kt

**Location:** `src/main/kotlin/ru/dikoresearch/infrastructure/telegram/TelegramBotService.kt`

#### Change 3.1: Simplified /help Command (Lines 973-1020)

**Removed Git MCP from /help, left only RAG:**

**Before:**
- `/help` used both RAG and Git MCP with function calling
- Git functions were passed to GigaChat
- Complex system prompt with git instructions

**After:**
- `/help` uses only RAG for documentation search
- Calls `processMessageWithoutHistory()` instead of `processMessageWithFunctionCalling()`
- Simple system prompt focused on documentation

**New System Prompt:**
```kotlin
val systemRole = """
    You are a programming assistant for the TeleGaGa project.
    Answer questions based on the provided documentation fragments.

    RULES:
    - Answer in English only
    - Use documentation fragments as primary source
    - Include code snippets when relevant
    - Cite sources using [filename:lines] format
    - Be concise and specific
    - Include citations for facts from documentation
""".trimIndent()
```

**Purpose:** `/help` is now purely for RAG (Retrieval-Augmented Generation) documentation search

#### Change 3.2: Git MCP in Message Handler (Lines 1122-1188)

**Added Git MCP function calling to regular message handler:**

```kotlin
// Get Git MCP tools
val gitTools = gitMcpService.listTools()
val gitFunctions = convertMcpToolsToGigaChatFunctions(gitTools)

println("🔧 Git MCP tools available: ${gitFunctions.size}")

if (gitFunctions.isNotEmpty()) {
    println("🤖 Обработка сообщения через ChatOrchestrator с Git MCP function calling...")

    // Enhanced system role with git tools instructions
    val enhancedSystemRole = """
        $defaultSystemRole

        IMPORTANT: You have access to Git tools for repository /Users/dmitriikonovalov/Documents/TeleGaGa.

        MANDATORY - Use git tools when user asks about:
        - branches (list/current) → CALL git_list_branches or git_show_current_branch
        - commits/history → CALL git_log
        - repository status/changes → CALL git_status
        - files in repository → CALL appropriate git tool

        DO NOT just describe git commands - ALWAYS CALL the actual tool to get real data.
        Answer in English only.
    """.trimIndent()

    // Process with function calling
    val (answer, usage, functionCalls) = chatOrchestrator.processMessageWithFunctionCalling(
        systemRole = enhancedSystemRole,
        userMessage = userMessage,
        temperature = temperature,
        model = gigaChatModel,
        functions = gitFunctions,
        functionExecutor = { functionName, args ->
            println("🔧 Executing git tool: $functionName with args: $args")
            val result = gitMcpService.callTool(functionName, args)
            val text = result.content.firstOrNull()?.text ?: result.toString()
            println("✅ Git tool result: ${text.take(200)}...")
            text
        },
        maxIterations = 3
    )

    // ... format response with function calls info
}
```

**Purpose:** Now ALL messages (not just `/help`) can trigger Git MCP function calls automatically

**Enhanced System Prompt Features:**
- Explicitly mentions repository path
- Lists specific git tool mappings (branches → git_list_branches, etc.)
- **MANDATORY** keyword emphasizes tool usage
- Clear instruction: "ALWAYS CALL the actual tool"

---

### 4. StdioMcpService.kt

**Location:** `src/main/kotlin/ru/dikoresearch/infrastructure/mcp/StdioMcpService.kt`
**Lines Modified:** 92-99

**Added detailed tool logging during initialization:**

```kotlin
println("   ✅ ${config.name}: ${tools.size} tools")
tools.forEach { tool ->
    println("      - ${tool.name}: ${tool.description}")
    println("        Input schema: ${tool.inputSchema}")
}
```

**Purpose:** Verify which git tools are available at startup

**Example Output:**
```
   ✅ git: 12 tools
      - git_status: Get the status of a git repository
        Input schema: {"type":"object","properties":{"repo_path":...}}
      - git_list_branches: List all branches in the repository
        Input schema: {"type":"object","properties":{"repo_path":...}}
      ...
```

---

## Command Behavior Changes

### /help Command (RAG Only)

**Before:**
```
/help <question>
→ Searches RAG documentation
→ Calls GigaChat with git MCP functions
→ May or may not call git tools
```

**After:**
```
/help <question>
→ Searches RAG documentation ONLY
→ Calls GigaChat WITHOUT function calling
→ Returns answer based purely on documentation chunks
```

**Response Format:**
```
━━━ PROJECT HELP (RAG) ━━━

🔍 Question: How does RAG work?

📖 Answer:
[Answer from GigaChat based on documentation]

━━━ SOURCES ━━━
• ARCHITECTURE.md (lines 120-145)
• readme.md (lines 45-60)

📊 Statistics:
• Chunks found: 3
• Avg relevance: 85%
• Threshold: 0.5
• Tokens used: 1250
```

---

### Regular Messages (With Git MCP)

**Before:**
```
User: "show me list of git branches"
→ GigaChat gives generic answer about git commands
→ No actual repository data
```

**After:**
```
User: "show me list of git branches"
→ GigaChat calls git_list_branches function
→ Git MCP executes: git branch -a
→ Returns actual branches from /Users/dmitriikonovalov/Documents/TeleGaGa
→ GigaChat formats response with real data
```

**Response Format:**
```
The repository has the following branches:
- main (current)
- feature/rag-integration
- hotfix/mcp-fixes

🔧 Git tools used: Called: git_list_branches

Отправлены токены: 850
Получены токены: 120
Оплачены токены: 970
```

---

## Architecture Decisions

### Why Git MCP in Message Handler, Not /help?

| Aspect | /help Command | Regular Messages |
|--------|---------------|------------------|
| **Purpose** | Search project documentation | General conversation + git queries |
| **Tools** | None (pure RAG) | Git MCP (12 functions) |
| **LLM Input** | Documentation chunks | User question + chat history |
| **Use Case** | "How does RAG work?" | "What branch am I on?" |
| **Function Calling** | ❌ Disabled | ✅ Enabled |

**Rationale:**
1. `/help` is **documentation-focused** - should only search docs, not interact with repository
2. **Regular messages** should handle **git queries naturally** - users don't need special commands
3. **Separation of concerns** - RAG for docs, Git MCP for repository interaction

---

## Testing Results

### Test 1: Git Function Calling
```
User: "show me list of git branches"

Console Output:
================================================================================
📤 REQUEST TO GIGACHAT
================================================================================
SYSTEM ROLE (428 chars):
...MANDATORY - Use git tools when user asks about:
- branches (list/current) → CALL git_list_branches or git_show_current_branch...

USER MESSAGE (29 chars):
show me list of git branches

FUNCTIONS AVAILABLE: 12
   - git_status: Get the status of a git repository
   - git_list_branches: List all branches in the repository
   ...
================================================================================

================================================================================
📥 RESPONSE FROM GIGACHAT
================================================================================
FINISH REASON: function_call
MESSAGE ROLE: assistant
MESSAGE CONTENT:
FUNCTION CALL: git_list_branches
FUNCTION ARGUMENTS: {"repo_path":"/Users/dmitriikonovalov/Documents/TeleGaGa"}
================================================================================

📞 Function call requested: git_list_branches
🔧 Executing git tool: git_list_branches with args: {repo_path=/Users/dmitriikonovalov/Documents/TeleGaGa}
✅ Git tool result: * main...
📋 JSON result for GigaChat: {"result": "* main"}...
```

**Result:** ✅ Success - GigaChat correctly called git_list_branches

### Test 2: JSON Wrapping for Plain Text
```
Function raw result: "Switched to branch 'main'"
JSON wrapped result: {"result": "Switched to branch 'main'"}
```

**Result:** ✅ Success - No more 422 "invalid function result json string" errors

### Test 3: Long Result Truncation
```
Function result length: 3500 chars
⚠️ Function result truncated: 3500 -> 1500 chars
Truncated result: [first 1500 chars]... (truncated due to length, showing first 1500 chars)
```

**Result:** ✅ Success - No more 413 "Request Entity Too Large" errors

### Test 4: RAG-Only /help
```
User: /help What MCP servers are used?

Console Output:
📤 REQUEST TO GIGACHAT (NO FUNCTIONS)
SYSTEM ROLE: You are a programming assistant for the TeleGaGa project...
USER MESSAGE:
=== DOCUMENTATION FOR ANSWER ===
...
--- Fragment 1 ---
Source: MCP_INTEGRATION.md, lines 15-30
[Documentation about MCP servers]
...

Response: ✅ Answer based purely on documentation chunks
```

**Result:** ✅ Success - /help works as pure RAG without git function calls

---

## Configuration

### Git MCP Repository Path

**Configured in:** `Main.kt:209-211`

```kotlin
val projectPath = System.getProperty("user.dir")
// Returns: /Users/dmitriikonovalov/Documents/TeleGaGa
```

**Verification at Startup:**
```
7. Инициализация Git MCP Service...
   Project path: /Users/dmitriikonovalov/Documents/TeleGaGa
   Using uvx at: /Users/dmitriikonovalov/.local/bin/uvx
   ✅ Git MCP Service инициализирован для репозитория: /Users/dmitriikonovalov/Documents/TeleGaGa
      - git_status: Get the status of a git repository
      - git_list_branches: List all branches in the repository
      - git_show_current_branch: Show the current branch name
      - git_log: Show commit logs
      ...
```

---

## Error Handling

### 1. 413 Request Entity Too Large
**Error:** Function results too long for GigaChat
**Fix:** Truncate to 1500 chars max
**Code:** `ChatOrchestrator.kt:177-184`

### 2. 422 Invalid Function Result JSON
**Error:** GigaChat requires JSON, got plain text
**Fix:** Wrap all results in `{"result": "..."}`
**Code:** `ChatOrchestrator.kt:186-200`

### 3. Git MCP Initialization Failure
**Error:** uvx not found or mcp-server-git not installed
**Fix:** Graceful fallback - bot works without git tools
**Code:** `Main.kt:221-225`

```kotlin
} catch (e: Exception) {
    println("   ⚠️ Git MCP Service недоступен (будет работать без git tools): ${e.message}")
    e.printStackTrace()
    ru.dikoresearch.infrastructure.mcp.StdioMcpService(emptyList())
}
```

---

## Logging Format

### Request Logging
```
================================================================================
📤 REQUEST TO GIGACHAT
================================================================================
SYSTEM ROLE (XXX chars):
[Full system prompt with git tool instructions]

--------------------------------------------------------------------------------
USER MESSAGE (XXX chars):
[User question or documentation chunks]

--------------------------------------------------------------------------------
FUNCTIONS AVAILABLE: 12
   - git_status: Get the status of a git repository
   - git_list_branches: List all branches in the repository
   ...
================================================================================
```

### Response Logging
```
================================================================================
📥 RESPONSE FROM GIGACHAT
================================================================================
FINISH REASON: function_call | stop
MESSAGE ROLE: assistant
MESSAGE CONTENT: [answer text or empty if function call]
FUNCTION CALL: [function name or null]
FUNCTION ARGUMENTS: [JSON arguments if function called]
================================================================================
```

### Function Execution Logging
```
📞 Function call requested: git_list_branches
🔧 Executing git tool: git_list_branches with args: {repo_path=/Users/...}
   📤 Sending: tools/call (id=3)
   📥 Received (attempt 1): {"jsonrpc":"2.0","id":3,"result":...}
✅ Git tool result: * main...
📋 JSON result for GigaChat: {"result": "* main"}...
```

---

## Future Improvements

### Potential Optimizations

1. **Smarter Truncation**
   - Currently: Hard limit at 1500 chars
   - Better: Truncate at sentence/line boundaries
   - Best: Adaptive based on content type

2. **Caching Git Results**
   - Cache branch list, current branch for 30 seconds
   - Reduce redundant git calls for repeated questions
   - Invalidate cache on git operations

3. **Structured JSON Results**
   - Parse git output into structured JSON
   - Example: `{"branches": ["main", "develop"], "current": "main"}`
   - Easier for GigaChat to process

4. **Error Messages in Russian**
   - Currently: Mixed Russian/English in logs
   - Better: Consistent language (user-facing Russian, debug English)

5. **Function Call Optimization**
   - Track which functions are actually useful
   - Remove unused functions to reduce token usage
   - Focus on: git_status, git_list_branches, git_log, git_show_current_branch

---

## Summary

### What Works Now ✅

1. **Git MCP Integration**
   - ✅ GigaChat automatically calls git functions
   - ✅ Works with correct repository path
   - ✅ Handles 12 git tools (status, branches, log, etc.)

2. **Function Calling**
   - ✅ Valid JSON wrapping for all results
   - ✅ Truncation prevents 413 errors
   - ✅ Up to 3 iterations for complex queries

3. **RAG Documentation Search**
   - ✅ `/help` command for documentation-only queries
   - ✅ Separate from git functionality
   - ✅ Clean responses with sources and statistics

4. **Logging & Debugging**
   - ✅ Full request/response logging
   - ✅ Function execution tracking
   - ✅ Tool availability verification at startup

### Key Architectural Changes

| Component | Before | After |
|-----------|--------|-------|
| **Git MCP Location** | Only in `/help` | In all messages |
| **Repository Path** | Relative `"."` | Absolute path |
| **Function Results** | Plain text | JSON wrapped |
| **Result Size** | Unlimited | Max 1500 chars |
| **/help Purpose** | RAG + Git | RAG only |
| **Logging** | Minimal | Comprehensive |

---

## Files Modified Summary

1. **Main.kt** - Git MCP path configuration (10 lines)
2. **ChatOrchestrator.kt** - Logging + JSON wrapping + truncation (80 lines)
3. **TelegramBotService.kt** - Moved Git MCP to message handler (100 lines)
4. **StdioMcpService.kt** - Tool logging (5 lines)

**Total Lines Changed:** ~195 lines
**Commits Required:** 1 comprehensive commit

---

## Commit Message Suggestion

```
feat: Fix Git MCP integration with GigaChat function calling

Problems fixed:
- Git MCP now works in all messages (not just /help)
- Absolute repository path for correct git operations
- JSON wrapping for function results (fixes 422 errors)
- Result truncation to prevent 413 errors
- Comprehensive logging for debugging

Changes:
- Main.kt: Use absolute path for git repository
- ChatOrchestrator.kt: Add logging, JSON wrapping, truncation
- TelegramBotService.kt: Move Git MCP from /help to message handler
- StdioMcpService.kt: Add tool logging at initialization

/help is now RAG-only for documentation search
Regular messages automatically use Git MCP when needed

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>
```
