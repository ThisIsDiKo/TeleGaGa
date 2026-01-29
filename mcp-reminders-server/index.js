#!/usr/bin/env node

import express from 'express';
import { v4 as uuidv4 } from 'uuid';
import fs from 'fs/promises';
import path from 'path';
import { fileURLToPath } from 'url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const STORAGE_DIR = path.join(__dirname, 'storage');
const PORT = 3002;

// Ensure storage directory exists
await fs.mkdir(STORAGE_DIR, { recursive: true });

const app = express();
app.use(express.json());

// Session storage
const sessions = new Map();

/**
 * Load reminders for a specific chat
 */
async function loadReminders(chatId) {
  const filePath = path.join(STORAGE_DIR, `${chatId}.json`);

  try {
    const data = await fs.readFile(filePath, 'utf-8');
    return JSON.parse(data);
  } catch (error) {
    if (error.code === 'ENOENT') {
      // File doesn't exist, return empty structure
      return {
        chatId: chatId,
        reminders: []
      };
    }
    throw error;
  }
}

/**
 * Save reminders for a specific chat
 */
async function saveReminders(chatId, data) {
  const filePath = path.join(STORAGE_DIR, `${chatId}.json`);
  await fs.writeFile(filePath, JSON.stringify(data, null, 2), 'utf-8');
}

/**
 * Validate date format (YYYY-MM-DD)
 */
function isValidDate(dateString) {
  const regex = /^\d{4}-\d{2}-\d{2}$/;
  if (!regex.test(dateString)) return false;

  const date = new Date(dateString);
  return date instanceof Date && !isNaN(date);
}

// Tool definitions
const TOOLS = [
  {
    name: 'create_reminder',
    description: 'Создает новое напоминание для пользователя',
    inputSchema: {
      type: 'object',
      properties: {
        chatId: {
          type: 'string',
          description: 'ID чата пользователя'
        },
        text: {
          type: 'string',
          description: 'Текст напоминания'
        },
        dueDate: {
          type: 'string',
          description: 'Дата напоминания в формате YYYY-MM-DD'
        }
      },
      required: ['chatId', 'text', 'dueDate']
    }
  },
  {
    name: 'get_reminders',
    description: 'Получает список напоминаний пользователя за указанный период',
    inputSchema: {
      type: 'object',
      properties: {
        chatId: {
          type: 'string',
          description: 'ID чата пользователя'
        },
        dateFrom: {
          type: 'string',
          description: 'Начальная дата в формате YYYY-MM-DD (опционально)'
        },
        dateTo: {
          type: 'string',
          description: 'Конечная дата в формате YYYY-MM-DD (опционально)'
        }
      },
      required: ['chatId']
    }
  },
  {
    name: 'delete_reminder',
    description: 'Удаляет (отмечает как выполненное) напоминание',
    inputSchema: {
      type: 'object',
      properties: {
        chatId: {
          type: 'string',
          description: 'ID чата пользователя'
        },
        reminderId: {
          type: 'string',
          description: 'UUID напоминания'
        }
      },
      required: ['chatId', 'reminderId']
    }
  }
];

// Tool execution functions
async function executeCreateReminder(args) {
  const { chatId, text, dueDate } = args;

  // Validate parameters
  if (!chatId || !text || !dueDate) {
    return {
      content: [
        {
          type: 'text',
          text: JSON.stringify({
            error: 'Отсутствуют обязательные параметры: chatId, text, dueDate'
          })
        }
      ],
      isError: true
    };
  }

  // Validate date format
  if (!isValidDate(dueDate)) {
    return {
      content: [
        {
          type: 'text',
          text: JSON.stringify({
            error: 'Неверный формат даты. Используйте YYYY-MM-DD (например, 2026-01-28)'
          })
        }
      ],
      isError: true
    };
  }

  // Load existing reminders
  const data = await loadReminders(chatId);

  // Create new reminder
  const newReminder = {
    id: uuidv4(),
    text: text,
    dueDate: dueDate,
    createdAt: new Date().toISOString(),
    completed: false
  };

  data.reminders.push(newReminder);
  await saveReminders(chatId, data);

  return {
    content: [
      {
        type: 'text',
        text: JSON.stringify({
          success: true,
          message: 'Напоминание создано',
          reminder: newReminder
        }, null, 2)
      }
    ]
  };
}

