import kotlinx.serialization.Serializable

@Serializable
data class OllamaChatRequest(
    val model: String,
    val messages: List<GigaChatMessage>,
    val stream: Boolean
)

@Serializable
data class OllamaChatResponse(
    val message: GigaChatMessage,
    val total_duration: Long,
    val prompt_eval_count: Long,
    val eval_count: Long
)