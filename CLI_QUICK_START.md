# CLI RAG Chatbot - Quick Start Guide

## 🚀 5-Minute Setup

### Prerequisites Check
```bash
# 1. Check Ollama is running
curl http://localhost:11434/api/tags

# 2. Check models are installed
ollama list
# Should show: llama3.2:3b, nomic-embed-text

# 3. If models missing, install them
ollama pull llama3.2:3b
ollama pull nomic-embed-text
```

### First Run
```bash
# 1. Set Java 17
export JAVA_HOME=/Users/dmitriikonovalov/Library/Java/JavaVirtualMachines/openjdk-17.0.1/Contents/Home

# 2. Build project
./gradlew build

# 3. Run CLI
./gradlew runCli
```

### Initial Setup (First Time Only)
```bash
# In CLI, create embeddings from your docs
/createEmbeddings

# Adjust threshold if needed (optional)
/setThreshold 0.6
```

---

## 📚 Adding Your Documentation

```bash
# 1. Create folder (if not exists)
mkdir -p rag_docs

# 2. Add your markdown files
cp your_doc.md rag_docs/
cp another_doc.md rag_docs/

# 3. In CLI, create embeddings
/createEmbeddings
```

**Supported**: Only `.md` (Markdown) files

---

## 💬 Using the Chatbot

### Example Session
```
You: What is in the documentation?

The documentation covers [specific topics from your docs]...

[Mode: Documentation-based answer]
Sources:
1. readme.md (lines 15-42) - 92% relevance
```

```
You: What is machine learning?

Machine learning is a subset of AI that enables systems to learn...

[Mode: General knowledge - no relevant documentation found]
(Best match: 15%, threshold: 50%)
```

---

## ⚙️ Threshold Control

### Understanding Threshold

**What it does**: Controls when to use docs vs general knowledge

| Threshold | Behavior |
|-----------|----------|
| **30-40%** | Almost always uses docs |
| **50-60%** | Balanced (default) |
| **70-80%** | Mostly general knowledge |

### Changing Threshold
```bash
# View current
/threshold

# Set to 70% (stricter)
/setThreshold 0.7

# Set to 40% (looser)
/setThreshold 0.4
```

**Tip**: Start with default (50%), adjust based on results.

---

## 🎯 Common Commands

```bash
/help              # Show all commands
/threshold         # View current threshold
/setThreshold 0.6  # Change threshold
/createEmbeddings  # Process docs
/stats             # Session statistics
/clear             # Clear history
/exit              # Quit
```

---

## 🔍 How It Works

### Question Flow
```
Your Question
    ↓
Search Documentation (all .md files)
    ↓
Check Best Match >= Threshold?
    ↓
├─ YES (≥50%) → Use Documentation + Citations
└─ NO  (<50%) → Use General Knowledge
    ↓
Display Answer + Mode Indicator
```

### Mode Indicators

**Documentation Mode** (RAG used):
```
[Mode: Documentation-based answer]
Sources:
1. file.md (lines X-Y) - Z% relevance
```

**General Knowledge Mode** (RAG not used):
```
[Mode: General knowledge - no relevant documentation found]
(Best match: 23%, threshold: 50%)
```

---

## 🐛 Troubleshooting

### "No embeddings found"
```bash
# Solution: Create embeddings
/createEmbeddings
```

### "Ollama connection error"
```bash
# Solution: Start Ollama
ollama serve

# In another terminal
ollama pull llama3.2:3b
ollama pull nomic-embed-text
```

### RAG never/always triggers
```bash
# Solution: Adjust threshold
/setThreshold 0.6  # Try different values
```

### Build fails
```bash
# Solution: Clean and rebuild
export JAVA_HOME=/path/to/java17
./gradlew clean build
```

---

## 📊 Understanding Output

### Full Response Example
```
You: How do I create embeddings?

To create embeddings, use the /createEmbeddings command in the CLI
[To create embeddings, use the /createEmbeddings command].
This processes all markdown files in the rag_docs folder
[processes all markdown files in the rag_docs folder].

[Mode: Documentation-based answer]
Sources:
1. CLI_QUICK_START.md (lines 15-25) - 95% relevance
2. CLI_RAG_IMPLEMENTATION.md (lines 234-256) - 88% relevance
```

**Components**:
- **Answer**: LLM response with [citations]
- **Mode**: Documentation-based or General knowledge
- **Sources**: File, lines, relevance percentage

---

## 🎓 Best Practices

### 1. Documentation Quality
- ✅ Clear, concise markdown
- ✅ Good structure (headings, lists)
- ✅ Specific information
- ❌ Avoid very long paragraphs
- ❌ Don't mix topics

### 2. Threshold Settings
- **Technical docs**: 0.5-0.6 (default works well)
- **Sparse docs**: 0.3-0.4 (lower for better matching)
- **General chat**: 0.7-0.8 (higher for creativity)

### 3. Question Formulation
- ✅ "What MCP servers are used?"
- ✅ "How to create embeddings?"
- ✅ "What is the default threshold?"
- ❌ Vague: "Tell me about this"
- ❌ Too broad: "Everything about the project"

### 4. Managing Embeddings
- Re-run `/createEmbeddings` after updating docs
- Keep docs in `rag_docs/` organized
- Use descriptive filenames

---

## 🔄 Typical Workflow

```bash
# 1. Start CLI
./gradlew runCli

# 2. Check threshold
/threshold

# 3. Ask project-specific question
You: What models are used?
# → Gets documentation-based answer

# 4. Ask general question
You: What is REST API?
# → Gets general knowledge answer

# 5. View stats
/stats

# 6. Update docs and re-create embeddings
# (Outside CLI: edit files in rag_docs/)
/createEmbeddings

# 7. Continue chatting...
```

---

## 📈 Performance Tips

### Faster Responses
- Lower `topK` (e.g., `--topK 3`)
- Higher threshold (less RAG processing)
- Smaller documentation files

### Better Accuracy
- Higher `topK` (e.g., `--topK 7`)
- Lower threshold (more RAG usage)
- Well-structured docs with headers

### Balanced (Recommended)
```bash
./gradlew runCli --args="--threshold 0.5 --topK 5"
```

---

## 🆘 Getting Help

### In CLI
```bash
/help  # Show all commands
```

### Documentation
- Full guide: `CLI_RAG_IMPLEMENTATION.md`
- Changelog: `CHANGELOG_CLI.md`
- Project readme: `CLAUDE.md`

### Common Questions

**Q: Can I use PDF files?**
A: No, only `.md` (Markdown) files supported.

**Q: How many docs can I add?**
A: No hard limit, but performance degrades after ~50 files.

**Q: Can I change the model?**
A: Currently hardcoded to llama3.2:3b. Edit `OllamaClient.kt` to change.

**Q: Does history persist?**
A: No, history is in-memory only. Cleared on restart.

**Q: Can I export conversations?**
A: Not yet, but you can use `/stats` to see session info.

---

## ✅ Success Checklist

Before asking questions, verify:
- [ ] Ollama is running (`curl http://localhost:11434/api/tags`)
- [ ] Models installed (llama3.2:3b, nomic-embed-text)
- [ ] Documentation in `rag_docs/` folder
- [ ] Embeddings created (`/createEmbeddings`)
- [ ] CLI started successfully
- [ ] Threshold set appropriately (`/threshold`)

---

## 🎉 You're Ready!

Start chatting with your documentation-aware AI assistant!

```
You: <your question here>
```

**Happy chatting!** 🚀
