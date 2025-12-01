package com.codeassistant.llm

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

@Serializable
data class ClaudeMessageRequest(
    val model: String,
    val messages: List<ClaudeMessage>,
    @SerialName("max_tokens")
    val maxTokens: Int,
    val system: String? = null,
    val temperature: Double? = null
)

@Serializable
data class ClaudeMessage(
    val role: String, // "user" or "assistant"
    val content: String
)

@Serializable
data class ClaudeMessageResponse(
    val id: String,
    val type: String,
    val role: String,
    val content: List<ClaudeContent>,
    val model: String,
    @SerialName("stop_reason")
    val stopReason: String? = null,
    val usage: ClaudeUsage
)

@Serializable
data class ClaudeContent(
    val type: String, // "text"
    val text: String? = null
)

@Serializable
data class ClaudeUsage(
    @SerialName("input_tokens")
    val inputTokens: Int? = null,
    @SerialName("output_tokens")
    val outputTokens: Int? = null
)

@Serializable
data class ClaudeErrorResponse(
    val type: String,
    val error: ClaudeError
)

@Serializable
data class ClaudeError(
    val type: String,
    val message: String
)

/**
 * Client for Claude API
 */
class ClaudeClient(
    private val httpClient: HttpClient,
    private val apiKey: String,
    private val baseUrl: String = "https://api.anthropic.com/v1"
) {
    companion object {
        const val ANTHROPIC_VERSION = "2023-06-01"
    }

    /**
     * Send a message to Claude and get a response
     */
    suspend fun sendMessage(
        messages: List<ClaudeMessage>,
        model: String = "claude-sonnet-4-5-20250929",
        systemPrompt: String? = null,
        maxTokens: Int = 4096,
        temperature: Double? = null
    ): Result<ClaudeMessageResponse> {
        return try {
            logger.debug { "Sending message to Claude (model: $model)" }

            val request = ClaudeMessageRequest(
                model = model,
                messages = messages,
                maxTokens = maxTokens,
                system = systemPrompt,
                temperature = temperature
            )

            val response: HttpResponse = httpClient.post("$baseUrl/messages") {
                header("x-api-key", apiKey)
                header("anthropic-version", ANTHROPIC_VERSION)
                contentType(ContentType.Application.Json)
                setBody(request)
            }

            if (response.status.isSuccess()) {
                val messageResponse = response.body<ClaudeMessageResponse>()
                logger.debug { "Received response from Claude: ${messageResponse.content.firstOrNull()?.text?.take(100)}..." }
                logger.info { "Token usage: input=${messageResponse.usage.inputTokens}, output=${messageResponse.usage.outputTokens}" }
                Result.success(messageResponse)
            } else {
                val errorBody = response.bodyAsText()
                logger.error { "Claude API error: ${response.status} - $errorBody" }

                try {
                    val errorResponse = response.body<ClaudeErrorResponse>()
                    Result.failure(Exception("Claude API error: ${errorResponse.error.message}"))
                } catch (e: Exception) {
                    Result.failure(Exception("Claude API error: ${response.status} - $errorBody"))
                }
            }

        } catch (e: Exception) {
            logger.error(e) { "Error sending message to Claude" }
            Result.failure(e)
        }
    }

    /**
     * Send a simple text message and get text response
     */
    suspend fun chat(
        userMessage: String,
        systemPrompt: String? = null,
        conversationHistory: List<ClaudeMessage> = emptyList()
    ): Result<String> {
        val messages = conversationHistory + ClaudeMessage(role = "user", content = userMessage)

        val response = sendMessage(
            messages = messages,
            systemPrompt = systemPrompt
        )

        return response.map { resp ->
            resp.content.firstOrNull { it.type == "text" }?.text
                ?: throw Exception("No text content in response")
        }
    }
}