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
    val messages: List<GigaChatMessage>
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
