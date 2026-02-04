# Chuck Norris MCP Server Integration

## Overview

TeleGaGa bot now integrates with a custom Chuck Norris MCP (Model Context Protocol) server that provides random Chuck Norris jokes through the `get_chuck_norris_joke` tool.

## Architecture

```
TeleGaGa/
├── mcp-chuck-server/           # Node.js MCP Server
│   ├── package.json            # Node.js dependencies
│   ├── index.js                # MCP server implementation
│   ├── README.md               # Server documentation
│   └── node_modules/           # Installed dependencies
└── src/main/kotlin/ru/dikoresearch/
    ├── infrastructure/mcp/
    │   └── McpService.kt       # MCP client that launches Node.js server
    ├── domain/
    │   ├── ChatOrchestrator.kt # Updated to support nullable McpService
    │   └── ToolCallHandler.kt  # Handles MCP tool calls
    └── Main.kt                 # Updated with graceful MCP initialization
```

## Components

### 1. Chuck Norris MCP Server (Node.js)

**Location**: `mcp-chuck-server/index.js`

**Features**:
- Implements MCP protocol using official `@modelcontextprotocol/sdk`
- Uses stdio transport for communication
- Fetches jokes from `https://geek-jokes.sameerkumar.website/api?format=json`
- Zero-configuration tool (no parameters needed)

**Tool Provided**:
- **Name**: `get_chuck_norris_joke`
- **Description**: Returns a random Chuck Norris joke
- **Parameters**: None
- **Output**: Text content with the joke

### 2. McpService (Kotlin)

**Location**: `src/main/kotlin/ru/dikoresearch/infrastructure/mcp/McpService.kt`

**Key Changes**:
- Launches Node.js process with `mcp-chuck-server/index.js`
- Uses `StdioClientTransport` for communication
- Validates server file and node_modules existence before starting
- Graceful error handling with informative messages

**Features**:
- Automatic process lifecycle management
- Thread-safe operations with Mutex
- Health checks via `isAvailable()`
- Clean shutdown with timeout handling

### 3. Main.kt Updates

**Location**: `src/main/kotlin/ru/dikoresearch/Main.kt`

**Changes**:
- MCP initialization wrapped in try-catch
- Falls back to no-MCP mode if server unavailable
- Provides helpful error messages with setup instructions
- Bot continues working even if MCP fails

### 4. ChatOrchestrator Updates

**Location**: `src/main/kotlin/ru/dikoresearch/domain/ChatOrchestrator.kt`

**Changes**:
- Accepts `McpService?` (nullable)
- Skips MCP functionality if service is null
- Tool calling loop works only when MCP is available

### 5. TelegramBotService Updates

**Location**: `src/main/kotlin/ru/dikoresearch/infrastructure/telegram/TelegramBotService.kt`

**Changes**:
- Accepts `McpService?` (nullable)
- `/listTools` command checks for MCP availability
- Shows helpful message if MCP is not available

## Setup Instructions

### 1. Install Node.js Dependencies

```bash
cd mcp-chuck-server
npm install
```

### 2. Build the Bot

```bash
./gradlew build
```

### 3. Run the Bot

```bash
./gradlew run
```

The bot will automatically:
1. Start the Chuck Norris MCP server as a subprocess
2. Connect to it via stdio
3. Register the `get_chuck_norris_joke` tool
4. Make it available to GigaChat through function calling

## Usage

### 1. Enable MCP Mode

Send `/enableMcp` command in Telegram to activate MCP mode for your chat.

### 2. List Available Tools

Send `/listTools` to see available MCP tools (should show `get_chuck_norris_joke`).

### 3. Ask for a Joke

Send any message asking for a Chuck Norris joke:
- "Tell me a Chuck Norris joke"
- "Give me a Chuck Norris joke"
- "Chuck Norris joke please"

The bot will:
1. Recognize the request needs the tool
2. Call `get_chuck_norris_joke` through MCP
3. Fetch a random joke from the API
4. Format and return it to you with MCP markers

### Example Interaction

```
User: Tell me a Chuck Norris joke

Bot: Использованы MCP инструменты:

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
[MCP Tool: get_chuck_norris_joke]
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

Chuck Norris can divide by zero.

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

Итоговый ответ:

Here's a Chuck Norris joke for you! 😄
```

## Error Handling

### If MCP Server Fails to Start

The bot logs the error and continues without MCP:

```
⚠️ Не удалось запустить Chuck Norris MCP сервер: MCP сервер не найден: ...
💡 Для работы с MCP установите зависимости:
   cd mcp-chuck-server && npm install
Бот продолжит работу без MCP функций
```

### If API is Unavailable

The tool returns an error message:
```
Error: Failed to fetch joke: ...
```

### If Tool is Called with Wrong Name

Returns:
```
Error: Unknown tool: <name>
```

## Graceful Degradation

The implementation follows a graceful degradation pattern:

1. **MCP Available**: Full functionality with tool calling
2. **MCP Unavailable**: Bot works normally without tool access
3. **MCP Fails During Runtime**: Bot logs error but continues serving requests

## Testing Checklist

- [x] MCP server starts successfully
- [x] Bot connects to MCP server
- [x] `/listTools` shows `get_chuck_norris_joke`
- [ ] Asking for a joke triggers the tool
- [ ] Joke is fetched and displayed correctly
- [ ] Visual markers show MCP tool usage
- [ ] Bot works without MCP if server not available
- [ ] Error handling works for API failures

## Technical Details

### Transport Protocol

- **Client → Server**: stdio (stdin/stdout)
- **Protocol**: JSON-RPC 2.0 (MCP standard)
- **Request/Response**: Async with coroutines

### API Endpoint

- **URL**: `https://geek-jokes.sameerkumar.website/api?format=json`
- **Response**: `{ "joke": "..." }`
- **Rate Limit**: None (public API)

### Dependencies

**Node.js (MCP Server)**:
- `@modelcontextprotocol/sdk` - Official MCP SDK
- `node-fetch` - HTTP client

**Kotlin (Bot)**:
- `io.modelcontextprotocol:kotlin-sdk:0.8.3` - MCP client

## Future Improvements

1. **Caching**: Cache jokes to reduce API calls
2. **More Tools**: Add weather, time, calculator tools
3. **Configuration**: Make API URL configurable
4. **Monitoring**: Add metrics for tool usage
5. **Testing**: Add integration tests for MCP flow

## Troubleshooting

### Bot Says "MCP сервис недоступен"

1. Check if node_modules are installed:
   ```bash
   ls mcp-chuck-server/node_modules
   ```

2. Try manual installation:
   ```bash
   cd mcp-chuck-server
   npm install
   ```

3. Verify Node.js is installed:
   ```bash
   node --version  # Should be >= 18
   ```

### Server Starts But No Jokes

1. Check server logs in bot console
2. Verify API is accessible:
   ```bash
   curl https://geek-jokes.sameerkumar.website/api?format=json
   ```

3. Check `/listTools` output - tool should be visible

### Process Doesn't Shut Down Cleanly

The bot has a 5-second graceful shutdown timeout, then force-kills the process. Check logs for any shutdown errors.

## Credits

- Chuck Norris API: https://geek-jokes.sameerkumar.website/
- MCP Protocol: https://modelcontextprotocol.io/
- Implementation: TeleGaGa Bot Team
