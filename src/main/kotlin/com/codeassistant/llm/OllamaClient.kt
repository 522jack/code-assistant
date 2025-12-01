package com.codeassistant.llm

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

@Serializable
data class OllamaEmbeddingRequest(
    val model: String,
    val prompt: String
)

@Serializable
data class OllamaEmbeddingResponse(
    val embedding: List<Double>
)

/**
 * Client for interacting with Ollama API
 */
class OllamaClient(
    private val httpClient: HttpClient,
    private val baseUrl: String = "http://localhost:11434"
) {
    /**
     * Generate embedding for a given text using Ollama
     */
    suspend fun generateEmbedding(
        text: String,
        model: String = "nomic-embed-text"
    ): Result<List<Double>> {
        return try {
            logger.debug { "Generating embedding for text of length ${text.length} with model $model" }

            val request = OllamaEmbeddingRequest(
                model = model,
                prompt = text
            )

            val response: HttpResponse = httpClient.post("$baseUrl/api/embeddings") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }

            if (response.status.isSuccess()) {
                val embeddingResponse = response.body<OllamaEmbeddingResponse>()
                logger.debug { "Successfully generated embedding of dimension ${embeddingResponse.embedding.size}" }
                Result.success(embeddingResponse.embedding)
            } else {
                val error = "Ollama embedding request failed: ${response.status}"
                logger.error { error }
                Result.failure(Exception(error))
            }
        } catch (e: Exception) {
            logger.error(e) { "Error generating embedding" }
            val errorMessage = when {
                e.message?.contains("Connection refused", ignoreCase = true) == true ->
                    "Cannot connect to Ollama at $baseUrl. Please start Ollama and ensure the '$model' model is installed."
                e.message?.contains("ConnectException", ignoreCase = true) == true ->
                    "Cannot connect to Ollama at $baseUrl. Please start Ollama and ensure the '$model' model is installed."
                e.message?.contains("timeout", ignoreCase = true) == true ->
                    "Ollama connection timeout. Please check if Ollama is running."
                else -> "Ollama error: ${e.message ?: "Unknown error"}"
            }
            Result.failure(Exception(errorMessage, e))
        }
    }

    /**
     * Generate embeddings for multiple texts in batch
     */
    suspend fun generateEmbeddings(
        texts: List<String>,
        model: String = "nomic-embed-text"
    ): Result<List<List<Double>>> {
        return try {
            val embeddings = mutableListOf<List<Double>>()

            texts.forEachIndexed { index, text ->
                logger.debug { "Generating embedding ${index + 1}/${texts.size}" }
                val result = generateEmbedding(text, model)

                if (result.isSuccess) {
                    embeddings.add(result.getOrThrow())
                } else {
                    return Result.failure(result.exceptionOrNull() ?: Exception("Unknown error"))
                }
            }

            Result.success(embeddings)
        } catch (e: Exception) {
            logger.error(e) { "Error generating embeddings batch" }
            Result.failure(e)
        }
    }

    /**
     * Check if Ollama is available
     */
    suspend fun checkHealth(): Boolean {
        return try {
            val response: HttpResponse = httpClient.get("$baseUrl/api/tags")
            response.status.isSuccess()
        } catch (e: Exception) {
            logger.warn(e) { "Ollama health check failed" }
            false
        }
    }
}