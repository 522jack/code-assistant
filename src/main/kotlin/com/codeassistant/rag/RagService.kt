package com.codeassistant.rag

import com.codeassistant.llm.OllamaClient
import kotlinx.coroutines.coroutineScope
import mu.KotlinLogging
import java.util.UUID
import kotlin.math.sqrt

private val logger = KotlinLogging.logger {}

/**
 * Service for RAG (Retrieval-Augmented Generation) operations
 */
class RagService(
    private val ollamaClient: OllamaClient,
    private val textChunker: TextChunker,
    private val embeddingModel: String = "nomic-embed-text"
) {
    private var currentIndex: RagIndex? = null

    /**
     * Index a document by chunking it and generating embeddings
     */
    suspend fun indexDocument(
        title: String,
        content: String,
        filePath: String,
        metadata: Map<String, String> = emptyMap()
    ): Result<RagDocument> = coroutineScope {
        try {
            logger.info { "Indexing document: $title (path: $filePath)" }

            // Create document
            val document = RagDocument(
                id = UUID.randomUUID().toString(),
                title = title,
                content = content,
                timestamp = System.currentTimeMillis(),
                filePath = filePath,
                metadata = metadata
            )

            // Chunk the text
            val chunks = textChunker.chunkText(content, document.id)
            logger.info { "Created ${chunks.size} chunks for document" }

            // Generate embeddings for all chunks
            val chunkTexts = chunks.map { it.content }
            val embeddingsResult = ollamaClient.generateEmbeddings(chunkTexts, embeddingModel)

            if (embeddingsResult.isFailure) {
                return@coroutineScope Result.failure(
                    embeddingsResult.exceptionOrNull() ?: Exception("Failed to generate embeddings")
                )
            }

            val embeddings = embeddingsResult.getOrThrow()

            // Create chunk embeddings
            val chunkEmbeddings = chunks.mapIndexed { index, chunk ->
                ChunkEmbedding(
                    chunkId = chunk.id,
                    documentId = document.id,
                    content = chunk.content,
                    embedding = embeddings[index],
                    chunkIndex = chunk.chunkIndex
                )
            }

            // Update index
            val existingDocuments = currentIndex?.documents ?: emptyList()
            val existingEmbeddings = currentIndex?.embeddings ?: emptyList()

            currentIndex = RagIndex(
                documents = existingDocuments + document,
                embeddings = existingEmbeddings + chunkEmbeddings,
                lastUpdated = System.currentTimeMillis(),
                projectHash = currentIndex?.projectHash ?: ""
            )

            logger.info { "Document indexed successfully with ${chunkEmbeddings.size} embeddings" }
            Result.success(document)

        } catch (e: Exception) {
            logger.error(e) { "Error indexing document" }
            Result.failure(e)
        }
    }

    /**
     * Search for relevant chunks based on a query
     */
    suspend fun search(
        query: String,
        config: RagSearchConfig = RagSearchConfig()
    ): Result<List<RagSearchResult>> = coroutineScope {
        try {
            val index = currentIndex
            if (index == null || index.embeddings.isEmpty()) {
                logger.warn { "No index available for search" }
                return@coroutineScope Result.success(emptyList())
            }

            logger.info { "Searching for query: $query" }

            // Generate embedding for query
            val queryEmbeddingResult = ollamaClient.generateEmbedding(query, embeddingModel)
            if (queryEmbeddingResult.isFailure) {
                return@coroutineScope Result.failure(
                    queryEmbeddingResult.exceptionOrNull() ?: Exception("Failed to generate query embedding")
                )
            }

            val queryEmbedding = queryEmbeddingResult.getOrThrow()

            // Calculate cosine similarities
            val results = index.embeddings.map { chunkEmbedding ->
                val similarity = cosineSimilarity(queryEmbedding, chunkEmbedding.embedding)
                val document = index.documents.find { it.id == chunkEmbedding.documentId }

                RagSearchResult(
                    chunk = chunkEmbedding,
                    similarity = similarity,
                    documentTitle = document?.title ?: "Unknown",
                    filePath = document?.filePath ?: ""
                )
            }
                .filter { it.similarity >= config.minSimilarity }
                .sortedByDescending { it.similarity }
                .take(config.topK)

            logger.info { "Found ${results.size} relevant chunks" }
            Result.success(results)

        } catch (e: Exception) {
            logger.error(e) { "Error searching" }
            Result.failure(e)
        }
    }

    /**
     * Calculate cosine similarity between two vectors
     */
    private fun cosineSimilarity(vec1: List<Double>, vec2: List<Double>): Double {
        if (vec1.size != vec2.size) {
            throw IllegalArgumentException("Vectors must have the same dimension")
        }

        var dotProduct = 0.0
        var norm1 = 0.0
        var norm2 = 0.0

        for (i in vec1.indices) {
            dotProduct += vec1[i] * vec2[i]
            norm1 += vec1[i] * vec1[i]
            norm2 += vec2[i] * vec2[i]
        }

        val denominator = sqrt(norm1) * sqrt(norm2)
        return if (denominator == 0.0) 0.0 else dotProduct / denominator
    }

    /**
     * Load existing index
     */
    fun loadIndex(index: RagIndex) {
        currentIndex = index
        logger.info { "Loaded index with ${index.documents.size} documents and ${index.embeddings.size} embeddings" }
    }

    /**
     * Get current index
     */
    fun getIndex(): RagIndex? = currentIndex

    /**
     * Clear the index
     */
    fun clearIndex() {
        currentIndex = null
        logger.info { "Index cleared" }
    }

    /**
     * Remove a document from the index
     */
    fun removeDocument(documentId: String) {
        val index = currentIndex ?: return

        currentIndex = index.copy(
            documents = index.documents.filter { it.id != documentId },
            embeddings = index.embeddings.filter { it.documentId != documentId },
            lastUpdated = System.currentTimeMillis()
        )

        logger.info { "Removed document $documentId from index" }
    }
}
