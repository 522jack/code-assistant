package com.codeassistant.rag

import kotlinx.serialization.Serializable

/**
 * Represents a document that can be indexed for RAG
 */
@Serializable
data class RagDocument(
    val id: String,
    val title: String,
    val content: String,
    val timestamp: Long,
    val filePath: String,
    val metadata: Map<String, String> = emptyMap()
)

/**
 * Represents a text chunk from a document
 */
@Serializable
data class TextChunk(
    val id: String,
    val documentId: String,
    val content: String,
    val chunkIndex: Int,
    val startPosition: Int,
    val endPosition: Int
)

/**
 * Represents an embedding vector with associated chunk
 */
@Serializable
data class ChunkEmbedding(
    val chunkId: String,
    val documentId: String,
    val content: String,
    val embedding: List<Double>,
    val chunkIndex: Int
)

/**
 * Represents the indexed knowledge base
 */
@Serializable
data class RagIndex(
    val documents: List<RagDocument>,
    val embeddings: List<ChunkEmbedding>,
    val lastUpdated: Long,
    val projectHash: String
)

/**
 * Search result from RAG index
 */
data class RagSearchResult(
    val chunk: ChunkEmbedding,
    val similarity: Double,
    val documentTitle: String,
    val filePath: String
)

/**
 * Configuration for RAG search
 */
data class RagSearchConfig(
    val topK: Int = 5,
    val minSimilarity: Double = 0.6
)