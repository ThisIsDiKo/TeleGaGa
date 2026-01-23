import com.github.kotlintelegrambot.entities.Update

suspend fun handleTextUpdate(
    systemRole: String,
    gigaClient: GigaChatClient,
    ollamaClient: OllamaClient,
    gigaModel: String,
    update: Update,
    gigaChatHistory: MutableList<GigaChatMessage>,
    temperature: Float,
    historyManager: ChatHistoryManager,
    destroy: Boolean = false,
    reply: (chatId: Long, text: String) -> Unit
) {
    val message = update.message ?: return
    val chatId = message.chat.id
    //val text = message.text ?: return

    val text = if (destroy){
        println("We will break context now")
        //Чтобы сломать контекст модели, нам нужно отправить 128к токенов
        //Ниже код, который получен эмпирическим путем
        //После получения такого запроса, Gigachat присылает ответ с ошибкой 422
        val filler = "Подробное описание датчика давления в пневмосистеме ESP32-C6. "
        filler.repeat(10000)
    }
    else {
        message.text ?: return
    }

    gigaChatHistory.add(GigaChatMessage(role = "user", content = text))

    val gigaChatAnswer = try {

        val modelAnswer = gigaClient.chatCompletion(
            model = gigaModel,
            messages = gigaChatHistory,
            temperature = temperature
        )

        val preparedText = modelAnswer.choices.firstOrNull()?.message?.content ?: ""

        //Удалим возможность запоминаня контекста

        gigaChatHistory.add(GigaChatMessage(role = "assistant", content = preparedText))

        //Обрезаем текст, так как проект учебный, а обходить ограничения телеграмма пока не хочется.
        val tText = if (preparedText.length > 3800){
            preparedText.take(3799) + "..."
        }
        else {
            preparedText
        }

        val g = buildString {
            appendLine("*** Gigachat T = $temperature ***")
            append(tText)

            appendLine("\n\nОтправлены токены: ${modelAnswer.usage.promptTokens}")
            appendLine("Получены токены: ${modelAnswer.usage.completionTokens}")
            appendLine("Оплачены токены ${modelAnswer.usage.totalTokens}")
        }

        g

    } catch (e: Exception) {
        println("GigaChat error: ${e}")
        "Ошибка при обращении к Gigachat LLM"
    }

    reply(chatId, gigaChatAnswer.ifBlank { "Пустой ответ от модели" })

    // Сохраняем историю после получения ответа от модели
    historyManager.saveHistory(chatId, gigaChatHistory)

    if (gigaChatHistory.size > 20){
        println("Запускаем процесс суммаризации")
        reply(chatId, "Диалог из 10 сообщений, запускаю сумамризацию")

        val summarySystemPrompt = GigaChatMessage(
            role = "system",
            content = "Ты - мастер пересказа. Кратко (до 3000 символов) опиши суть этого диалога, только факты без воды. Без примеров кода"
        )

        val summaryUserPrompt = GigaChatMessage(
            role = "user",
            content = gigaChatHistory.joinToString("\n"){"${it.role}: ${it.content}"}
        )

        val summaryRequest = listOf(summarySystemPrompt, summaryUserPrompt)

        val modelAnswer = gigaClient.chatCompletion(
            model = gigaModel,
            messages = summaryRequest,
            temperature = 0.0F
        )

        val chatSummary = modelAnswer.choices.firstOrNull()?.message?.content ?: ""
        println("Got summary: $chatSummary")

        reply(chatId, "Получили описание диалога:\n$chatSummary")

        val newSystemMessage = buildString {
            appendLine(systemRole)
            appendLine("Предыдущий контекст:")
            appendLine(chatSummary)
        }

        val newSystemPromt= GigaChatMessage(role = "system", content = newSystemMessage)

        gigaChatHistory.clear()
        gigaChatHistory.add(0, newSystemPromt)

        // Сохраняем обновленную историю после суммаризации
        historyManager.saveHistory(chatId, gigaChatHistory)
    }
}

