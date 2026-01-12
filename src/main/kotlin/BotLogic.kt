import com.github.kotlintelegrambot.entities.Update

suspend fun handleTextUpdate(
    gigaClient: GigaChatClient,
    gigaModel: String,
    update: Update,
    reply: (chatId: Long, text: String) -> Unit
) {
    val message = update.message ?: return
    val chatId = message.chat.id
    val text = message.text ?: return

    val answer = try {
        val messages = listOf(
            GigaChatMessage(role = "system", content = "Ты ученый из Вологды"),
            GigaChatMessage(role = "user", content = text)
        )
        gigaClient.chatCompletion(
            model = gigaModel,
            messages = messages
        )
    } catch (e: Exception) {
        println("GigaChat error: ${e}")
        "Ошибка при обращении к LLM"
    }

    reply(chatId, answer.ifBlank { "Пустой ответ от модели" })
}
