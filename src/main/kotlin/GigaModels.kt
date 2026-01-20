import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
    val content: String
)

@Serializable
data class GigaChatChatRequest(
    val model: String,
    val messages: List<GigaChatMessage>,
    val temperature: Float
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
    val role: String
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
