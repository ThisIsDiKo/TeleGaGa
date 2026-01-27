# Chuck Norris MCP Server

A Model Context Protocol (MCP) server that provides Chuck Norris jokes.

## Features

- **Tool**: `get_chuck_norris_joke` - Returns random Chuck Norris jokes from the geek-jokes API
- **Transport**: stdio (standard input/output)
- **Zero configuration** - No parameters needed

## Installation

```bash
cd mcp-chuck-server
npm install
```

## Usage

### Standalone

```bash
npm start
```

### With MCP Client (via npx)

```bash
npx mcp-chuck-server
```

### Integration with TeleGaGa Bot

The bot's `McpService` will automatically connect to this server when configured with:

```kotlin
val process = ProcessBuilder(
    "node", "/path/to/mcp-chuck-server/index.js"
).start()
```

## API

### get_chuck_norris_joke

Fetches a random Chuck Norris joke.

**Parameters**: None

**Returns**: A text response with the joke

**Example**:
```
Chuck Norris can divide by zero.
```

## Testing

You can test the server manually using the MCP inspector or by running it directly:

```bash
node index.js
```

Then send MCP requests via stdin.

## Error Handling

- If the API is unavailable, returns an error message
- Handles network failures gracefully
- Logs errors to stderr

## Dependencies

- `@modelcontextprotocol/sdk` - Official MCP SDK
- `node-fetch` - HTTP client for fetching jokes

## License

Same as parent project
