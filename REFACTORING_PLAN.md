# План рефакторинга TeleGaGa - Этап 2

## Статус: Этап 1 завершен ✓

### Что выполнено в Этапе 1:
- ✓ Создан `McpService` с управлением lifecycle MCP процесса
- ✓ Добавлен `ApplicationScope` для управления корутинами
- ✓ Команда `/listTools` вызывает `mcpService.listTools()` динамически при получении команды
- ✓ Реализован graceful shutdown
- ✓ Проект успешно собирается

### Ключевое решение проблемы:
Используется паттерн `applicationScope.launch {}` внутри синхронных обработчиков команд для вызова suspend функций MCP клиента.

---

## Этап 2: Рефакторинг архитектуры (опционально)

### Цель:
Привести код к лучшим практикам Kotlin backend разработки с правильным разделением на слои.

### Шаг 2.1 - Создать TelegramBotService

**Файл:** `src/main/kotlin/ru/dikoresearch/infrastructure/telegram/TelegramBotService.kt`

**Структура:**
```kotlin
class TelegramBotService(
    private val telegramToken: String,
    private val gigaClient: GigaChatClient,
    private val mcpService: McpService,
    private val historyManager: ChatHistoryManager,
    private val applicationScope: CoroutineScope
) {
    private lateinit var bot: Bot

    fun start() {
        bot = bot {
            token = telegramToken
            dispatch {
                setupCommands()
                setupMessageHandlers()
            }
        }
        bot.startPolling()
    }

    fun stop() {
        // Остановка polling
    }

    private fun Dispatcher.setupCommands() {
        command("start") { /* ... */ }
        command("listTools") { handleListTools() }
        command("changeRole") { /* ... */ }
        // и т.д.
    }

    private fun HandlerEnvironment.handleListTools() {
        val chatId = message.chat.id
        applicationScope.launch {
            try {
                val tools = mcpService.listTools()
                bot.sendMessage(chatId, formatToolsList(tools))
            } catch (e: Exception) {
                bot.sendMessage(chatId, "Ошибка: ${e.message}")
            }
        }
    }

    private fun formatToolsList(tools: ListToolsResult): String {
        val toolNames = tools.tools.map { it.name }
        return "Доступные MCP инструменты (${toolNames.size}):\n" +
               toolNames.joinToString("\n") { "• $it" }
    }
}
```

**Преимущества:**
- Изолирует всю Telegram специфику
- Main.kt занимается только инициализацией
- Легко тестировать отдельно
- Можно добавить другие интерфейсы (REST API) без изменения бизнес-логики

---

### Шаг 2.2 - Рефакторинг BotLogic.kt в ChatOrchestrator

**Файл:** `src/main/kotlin/ru/dikoresearch/domain/ChatOrchestrator.kt`

**Цель:** Превратить `handleTextUpdate` в чистую бизнес-логику без зависимости от Telegram.

**Структура:**
```kotlin
class ChatOrchestrator(
    private val gigaClient: GigaChatClient,
    private val mcpService: McpService,
    private val historyManager: ChatHistoryManager
) {
    suspend fun processMessage(
        chatId: Long,
        userMessage: String,
        systemRole: String,
        temperature: Float
    ): ChatResponse {
        // Получаем историю
        val history = loadOrCreateHistory(chatId, systemRole)

        // Добавляем сообщение пользователя
        history.add(GigaChatMessage(role = "user", content = userMessage))

        // Проверяем, нужен ли вызов MCP tool
        // (будущая интеграция с GigaChat function calling)

        // Вызываем GigaChat
        val response = gigaClient.chatCompletion(
            model = "GigaChat",
            messages = history,
            temperature = temperature
        )

        // Обрабатываем ответ
        val assistantMessage = response.choices.firstOrNull()?.message?.content
            ?: throw IllegalStateException("Empty response")

        history.add(GigaChatMessage(role = "assistant", content = assistantMessage))

        // Суммаризация если нужно
        if (history.size > 20) {
            summarizeHistory(chatId, history, systemRole)
        }

        // Сохраняем историю
        historyManager.saveHistory(chatId, history)

        return ChatResponse(
            text = assistantMessage.take(3800),
            tokenUsage = response.usage
        )
    }

    private fun loadOrCreateHistory(chatId: Long, systemRole: String): MutableList<GigaChatMessage> {
        val loaded = historyManager.loadHistory(chatId)
        return if (loaded.isNotEmpty()) loaded
        else mutableListOf(GigaChatMessage(role = "system", content = systemRole))
    }

    private suspend fun summarizeHistory(
        chatId: Long,
        history: MutableList<GigaChatMessage>,
        systemRole: String
    ) {
        // Логика суммаризации
    }
}

data class ChatResponse(
    val text: String,
    val tokenUsage: TokenUsage?
)
```

**Преимущества:**
- Нет зависимости от Telegram типов (Update, Message)
- Принимает простые типы: Long, String
- Возвращает результат вместо callback
- Можно протестировать без Telegram бота
- Легко переиспользовать для REST API

---

### Шаг 2.3 - Структура пакетов

