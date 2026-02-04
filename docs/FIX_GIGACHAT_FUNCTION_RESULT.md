# Исправление: GigaChat Function Result Error

## Проблема

При вызове MCP инструмента `get_chuck_norris_joke` GigaChat возвращал ошибку:

```
Invalid params: invalid function result json string
error: JSON parse error at line 1 column 1: Invalid value
```

**Причина**: GigaChat требует, чтобы результат функции (`role=function`) был **валидным JSON**, а не просто текстом.

### Примеры ошибочных результатов

❌ **Неправильно** (просто текст):
```
Chuck Norris can divide by zero.
```

❌ **Неправильно** (с декоративными символами):
```
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Chuck Norris can divide by zero.
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
```

✅ **Правильно** (валидный JSON):
```json
{"result": "Chuck Norris can divide by zero."}
```

## Решение

### 1. Изменен `ToolCallHandler.kt`

**Файл**: `src/main/kotlin/ru/dikoresearch/domain/ToolCallHandler.kt`

**Изменение**: Метод `formatToolResult()` теперь возвращает **валидный JSON объект** с экранированием специальных символов.

```kotlin
private fun formatToolResult(toolName: String, result: CallToolResult): String {
    val contentText = result.content.joinToString("\n") { content ->
        // Извлекаем текст из MCP результата
        val contentStr = content.toString()
        when {
            contentStr.contains("text=") -> {
                contentStr.substringAfter("text=").substringBefore(",").trim()
            }
            contentStr.contains("resource=") -> {
                "Resource: ${contentStr.substringAfter("uri=").substringBefore(")").trim()}"
            }
            else -> contentStr
        }
    }

    // Экранируем специальные символы для JSON
    val escapedText = contentText
        .replace("\\", "\\\\")  // Обратный слэш
        .replace("\"", "\\\"")  // Кавычки
        .replace("\n", "\\n")   // Переносы строк
        .replace("\r", "\\r")   // Возврат каретки
        .replace("\t", "\\t")   // Табуляция

    // Возвращаем валидный JSON объект для GigaChat
    return """{"result": "$escapedText"}"""
}
```

### 2. Изменен `ChatOrchestrator.kt`

**Файл**: `src/main/kotlin/ru/dikoresearch/domain/ChatOrchestrator.kt`

**Изменения**:

1. **Добавлены импорты** для парсинга JSON:
   ```kotlin
   import kotlinx.serialization.json.Json
   import kotlinx.serialization.json.jsonObject
   import kotlinx.serialization.json.jsonPrimitive
   ```

2. **Извлечение текста из JSON** при формировании ответа пользователю:
   ```kotlin
   toolExecutionResults.forEach { (toolName, jsonResult) ->
       // Извлекаем текст из JSON результата {"result": "text"}
       val actualResult = try {
           val jsonElement = Json.parseToJsonElement(jsonResult)
           jsonElement.jsonObject["result"]?.jsonPrimitive?.content ?: jsonResult
       } catch (e: Exception) {
           // Если не JSON или ошибка парсинга - используем как есть
           jsonResult
       }

       appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
       appendLine("🛠️ Инструмент: $toolName")
       appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
       appendLine(actualResult)
       appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
   }
   ```

## Архитектура решения

```
User: "Давай шутку про Чака Норриса"
  ↓
GigaChat: function_call = get_chuck_norris_joke
  ↓
ToolCallHandler.executeFunctionCall()
  ↓
MCP Server → "Chuck Norris can divide by zero."
  ↓
formatToolResult() → JSON: {"result": "Chuck Norris can divide by zero."} ✅
  ↓
GigaChat: role="function", content='{"result": "Chuck Norris can divide by zero."}' ✅
  ↓
GigaChat: Финальный ответ модели (успешно парсит JSON)
  ↓
ChatOrchestrator: Извлекает "result" из JSON и добавляет визуальное форматирование
  ↓
User: Видит красиво отформатированный ответ
```

## Ключевая информация

### Формат данных на разных этапах

