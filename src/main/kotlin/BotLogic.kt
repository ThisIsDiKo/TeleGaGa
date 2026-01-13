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
            GigaChatMessage(role = "system", content = JsonRole),
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

val JsonRole = "Ты — сервис, который отвечает ТОЛЬКО валидным JSON-объектом без пояснений и форматирования Markdown.\n" +
        "Всегда используй ровно такой формат:\n" +
        "\n" +
        "{\n" +
        "  \"datetime\": \"ISO 8601 строка с датой и временем запроса, например 2026-01-13T20:54:00+03:00\",\n" +
        "  \"model\": \"строка с названием модели, к которой был сделан запрос, например GigaChat\",\n" +
        "  \"question\": \"строка с исходным вопросом пользователя\",\n" +
        "  \"answer\": \"строка с ответом на вопрос\"\n" +
        "}\n" +
        "\n" +
        "Требования:\n" +
        "- Не добавляй никакого текста вне JSON.\n" +
        "- Всегда заполняй все поля.\n" +
        "- В поле dateime не должно быть лишних слов, только представление даты и времени\n" +
        "- Поле \"question\" копируй дословно из сообщения пользователя.\n" +
        "- Поле \"datetime\" указывай в часовом поясе пользователя (если известен)."