async function executeGetReminders(args) {
  const { chatId, dateFrom, dateTo } = args;

  if (!chatId) {
    return {
      content: [
        {
          type: 'text',
          text: JSON.stringify({
            error: 'Отсутствует обязательный параметр: chatId'
          })
        }
      ],
      isError: true
    };
  }

  // Validate date formats if provided
  if (dateFrom && !isValidDate(dateFrom)) {
    return {
      content: [
        {
          type: 'text',
          text: JSON.stringify({
            error: 'Неверный формат dateFrom. Используйте YYYY-MM-DD'
          })
        }
      ],
      isError: true
    };
  }
  if (dateTo && !isValidDate(dateTo)) {
    return {
      content: [
        {
          type: 'text',
          text: JSON.stringify({
            error: 'Неверный формат dateTo. Используйте YYYY-MM-DD'
          })
        }
      ],
      isError: true
    };
  }

  // Load reminders
  const data = await loadReminders(chatId);

  // Filter reminders
  let filteredReminders = data.reminders.filter(r => !r.completed);

  if (dateFrom) {
    filteredReminders = filteredReminders.filter(r => r.dueDate >= dateFrom);
  }
  if (dateTo) {
    filteredReminders = filteredReminders.filter(r => r.dueDate <= dateTo);
  }

  // Sort by date
  filteredReminders.sort((a, b) => a.dueDate.localeCompare(b.dueDate));

  return {
    content: [
      {
        type: 'text',
        text: JSON.stringify({
          success: true,
          count: filteredReminders.length,
          reminders: filteredReminders
        }, null, 2)
      }
    ]
  };
}

async function executeDeleteReminder(args) {
  const { chatId, reminderId } = args;

  if (!chatId || !reminderId) {
    return {
      content: [
        {
          type: 'text',
          text: JSON.stringify({
            error: 'Отсутствуют обязательные параметры: chatId, reminderId'
          })
        }
      ],
      isError: true
    };
  }

  // Load reminders
  const data = await loadReminders(chatId);

  // Find reminder
  const reminder = data.reminders.find(r => r.id === reminderId);

  if (!reminder) {
    return {
      content: [
        {
          type: 'text',
          text: JSON.stringify({
            error: `Напоминание с ID ${reminderId} не найдено`
          })
        }
      ],
      isError: true
    };
  }

  // Mark as completed
  reminder.completed = true;
  reminder.completedAt = new Date().toISOString();

  await saveReminders(chatId, data);

  return {
    content: [
      {
        type: 'text',
        text: JSON.stringify({
          success: true,
          message: 'Напоминание отмечено как выполненное',
          reminder: reminder
        }, null, 2)
      }
    ]
  };
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
      name: 'mcp-reminders-server',
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

  const { name, arguments: args } = req.body;

  try {
    let result;

    if (name === 'create_reminder') {
      result = await executeCreateReminder(args);
    } else if (name === 'get_reminders') {
      result = await executeGetReminders(args);
    } else if (name === 'delete_reminder') {
      result = await executeDeleteReminder(args);
    } else {
      result = {
        content: [
          {
            type: 'text',
            text: JSON.stringify({ error: `Unknown tool: ${name}` })
          }
        ],
        isError: true
      };
    }

    res.json(result);
  } catch (error) {
    res.status(500).json({
      content: [
        {
          type: 'text',
          text: JSON.stringify({ error: `Execution error: ${error.message}` })
        }
      ],
      isError: true
    });
  }
});

// GET /health - Health check
app.get('/health', (req, res) => {
  res.json({
    status: 'ok',
    server: 'mcp-reminders-server',
    sessions: sessions.size
  });
});

// Start server
app.listen(PORT, () => {
  console.log(`📝 MCP Reminders Server running on http://localhost:${PORT}`);
  console.log(`   Health: http://localhost:${PORT}/health`);
  console.log(`   Protocol: Streamable HTTP (MCP 2024-11-05)`);
  console.log(`   Storage: ${STORAGE_DIR}`);
});
