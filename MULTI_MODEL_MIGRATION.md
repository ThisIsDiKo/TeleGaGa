# TeleGaGa Multi-Model Migration Guide

## Обзор изменений

Проект TeleGaGa был полностью переработан с мульти-сервисной архитектуры (GigaChat + MCP + RAG + GitHub) на упрощенную мульти-модельную архитектуру с тремя локальными моделями Ollama.

**Дата миграции**: 16 февраля 2026
**Версия**: 2.0 (Multi-Model)

---

## 🎯 Ключевые изменения

### До миграции
- **1 модель**: GigaChat (облачный API)
- **MCP интеграция**: 5 MCP серверов (weather, reminders, chuck jokes, docker, github)
- **RAG система**: Поиск по документации с embeddings
- **GitHub интеграция**: Анализ Pull Requests
- **Сложная архитектура**: 8 DI модулей, 11 команд

### После миграции
- **3 модели**: Gemma3 1B, Qwen3 1.7B, Llama3.2 3B (локальные)
- **Без MCP**: Вся MCP инфраструктура удалена
- **Без RAG**: Система embeddings и поиска удалена
- **Без GitHub**: Интеграция с GitHub удалена
- **Упрощенная архитектура**: 6 DI модулей, 4 команды

---

## 📊 Архитектурные изменения

### 1. Новая структура хранения истории

**До:**
```
chat_histories/
  └── {chatId}.json  # Одна история для GigaChat
```

**После:**
```
chat_histories/
  ├── {chatId}_gemma3.json   # История для Gemma3 1B
  ├── {chatId}_qwen3.json    # История для Qwen3 1.7B
  └── {chatId}_llama3.json   # История для Llama3.2 3B
```

### 2. Новая модель взаимодействия

**До:** Одно сообщение → Один ответ (GigaChat)

**После:** Одно сообщение → Три ответа (Gemma3 + Qwen3 + Llama3)

```
User: "Why does the sun shine?"
  ↓
Bot: [Gemma3 1B]     "The sun shines because..." ⏱️ 1234ms
Bot: [Qwen3 1.7B]    "The sun emits light..."    ⏱️ 987ms
Bot: [Llama3.2 3B]   "Solar fusion creates..."   ⏱️ 15060ms
```

### 3. Упрощенная конфигурация

**До (config.properties):**
```properties
telegram.token=...
gigachat.authKey=...
gigachat.baseUrl=...
gigachat.model=...
ollama.chatModel=...
ollama.embeddingModel=...
github.token=...
github.owner=...
github.repo=...
```

**После (config.properties):**
```properties
telegram.token=...
ollama.baseUrl=http://localhost:11434
ollama.embeddingModel=nomic-embed-text
```

---

## 🆕 Новые компоненты

### 1. `OllamaModel` enum
**Файл:** `ChatHistoryManager.kt`

```kotlin
enum class OllamaModel(val filename: String, val displayName: String) {
    GEMMA3("gemma3", "Gemma3 1B"),
    QWEN3("qwen3", "Qwen3 1.7B"),
    LLAMA3("llama3", "Llama3.2 3B")
}
```

**Назначение**: Типизация моделей для раздельного хранения истории.

### 2. `MultiModelChatOrchestrator`
**Файл:** `MultiModelChatOrchestrator.kt` (новый)

**Ответственность**:
- Управление тремя клиентами Ollama
- Последовательная обработка сообщений через все модели
- Раздельное управление историей для каждой модели
- Измерение времени генерации
- Обработка ошибок для каждой модели независимо

**Основной метод**:
```kotlin
suspend fun processMessage(
    chatId: ChatId,
    userMessage: String,
    systemRole: SystemPrompt,
    temperature: Temperature
): List<ModelResponse>
```

**Возвращает**:
```kotlin
data class ModelResponse(
    val modelName: String,      // "Gemma3 1B"
    val content: String,         // Ответ модели
    val generationTimeMs: Long   // Время генерации в мс
)
```

### 3. `MultiModelModule`
**Файл:** `MultiModelModule.kt` (новый)

