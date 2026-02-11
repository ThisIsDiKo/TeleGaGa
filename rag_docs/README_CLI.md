# CLI RAG Chatbot - Documentation Index

## 🎯 Quick Navigation

Choose the document that fits your needs:

---

### 📘 For Users - Getting Started

**[CLI_QUICK_START.md](CLI_QUICK_START.md)** (6.6 KB)
- ⏱️ Read time: 5 minutes
- 🎯 Purpose: Get up and running quickly
- 📋 Contains:
  - 5-minute setup guide
  - Command reference
  - Usage examples
  - Troubleshooting tips
  - Best practices

**👉 Start here if you just want to use the chatbot!**

---

### 📖 For Developers - Technical Details

**[CLI_RAG_IMPLEMENTATION.md](CLI_RAG_IMPLEMENTATION.md)** (18 KB)
- ⏱️ Read time: 20 minutes
- 🎯 Purpose: Understand the implementation
- 📋 Contains:
  - Complete architecture overview
  - Technical implementation details
  - Data flow diagrams
  - API reference
  - Performance characteristics
  - Testing checklist

**👉 Read this for deep technical understanding!**

---

### 📋 For Project Managers - Change Summary

**[CHANGELOG_CLI.md](CHANGELOG_CLI.md)** (4.5 KB)
- ⏱️ Read time: 3 minutes
- 🎯 Purpose: Track what changed
- 📋 Contains:
  - Version history (v1.0.0)
  - New features list
  - Modified components
  - Breaking changes
  - Migration guide
  - Testing status

**👉 Read this for release notes and change tracking!**

---

### 🗂️ For Code Reviewers - File Inventory

**[FILES_CHANGED.md](FILES_CHANGED.md)** (11 KB)
- ⏱️ Read time: 8 minutes
- 🎯 Purpose: See what files were touched
- 📋 Contains:
  - Complete file list (new + modified)
  - Line-by-line change descriptions
  - Code metrics and statistics
  - Dependency graph
  - Verification checklist

**👉 Read this before code review!**

---

## 🚀 Quick Start (TL;DR)

```bash
# 1. Prerequisites
ollama serve
ollama pull llama3.2:3b
ollama pull nomic-embed-text

# 2. Build and run
export JAVA_HOME=/Users/dmitriikonovalov/Library/Java/JavaVirtualMachines/openjdk-17.0.1/Contents/Home
./gradlew build
./gradlew runCli

# 3. Create embeddings
/createEmbeddings

# 4. Start chatting!
You: What is in the documentation?
```

**For details, see [CLI_QUICK_START.md](CLI_QUICK_START.md)**

---

## 📚 What is This?

A **CLI (Command Line Interface) chatbot** with **hybrid RAG (Retrieval-Augmented Generation)** capabilities.

### Key Features

✅ **Two Modes of Operation**:
1. **Documentation Mode**: Answers based on your docs with citations
2. **General Knowledge Mode**: Uses LLM knowledge when docs don't match

✅ **Smart Decision Making**:
- Automatically decides which mode to use
- Based on relevance threshold (configurable)
- Shows which mode was used in each response

✅ **Multi-File Support**:
- Search across all `.md` files in `rag_docs/`
- Citations show specific file and line numbers
- Aggregate results from multiple sources

✅ **Dynamic Control**:
- Adjust threshold on the fly (`/setThreshold`)
- Create embeddings from CLI (`/createEmbeddings`)
- View statistics (`/stats`)

---

## 💡 Use Cases

### Perfect For

✅ Developers wanting quick answers from project docs
✅ Teams with extensive documentation
✅ Technical support with knowledge bases
✅ Researchers working with multiple papers/docs
✅ Anyone wanting AI + doc search combination

### Not Ideal For

