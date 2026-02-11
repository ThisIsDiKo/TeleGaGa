# TeleGaGa Bot Testing Plan

## 1. Preparation for Testing

### 1.1 Starting from Clean State
```bash
# Clear all data
rm -rf chat_history/
rm -rf chat_settings/
rm -rf mcp-reminders-server/reminders.json

# Rebuild the project
./gradlew clean build

# Start the bot
./gradlew run
```

### 1.2 What to Check in Logs at Startup
```
✅ Check for:
[x] "=== Starting TeleGaGa bot ==="
[x] "Configuration loaded successfully"
[x] "HTTP client created"
[x] "GigaChat client created"
[x] "🧹 Cleaning ports from old processes..." (for ports 3001, 3002, 3003)
[x] "✅ weather: X tools on port 3001"
[x] "✅ reminders: X tools on port 3002"
[x] "✅ chuck: X tools on port 3003"
[x] "✅ All MCP servers started and connected"
[x] "🕐 ReminderScheduler started"
[x] "Telegram bot started and waiting for messages"

❌ Should NOT see:
[ ] "EADDRINUSE: address already in use"
[ ] "Failed to initialize"
[ ] "processesAlive=false"
```

---

## 2. Testing Basic Commands

### Test 2.1: /start
**Message to bot:** `/start`

**Expected result:**
- Welcome message
- List of all commands
- "MCP tools" section
- "Reminders management" section

**What to check in logs:**
- No errors during command processing

---

### Test 2.2: /listTools
**Message to bot:** `/listTools`

**Expected result:**
```
Available MCP tools (5):
• get_weather
• create_reminder
• get_reminders
• delete_reminder
• get_chuck_norris_joke
```

**What to check in logs:**
- `🔍 HttpMcpService.isAvailable(): sessions=true (3), processesAlive=true (3), result=true`

---

### Test 2.3: /changeT
**Message to bot:** `/changeT 0.5`

**Expected result:**
- "New response temperature: 0.5"

**Verification:** Ask a question and check tokens in logs - should see `temperature=0.5`

---

### Test 2.4: /clearChat
**Message to bot:** `/clearChat`

**Expected result:**
- "Chat history successfully deleted"

**Verification:** File in `chat_history/<chatId>_history.json` should disappear

---

## 3. Testing MCP: Weather

### Test 3.1: Simple Weather Request
**Message to bot:** `What's the weather in Saint Petersburg?`

**Expected result:**
- Response with temperature, description, humidity, wind
- Token information at the end

**What to check in logs:**
```bash
# Should see lines:
📤 Calling gigaClient.chatCompletion (functions=5)...
Model requested function call: get_weather
✅ MCP functions retrieved: 5 items
```

**Function verification:**
```
GigaChatFunctionCall(
  name=get_weather,
  arguments={"city":"Saint Petersburg","lang":"en"}
)
```

---

### Test 3.2: Weather for Multiple Cities
**Message to bot:** `Compare weather in Moscow and Saint Petersburg`

**Expected result:**
- Two get_weather calls
- Comparative analysis from LLM

**What to check in logs:**
```
🔄 Iteration 1: sending request to GigaChat...
Model requested function call: get_weather
🔄 Iteration 2: sending request to GigaChat...
Model requested function call: get_weather
🔄 Iteration 3: sending request to GigaChat...
```

---

### Test 3.3: Weather for Non-existent City
**Message to bot:** `What's the weather in Atlantis?`

**Expected result:**
- Proper error handling
- LLM should inform that city was not found

**What to check in logs:**
```
GigaChatMessage(role=function, content={"error": "..."})
```

---

## 4. Testing MCP: Reminders

### Test 4.1: Creating Reminder for Specific Date
**Message to bot:** `Remind me the day after tomorrow at 15:00 to submit the report`

**Expected result:**
- Confirmation of reminder creation
- Specific date indicated

**What to check in logs:**
```bash
# Check that dueDate is correct (day after tomorrow from current date):
GigaChatFunctionCall(
  name=create_reminder,
  arguments={
    "chatId":"...",
    "dueDate":"2026-01-31",  # should be correct date
    "text":"At 15:00 submit the report"
  }
)
```

**File verification:**
```bash
cat mcp-reminders-server/reminders.json
# Should contain new reminder with correct date
```

---

### Test 4.2: Creating Reminder with Relative Date
**Message to bot:** `Remind me tomorrow to buy milk`

**Expected result:**
- LLM should calculate "tomorrow" relative to current date (from system prompt)

**What to check in logs:**
```bash
# At the beginning of processing should see context:
🔧 Adding context to system prompt...
CURRENT DATE AND TIME (timezone: Europe/Moscow):
- Date: 2026-01-29 (Wednesday)
- Time: 15:30:00

# Then check that "tomorrow" = 2026-01-30:
GigaChatFunctionCall(
  name=create_reminder,
  arguments={"chatId":"...","dueDate":"2026-01-30","text":"buy milk"}
)
```