**Koin DI модуль** для `MultiModelChatOrchestrator`:
```kotlin
val multiModelModule = module {
    single {
        MultiModelChatOrchestrator(
            gemma3Client = get<OllamaClient>(named("gemma3")),
            qwen3Client = get<OllamaClient>(named("qwen3")),
            llama3Client = get<OllamaClient>(named("llama3")),
            historyManager = get()
        )
    }
}
```

---

## 🔄 Измененные компоненты

### 1. ChatHistoryManager
**Изменения:**
- ✅ Добавлен `OllamaModel` enum
- ✅ Новые методы с поддержкой модели:
  - `loadHistory(chatId: Long, model: OllamaModel)`
  - `saveHistory(chatId: Long, model: OllamaModel, history: List<GigaChatMessage>)`
  - `clearHistory(chatId: Long, model: OllamaModel)`
  - `clearAllHistories(chatId: Long)` - новый метод
- ⚠️ Старые методы помечены `@Deprecated`

**Формат файлов истории:**
```
chat_histories/{chatId}_{model.filename}.json
```

### 2. TelegramBotService
**Изменения:**
- ❌ Удалена вся RAG логика (поиск документации, embeddings)
- ❌ Удалена зависимость от `ChatOrchestrator`
- ✅ Добавлена зависимость от `MultiModelChatOrchestrator`
- ✅ Новая логика отправки трех сообщений:
  - Последовательная обработка через все модели
  - Задержка 100ms между сообщениями (защита от rate limiting)
  - Markdown форматирование с заголовком модели и временем

**Формат ответа:**
```markdown
*Gemma3 1B*

The sun shines because of nuclear fusion...

⏱️ 1234ms
```

### 3. ConfigService
**Изменения:**
- ❌ Удалены поля: `gigaChatAuthKey`, `gigaChatBaseUrl`, `gigaChatModel`
- ❌ Удалены поля: `githubToken`, `githubOwner`, `githubRepo`
- ❌ Удалено поле: `ollamaChatModel` (модели hardcoded)
- ✅ Оставлены только: `telegramToken`, `ollamaBaseUrl`, `ollamaEmbeddingModel`
- ✅ Значения по умолчанию для Ollama параметров

### 4. ChatSettings
**Изменения:**
- ❌ Удалены поля: `reminderTime`, `reminderEnabled`, `lastReminderSent`
- ❌ Удалены поля: `ragRelevanceThreshold`, `ragEnabled`, `ragTopK`
- ✅ Добавлено поле: `systemPrompt: SystemPrompt?` (nullable)
- ✅ Упрощенная структура: только `chatId`, `temperature`, `systemPrompt`

### 5. LlmModule
**Полностью переписан:**

**До:**
```kotlin
single { GigaChatClient(...) }
single { OllamaClient(...) }
single<LlmClient>(named("gigachat")) { ... }
single<LlmClient>(named("ollama")) { ... }
```

**После:**
```kotlin
single(named("gemma3")) { OllamaClient(..., chatModel = "gemma3:1b") }
single(named("qwen3")) { OllamaClient(..., chatModel = "qwen3:1.7b") }
single(named("llama3")) { OllamaClient(..., chatModel = "llama3.2:3b") }
```

### 6. DomainModule
**Упрощен:**
- ❌ Удалены: `MarkdownPreprocessor`, `EmbeddingService`, `RagService`, `ChatOrchestrator`
- ✅ Оставлен только: `CoroutineScope`

### 7. CommandModule
**Упрощен:**
- ❌ Удалены зависимости: `PullRequestAnalysisService`, `RagService`, `EmbeddingService`
- ❌ Удалены обработчики: `SetThresholdCommandHandler`, `CreateEmbeddingsCommandHandler`, `HelpCommandHandler`, `TestRagCommandHandler`, `CompareRagCommandHandler`, `ListGitHubToolsCommandHandler`, `ShowPRCommandHandler`
- ✅ Оставлены: `StartCommandHandler`, `ChangeRoleCommandHandler`, `ChangeTemperatureCommandHandler`, `ClearChatCommandHandler`

