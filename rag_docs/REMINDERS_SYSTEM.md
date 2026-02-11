# TeleGaGa Reminders System

Full-featured reminder management system with automatic daily delivery via Telegram.

## Architecture

### 1. MCP Reminders Server (Node.js)
**Location:** `mcp-reminders-server/`

Provides 3 MCP tools for working with reminders:
- `create_reminder` - create new reminder
- `get_reminders` - get list of reminders for a period
- `delete_reminder` - delete/complete reminder

**Storage:** `mcp-reminders-server/storage/<chatId>.json`
- Data isolation by chatId
- Automatic file creation
- Validation of all parameters

### 2. ChatSettingsManager (Kotlin)
**Location:** `src/main/kotlin/ru/dikoresearch/infrastructure/persistence/ChatSettingsManager.kt`

Persistent storage for chat settings:
```kotlin
data class ChatSettings(
    val chatId: Long,
    val temperature: Float = 0.87F,
    val reminderTime: String? = null,        // HH:mm
    val reminderEnabled: Boolean = false,
    val lastReminderSent: String? = null     // ISO 8601
)
```

**Storage:** `chat_settings/<chatId>_settings.json`

### 3. ReminderScheduler (Kotlin)
**Location:** `src/main/kotlin/ru/dikoresearch/domain/ReminderScheduler.kt`

Background scheduler with per-minute checking:
- Checks send time for all active chats
- Prevents duplicate sends on same day
- Uses LLM + MCP to generate messages
- Automatic error handling

## Telegram Commands

### /setReminderTime HH:mm
Configure time for daily reminders.

**Example:**
```
/setReminderTime 09:00
```

**Result:**
- Reminder time set
- reminderEnabled = true
- Ready for automatic sending

### /disableReminders
Disable automatic reminders.

**Result:**
- reminderEnabled = false
- Reminders not sent automatically
- Data preserved

### /enableMcp
Enable MCP mode for working with reminders.

**Available operations:**
- "Remind me tomorrow to buy milk"
- "What do I have for today?"
- "Delete the reminder about the meeting"

## Workflow

### Creating a Reminder
1. User: "Remind me tomorrow to buy milk"
2. LLM analyzes the request
3. LLM calls `create_reminder` via MCP
4. MCP server creates reminder in `storage/<chatId>.json`
5. Response returned to user

### Automatic Sending
1. ReminderScheduler checks time every minute
2. Finds chats with reminderTime = current time
3. Checks that we haven't sent today already
4. Forms prompt: "Use get_reminders to get tasks for today"
5. ChatOrchestrator calls LLM with MCP
6. LLM calls `get_reminders` via MCP
7. MCP returns list of reminders
8. LLM formats nice message with emojis
9. Send to Telegram: "🌅 Good morning! Here are your tasks..."
10. Update lastReminderSent

## Security

### Data Isolation
- Each chatId has its own storage file
- MCP server checks chatId in all operations
- No access to other users' reminders possible

### Passing chatId
In ChatOrchestrator before MCP calls:
```kotlin
val contextMessage = GigaChatMessage(
    role = "system",
    content = "IMPORTANT: Your chatId = $chatId. ALWAYS use this chatId..."
)
history.add(1, contextMessage)
```

After processing, the context message is removed.

## Technical Details

### Time Format
- Input: HH:mm (e.g., 09:00)
- Validation: regular expression `^([01]\d|2[0-3]):([0-5]\d)$`
- Timezone: server system timezone

### Reminder Date Format
- YYYY-MM-DD (ISO 8601)
- Validation in MCP server

### Limitations
- Time checking: once per minute
- Maximum 5 tool calling iterations
- Automatic sending: once per day

## Usage Examples

### Setting Up Reminders
```
1. /setReminderTime 09:00
2. /enableMcp
3. "Remind me tomorrow to buy milk"
4. "Remind me on Friday to call mom"
```

### Viewing Reminders
```
"What do I have for today?"
"Show my tasks for this week"
```

### Management
```
"Delete the reminder about milk"
/disableReminders  # disable auto-send
```

## File Structure

```
TeleGaGa/
├── mcp-reminders-server/
│   ├── index.js                      # MCP server
│   ├── package.json
│   ├── storage/
│   │   └── <chatId>.json             # Reminders
│   └── README.md
│
├── chat_settings/
│   └── <chatId>_settings.json        # Chat settings
│
├── src/main/kotlin/ru/dikoresearch/
│   ├── domain/
│   │   ├── ReminderScheduler.kt      # Scheduler
│   │   └── ChatOrchestrator.kt       # Updated for chatId
│   │
│   └── infrastructure/
│       ├── persistence/
│       │   └── ChatSettingsManager.kt # Settings manager
│       │
│       └── telegram/
│           └── TelegramBotService.kt  # New commands
│
├── Main.kt                           # Integration of all components
└── REMINDERS_SYSTEM.md               # This documentation
```

## Running and Testing

### Compilation
```bash
./gradlew build
```

### Running
```bash
./gradlew run
```

### Checking MCP Server
```bash
cd mcp-reminders-server
npm install
node index.js  # Ctrl+C to stop
```

### Logging
- `✅ MCP service initialized` - MCP ready
- `🕐 ReminderScheduler started` - Scheduler active
- `📤 Sending reminders for chat <id>` - Start of sending
- `✅ Reminders sent for chat <id>` - Successful send
- `💾 Settings saved for chat <id>` - Settings saved

## Migration

### Temperature
Temperature was migrated from memory (MutableMap) to ChatSettings:
- Old code: `chatTemperatures[chatId] = temp`
- New code: `settingsManager.saveSettings(chatId, settings.copy(temperature = temp))`

Benefits:
- Persistence across restarts
- Centralized settings management
- Preparation for new settings

## Resolved Issues

1. **Chuck Norris → Reminders**: Replaced test MCP server with production one
2. **Temperature in memory**: Migrated to file storage
3. **chatId security**: Automatic insertion into context
4. **bot private**: Made public for ReminderScheduler

## Future Development

Possible improvements:
- [ ] Recurring reminders (daily, weekly)
- [ ] Notifications N minutes before event
- [ ] Reminder categories
- [ ] Export/import reminders
- [ ] Calendar integrations
- [ ] Multiple send times per day