| Этап | Формат | Пример |
|------|--------|--------|
| MCP Server → Bot | Text | `Chuck Norris can divide by zero.` |
| ToolCallHandler → GigaChat | **JSON** | `{"result": "Chuck Norris can divide by zero."}` |
| GigaChat → ChatOrchestrator | Text (ответ модели) | `Вот шутка про Чака Норриса!` |
| ChatOrchestrator → User | Formatted Text | `🛠️ Инструмент: get_chuck_norris_joke\n━━━━━\n...` |

### Почему JSON?

GigaChat использует **строгую валидацию** для `role=function`. API ожидает структурированные данные в JSON формате, чтобы:
1. Обеспечить консистентность данных
2. Поддерживать сложные типы результатов
3. Избежать проблем с экранированием при обработке специальных символов

## Тестирование

### 1. Сборка проекта
```bash
./gradlew build
```
✅ **Результат**: `BUILD SUCCESSFUL`

### 2. Запуск бота
```bash
./gradlew run
```

### 3. Тестирование в Telegram

```
User: /enableMcp
Bot: MCP режим активирован

User: Давай шутку про Чака Норриса
Bot: 🔧 Использованы MCP инструменты:

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
🛠️ Инструмент: get_chuck_norris_joke
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Chuck Norris can divide by zero.
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

💬 Итоговый ответ:
Вот отличная шутка про Чака Норриса! 😄
```

### Проверка логов

✅ **Успешные логи**:
```
Модель запросила вызов функции: get_chuck_norris_joke
Вызов MCP инструмента: get_chuck_norris_joke с аргументами: {}
Инструмент get_chuck_norris_joke выполнен успешно
GigaChat Request: ... role=function, content={"result": "Chuck Norris can divide by zero."} ...
```

❌ **Ошибочные логи** (до исправления):
```
GigaChat error: Invalid params: invalid function result json string
error: JSON parse error at line 1 column 1: Invalid value
```

## Экранирование специальных символов

Реализовано экранирование для корректного JSON:

| Символ | Экранирование | Пример |
|--------|---------------|--------|
| `\` | `\\` | `path\file` → `path\\file` |
| `"` | `\"` | `He said "Hi"` → `He said \"Hi\"` |
| `\n` | `\\n` | Многострочный текст → `текст\\nтекст` |
| `\r` | `\\r` | Возврат каретки → `\\r` |
| `\t` | `\\t` | Табуляция → `\\t` |

## Статус

✅ **ПОЛНОСТЬЮ ИСПРАВЛЕНО**

Ошибка `Invalid params: invalid function result json string` устранена. GigaChat теперь корректно принимает результаты функций в JSON формате.

## Коммит

```bash
git add .
git commit -m "Fix: Wrap function results in JSON for GigaChat

- ToolCallHandler returns JSON: {\"result\": \"text\"}
- Added JSON escaping for special characters
- ChatOrchestrator parses JSON and extracts result for display
- Fixes 'Invalid params: invalid function result json string' error"
```

## Дополнительные улучшения

### Обработка ошибок

Если парсинг JSON не удался (например, неожиданный формат), код использует результат как есть:

```kotlin
val actualResult = try {
    Json.parseToJsonElement(jsonResult).jsonObject["result"]?.jsonPrimitive?.content ?: jsonResult
} catch (e: Exception) {
    jsonResult  // Fallback на оригинальный результат
}
```

### Поддержка сложных типов

В будущем можно расширить JSON схему:

```json
{
  "result": "Chuck Norris joke",
  "metadata": {
    "source": "geek-jokes API",
    "timestamp": "2025-01-27T10:00:00Z"
  }
}
```

## Связанные файлы

- `src/main/kotlin/ru/dikoresearch/domain/ToolCallHandler.kt` - Форматирование в JSON
- `src/main/kotlin/ru/dikoresearch/domain/ChatOrchestrator.kt` - Парсинг JSON и отображение
- `mcp-chuck-server/index.js` - MCP сервер (без изменений)

## Ссылки

- [GigaChat API Documentation](https://developers.sber.ru/docs/ru/gigachat/api/overview)
- [MCP Protocol Specification](https://modelcontextprotocol.io/)
