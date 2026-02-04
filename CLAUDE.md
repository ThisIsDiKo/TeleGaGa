# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.


I need you to run with java version tools located at /Users/dmitriikonovalov/Library/Java/JavaVirtualMachines/openjdk-17.0.1

## Project Overview

TeleGaGa is a Telegram bot written in Kotlin that interfaces with two LLM providers:
- **GigaChat** (Sberbank's LLM service) - the primary AI backend with **MCP tool calling support**
- **Ollama** (local LLM) - for local model testing

The bot maintains conversation history with automatic summarization when the history exceeds 20 messages. It features configurable system prompts, temperature settings, and **integration with Model Context Protocol (MCP)** for external tool usage.

### MCP Integration

The bot supports **function calling** through **4 MCP servers** via two protocols:

#### HTTP MCP Servers (Node.js)
- **get_weather** - Get current weather for any city using wttr.in (mcp-weather-server, port 3001)
- **create_reminder** - Create reminders with automatic date handling (mcp-reminders-server, port 3002)
- **get_reminders** - Retrieve reminders by date range (mcp-reminders-server, port 3002)
- **delete_reminder** - Mark reminders as completed (mcp-reminders-server, port 3002)
- **get_chuck_norris_joke** - Get Chuck Norris jokes (English, LLM translates to Russian) (mcp-chuck-server, port 3003)

#### Stdio MCP Servers (Python)
- **compose_create** - Create Docker Compose configuration for applications (mcp-server-docker)
- **compose_spec** - Show current Docker Compose configuration (mcp-server-docker)
- **compose_apply** - Apply Docker Compose configuration (start containers) (mcp-server-docker)
- **compose_down** - Stop and remove containers (mcp-server-docker)
- **docker_ps** - Show running Docker containers (mcp-server-docker)
- **docker_images** - Show available Docker images (mcp-server-docker)
- **docker_logs** - Show container logs (mcp-server-docker)

**Architecture**:
- HTTP servers run on localhost (ports 3001-3003), auto-started by bot, using Streamable HTTP protocol (MCP 2024-11-05)
- Stdio server (mcp-server-docker) communicates via JSON-RPC over stdin/stdout protocol
- Docker MCP server requires Docker to be running and pipx-installed mcp-server-docker package

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
- `HttpMcpService.kt` - HTTP MCP client for Node.js servers:
  - **Protocol**: Streamable HTTP (MCP 2024-11-05)
  - **Manages 3 servers**: weather (port 3001), reminders (port 3002), chuck (port 3003)
  - Automatically launches Node.js servers via ProcessBuilder
  - Creates HTTP sessions for each server (mcp-session-id header)
  - Aggregates tools from all servers into single list
  - Provides `listTools()` and `callTool()` methods
  - Thread-safe with Mutex protection
  - Health checks and graceful shutdown

- `StdioMcpService.kt` - Stdio MCP client for Python servers:
  - **Protocol**: JSON-RPC over stdin/stdout (MCP 2024-11-05)
  - **Manages Docker MCP server**: mcp-server-docker (Python, pipx-installed)
  - Launches Python process and communicates via BufferedReader/BufferedWriter
  - Sends JSON-RPC requests (initialize, tools/list, tools/call)
  - Handles notifications (notifications/initialized)
  - Thread-safe with Mutex protection
  - Graceful process termination on shutdown

- `ToolCallHandler.kt` - Tool calling orchestrator:
  - Supports both HTTP and Stdio MCP services
  - Converts MCP tools to GigaChat function format (from both protocols)
  - Routes function calls to appropriate MCP service
  - Executes function calls and returns results as JSON
  - Handles JSON argument parsing and error handling

### RAG (Retrieval-Augmented Generation)
- `RagService.kt` - **NEW (Day 18)** RAG service with relevance filtering:
  - `findRelevantChunks()` - Vector search for top-K most relevant chunks using cosine similarity
  - **`findRelevantChunksWithFilter()`** - **NEW** Filters chunks by relevance threshold (0.0-1.0)
  - `formatContext()` - Formats chunks into LLM-friendly context
  - **`RagSearchResult`** - **NEW** Data class with filtering statistics (original count, filtered count, avg/min/max relevance)
  - Uses Ollama's `nomic-embed-text` model for embeddings (768-dimensional vectors)
  - Stores embeddings in `embeddings_store/<filename>.embeddings.json`
  - **Relevance filtering reduces noise by 20-30%** and improves answer quality

- `EmbeddingService.kt` - Embedding generation service:
  - Communicates with Ollama's embedding API (`http://localhost:11434/api/embeddings`)
  - `generateEmbeddings()` - Generates embeddings for single text
  - `generateEmbeddingsForMarkdown()` - Preprocesses Markdown and splits into chunks
  - Uses `TextChunker` for intelligent text splitting (respects paragraphs, code blocks)

- `ChatSettingsManager.kt` - **UPDATED (Day 18)** Persistent settings storage:
  - **New fields**: `ragRelevanceThreshold` (Float, default 0.5), `ragEnabled` (Boolean), `ragTopK` (Int)
  - Thread-safe storage using Mutex per chatId
  - JSON-based persistence in `chat_settings/<chatId>_settings.json`

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
  - **MCP commands**: /enableMcp, /listTools
  - **RAG commands**: /createEmbeddings, /testRag
  - **NEW RAG commands (Day 18)**: /compareRag, /setThreshold
    - `/compareRag` - Compares 3 approaches (no RAG, RAG without filter, RAG with filter)
    - `/setThreshold` - Configures relevance threshold for filtering (0.0-1.0)
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

### General Commands
- `/start` - Bot initialization with command list
- `/changeRole <text>` - Updates the system prompt
- `/changeT <float>` - Changes the model temperature parameter (0.0 - 1.0)
- `/clearChat` - Clears conversation history
- `/destroyContext` - Legacy command for context overflow testing (deprecated)

### MCP Commands
- **`/enableMcp`** - Activates MCP mode (only needed for existing chats; new chats have MCP by default)
- **`/listTools`** - Shows available MCP tools

### RAG Commands
- `/createEmbeddings` - Creates embeddings from rag_docs/readme.md using Ollama (nomic-embed-text)
- `/testRag <question>` - Compares answers with RAG and without RAG
- **`/compareRag <question>`** - **NEW (Day 18)** Compares 3 approaches: without RAG, with RAG (top-5, no filter), with RAG + relevance filter
- **`/setThreshold <0.0-1.0>`** - **NEW (Day 18)** Configures relevance threshold for RAG filtering (default: 0.5)
  - 0.3-0.4 (low) - more results, may include noise
  - 0.5-0.6 (medium) - balanced approach (recommended)
  - 0.7-0.8 (high) - only highly relevant chunks

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
  - Lists all 12 available MCP tools (weather, reminders x3, chuck joke, docker x7)
  - **Auto-enhanced** with current date/time (Europe/Moscow) by ChatOrchestrator
  - Instructions to translate Chuck Norris jokes from English to Russian
  - Examples of correct dueDate format (YYYY-MM-DD only)
  - Docker usage instructions for Caddy server and other containers

## Key Implementation Details

- SSL verification is disabled in the HTTP client due to GigaChat certificate issues
- Token management uses Mutex for thread-safe access with automatic refresh
- Conversation history is preserved and automatically summarized to maintain context
- Responses are truncated to 3800 chars to fit Telegram's message limits
- Health check endpoint at `http://localhost:12222/` returns "Bot OK"
- **Dual MCP protocol support**:
  - **HTTP MCP**: 3 Node.js servers on ports 3001-3003 (Streamable HTTP, MCP 2024-11-05)
  - **Stdio MCP**: 1 Python server via stdin/stdout (JSON-RPC, MCP 2024-11-05)
- All MCP servers auto-started via ProcessBuilder
- **Tool calling loop** with max 5 iterations prevents infinite loops
- **Clean output** - MCP tools work seamlessly without visual markers or headers
- Function calling uses `functionCall: "auto"` mode for automatic tool selection
- **Automatic date context injection** - current date/time auto-added to system prompt when MCP enabled (fixes "today" calculation issues)
- **Morning reminders** include weather (St. Petersburg) and translated Chuck Norris jokes
- **Docker integration** - requires Docker daemon running and mcp-server-docker installed via pipx
- **RAG relevance filtering (Day 18)**:
  - Two-stage filtering: vector search → relevance threshold filter
  - Default threshold: 0.5 (50% cosine similarity)
  - Filters out 20-30% of low-relevance chunks, improving answer quality
  - User-configurable threshold via `/setThreshold` command
  - Filtering adds < 10ms overhead
  - Persistent threshold storage per chat

## Dependencies

```kotlin
// Key dependencies in build.gradle.kts
implementation("io.ktor:ktor-client-core-jvm:3.3.0")
implementation("io.github.kotlin-telegram-bot.kotlin-telegram-bot:telegram:6.1.0")
// Note: Using custom HttpMcpService (HTTP) and StdioMcpService (Stdio) for MCP integration
```

## MCP Server Setup

### HTTP MCP Servers (Node.js)
Each HTTP MCP server requires npm dependencies:
```bash
cd mcp-weather-server && npm install
cd mcp-reminders-server && npm install
cd mcp-chuck-server && npm install
```
Servers are auto-started by the bot on ports 3001-3003.

### Stdio MCP Server (Python - Docker)
Install Docker MCP server via pipx:
```bash
brew install pipx  # If not installed
pipx install mcp-server-docker
pipx ensurepath
```

**Requirements**:
- Docker Desktop must be installed and running
- Docker socket must be accessible at default location
- Python 3.12+ required for mcp-server-docker

## Testing MCP Integration

**MCP режим активен по умолчанию для всех новых чатов!**

1. **Start Docker** (for Docker MCP tools):
   ```bash
   # macOS with Docker Desktop
   open -a Docker
   ```

2. **Start the bot**:
   ```bash
   ./gradlew run
   ```

3. Send `/start` to get command list
4. Send `/listTools` to see all 12 MCP tools
5. Test HTTP MCP tools:
   - Weather: "Какая погода в Санкт-Петербурге?"
   - Reminders: "Напомни мне завтра в 10:00 позвонить маме"
   - Joke: "Расскажи шутку про Чака Норриса" (will be translated to Russian)
6. Test Stdio MCP tools (Docker):
   - Start Caddy: "Запусти Caddy сервер"
   - Check containers: "Покажи запущенные контейнеры"
   - Stop Caddy: "Останови Caddy"

Note: For existing chats created before this change, use `/enableMcp` to activate MCP tools, or `/clearChat` to start fresh.

## Troubleshooting

### HTTP MCP Servers
- If MCP tools don't work, check that Node.js is installed
- Check that ports 3001-3003 are not in use by other processes
- Verify npm dependencies are installed in each mcp-*-server directory
- MCP server logs appear in console during bot startup

### Stdio MCP Server (Docker)
- Ensure Docker Desktop is running (`docker ps` should work)
- Verify mcp-server-docker is installed: `which mcp-server-docker`
- Check pipx installation: `pipx list | grep mcp-server-docker`
- Docker socket location: `/var/run/docker.sock` or `~/.docker/run/docker.sock`
- If Docker tools fail, check bot console for Python traceback

### General
- Use `/listTools` to verify servers are running (should show 12 tools total)
- For function calling, ensure you're using GigaChat model with function support
- Check bot console for detailed error messages and MCP communication logs
