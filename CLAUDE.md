# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.


I need you to run with java version tools located at /Users/dmitriikonovalov/Library/Java/JavaVirtualMachines/openjdk-17.0.1

## Project Overview

TeleGaGa is a Telegram bot written in Kotlin that interfaces with two LLM providers:
- **GigaChat** (Sberbank's LLM service) - the primary AI backend with **MCP tool calling support**
- **Ollama** (local LLM) - for local model testing

The bot maintains conversation history with automatic summarization when the history exceeds 20 messages. It features configurable system prompts, temperature settings, and **integration with Model Context Protocol (MCP)** for external tool usage.

### MCP Integration

The bot supports **function calling** through **3 MCP servers** via **Streamable HTTP**, allowing GigaChat to:
- **get_weather** - Get current weather for any city using wttr.in (mcp-weather-server)
- **create_reminder** - Create reminders with automatic date handling (mcp-reminders-server)
- **get_reminders** - Retrieve reminders by date range (mcp-reminders-server)
- **delete_reminder** - Mark reminders as completed (mcp-reminders-server)
- **get_chuck_norris_joke** - Get Chuck Norris jokes (English, LLM translates to Russian) (mcp-chuck-server)

**Architecture**: All servers run on localhost (ports 3001-3003), auto-started by bot, using Streamable HTTP protocol (MCP 2024-11-05).

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
- `HttpMcpService.kt` - HTTP MCP client for managing multiple MCP servers:
  - **Protocol**: Streamable HTTP (MCP 2024-11-05)
  - **Manages 3 servers**: datetime (port 3001), reminders (port 3002), chuck (port 3003)
  - Automatically launches Node.js servers via ProcessBuilder
  - Creates HTTP sessions for each server (mcp-session-id header)
  - Aggregates tools from all servers into single list
  - Provides `listTools()` and `callTool()` methods
  - Thread-safe with Mutex protection
  - Health checks and graceful shutdown

- `ToolCallHandler.kt` - Tool calling orchestrator:
  - Converts HttpMcpService.Tool to GigaChat function format
  - Executes function calls and returns results as JSON
  - Handles JSON argument parsing and error handling

### Bot Logic
- `ChatOrchestrator.kt:processMessage()` - Core message orchestrator that:
  - Adds user messages to conversation history
  - Gets available MCP functions if enabled
  - **Auto-injects current date/time** (Europe/Moscow timezone) into system prompt when MCP enabled
    - Ensures LLM always has accurate date for "today", "tomorrow" calculations
    - Fixes issue where LLM uses outdated date information
  - **Implements tool calling loop** (up to 5 iterations):
    - Sends request with functions to GigaChat
    - Detects `finish_reason: "function_call"`
    - Executes tool via MCP
    - Adds tool result to history
    - Continues until final answer
  - Truncates responses to 3800 characters (Telegram limit workaround)
  - Triggers automatic summarization when history exceeds 20 messages
  - Summarization uses temperature 0.0 and condenses history to under 3000 characters
  - Returns clean responses without MCP markers

- `TelegramBotService.kt` - Telegram bot handlers:
  - Command handlers for /start, /changeRole, /changeT, /clearChat
  - **New commands**: /enableMcp, /listTools
  - Message handler that calls ChatOrchestrator
  - Clean output without visual headers or markers

- `ReminderScheduler.kt` - Daily reminder scheduler:
  - Checks every minute for chats with reminderTime set
  - Sends daily reminders at configured time
  - **Includes weather for St. Petersburg** via get_weather MCP tool
  - **Includes translated Chuck Norris joke** (LLM translates from English)
  - Uses ChatOrchestrator with MCP to fetch reminders, weather, and jokes
  - Tracks lastReminderSent to prevent duplicates

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
- **`/enableMcp`** - Activates MCP mode (only needed for existing chats; new chats have MCP by default)
- **`/listTools`** - Shows available MCP tools
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
- `SingleRole` - ESP32 microcontroller systems expert
- **`McpEnabledRole`** - AI assistant with MCP tool access (default):
  - Lists all 5 available MCP tools (weather, reminders x3, chuck joke)
  - **Auto-enhanced** with current date/time (Europe/Moscow) by ChatOrchestrator
  - Instructions to translate Chuck Norris jokes from English to Russian
  - Examples of correct dueDate format (YYYY-MM-DD only)

## Key Implementation Details

- SSL verification is disabled in the HTTP client due to GigaChat certificate issues
- Token management uses Mutex for thread-safe access with automatic refresh
- Conversation history is preserved and automatically summarized to maintain context
- Responses are truncated to 3800 chars to fit Telegram's message limits
- Health check endpoint at `http://localhost:12222/` returns "Bot OK"
- **3 MCP servers** auto-started via ProcessBuilder on ports 3001-3003
- **Streamable HTTP protocol** (MCP 2024-11-05) with session management
- **Tool calling loop** with max 5 iterations prevents infinite loops
- **Clean output** - MCP tools work seamlessly without visual markers or headers
- Function calling uses `functionCall: "auto"` mode for automatic tool selection
- **Automatic date context injection** - current date/time auto-added to system prompt when MCP enabled (fixes "today" calculation issues)
- **Morning reminders** include weather (St. Petersburg) and translated Chuck Norris jokes

## Dependencies

```kotlin
// Key dependencies in build.gradle.kts
implementation("io.ktor:ktor-client-core-jvm:3.3.0")
implementation("io.github.kotlin-telegram-bot.kotlin-telegram-bot:telegram:6.1.0")
// Note: MCP SDK removed - using custom HttpMcpService with Streamable HTTP
```

## MCP Server Setup

Each MCP server requires dependencies:
```bash
cd mcp-weather-server && npm install
cd mcp-reminders-server && npm install
cd mcp-chuck-server && npm install
```

Servers are auto-started by the bot on ports 3001-3003.

## Testing MCP Integration

**MCP режим активен по умолчанию для всех новых чатов!**

1. Start the bot: `./gradlew run`
2. Send `/start` to get command list
3. Send `/listTools` to see all 5 MCP tools
4. Test weather: "Какая погода в Санкт-Петербурге?"
5. Test reminders: "Напомни мне завтра в 10:00 позвонить маме"
6. Test joke: "Расскажи шутку про Чака Норриса" (will be translated to Russian)

Note: For existing chats created before this change, use `/enableMcp` to activate MCP tools, or `/clearChat` to start fresh.

## Troubleshooting

- If MCP tools don't work, check that Node.js is installed
- Use `/listTools` to verify all 3 MCP servers are running (should show 5 tools)
- MCP server logs appear in console during bot startup
- For function calling, ensure you're using GigaChat model with function support
- Check that ports 3001-3003 are not in use by other processes
- Verify npm dependencies are installed in each mcp-*-server directory
