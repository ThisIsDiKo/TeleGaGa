import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Опции для Ollama API (temperature и другие параметры)
 */
@Serializable
data class OllamaOptions(
    val temperature: Float? = null,
    val top_p: Float? = null,
    val top_k: Int? = null
)

// ─── Tool Calling Models ──────────────────────────────────────────────────────

/**
 * Определение функции инструмента (Ollama/OpenAI-совместимый формат)
 */
@Serializable
data class OllamaToolFunction(
    val name: String,
    val description: String,
    val parameters: JsonObject
)

/**
 * Инструмент для передачи в Ollama запрос
 */
@Serializable
data class OllamaTool(
    val type: String = "function",
    val function: OllamaToolFunction
)

/**
 * Функция, которую модель хочет вызвать (из ответа модели)
 */
@Serializable
data class OllamaToolCallFunction(
    val name: String,
    val arguments: JsonElement  // может быть JsonObject или строка-JSON
)

/**
 * Вызов инструмента из ответа модели
 */
@Serializable
data class OllamaToolCall(
    val function: OllamaToolCallFunction
)

// ─── Chat Request / Response ──────────────────────────────────────────────────

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class OllamaChatRequest(
    val model: String,
    val messages: List<GigaChatMessage>,
    val stream: Boolean,
    val options: OllamaOptions? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val tools: List<OllamaTool>? = null
)

@Serializable
data class OllamaChatResponse(
    val message: GigaChatMessage,
    val total_duration: Long,
    val prompt_eval_count: Long,
    val eval_count: Long
)

// ─── Embeddings ───────────────────────────────────────────────────────────────

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
