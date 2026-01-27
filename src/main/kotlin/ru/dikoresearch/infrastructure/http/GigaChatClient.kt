package ru.dikoresearch.infrastructure.http

import GigaChatChatRequest
import GigaChatFunction
import GigaChatMessage
import GigaChatResponse
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

class GigaChatClient(
    private val httpClient: HttpClient,
    private val baseUrl: String,
    private val authorizationKey: String,
    private val scope: String = "GIGACHAT_API_PERS"
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    @Volatile
    private var currentToken: String? = null

    suspend fun chatCompletion(
        model: String,
        messages: List<GigaChatMessage>,
        temperature: Float,
        functions: List<GigaChatFunction>? = null,
        functionCall: String? = null
    ): GigaChatResponse {
        return callWithTokenRetry { token ->
            val rqUID = UUID.randomUUID().toString()

            val requestBody = GigaChatChatRequest(
                model = model,
                messages = messages,
                temperature = temperature,
                functions = functions,
                functionCall = functionCall
            )

            println("GigaChat Request: $requestBody")
            if (functions != null) {
                println("Functions передано: ${functions.size} шт.")
            }

            httpClient.post("$baseUrl/api/v1/chat/completions") {
                contentType(ContentType.Application.Json)
                bearerAuth(token)
                header("RqUID", rqUID)
                setBody(requestBody)
            }
        }
    }

    private suspend fun callWithTokenRetry(
        requestBlock: suspend (token: String) -> HttpResponse
    ): GigaChatResponse {
        var token = getOrRequestToken()

        var response = requestBlock(token)

        if (response.status == HttpStatusCode.Unauthorized) {
            println("GigaChat: got 401, refreshing access token")
            token = refreshTokenForce()
            response = requestBlock(token)
        }

        println(response.bodyAsText())

        val bodyText = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw IllegalStateException("GigaChat error: ${response.status}: $bodyText")
        }

        val parsed: GigaChatResponse = json.decodeFromString(bodyText)
        println("Gigachat json parsed : $parsed")

        return parsed
    }

    private suspend fun getOrRequestToken(): String {
        val existing = currentToken
        if (existing != null) return existing
        return mutex.withLock {
            currentToken?.let { return it }
            obtainTokenInternal()
        }
    }

    private suspend fun refreshTokenForce(): String {
        return mutex.withLock {
            obtainTokenInternal()
        }
    }

    private suspend fun obtainTokenInternal(): String {
        val rqUID = UUID.randomUUID().toString()

        val response: HttpResponse = httpClient.post("https://ngw.devices.sberbank.ru:9443/api/v2/oauth") {
            header(HttpHeaders.Authorization, "Basic $authorizationKey")
            header("RqUID", rqUID)
            setBody(
                FormDataContent(Parameters.build {
                    append("scope", scope)
                })
            )
        }

        val bodyText = response.bodyAsText()
        println("Response is $response")
        if (!response.status.isSuccess()) {
            throw IllegalStateException("Failed to get GigaChat token: ${response.status}: $bodyText")
        }

        val jsonElement = json.parseToJsonElement(bodyText).jsonObject
        val accessToken = jsonElement["access_token"]?.jsonPrimitive?.content
            ?: error("No access_token in response")

        currentToken = accessToken
        return accessToken
    }
}
