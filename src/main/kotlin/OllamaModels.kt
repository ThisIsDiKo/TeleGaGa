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

// === Models for Ollama Embeddings API ===

/**
 * Запрос для генерации embeddings через Ollama
 */
@Serializable
data class OllamaEmbeddingRequest(
    val model: String, // например: "nomic-embed-text", "llama2", "mxbai-embed-large"
    val prompt: String
)

/**
 * Ответ от Ollama Embeddings API
 */
@Serializable
data class OllamaEmbeddingResponse(
    val embedding: List<Double>
)