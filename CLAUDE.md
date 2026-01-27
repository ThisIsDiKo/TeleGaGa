# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.


I need you to run with java version tools located at /Users/dmitriikonovalov/Library/Java/JavaVirtualMachines/openjdk-17.0.1

## Project Overview

TeleGaGa is a Telegram bot written in Kotlin that interfaces with two LLM providers:
- **GigaChat** (Sberbank's LLM service) - the primary AI backend with **MCP tool calling support**
- **Ollama** (local LLM) - for local model testing

The bot maintains conversation history with automatic summarization when the history exceeds 20 messages. It features configurable system prompts, temperature settings, and **integration with Model Context Protocol (MCP)** for external tool usage.

### MCP Integration (NEW)

The bot now supports **function calling** through MCP server "everything", allowing GigaChat to:
- Fetch real-time data from the internet
- Search for information
- Work with files
- Store and retrieve memory between sessions

See [MCP_INTEGRATION.md](MCP_INTEGRATION.md) for detailed documentation.

## Build and Run Commands

```bash
# Build the project
./gradlew build

# Run the application
./gradlew run

# Clean build artifacts
./gradlew clean
```

## Core Architecture

### Entry Point
- `Main.kt` - Application entry point that:
  - Initializes HTTP client with SSL certificate verification disabled (required for GigaChat's certificate issues)
  - Sets up GigaChat and Ollama clients
  - Configures Telegram bot with command handlers
  - Starts a health check server on port 12222
  - Manages conversation history with system prompts

### AI Clients
- `GigaChatClient.kt` - Handles GigaChat API communication with:
  - Token-based authentication with automatic refresh on 401 errors
  - Thread-safe token management using Mutex
  - **Function calling support** - передача списка функций в запросах
  - OAuth token endpoint: `https://ngw.devices.sberbank.ru:9443/api/v2/oauth`
  - Chat completions endpoint: `{baseUrl}/api/v1/chat/completions`

- `OllamaClient.kt` - Local LLM client for testing:
  - Uses hardcoded `llama3.2:1b` model
  - Endpoint: `http://localhost:11434/api/chat`

### MCP Integration
- `McpService.kt` - MCP client for managing connection to MCP server:
  - Launches npx process with `@modelcontextprotocol/server-everything`
  - Provides `listTools()` and `callTool()` methods
  - Thread-safe with Mutex protection

- `ToolCallHandler.kt` - Tool calling orchestrator:
  - Converts MCP tools to GigaChat function format
  - Executes function calls and formats results with visual markers
  - Handles JSON argument parsing and error handling

### Bot Logic
- `ChatOrchestrator.kt:processMessage()` - Core message orchestrator that:
  - Adds user messages to conversation history
  - Gets available MCP functions if enabled
  - **Implements tool calling loop** (up to 5 iterations):
    - Sends request with functions to GigaChat
    - Detects `finish_reason: "function_call"`
    - Executes tool via MCP
    - Adds tool result to history
    - Continues until final answer
  - Truncates responses to 3800 characters (Telegram limit workaround)
  - Triggers automatic summarization when history exceeds 20 messages
  - Summarization uses temperature 0.0 and condenses history to under 3000 characters
  - Formats response with visual MCP tool markers

- `TelegramBotService.kt` - Telegram bot handlers:
  - Command handlers for /start, /changeRole, /changeT, /clearChat
  - **New commands**: /enableMcp, /listTools
  - Message handler that calls ChatOrchestrator
  - Visual formatting for MCP tool usage

### Data Models
- `GigaModels.kt` - GigaChat request/response models with:
  - Token usage tracking
  - **Function calling models**: GigaChatFunction, GigaChatFunctionParameters, GigaChatFunctionCall
  - Support for `functions` and `functionCall` in requests
  - Support for `functionCall` in messages
- `OllamaModels.kt` - Ollama request/response models

## Bot Commands

- `/start` - Bot initialization with command list
- `/changeRole <text>` - Updates the system prompt
- `/changeT <float>` - Changes the model temperature parameter (0.0 - 1.0)
- `/clearChat` - Clears conversation history
- **`/enableMcp`** - Activates MCP mode with tool access (NEW)
- **`/listTools`** - Shows available MCP tools (NEW)
- `/destroyContext` - Legacy command for context overflow testing (deprecated)

## Configuration

Authentication tokens and API keys must be set in Main.kt:
```kotlin
val telegramToken = "" // Line 53
val gigaAuthKey = "" // Line 55
```

Default settings:
- GigaChat model: "GigaChat"
- Temperature: 0.87
- Base URL: "https://gigachat.devices.sberbank.ru"
- History limit: 20 messages before summarization

## System Prompts

Four predefined system prompts are defined in Main.kt:
- `JsonRole` - For structured JSON responses
- `AssistantRole` - Expert assistant with clarifying questions
- `SingleRole` - ESP32 microcontroller systems expert (default)
- **`McpEnabledRole`** - AI assistant with MCP tool access instructions (NEW)

## Key Implementation Details

- SSL verification is disabled in the HTTP client due to GigaChat certificate issues
- Token management uses Mutex for thread-safe access with automatic refresh
- Conversation history is preserved and automatically summarized to maintain context
- Responses are truncated to 3800 chars to fit Telegram's message limits
- Health check endpoint at `http://localhost:12222/` returns "Bot OK"
- **MCP server** is initialized on startup via npx subprocess
- **Tool calling loop** with max 5 iterations prevents infinite loops
- **Visual markers** clearly show when MCP tools are used: `[MCP Tool: toolname]`
- Function calling uses `functionCall: "auto"` mode for automatic tool selection

## Dependencies

```kotlin
// Key dependencies in build.gradle.kts
implementation("io.ktor:ktor-client-core-jvm:3.3.0")
implementation("io.github.kotlin-telegram-bot.kotlin-telegram-bot:telegram:6.1.0")
implementation("io.modelcontextprotocol:kotlin-sdk:0.8.3") // NEW: MCP support
```

## Testing MCP Integration

1. Start the bot: `./gradlew run`
2. Send `/start` to get command list
3. Send `/listTools` to see available MCP tools
4. Send `/enableMcp` to activate tool access
5. Ask: "Какая погода в Москве?" to trigger fetch tool
6. Observe visual markers showing tool usage

## Troubleshooting

- If MCP tools don't work, check that Node.js and npx are installed
- Use `/listTools` to verify MCP server is running
- MCP server logs appear in console during bot startup
- For function calling, ensure you're using GigaChat-Pro or GigaChat-Plus model
