import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// Моделька для второго урока
@Serializable
data class AIChallengeResponse(
    val datetime: String,
    val model: String,
    val question: String,
    val answer: String
)

@Serializable
data class GigaChatMessage(
    val role: String,
    val content: String,
    @SerialName("function_call")
    val functionCall: GigaChatFunctionCall? = null
)

@Serializable
data class GigaChatChatRequest(
    val model: String,
    val messages: List<GigaChatMessage>,
    val temperature: Float,
    val functions: List<GigaChatFunction>? = null,
    @SerialName("function_call")
    val functionCall: String? = null // "auto" | "none" или {"name": "function_name"}
)

@Serializable
data class GigaChatChoiceMessage(
    val role: String,
    val content: String
)

@Serializable
data class GigaChatChoice(
    val index: Int,
    val message: GigaChatChoiceMessage
)

@Serializable
data class GigaChatChatResponse(
    val choices: List<GigaChatChoice>
)

//@Serializable
//data class GigaChatResponse(
//    val value: Value
//)

@Serializable
data class GigaChatResponse(
    val choices: List<Choice>,
    val created: Long,
    val model: String,
    val `object`: String,
    val usage: Usage
)

@Serializable
data class Choice(
    val message: Message,
    val index: Int,
    @SerialName("finish_reason")
    val finishReason: String
)

@Serializable
data class Message(
    val content: String,
    val role: String,
    @SerialName("function_call")
    val functionCall: GigaChatFunctionCall? = null
)

@Serializable
data class Usage(
    @SerialName("prompt_tokens")
    val promptTokens: Int,
    @SerialName("completion_tokens")
    val completionTokens: Int,
    @SerialName("total_tokens")
    val totalTokens: Int,
    @SerialName("precached_prompt_tokens")
    val precachedPromptTokens: Int
)

// === Models for Function Calling Support ===

/**
 * Определение функции для GigaChat API
 */
@Serializable
data class GigaChatFunction(
    val name: String,
    val description: String,
    val parameters: GigaChatFunctionParameters
)

/**
 * Параметры функции в формате JSON Schema
 */
@Serializable
data class GigaChatFunctionParameters(
    val type: String, // "object"
    val properties: Map<String, GigaChatPropertySchema>,
    val required: List<String>? = null
)

/**
 * Схема свойства параметра функции
 */
@Serializable
data class GigaChatPropertySchema(
    val type: String, // "string", "number", "boolean", "array", "object"
    val description: String? = null,
    val enum: List<String>? = null,
    val items: GigaChatPropertySchema? = null, // для массивов
    val properties: Map<String, GigaChatPropertySchema>? = null, // для объектов с известной структурой
    val additionalProperties: GigaChatPropertySchema? = null // для словарей с произвольными ключами
)

/**
 * Вызов функции от модели
 */
@Serializable
data class GigaChatFunctionCall(
    val name: String,
    val arguments: JsonElement // JSON объект или строка с аргументами
)