---

### Test 4.3: Getting List of Reminders
**Message to bot:** `What do I have for tomorrow?`

**Expected result:**
- List of all reminders for tomorrow's date
- Numbered list with emojis

**What to check in logs:**
```
Model requested function call: get_reminders
GigaChatFunctionCall(
  name=get_reminders,
  arguments={"chatId":"...","startDate":"2026-01-30","endDate":"2026-01-30"}
)
```

---

### Test 4.4: Deleting Reminder
**Prerequisites:** Create a reminder

**Message to bot:** `Delete the reminder about milk`

**Expected result:**
- Deletion confirmation

**What to check in logs:**
```
Model requested function call: delete_reminder
```

**File verification:**
```bash
cat mcp-reminders-server/reminders.json
# Reminder should have completed: true
```

---

### Test 4.5: "Today" Reminder (Regression Test)
**Message to bot:** `Remind me today at 23:00 to watch football`

**Expected result:**
- dueDate should be TODAY's date, not yesterday's

**What to check in logs:**
```bash
# Check that dueDate = today's date from system prompt
GigaChatFunctionCall(
  name=create_reminder,
  arguments={
    "dueDate":"2026-01-29",  # TODAY, not 2026-01-28!
    "text":"At 23:00 watch football"
  }
)
```

---

## 5. Testing MCP: Chuck Norris Jokes

### Test 5.1: Joke Request
**Message to bot:** `Tell me a Chuck Norris joke`

**Expected result:**
- Joke in Russian (NOT in English!)
- Should have humor and natural translation

**What to check in logs:**
```bash
Model requested function call: get_chuck_norris_joke

# MCP response will be in English:
GigaChatMessage(
  role=function,
  content="{\"joke\":\"Chuck Norris can divide by zero.\"}"
)

# But LLM should translate in final response
```

**Verification:** Response to user should NOT contain English text

---

## 6. Testing Complex Scenarios

### Test 6.1: Chain of MCP Calls
**Message to bot:** `Remind me tomorrow at 10:00 to check weather in Moscow and then tell me a joke`

**Expected result:**
- create_reminder called
- get_chuck_norris_joke called
- Joke translated to Russian

**What to check in logs:**
```
🔄 Iteration 1: sending request to GigaChat...
Model requested function call: create_reminder
🔄 Iteration 2: sending request to GigaChat...
Model requested function call: get_chuck_norris_joke
🔄 Iteration 3: sending request to GigaChat...
(final response)
```

---

### Test 6.2: Reminder + Check + Delete
**Step 1:** `Remind me tomorrow to buy bread`
**Step 2:** `What do I have for tomorrow?`
**Step 3:** `Delete the reminder about bread`
**Step 4:** `What do I have for tomorrow?`

**Expected result:**
- Step 2: Should show reminder about bread
- Step 4: Should say there are no tasks (or show other tasks)

---

## 7. Testing Scheduler

### Test 7.1: Setting Reminder Time
**Message to bot:** `/setReminderTime 09:00`

**Expected result:**
- "⏰ Reminders will arrive daily at 09:00"

**File verification:**
```bash
cat chat_settings/<chatId>_settings.json
# Should be:
{
  "chatId": ...,
  "temperature": 0.87,
  "reminderTime": "09:00",
  "reminderEnabled": true,
  "lastReminderSent": null
}
```

---

### Test 7.2: Simulating Morning Reminder
**Preparation:**
1. Create several reminders for today
2. Set reminderTime to current time +2 minutes

**What should happen in 2 minutes:**
- Message from bot with:
  - "🌅 Good morning! Here are your tasks for today:"
  - List of reminders (numbered)
  - "🌤️ Weather in Saint Petersburg:" + weather data
  - "😄 Joke of the day from Chuck Norris:" + translated joke

**What to check in logs:**
```bash
# Every minute should NOT have logs (we removed them)

# When reminder triggers:
✅ Sending reminders for chat <chatId>
📤 Sending reminders for chat <chatId>

# Should see 3 requests to GigaChat:
📤 Calling gigaClient.chatCompletion (functions=5)...  # get_reminders
📤 Calling gigaClient.chatCompletion (functions=5)...  # get_weather
📤 Calling gigaClient.chatCompletion (functions=5)...  # get_chuck_norris_joke

# Should NOT see:
❌ Request timeout has expired
❌ processesAlive=false
```

**File verification after sending:**
```bash
cat chat_settings/<chatId>_settings.json
# lastReminderSent should be updated:
{
  ...
  "lastReminderSent": "2026-01-29T09:00:35.123456"
}
```

---

### Test 7.3: Disabling Reminders
**Message to bot:** `/disableReminders`

**Expected result:**
- "🔕 Automatic reminders disabled"

**Verification:** Even if reminderTime matches current time, message should NOT arrive

---

## 8. Testing Edge Cases

### Test 8.1: Very Long Response
**Message to bot:** `Write a detailed article about quantum computers with 5000 words`

