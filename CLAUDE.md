# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.


I need you to run with java version tools located at /Users/dmitriikonovalov/Library/Java/JavaVirtualMachines/openjdk-17.0.1

## Project Overview

TeleGaGa is a Telegram bot written in Kotlin that interfaces with two LLM providers:
- **GigaChat** (Sberbank's LLM service) - the primary AI backend
- **Ollama** (local LLM) - for local model testing

The bot maintains conversation history with automatic summarization when the history exceeds 20 messages. It features configurable system prompts and temperature settings.

## Build and Run Commands

```bash
# Build the project
./gradlew build

# Run the application
./gradlew run

# Clean build artifacts
./gradlew clean
```

## Core Architecture

### Entry Point
- `Main.kt` - Application entry point that:
  - Initializes HTTP client with SSL certificate verification disabled (required for GigaChat's certificate issues)
  - Sets up GigaChat and Ollama clients
  - Configures Telegram bot with command handlers
  - Starts a health check server on port 12222
  - Manages conversation history with system prompts

### AI Clients
- `GigaChatClient.kt` - Handles GigaChat API communication with:
  - Token-based authentication with automatic refresh on 401 errors
  - Thread-safe token management using Mutex
  - OAuth token endpoint: `https://ngw.devices.sberbank.ru:9443/api/v2/oauth`
  - Chat completions endpoint: `{baseUrl}/api/v1/chat/completions`

- `OllamaClient.kt` - Local LLM client for testing:
  - Uses hardcoded `llama3.2:1b` model
  - Endpoint: `http://localhost:11434/api/chat`

### Bot Logic
- `BotLogic.kt:handleTextUpdate()` - Core message handler that:
  - Adds user messages to conversation history
  - Calls GigaChat for completions
  - Adds assistant responses to history
  - Truncates responses to 3800 characters (Telegram limit workaround)
  - Triggers automatic summarization when history exceeds 20 messages
  - Summarization uses temperature 0.0 and condenses history to under 3000 characters
  - Clears history and reinitializes with summarized context

### Data Models
- `GigaModels.kt` - GigaChat request/response models with token usage tracking
- `OllamaModels.kt` - Ollama request/response models

## Bot Commands

- `/start` - Bot initialization (currently empty handler)
- `/changeRole <text>` - Updates the system prompt (gigaChatHistory[0])
- `/changeT <float>` - Changes the model temperature parameter
- `/destroyContext` - Legacy command for context overflow testing (no longer functional)

## Configuration

Authentication tokens and API keys must be set in Main.kt:
```kotlin
val telegramToken = "" // Line 53
val gigaAuthKey = "" // Line 55
```

Default settings:
- GigaChat model: "GigaChat"
- Temperature: 0.87
- Base URL: "https://gigachat.devices.sberbank.ru"
- History limit: 20 messages before summarization

## System Prompts

Three predefined system prompts are defined in Main.kt:
- `JsonRole` - For structured JSON responses
- `AssistantRole` - Expert assistant with clarifying questions
- `SingleRole` - ESP32 microcontroller systems expert (default)

## Key Implementation Details

- SSL verification is disabled in the HTTP client due to GigaChat certificate issues
- Token management uses Mutex for thread-safe access with automatic refresh
- Conversation history is preserved and automatically summarized to maintain context
- Responses are truncated to 3800 chars to fit Telegram's message limits
- Health check endpoint at `http://localhost:12222/` returns "Bot OK"
