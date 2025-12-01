package com.codeassistant.rag

import java.util.UUID

/**
 * Service for splitting text into chunks for RAG processing
 */
class TextChunker(
    private val chunkSize: Int = 500,
    private val chunkOverlap: Int = 50
) {
    /**
     * Split text into overlapping chunks
     */
    fun chunkText(
        text: String,
        documentId: String
    ): List<TextChunk> {
        if (text.isBlank()) {
            return emptyList()
        }

        // Safety check: limit maximum text size to prevent OOM
        val maxTextSize = 10_000_000 // 10MB of text
        if (text.length > maxTextSize) {
            throw IllegalArgumentException("Text too large for chunking. Maximum size: $maxTextSize characters")
        }

        val chunks = mutableListOf<TextChunk>()
        var startPosition = 0
        var chunkIndex = 0
        val minChunkStep = maxOf(1, chunkSize - chunkOverlap) // Prevent infinite loop

        while (startPosition < text.length) {
            // Calculate end position
            val endPosition = minOf(startPosition + chunkSize, text.length)

            // Extract chunk content
            var actualEndPosition = endPosition
            var chunkContent = text.substring(startPosition, actualEndPosition)

            // Try to break at sentence boundary if not at the end
            if (endPosition < text.length && chunkContent.length >= chunkSize) {
                val lastPeriod = chunkContent.lastIndexOf('.')
                val lastNewline = chunkContent.lastIndexOf('\n')
                val breakPoint = maxOf(lastPeriod, lastNewline)

                if (breakPoint > chunkSize / 2) {
                    actualEndPosition = startPosition + breakPoint + 1
                    chunkContent = text.substring(startPosition, actualEndPosition)
                }
            }

            // Create chunk
            val chunk = TextChunk(
                id = UUID.randomUUID().toString(),
                documentId = documentId,
                content = chunkContent.trim(),
                chunkIndex = chunkIndex,
                startPosition = startPosition,
                endPosition = actualEndPosition
            )

            chunks.add(chunk)

            // Move to next chunk with overlap (ensure we always move forward)
            val step = maxOf(minChunkStep, chunkContent.length - chunkOverlap)
            startPosition += step

            if (startPosition >= text.length) break

            chunkIndex++

            // Safety check: prevent too many chunks
            if (chunkIndex > 100_000) {
                throw IllegalStateException("Too many chunks. Please check chunk size configuration.")
            }
        }

        return chunks
    }

    /**
     * Split text by paragraphs and then chunk each paragraph
     */
    fun chunkByParagraphs(
        text: String,
        documentId: String
    ): List<TextChunk> {
        val paragraphs = text.split(Regex("\n\n+"))
        val allChunks = mutableListOf<TextChunk>()
        var globalChunkIndex = 0
        var globalPosition = 0

        paragraphs.forEach { paragraph ->
            if (paragraph.trim().isNotEmpty()) {
                if (paragraph.length <= chunkSize) {
                    // Small paragraph, keep as single chunk
                    val chunk = TextChunk(
                        id = UUID.randomUUID().toString(),
                        documentId = documentId,
                        content = paragraph.trim(),
                        chunkIndex = globalChunkIndex,
                        startPosition = globalPosition,
                        endPosition = globalPosition + paragraph.length
                    )
                    allChunks.add(chunk)
                    globalChunkIndex++
                } else {
                    // Large paragraph, split into chunks
                    val paragraphChunks = chunkText(paragraph, documentId)
                    paragraphChunks.forEach { chunk ->
                        allChunks.add(
                            chunk.copy(
                                chunkIndex = globalChunkIndex,
                                startPosition = globalPosition + chunk.startPosition,
                                endPosition = globalPosition + chunk.endPosition
                            )
                        )
                        globalChunkIndex++
                    }
                }
            }
            globalPosition += paragraph.length + 2 // +2 for \n\n
        }

        return allChunks
    }
}