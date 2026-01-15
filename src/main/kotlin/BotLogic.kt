import com.github.kotlintelegrambot.entities.Update

suspend fun handleTextUpdate(
    gigaClient: GigaChatClient,
    gigaModel: String,
    update: Update,
    gigaChatHistory: MutableList<GigaChatMessage>,
    reply: (chatId: Long, text: String) -> Unit
) {
    val message = update.message ?: return
    val chatId = message.chat.id
    val text = message.text ?: return

    val answer = try {
        gigaChatHistory.add(GigaChatMessage(role = "user", content = text))
        val modelAnswer = gigaClient.chatCompletion(
            model = gigaModel,
            messages = gigaChatHistory
        )
        gigaChatHistory.add(GigaChatMessage(role = "assistant", content = modelAnswer))

        gigaChatHistory.forEach {
            println("${it.role} -> ${it.content}")
        }

        modelAnswer

    } catch (e: Exception) {
        println("GigaChat error: ${e}")
        "Ошибка при обращении к LLM"
    }

    reply(chatId, answer.ifBlank { "Пустой ответ от модели" })
}

