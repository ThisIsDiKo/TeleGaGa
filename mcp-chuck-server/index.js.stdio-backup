#!/usr/bin/env node

import { Server } from "@modelcontextprotocol/sdk/server/index.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import {
  CallToolRequestSchema,
  ListToolsRequestSchema,
} from "@modelcontextprotocol/sdk/types.js";

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

/**
 * Create and configure the MCP server
 */
const server = new Server(
  {
    name: "chuck-norris-mcp-server",
    version: "1.0.0",
  },
  {
    capabilities: {
      tools: {},
    },
  }
);

/**
 * Handler for listing available tools
 */
server.setRequestHandler(ListToolsRequestSchema, async () => {
  return {
    tools: [
      {
        name: "get_chuck_norris_joke",
        description: "Returns a random Chuck Norris joke from the geek-jokes API. " +
                     "No parameters required. Use when user asks for a Chuck Norris joke or wants to hear something funny.",
        inputSchema: {
          type: "object",
          properties: {},
          required: [],
        },
      },
    ],
  };
});

/**
 * Handler for tool execution
 */
server.setRequestHandler(CallToolRequestSchema, async (request) => {
  const { name } = request.params;

  if (name === "get_chuck_norris_joke") {
    try {
      const joke = await getChuckNorrisJoke();
      return {
        content: [
          {
            type: "text",
            text: joke,
          },
        ],
      };
    } catch (error) {
      return {
        content: [
          {
            type: "text",
            text: `Error: ${error.message}`,
          },
        ],
        isError: true,
      };
    }
  } else {
    throw new Error(`Unknown tool: ${name}`);
  }
});

/**
 * Start the server using stdio transport
 */
async function main() {
  const transport = new StdioServerTransport();
  await server.connect(transport);
  console.error("Chuck Norris MCP server running on stdio");
}

main().catch((error) => {
  console.error("Server error:", error);
  process.exit(1);
});
