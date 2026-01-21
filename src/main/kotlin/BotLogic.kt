import com.github.kotlintelegrambot.entities.Update

suspend fun handleTextUpdate(
    gigaClient: GigaChatClient,
    ollamaClient: OllamaClient,
    gigaModel: String,
    update: Update,
    gigaChatHistory: MutableList<GigaChatMessage>,
    temperature: Float,
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

        //Удалим возможность запоминаня контекста

        //gigaChatHistory.add(GigaChatMessage(role = "assistant", content = modelAnswer))

        //Обрезаем текст, так как проект учебный, а обходить ограничения телеграмма пока не хочется.
        val preparedText = modelAnswer.choices.firstOrNull()?.message?.content ?: ""
        val tText = if (preparedText.length > 3800){
            preparedText.substring(0, 3799) + "..."
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

    // отключим возможность опроса OLLAMA
    /*
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




    reply(chatId, ollamaAnswer.ifBlank { "Пустой ответ от модели" })
    */
    gigaChatHistory.removeLast()
}

