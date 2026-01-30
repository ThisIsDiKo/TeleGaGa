#!/usr/bin/env node

import express from 'express';
import { v4 as uuidv4 } from 'uuid';
import fetch from 'node-fetch';

const app = express();
const PORT = 3001;

app.use(express.json());

// Session storage
const sessions = new Map();

// Tool definitions
const TOOLS = [
  {
    name: 'get_weather',
    description: 'Получить текущую погоду для указанного города. Использует бесплатный сервис wttr.in',
    inputSchema: {
      type: 'object',
      properties: {
        city: {
          type: 'string',
          description: 'Название города на русском или английском (например: "Санкт-Петербург", "Москва", "London")'
        },
        lang: {
          type: 'string',
          description: 'Язык ответа: "ru" для русского, "en" для английского. По умолчанию "ru"',
          default: 'ru'
        }
      },
      required: ['city']
    }
  }
];

// Tool execution
async function executeGetWeather(args) {
  const { city, lang = 'ru' } = args;

  if (!city) {
    return {
      content: [
        {
          type: 'text',
          text: JSON.stringify({ error: 'Параметр city обязателен' })
        }
      ],
      isError: true
    };
  }

  try {
    // wttr.in API: format=j1 для JSON, lang для языка
    const url = `https://wttr.in/${encodeURIComponent(city)}?format=j1&lang=${lang}`;

    const response = await fetch(url, {
      headers: {
        'User-Agent': 'MCP-Weather-Server/1.0'
      }
    });

    if (!response.ok) {
      return {
        content: [
          {
            type: 'text',
            text: JSON.stringify({
              error: `Не удалось получить погоду для города "${city}". Проверьте название города.`
            })
          }
        ],
        isError: true
      };
    }

    const data = await response.json();

    // Извлекаем нужную информацию
    const currentCondition = data.current_condition[0];
    const weatherDesc = currentCondition.lang_ru?.[0]?.value || currentCondition.weatherDesc[0].value;

    const weatherInfo = {
      location: data.nearest_area[0].areaName[0].value,
      region: data.nearest_area[0].region?.[0]?.value || '',
      country: data.nearest_area[0].country[0].value,
      temperature: `${currentCondition.temp_C}°C`,
      feels_like: `${currentCondition.FeelsLikeC}°C`,
      description: weatherDesc,
      humidity: `${currentCondition.humidity}%`,
      wind_speed: `${currentCondition.windspeedKmph} км/ч`,
      wind_dir: currentCondition.winddir16Point,
      pressure: `${currentCondition.pressure} мбар`,
      visibility: `${currentCondition.visibility} км`,
      uv_index: currentCondition.uvIndex,
      observation_time: currentCondition.observation_time
    };

    return {
      content: [
        {
          type: 'text',
          text: JSON.stringify(weatherInfo, null, 2)
        }
      ]
    };
  } catch (error) {
    return {
      content: [
        {
          type: 'text',
          text: JSON.stringify({
            error: `Ошибка при получении погоды: ${error.message}`
          })
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
      name: 'mcp-weather-server',
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

  if (name === 'get_weather') {
    const result = await executeGetWeather(args);
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
    server: 'mcp-weather-server',
    sessions: sessions.size
  });
});

// Start server
app.listen(PORT, () => {
  console.log(`🌤️  MCP Weather Server running on http://localhost:${PORT}`);
  console.log(`   Health: http://localhost:${PORT}/health`);
  console.log(`   Protocol: Streamable HTTP (MCP 2024-11-05)`);
  console.log(`   Provider: wttr.in (no API key required)`);
});
