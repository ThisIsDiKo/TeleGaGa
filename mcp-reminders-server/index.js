#!/usr/bin/env node

import { Server } from '@modelcontextprotocol/sdk/server/index.js';
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js';
import {
  ListToolsRequestSchema,
  CallToolRequestSchema,
  ErrorCode,
  McpError
} from '@modelcontextprotocol/sdk/types.js';
import { v4 as uuidv4 } from 'uuid';
import fs from 'fs/promises';
import path from 'path';
import { fileURLToPath } from 'url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const STORAGE_DIR = path.join(__dirname, 'storage');

// Ensure storage directory exists
await fs.mkdir(STORAGE_DIR, { recursive: true });

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

// Create MCP server
const server = new Server(
  {
    name: 'mcp-reminders-server',
    version: '1.0.0',
  },
  {
    capabilities: {
      tools: {},
    },
  }
);

// List available tools
server.setRequestHandler(ListToolsRequestSchema, async () => {
  return {
    tools: [
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
    ]
  };
});

// Handle tool calls
server.setRequestHandler(CallToolRequestSchema, async (request) => {
  const { name, arguments: args } = request.params;

  try {
    if (name === 'create_reminder') {
      const { chatId, text, dueDate } = args;

      // Validate parameters
      if (!chatId || !text || !dueDate) {
        throw new McpError(
          ErrorCode.InvalidParams,
          'Отсутствуют обязательные параметры: chatId, text, dueDate'
        );
      }

      // Validate date format
      if (!isValidDate(dueDate)) {
        throw new McpError(
          ErrorCode.InvalidParams,
          `Неверный формат даты. Используйте YYYY-MM-DD (например, 2026-01-28)`
        );
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

    if (name === 'get_reminders') {
      const { chatId, dateFrom, dateTo } = args;

      if (!chatId) {
        throw new McpError(
          ErrorCode.InvalidParams,
          'Отсутствует обязательный параметр: chatId'
        );
      }

      // Validate date formats if provided
      if (dateFrom && !isValidDate(dateFrom)) {
        throw new McpError(
          ErrorCode.InvalidParams,
          `Неверный формат dateFrom. Используйте YYYY-MM-DD`
        );
      }
      if (dateTo && !isValidDate(dateTo)) {
        throw new McpError(
          ErrorCode.InvalidParams,
          `Неверный формат dateTo. Используйте YYYY-MM-DD`
        );
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

    if (name === 'delete_reminder') {
      const { chatId, reminderId } = args;

      if (!chatId || !reminderId) {
        throw new McpError(
          ErrorCode.InvalidParams,
          'Отсутствуют обязательные параметры: chatId, reminderId'
        );
      }

      // Load reminders
      const data = await loadReminders(chatId);

      // Find reminder
      const reminder = data.reminders.find(r => r.id === reminderId);

      if (!reminder) {
        throw new McpError(
          ErrorCode.InvalidParams,
          `Напоминание с ID ${reminderId} не найдено`
        );
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

    throw new McpError(
      ErrorCode.MethodNotFound,
      `Неизвестный инструмент: ${name}`
    );

  } catch (error) {
    if (error instanceof McpError) {
      throw error;
    }

    throw new McpError(
      ErrorCode.InternalError,
      `Ошибка выполнения: ${error.message}`
    );
  }
});

// Start server
async function main() {
  const transport = new StdioServerTransport();
  await server.connect(transport);
  console.error('MCP Reminders Server запущен');
}

main().catch((error) => {
  console.error('Критическая ошибка:', error);
  process.exit(1);
});
