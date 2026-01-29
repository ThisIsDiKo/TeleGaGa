#!/usr/bin/env node

import express from 'express';
import { v4 as uuidv4 } from 'uuid';

const PORT = 3003;
const app = express();

app.use(express.json());

// Session storage
const sessions = new Map();

// Chuck Norris joke API
const JOKE_API = "https://geek-jokes.sameerkumar.website/api?format=json";

/**
 * Fetches a random Chuck Norris joke from the API
 */
async function getChuckNorrisJoke() {
  try {
    const response = await fetch(JOKE_API);
    const data = await response.json();
    return data.joke || "Chuck Norris doesn't need jokes. Jokes need Chuck Norris.";
  } catch (error) {
    console.error("Error fetching Chuck Norris joke:", error);
    throw new Error(`Failed to fetch joke: ${error.message}`);
  }
}

// Tool definitions
const TOOLS = [
  {
    name: 'get_chuck_norris_joke',
    description: 'Returns a random Chuck Norris joke from the geek-jokes API. ' +
                 'No parameters required. Use when user asks for a Chuck Norris joke or wants to hear something funny.',
    inputSchema: {
      type: 'object',
      properties: {},
      required: []
    }
  }
];

// Tool execution
async function executeGetChuckNorrisJoke() {
  try {
    const joke = await getChuckNorrisJoke();
    return {
      content: [
        {
          type: 'text',
          text: joke
        }
      ]
    };
  } catch (error) {
    return {
      content: [
        {
          type: 'text',
          text: JSON.stringify({ error: error.message })
        }
      ],
      isError: true
    };
  }
}

// Streamable HTTP endpoints

// POST /mcp/v1/initialize - Create a new session
app.post('/mcp/v1/initialize', (req, res) => {
  const sessionId = uuidv4();
  sessions.set(sessionId, {
    createdAt: new Date(),
    clientInfo: req.body.clientInfo
  });

  res.json({
    protocolVersion: '2024-11-05',
    capabilities: {
      tools: {}
    },
    serverInfo: {
      name: 'mcp-chuck-server',
      version: '1.0.0'
    },
    sessionId: sessionId
  });
});

// POST /mcp/v1/tools/list - List available tools
app.post('/mcp/v1/tools/list', (req, res) => {
  const sessionId = req.headers['mcp-session-id'];

  if (!sessionId || !sessions.has(sessionId)) {
    return res.status(401).json({ error: 'Invalid or missing session' });
  }

  res.json({
    tools: TOOLS
  });
});

// POST /mcp/v1/tools/call - Execute a tool
app.post('/mcp/v1/tools/call', async (req, res) => {
  const sessionId = req.headers['mcp-session-id'];

  if (!sessionId || !sessions.has(sessionId)) {
    return res.status(401).json({ error: 'Invalid or missing session' });
  }

  const { name } = req.body;

  if (name === 'get_chuck_norris_joke') {
    const result = await executeGetChuckNorrisJoke();
    return res.json(result);
  }

  res.status(404).json({
    content: [
      {
        type: 'text',
        text: JSON.stringify({ error: `Unknown tool: ${name}` })
      }
    ],
    isError: true
  });
});

// GET /health - Health check
app.get('/health', (req, res) => {
  res.json({
    status: 'ok',
    server: 'mcp-chuck-server',
    sessions: sessions.size
  });
});

// Start server
app.listen(PORT, () => {
  console.log(`😄 MCP Chuck Norris Server running on http://localhost:${PORT}`);
  console.log(`   Health: http://localhost:${PORT}/health`);
  console.log(`   Protocol: Streamable HTTP (MCP 2024-11-05)`);
});
