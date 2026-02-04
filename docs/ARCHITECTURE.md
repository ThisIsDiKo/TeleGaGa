# Архитектура TeleGaGa

## Обзор

TeleGaGa - это Telegram бот на Kotlin, использующий GigaChat в качестве основного AI бэкенда. Проект построен по принципам Clean Architecture с четким разделением на слои.

## Структура проекта

```
src/main/kotlin/
├── GigaModels.kt                    # Модели данных GigaChat API
├── OllamaModels.kt                  # Модели данных Ollama API
└── ru/dikoresearch/
    ├── Main.kt                      # Точка входа приложения
    ├── domain/                      # Бизнес-логика (Domain Layer)
    │   └── ChatOrchestrator.kt      # Оркестратор обработки сообщений
    └── infrastructure/              # Инфраструктурный слой
        ├── config/
        │   └── ConfigService.kt     # Управление конфигурацией
        ├── http/
        │   ├── GigaChatClient.kt    # HTTP клиент для GigaChat API
        │   └── OllamaClient.kt      # HTTP клиент для Ollama API
        ├── mcp/
        │   └── McpService.kt        # Управление MCP (Model Context Protocol)
        ├── persistence/
        │   └── ChatHistoryManager.kt # Сохранение истории чатов
        └── telegram/
            └── TelegramBotService.kt # Telegram бот интерфейс
```

## Слои архитектуры

### 1. Entry Point (Main.kt)

**Ответственность:** Инициализация и сборка всех компонентов приложения

**Ключевые функции:**
- Загрузка конфигурации через ConfigService
- Создание HTTP клиента с отключенной SSL-проверкой (для GigaChat)
- Инициализация всех сервисов в правильном порядке
- Запуск Health Check сервера (порт 12222)
- Graceful shutdown при завершении

**Системные промпты:**
- `JsonRole` - для структурированных JSON ответов
- `AssistantRole` - эксперт-консультант
- `SingleRole` - эксперт по ESP32 (используется по умолчанию)

### 2. Domain Layer (Бизнес-логика)

#### ChatOrchestrator

**Ответственность:** Чистая бизнес-логика обработки сообщений без зависимости от внешних frameworks

**Ключевые методы:**
- `processMessage()` - обрабатывает сообщение пользователя и возвращает ответ
- `updateSystemRole()` - обновляет системный промпт для чата
- `clearHistory()` - очищает историю чата

**Особенности:**
- Независим от Telegram API (принимает простые типы: Long, String)
- Автоматическая суммаризация истории при превышении 20 сообщений
- Обрезает ответы до 3800 символов (лимит Telegram)
- Возвращает структурированный результат (ChatResponse)

**Модели данных:**
- `ChatResponse` - результат обработки сообщения
- `TokenUsage` - информация об использовании токенов
- `ChatException` - исключение при работе с чатом

### 3. Infrastructure Layer (Инфраструктура)

#### ConfigService

**Ответственность:** Безопасное управление конфигурацией

**Функции:**
- Загрузка параметров из `config.properties`
- Валидация обязательных параметров
- Создание шаблона при отсутствии конфигурации
- Защита токенов (не коммитятся в git)

**Параметры:**
- `telegram.token` - токен Telegram бота
- `gigachat.authKey` - ключ авторизации GigaChat
- `gigachat.baseUrl` - базовый URL GigaChat API
- `gigachat.model` - название модели

#### GigaChatClient

**Ответственность:** Взаимодействие с GigaChat API

**Особенности:**
- Автоматическое получение и обновление OAuth токена
- Thread-safe управление токеном через Mutex
- Автоматический retry при 401 (Unauthorized)
- Endpoints:
  - OAuth: `https://ngw.devices.sberbank.ru:9443/api/v2/oauth`
  - Chat: `{baseUrl}/api/v1/chat/completions`

#### OllamaClient

**Ответственность:** Взаимодействие с локальной Ollama моделью

**Особенности:**
- Использует модель `llama3.2:1b`
- Endpoint: `http://localhost:11434/api/chat`
- Для локального тестирования

#### ChatHistoryManager

**Ответственность:** Персистентность истории чатов