### 8. TelegramModule
**Изменения:**
- ❌ Удалены параметры: `chatOrchestrator`, `embeddingService`, `gigaChatModel`
- ✅ Добавлен параметр: `multiModelOrchestrator`
- ✅ Изменен `defaultSystemRole` на английский язык

### 9. Main.kt
**Изменения:**
- ❌ Удалена инициализация MCP сервисов (gitMcp, githubMcp)
- ❌ Удален импорт `StdioMcpService`
- ✅ Упрощенный список модулей: `configModule`, `httpModule`, `llmModule`, `multiModelModule`, `persistenceModule`, `domainModule`, `commandModule`, `telegramModule`
- ✅ Обновлены комментарии и приветственное сообщение

### 10. HttpModule
**Изменения:**
- ✅ Добавлен `HttpTimeout` plugin для работы с Ollama
- ✅ Увеличены таймауты:
  - `requestTimeoutMillis = 120_000` (120 секунд)
  - `connectTimeoutMillis = 30_000` (30 секунд)
  - `socketTimeoutMillis = 120_000` (120 секунд)

**Причина**: Llama3.2 3B требует больше времени для первого запроса (загрузка модели в память).

### 11. Command Handlers

#### StartCommandHandler
**Изменения:**
- ✅ Новое приветственное сообщение для мульти-модельного режима
- ✅ Описание трех моделей
- ✅ Инструкции по установке моделей Ollama

#### ChangeRoleCommandHandler
**Изменения:**
- ❌ Удалена зависимость от `ChatOrchestrator`
- ✅ Добавлена зависимость от `ChatSettingsManager`
- ✅ Изменение промпта через сохранение в настройках чата

#### ClearChatCommandHandler
**Изменения:**
- ❌ Удалена зависимость от `ChatOrchestrator`
- ✅ Добавлена зависимость от `MultiModelChatOrchestrator`
- ✅ Очистка истории для всех трех моделей через `clearHistory()`

---

## 🗑️ Удаленные компоненты

### Модули DI
1. ❌ `McpModule.kt` - MCP сервисы (HTTP и Stdio)

### Command Handlers
1. ❌ `SetThresholdCommandHandler.kt` - настройка RAG threshold
2. ❌ `CreateEmbeddingsCommandHandler.kt` - создание embeddings
3. ❌ `HelpCommandHandler.kt` - поиск по документации через RAG
4. ❌ `TestRagCommandHandler.kt` - тестирование RAG
5. ❌ `CompareRagCommandHandler.kt` - сравнение RAG подходов
6. ❌ `ListGitHubToolsCommandHandler.kt` - список GitHub MCP tools
7. ❌ `ShowPRCommandHandler.kt` - анализ Pull Requests

### Зависимости (не используются)
- `ChatOrchestrator` - заменен на `MultiModelChatOrchestrator`
- `RagService` - RAG функционал удален
- `EmbeddingService` - embeddings удалены
- `GigaChatClient` - GigaChat удален
- `HttpMcpService`, `StdioMcpService`, `GitMcpService` - MCP удален
- `PullRequestAnalysisService` - GitHub интеграция удалена
- `ToolCallHandler` - function calling удален
- `ReminderScheduler` - напоминания удалены

---

## 💬 Изменения команд бота

### До миграции (11 команд)
```
/start - приветствие
/changeRole <text> - изменить системный промпт
/changeT <number> - изменить температуру
/clearChat - очистить историю
/enableMcp - включить MCP
/listTools - список MCP tools
/createEmbeddings - создать embeddings
/testRag <question> - тест RAG
/compareRag <question> - сравнение RAG
/setThreshold <0.0-1.0> - настройка RAG threshold
/showPR [number] - анализ PR
/listGitHubTools - список GitHub tools
```

### После миграции (4 команды)
```
/start - приветствие (обновленное)
/changeRole <text> - изменить системный промпт для всех моделей
/changeT <number> - изменить температуру
/clearChat - очистить историю всех трех моделей
```

---

## 🔧 Технические детали

### Последовательность обработки сообщения