```
src/main/kotlin/ru/dikoresearch/
├── infrastructure/
│   ├── mcp/
│   │   ├── McpService.kt ✓ (уже создан)
│   │   └── McpConfig.kt (опционально)
│   ├── telegram/
│   │   ├── TelegramBotService.kt (создать)
│   │   └── TelegramConfig.kt (опционально)
│   ├── http/
│   │   ├── GigaChatClient.kt (переместить)
│   │   ├── OllamaClient.kt (переместить)
│   │   └── HttpClientFactory.kt (опционально)
│   └── persistence/
│       └── ChatHistoryManager.kt (переместить)
├── domain/
│   ├── ChatOrchestrator.kt (создать)
│   └── models/
│       ├── ChatMessage.kt (опционально - обертка над GigaChatMessage)
│       └── ChatResponse.kt (создать)
└── Main.kt (только инициализация)
```

**Переименования:**
- `BotLogic.kt` → `domain/ChatOrchestrator.kt`
- `GigaChatClient.kt` → `infrastructure/http/GigaChatClient.kt`
- `ChatHistoryManager.kt` → `infrastructure/persistence/ChatHistoryManager.kt`

---

### Шаг 2.4 - Обновленный Main.kt

**Цель:** Main.kt должен содержать только инициализацию и не более 100 строк.

```kotlin
suspend fun main() = runBlocking {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    var mcpService: McpService? = null
    var botService: TelegramBotService? = null

    try {
        // 1. HTTP Client
        val httpClient = createHttpClient()

        // 2. AI Clients
        val gigaClient = GigaChatClient(httpClient, GIGA_BASE_URL, GIGA_AUTH_KEY)
        val ollamaClient = OllamaClient(httpClient)

        // 3. Persistence
        val historyManager = ChatHistoryManager()

        // 4. MCP Service
        mcpService = McpService()
        mcpService.initialize()

        // 5. Domain Layer
        val chatOrchestrator = ChatOrchestrator(gigaClient, mcpService, historyManager)

        // 6. Telegram Bot Service
        botService = TelegramBotService(
            telegramToken = TELEGRAM_TOKEN,
            chatOrchestrator = chatOrchestrator,
            mcpService = mcpService,
            historyManager = historyManager,
            applicationScope = applicationScope
        )

        // 7. Health Check Server
        startHealthCheckServer()

        // 8. Start Bot
        botService.start()

        println("Приложение запущено успешно")

    } catch (e: Exception) {
        e.printStackTrace()
    } finally {
        shutdown(applicationScope, mcpService, botService)
    }
}

private fun createHttpClient(): HttpClient { /* ... */ }
private fun startHealthCheckServer() { /* ... */ }
private suspend fun shutdown(scope: CoroutineScope, mcp: McpService?, bot: TelegramBotService?) { /* ... */ }
```

---

### Шаг 2.5 - Интеграция MCP с GigaChat (будущее)

**Когда MCP tools нужно использовать при работе с LLM:**

```kotlin
// В ChatOrchestrator.kt
suspend fun processMessage(...): ChatResponse {
    // ... получение истории ...

    // Шаг 1: Первый запрос к GigaChat
    val initialResponse = gigaClient.chatCompletion(...)

    // Шаг 2: Проверяем, нужен ли tool call
    val toolCall = extractToolCall(initialResponse)

    if (toolCall != null) {
        // Шаг 3: Вызываем MCP tool
        val toolResult = mcpService.callTool(
            name = toolCall.name,
            arguments = toolCall.arguments
        )

        // Шаг 4: Добавляем результат в историю
        history.add(GigaChatMessage(
            role = "tool",
            content = toolResult.toString()
        ))

        // Шаг 5: Второй запрос к GigaChat с результатом tool
        val finalResponse = gigaClient.chatCompletion(
            model = "GigaChat",
            messages = history,
            temperature = temperature
        )

        return ChatResponse(finalResponse)
    }

    return ChatResponse(initialResponse)
}
```

---

## Порядок реализации Этапа 2

**Если решите продолжить рефакторинг:**

1. **Шаг 2.1** - Создать `TelegramBotService` и вынести всю логику команд из Main.kt
2. **Шаг 2.2** - Рефакторинг `BotLogic.kt` в `ChatOrchestrator`
3. **Шаг 2.3** - Реорганизовать структуру пакетов (переместить файлы)
4. **Шаг 2.4** - Упростить Main.kt до инициализации
5. **Тестирование** - Проверить работу после каждого шага

---

## Преимущества финальной архитектуры

1. **Separation of Concerns** - каждый класс делает одно дело
2. **Testability** - можно тестировать каждый слой отдельно
3. **Maintainability** - легко найти и изменить код
4. **Scalability** - легко добавить новые функции
5. **Clean Architecture** - domain логика независима от frameworks
6. **Reusability** - ChatOrchestrator можно использовать из REST API, WebSocket и т.д.

---

## Примечания

- Этап 2 опционален - текущая реализация уже решает проблему с MCP
- Можно реализовывать постепенно, по одному шагу
- Каждый шаг независим и может быть протестирован отдельно
- Рефакторинг не меняет функциональность, только улучшает структуру кода

---

**Дата создания плана:** 2026-01-27
**Статус Этапа 1:** Завершен ✓
**Статус Этапа 2:** Ожидает решения о начале реализации