**Особенности:**
- Хранение в JSON файлах: `chat_histories/{chatId}.json`
- Автоматическое создание директории
- Методы: load, save, clear, hasHistory

#### McpService

**Ответственность:** Управление Model Context Protocol процессом

**Функции:**
- Инициализация MCP сервера
- Получение списка доступных инструментов
- Вызов MCP инструментов
- Graceful shutdown

#### TelegramBotService

**Ответственность:** Изоляция всей Telegram-специфичной логики

**Команды:**
- `/start` - приветствие и список команд
- `/listTools` - список MCP инструментов
- `/changeRole <text>` - изменить системный промпт
- `/changeT <float>` - изменить температуру модели
- `/clearChat` - очистить историю
- `/destroyContext` - информация о старом методе

**Особенности:**
- Использует `applicationScope.launch {}` для suspend функций
- Хранит температуру для каждого чата отдельно
- Делегирует обработку сообщений в ChatOrchestrator
- Безопасная отправка сообщений с обработкой ошибок

## Потоки данных

### Обработка сообщения пользователя

```
User Message (Telegram)
    ↓
TelegramBotService.setupMessageHandlers()
    ↓
ChatOrchestrator.processMessage()
    ↓
ChatHistoryManager.loadHistory()
    ↓
GigaChatClient.chatCompletion()
    ↓
ChatHistoryManager.saveHistory()
    ↓
[Суммаризация если history.size > 20]
    ↓
ChatResponse → TelegramBotService
    ↓
bot.sendMessage() → User
```

### Суммаризация истории

```
history.size > 20
    ↓
ChatOrchestrator.summarizeHistory()
    ↓
GigaChatClient.chatCompletion(temperature=0.0)
    ↓
Создание нового системного промпта с контекстом
    ↓
history.clear()
    ↓
history.add(newSystemPrompt)
    ↓
ChatHistoryManager.saveHistory()
```

## Ключевые принципы

### 1. Separation of Concerns
Каждый класс имеет одну четко определенную ответственность.

### 2. Dependency Injection
Все зависимости передаются через конструктор, что упрощает тестирование.

### 3. Clean Architecture
Domain слой независим от инфраструктуры и frameworks.

### 4. Thread Safety
- GigaChatClient использует Mutex для управления токеном
- ApplicationScope для управления корутинами

### 5. Error Handling
- Использование try-catch на границах слоев
- Понятные сообщения об ошибках
- Graceful degradation

### 6. Security
- Конфигурация в .gitignore
- Маскирование токенов в логах
- SSL (отключен для GigaChat из-за их проблем)

## Конфигурация

### Системные требования
- Java 17+
- Kotlin 1.9+
- Gradle 8.5+

### Переменные окружения
```bash
JAVA_HOME=/path/to/java17
```

### Порты
- `12222` - Health Check сервер

### Директории
- `chat_histories/` - хранение истории чатов (создается автоматически)

## Расширяемость

### Добавление нового AI провайдера

1. Создайте клиент в `infrastructure/http/`
2. Добавьте инициализацию в Main.kt
3. (Опционально) Добавьте поддержку в ChatOrchestrator

### Добавление нового UI интерфейса (например, REST API)

1. Создайте сервис в `infrastructure/api/`
2. Используйте ChatOrchestrator напрямую
3. Добавьте инициализацию в Main.kt

### Добавление новой команды

1. Откройте TelegramBotService.kt
2. Добавьте обработчик в `setupCommands()`
3. При необходимости добавьте метод в ChatOrchestrator

## Тестирование

Благодаря Clean Architecture каждый слой можно тестировать независимо:

- **Domain Layer:** Чистая логика, легко тестируется
- **Infrastructure:** Mock HTTP клиенты, FileSystem
- **Integration:** Полный flow с реальными сервисами

## Будущие улучшения

- [ ] Интеграция MCP tools с GigaChat function calling
- [ ] REST API интерфейс (параллельно с Telegram)
- [ ] Metrics и мониторинг
- [ ] Unit и Integration тесты
- [ ] Docker контейнеризация
- [ ] CI/CD pipeline