**Expected result:**
- Response truncated to 3800 characters
- "..." at the end

**What to check in logs:**
```
# If response.length > 3800:
val truncatedText = if (fullResponse.length > 3800) {
```

---

### Test 8.2: History Summarization
**Preparation:** Send 25+ messages (more than 20)

**Expected result after 21st message:**
- Additional message "Dialog of 10 messages, summarization performed..."

**What to check in logs:**
```
Starting summarization process for chat <chatId>
Received summarization: ...
```

**File verification:**
```bash
cat chat_history/<chatId>_history.json
# Should only have system message with context:
[
  {
    "role": "system",
    "content": "...\nPrevious context:\n<summary>"
  }
]
```

---

### Test 8.3: MCP Server Unavailable (Failure Simulation)
**Preparation:**
```bash
# Kill one of the MCP processes
lsof -ti:3001 | xargs kill -9
```

**Message to bot:** `What's the weather in Moscow?`

**Expected result:**
- Error or explanation that service is unavailable

**What to check in logs:**
```
🔍 HttpMcpService.isAvailable(): sessions=true (3), processesAlive=false (2), result=false
```

---

## 9. Testing Errors

### Test 9.1: Invalid Command Format
**Message to bot:** `/changeT abc`

**Expected result:**
- "Temperature must be a number from 0.0 to 1.0"

---

### Test 9.2: Invalid Time Format
**Message to bot:** `/setReminderTime 25:00`

**Expected result:**
- "❌ Invalid time format. Use HH:mm (e.g., 09:00)"

---

### Test 9.3: GigaChat Unavailable (Simulation)
**Preparation:** Temporarily change authorizationKey to incorrect value

**Message to bot:** `Hello`

**Expected result:**
- "An error occurred while processing the message: ..."

**What to check in logs:**
```
GigaChat error: ...
Error processing message from chat <chatId>: ...
```

---

## 10. Final Verification Checklist

### Before Release Check:

- [ ] All 5 MCP tools available (/listTools)
- [ ] MCP server processes are alive (check in startup logs)
- [ ] Weather works for different cities
- [ ] Reminders created with correct date (regression test "today")
- [ ] Reminders can be retrieved and deleted
- [ ] Chuck Norris jokes translated to Russian
- [ ] Scheduler sends morning messages with weather + joke
- [ ] No visual MCP markers in bot responses
- [ ] No spam in logs from scheduler every minute
- [ ] Timeouts configured (60 seconds for HTTP requests)
- [ ] History summarized after 20 messages
- [ ] Commands /changeRole, /changeT, /clearChat work
- [ ] Errors handled correctly (invalid command formats)

---

## 11. Debugging Utilities

### Viewing Logs with Filtering
```bash
# Start bot and save logs
./gradlew run 2>&1 | tee bot.log

# Filter only MCP logs
grep -E "(MCP|mcp|🔧|⚡|🔍)" bot.log

# Filter only tool calls
grep -E "(functionCall|function_call)" bot.log

# Filter scheduler
grep -E "(Scheduler|reminders)" bot.log

# Filter errors
grep -E "(Error|error|❌|Exception)" bot.log
```

### Monitoring MCP Processes
```bash
# Check that all 3 processes are alive
lsof -ti:3001 && echo "weather OK" || echo "weather DEAD"
lsof -ti:3002 && echo "reminders OK" || echo "reminders DEAD"
lsof -ti:3003 && echo "chuck OK" || echo "chuck DEAD"
```

### Data Verification
```bash
# View chat history
cat chat_history/<chatId>_history.json | jq .

# View chat settings
cat chat_settings/<chatId>_settings.json | jq .

# View all reminders
cat mcp-reminders-server/reminders.json | jq .
```

### Cleanup for Repeat Testing
```bash
# Delete all data
rm -rf chat_history/ chat_settings/
rm mcp-reminders-server/reminders.json

# Kill all MCP processes
lsof -ti:3001 | xargs kill -9 2>/dev/null
lsof -ti:3002 | xargs kill -9 2>/dev/null
lsof -ti:3003 | xargs kill -9 2>/dev/null
```

---

## 12. Testing Success Criteria

### ✅ All tests passed if:

1. **MCP Integration**
   - All 5 tools work
   - Function calling triggers automatically (not as text)
   - MCP call results processed correctly

2. **Reminders Logic**
   - Dates calculated correctly ("today", "tomorrow", "day after tomorrow")
   - Scheduler sends messages at correct time
   - No duplicates (lastReminderSent check)

3. **Stability**
   - No process leaks (ports cleaned up)
   - No timeouts when working with weather
   - Graceful shutdown works correctly

4. **UX**
   - No visual MCP markers
   - No spam in logs
   - Errors handled gracefully

5. **Performance**
   - Responses arrive within reasonable time (<10 sec for simple requests)
   - Summarization triggers automatically
   - Tokens used efficiently