```
1. User sends message
   ↓
2. TelegramBotService.setupMessageHandlers()
   ↓
3. Load ChatSettings (temperature, systemPrompt)
   ↓
4. MultiModelChatOrchestrator.processMessage()
   ↓
5. For each model (gemma3, qwen3, llama3):
   a. Load history for model
   b. Update system prompt
   c. Add user message
   d. Call OllamaClient.chatCompletion()
   e. Measure generation time
   f. Add assistant response to history
   g. Save history for model
   h. Create ModelResponse
   ↓
6. Return List<ModelResponse>
   ↓
7. TelegramBotService sends 3 separate messages
   (with 100ms delay between them)
```

### Формат истории (JSON)

**Файл:** `chat_histories/702588940_gemma3.json`
```json
[
  {
    "role": "system",
    "content": "You are a helpful assistant..."
  },
  {
    "role": "user",
    "content": "Why does the sun shine?"
  },
  {
    "role": "assistant",
    "content": "The sun shines because of nuclear fusion..."
  }
]
```

### Формат настроек чата (JSON)

**Файл:** `chat_settings/702588940_settings.json`
```json
{
  "chatId": {
    "value": 702588940
  },
  "temperature": {
    "value": 0.87
  },
  "systemPrompt": {
    "value": "You are a helpful assistant..."
  }
}
```

---

## 🐛 Известные проблемы и решения

### Проблема 1: "System prompt cannot be blank"
**Симптом**: Ошибка при первом сообщении пользователя

**Причина**: `SystemPrompt` value class не допускает пустые значения, но `ChatSettings` использовал `SystemPrompt("")` по умолчанию

**Решение**:
```kotlin
// До
data class ChatSettings(
    val systemPrompt: SystemPrompt = SystemPrompt("")  // ❌ Ошибка!
)

// После
data class ChatSettings(
    val systemPrompt: SystemPrompt? = null  // ✅ null = дефолтный промпт
)
```

### Проблема 2: Request timeout для Llama3.2 3B
**Симптом**: `Request timeout has expired url=http://localhost:11434/api/chat`

**Причина**:
- Llama3.2 3B - самая большая модель
- Первый запрос требует загрузки модели в память (60-120 секунд)
- Дефолтный таймаут Ktor (~15 секунд) слишком короткий

**Решение**:
```kotlin
// HttpModule.kt
install(HttpTimeout) {
    requestTimeoutMillis = 120_000  // 120 секунд
    connectTimeoutMillis = 30_000   // 30 секунд
    socketTimeoutMillis = 120_000   // 120 секунд
}
```

**Рекомендация**: Предварительно загрузите модель в память:
```bash
ollama run llama3.2:3b "hello"
```

---

## 🚀 Инструкции по запуску

### 1. Предварительные требования

#### Установка Ollama
```bash
# macOS
brew install ollama

# Linux
curl -fsSL https://ollama.com/install.sh | sh

# Запуск сервера
ollama serve
```

#### Загрузка моделей
```bash
ollama pull gemma3:1b      # ~1.3 GB
ollama pull qwen3:1.7b     # ~1.7 GB
ollama pull llama3.2:3b    # ~3.2 GB
```

**Проверка моделей:**
```bash
ollama list
# Должны быть: gemma3:1b, qwen3:1.7b, llama3.2:3b
```

### 2. Настройка конфигурации

```bash
# Создайте config.properties из шаблона
cp config.properties.template config.properties

# Отредактируйте файл
nano config.properties
```

**Минимальная конфигурация:**
```properties
telegram.token=YOUR_TELEGRAM_BOT_TOKEN
```

**Полная конфигурация:**
```properties
telegram.token=YOUR_TELEGRAM_BOT_TOKEN
ollama.baseUrl=http://localhost:11434
ollama.embeddingModel=nomic-embed-text
```

### 3. Сборка проекта

```bash
# Установите JAVA_HOME (если требуется)
export JAVA_HOME=/Users/dmitriikonovalov/Library/Java/JavaVirtualMachines/openjdk-17.0.1/Contents/Home

# Сборка
./gradlew clean build
```

