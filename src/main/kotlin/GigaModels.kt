import kotlinx.serialization.Serializable

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
