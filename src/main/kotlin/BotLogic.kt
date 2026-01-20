import com.github.kotlintelegrambot.entities.Update

suspend fun handleTextUpdate(
    gigaClient: GigaChatClient,
    ollamaClient: OllamaClient,
    gigaModel: String,
    update: Update,
    gigaChatHistory: MutableList<GigaChatMessage>,
    temperature: Float,
    reply: (chatId: Long, text: String) -> Unit
) {
    val message = update.message ?: return
    val chatId = message.chat.id
    val text = message.text ?: return

    gigaChatHistory.add(GigaChatMessage(role = "user", content = text))

    val gigaChatAnswer = try {

        val modelAnswer = gigaClient.chatCompletion(
            model = gigaModel,
            messages = gigaChatHistory,
            temperature = temperature
        )

        //Удалим возможность запоминаня контекста

        //gigaChatHistory.add(GigaChatMessage(role = "assistant", content = modelAnswer))

        val g = buildString {
            appendLine("*** Gigachat T = $temperature ***")
            append(modelAnswer.choices.firstOrNull()?.message?.content ?: "")
            appendLine("\nTotal time: Нет данных")
            appendLine("Tokens: ${modelAnswer.usage.totalTokens}")
        }

        g

    } catch (e: Exception) {
        println("GigaChat error: ${e}")
        "Ошибка при обращении к Gigachat LLM"
    }

    reply(chatId, gigaChatAnswer.ifBlank { "Пустой ответ от модели" })

    val ollamaAnswer  = try {

        val modelAnswer = ollamaClient.chatCompletion(
            messages = gigaChatHistory,
        )

        val o = buildString {
            appendLine("*** Llama3.2:1b  ***")
            append(modelAnswer.message.content)
            appendLine("\nTotal time: ${modelAnswer.total_duration}")
            appendLine("Tokens: ${modelAnswer.eval_count}")
        }

        o

    } catch (e: Exception) {
        println("Ollama error: ${e}")
        "Ошибка при обращении к Ollama LLM"
    }

    gigaChatHistory.removeLast()


    reply(chatId, ollamaAnswer.ifBlank { "Пустой ответ от модели" })


}