**Ожидаемый результат:**
```
BUILD SUCCESSFUL in 1m 1s
10 actionable tasks: 9 executed, 1 up-to-date
```

### 4. Запуск бота

```bash
./gradlew run
```

**Ожидаемый вывод:**
```
=== Starting TeleGaGa Multi-Model Ollama Bot ===

1. Initializing Koin DI container...
   ✅ Koin DI container initialized

2. Starting Telegram Bot...
   ✅ Telegram Bot started

=== TeleGaGa Bot is running ===
Press Enter to stop (or Ctrl+C)
```

### 5. Тестирование

Отправьте боту в Telegram:
```
/start
```

Затем задайте вопрос:
```
Why does the sun shine?
```

**Ожидаемый результат**: 3 сообщения от разных моделей:
```
*Gemma3 1B*

The sun shines because...

⏱️ 1234ms
```

```
*Qwen3 1.7B*

The sun emits light...

⏱️ 987ms
```

```
*Llama3.2 3B*

Solar fusion creates...

⏱️ 15060ms
```

---

## 📈 Статистика изменений

### Файлы

| Категория | Количество | Детали |
|-----------|------------|--------|
| ✅ Новые файлы | 2 | MultiModelChatOrchestrator.kt, MultiModelModule.kt |
| 🔄 Измененные файлы | 12 | ChatHistoryManager, TelegramBotService, ConfigService, Main.kt, и др. |
| ❌ Удаленные файлы | 8 | McpModule.kt, 7 command handlers |
| **Итого:** | **22** | |

### Строки кода

| Метрика | Значение |
|---------|----------|
| Добавлено строк | ~600-700 |
| Удалено строк | ~1500-2000 |
| Изменено строк | ~500-600 |
| **Чистое изменение** | **-1000 строк** (упрощение!) |

### Модули Koin

| До | После |
|----|-------|
| 8 модулей | 6 модулей |
| McpModule (удален) | - |
| - | MultiModelModule (новый) |

### Команды бота

| До | После |
|----|-------|
| 11 команд | 4 команды |
| 7 удалено | - |

---

## 🔍 Сравнение архитектур

### Сложность

| Метрика | До | После | Изменение |
|---------|-----|--------|-----------|
| Внешние зависимости | 3 (GigaChat, Ollama, GitHub) | 1 (Ollama) | -66% |
| DI модули | 8 | 6 | -25% |
| Команд бота | 11 | 4 | -64% |
| Основных компонентов | 15 | 8 | -47% |

### Производительность

| Метрика | До | После |
|---------|-----|--------|
| Время ответа (среднее) | 2-5 секунд | 5-20 секунд (3 модели) |
| Время первого запроса | 2-5 секунд | 30-120 секунд (загрузка моделей) |
| Потребление памяти | ~200 MB | ~4-6 GB (Ollama + модели) |
| Сетевые запросы | Да (GigaChat API) | Нет (локальные модели) |

### Стоимость

| Метрика | До | После |
|---------|-----|--------|
| API costs | ✅ Да (GigaChat tokens) | ❌ Нет |
| Hardware | Минимальные требования | Средние-высокие (4+ GB RAM) |
| Internet | Обязателен | Не требуется (только Telegram) |

---

## 🎓 Уроки и рекомендации

### 1. Управление таймаутами

**Урок**: Локальные LLM требуют значительно больше времени, чем облачные API.

**Рекомендация**:
- Устанавливайте таймауты минимум 60-120 секунд
- Предзагружайте модели в память перед использованием
- Рассмотрите использование более легких моделей (1B-3B вместо 7B+)

### 2. Раздельное хранение истории

**Урок**: Каждая модель должна иметь свой контекст для корректных ответов.

**Рекомендация**:
- Используйте типизацию (enum) для моделей
- Храните историю в отдельных файлах
- Предоставьте способ очистки всех историй одновременно

### 3. Пользовательский опыт

**Урок**: Получение трех ответов интересно, но может быть overwhelming.

**Рекомендация для будущих улучшений**:
- Добавить команду `/selectModels` для выбора активных моделей
- Реализовать параллельную обработку для ускорения
- Добавить статистику сравнения моделей

