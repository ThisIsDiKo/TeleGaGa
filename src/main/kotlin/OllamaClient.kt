import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json

class OllamaClient(
    private val httpClient: HttpClient
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun chatCompletion(
        messages: List<GigaChatMessage>,
    ): OllamaChatResponse {

        val requestBody = OllamaChatRequest(
            model = "llama3.2:1b",
            messages = messages,
            stream = false
        )

        println("Request for ollama is $requestBody")
        println("Request for ollama is ${Json { prettyPrint = true }.encodeToString(requestBody)}")

        val response = httpClient.post("http://localhost:11434/api/chat") {
            contentType(ContentType.Application.Json)
            setBody(requestBody)
        }

        println("Response from ollama is $response -> ${response.bodyAsText()}")

        val bodyText = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw IllegalStateException("Ollama error: ${response.status}: $bodyText")
        }

        val parsed: OllamaChatResponse = json.decodeFromString(bodyText)

        println("Parsed ollama response: $parsed")

        return parsed
    }


}