❌ Production user-facing chatbots (use Telegram bot instead)
❌ Non-technical users (no GUI)
❌ Real-time web applications (it's CLI-based)
❌ Mobile users (command line required)

---

## 🎨 Example Interaction

```
============================================================
CLI Chat Bot with Hybrid RAG
============================================================
RAG threshold: 50% (use /threshold to view, /setThreshold to change)
Ask questions in English. Type /help for commands.
============================================================

You: What MCP servers are used?

The project uses 5 MCP servers: weather server on port 3001,
reminders server on port 3002, and Chuck Norris jokes server
on port 3003 [The project uses 5 MCP servers].

[Mode: Documentation-based answer]
Sources:
1. readme.md (lines 15-42) - 92% relevance
2. MCP_INTEGRATION.md (lines 5-30) - 85% relevance

You: What is machine learning?

Machine learning is a subset of artificial intelligence that
enables systems to learn and improve from experience without
being explicitly programmed.

[Mode: General knowledge - no relevant documentation found]
(Best match: 18%, threshold: 50%)

You: /stats

Session Statistics:
- Questions asked: 2
- Total tokens used: 456
- Conversation history size: 4 messages
- Average tokens per question: 228
```

---

## 🏗️ Architecture at a Glance

```
┌─────────────────┐
│   User Input    │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│   CliChatbot    │  ← REPL interface
└────────┬────────┘    (commands, formatting)
         │
         ▼
┌─────────────────────┐
│ CliChatOrchestrator │  ← Business logic
└────────┬────────────┘    (threshold check, history)
         │
    ┌────┴────┐
    │         │
    ▼         ▼
┌─────────┐ ┌──────────┐
│   RAG   │ │   LLM    │
│ Service │ │ (Ollama) │
└─────────┘ └──────────┘
    │             │
    └──────┬──────┘
           │
           ▼
    ┌──────────────┐
    │   Response   │
    │ with Sources │
    └──────────────┘
```

**For detailed architecture, see [CLI_RAG_IMPLEMENTATION.md](CLI_RAG_IMPLEMENTATION.md)**

---

## 🔧 Technical Stack

| Component | Technology |
|-----------|-----------|
| **Language** | Kotlin |
| **LLM** | Ollama (llama3.2:3b) |
| **Embeddings** | Ollama (nomic-embed-text) |
| **HTTP Client** | Ktor |
| **Build Tool** | Gradle |
| **Java Version** | 17 |
| **Output Language** | English |

---

## 📊 What Was Changed?

### New Files (3 source + 4 docs)
1. `MainCli.kt` - Entry point
2. `CliChatOrchestrator.kt` - Chat logic
3. `CliChatbot.kt` - REPL interface
4. Documentation files (this and others)

### Modified Files (5)
1. `RagService.kt` - Multi-file search
2. `OllamaClient.kt` - Verbose flag
3. `EmbeddingService.kt` - Verbose flag
4. `build.gradle.kts` - runCli task
5. `CLAUDE.md` - Updated docs

**For detailed changes, see [FILES_CHANGED.md](FILES_CHANGED.md)**

---

## 🎓 Learning Path

### Beginner Path
1. Read [CLI_QUICK_START.md](CLI_QUICK_START.md) (5 min)
2. Try the chatbot (10 min)
3. Experiment with commands (15 min)
4. **Total**: ~30 minutes to productive use

### Advanced Path
1. Quick Start (5 min)
2. Read [CLI_RAG_IMPLEMENTATION.md](CLI_RAG_IMPLEMENTATION.md) (20 min)
3. Review [FILES_CHANGED.md](FILES_CHANGED.md) (8 min)
4. Check [CHANGELOG_CLI.md](CHANGELOG_CLI.md) (3 min)
5. **Total**: ~40 minutes to full understanding

### Developer Path
1. All of Advanced Path (40 min)
2. Review source code files (30 min)
3. Test with own documents (20 min)
4. **Total**: ~90 minutes to modification-ready

---

## ❓ FAQ

**Q: How is this different from the Telegram bot?**
A: CLI is for developers/command-line users. Telegram bot is for general users via messaging app.

**Q: Can I use both simultaneously?**
A: Yes! They share the same codebase but run independently.

**Q: Does this replace the Telegram bot?**
A: No, they serve different purposes. Telegram bot unchanged.

**Q: What files can I use for RAG?**
A: Only `.md` (Markdown) files currently supported.

**Q: How do I adjust when RAG is used?**
A: Use `/setThreshold` command. Higher = less RAG, lower = more RAG.

**Q: Is conversation history saved?**
A: No, CLI stores history in memory only. Cleared on restart.

**Q: Can I export conversations?**
A: Not yet, but you can use `/stats` for session info.

**Q: What if Ollama is not running?**
A: You'll get connection errors. Start Ollama with `ollama serve`.

---

## 🆘 Getting Help

### In CLI
```bash
/help  # Show all commands and help
```

### Documentation
- Quick issues: [CLI_QUICK_START.md](CLI_QUICK_START.md) → Troubleshooting section
- Technical issues: [CLI_RAG_IMPLEMENTATION.md](CLI_RAG_IMPLEMENTATION.md) → Technical Details
- Build issues: [FILES_CHANGED.md](FILES_CHANGED.md) → Verification Checklist

### Common Issues

| Issue | Quick Fix | Document |
|-------|-----------|----------|
| "No embeddings found" | `/createEmbeddings` | Quick Start |
| "Ollama error" | `ollama serve` | Quick Start |
| RAG not working | `/setThreshold 0.4` | Quick Start |
| Build fails | Check Java 17 | Files Changed |
| Import errors | `./gradlew clean build` | Files Changed |

---

## ✅ Quick Checklist

Before first use:
- [ ] Ollama installed
- [ ] Models downloaded (llama3.2:3b, nomic-embed-text)
- [ ] Java 17 set
- [ ] Project built
- [ ] rag_docs folder exists
- [ ] Docs added to rag_docs
- [ ] Embeddings created

---

## 📈 What's Next?

### Immediate Next Steps
1. Follow [CLI_QUICK_START.md](CLI_QUICK_START.md)
2. Create embeddings from your docs
3. Start asking questions!

### Future Enhancements (Potential)
- Conversation export to markdown/JSON
- Persistent history
- Multi-language support
- Streaming responses
- Custom system prompts
- More file formats (PDF, DOCX)
- Web UI frontend

---

## 📝 Document Versions

| Document | Version | Date | Size |
|----------|---------|------|------|
| README_CLI.md | 1.0.0 | 2026-02-06 | This file |
| CLI_QUICK_START.md | 1.0.0 | 2026-02-06 | 6.6 KB |
| CLI_RAG_IMPLEMENTATION.md | 1.0.0 | 2026-02-06 | 18 KB |
| CHANGELOG_CLI.md | 1.0.0 | 2026-02-06 | 4.5 KB |
| FILES_CHANGED.md | 1.0.0 | 2026-02-06 | 11 KB |

---

## 🎉 Ready to Start?

1. **New user?** → Read [CLI_QUICK_START.md](CLI_QUICK_START.md)
2. **Developer?** → Read [CLI_RAG_IMPLEMENTATION.md](CLI_RAG_IMPLEMENTATION.md)
3. **Reviewer?** → Read [FILES_CHANGED.md](FILES_CHANGED.md)
4. **Manager?** → Read [CHANGELOG_CLI.md](CHANGELOG_CLI.md)

**Choose your path and start exploring! 🚀**

---

**Version**: 1.0.0
**Date**: 2026-02-06
**Status**: ✅ Production Ready
**Maintained by**: Claude Sonnet 4.5