### 4. Упрощение конфигурации

**Урок**: Меньше параметров = меньше ошибок конфигурации.

**Рекомендация**:
- Используйте разумные значения по умолчанию
- Делайте параметры optional где возможно
- Документируйте каждый параметр

---

## 🔮 Возможные улучшения (Roadmap)

### Версия 2.1 (Краткосрочная)
- [ ] Параллельная обработка через три модели (async/await)
- [ ] Команда `/toggleModel <model>` для вкл/выкл моделей
- [ ] Команда `/stats` для просмотра статистики (время генерации, количество сообщений)
- [ ] Кэширование загруженных моделей в Ollama

### Версия 2.2 (Среднесрочная)
- [ ] Команда `/selectModels` для выбора активных моделей из списка
- [ ] Команда `/listModels` для просмотра доступных моделей в Ollama
- [ ] Сравнительная таблица ответов трех моделей в одном сообщении
- [ ] Экспорт истории в различных форматах (JSON, Markdown, CSV)

### Версия 3.0 (Долгосрочная)
- [ ] Web UI для управления моделями и просмотра статистики
- [ ] Поддержка кастомных моделей через конфигурацию
- [ ] Автоматическое определение лучшей модели для типа вопроса
- [ ] Система рейтингов ответов от пользователей

---

## 📞 Поддержка

### Частые проблемы

#### "Ollama is not running"
```bash
# Проверьте статус Ollama
curl http://localhost:11434/api/tags

# Если ошибка - запустите Ollama
ollama serve
```

#### "Model not found"
```bash
# Проверьте установленные модели
ollama list

# Установите недостающую модель
ollama pull <model-name>
```

#### "Out of memory"
**Причина**: Недостаточно RAM для трех моделей одновременно

**Решения**:
1. Используйте меньше моделей (закомментируйте в CommandModule)
2. Используйте более легкие модели (замените 3B на 1B)
3. Увеличьте swap memory
4. Добавьте физическую RAM

#### "Request timeout" для всех моделей
**Причина**: Ollama не отвечает или перегружена

**Решения**:
1. Перезапустите Ollama: `pkill ollama && ollama serve`
2. Проверьте загрузку CPU/RAM
3. Увеличьте таймауты в HttpModule
4. Используйте только одну модель для тестирования

---

## 📝 Checklist миграции

Для миграции с предыдущей версии проекта:

- [ ] Установить Ollama
- [ ] Загрузить три модели (gemma3:1b, qwen3:1.7b, llama3.2:3b)
- [ ] Обновить config.properties (удалить старые параметры)
- [ ] Удалить старые файлы истории (опционально)
- [ ] Удалить старые настройки чатов (опционально)
- [ ] Пересобрать проект: `./gradlew clean build`
- [ ] Запустить бот: `./gradlew run`
- [ ] Протестировать команду `/start`
- [ ] Протестировать отправку сообщения
- [ ] Проверить создание трех файлов истории
- [ ] Протестировать команду `/clearChat`
- [ ] Протестировать команду `/changeRole`
- [ ] Протестировать команду `/changeT`

---

## 📚 Дополнительные ресурсы

### Документация Ollama
- [Ollama Official Docs](https://ollama.com/docs)
- [Ollama API Reference](https://github.com/ollama/ollama/blob/main/docs/api.md)
- [Model Library](https://ollama.com/library)

### Документация моделей
- [Gemma3](https://ollama.com/library/gemma3)
- [Qwen3](https://ollama.com/library/qwen3)
- [Llama3.2](https://ollama.com/library/llama3.2)

### Исходный код проекта
- Основной репозиторий: (ваш репозиторий)
- История коммитов: См. git log для деталей изменений

---

## ✍️ Авторы миграции

**Дата**: 16 февраля 2026
**Разработчик**: Dmitrii Konovalov
**AI Assistant**: Claude Sonnet 4.5
**Версия документа**: 1.0

---

## 📄 Лицензия

Этот проект сохраняет ту же лицензию, что и оригинальный проект TeleGaGa.

---

**Конец документа**
