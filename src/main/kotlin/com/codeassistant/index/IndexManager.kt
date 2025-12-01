package com.codeassistant.index

import com.codeassistant.config.AppConfig
import com.codeassistant.project.ProjectInfo
import com.codeassistant.rag.RagIndex
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import java.io.File

private val logger = KotlinLogging.logger {}

/**
 * Manager for storing and loading RAG indexes
 */
class IndexManager(
    private val config: AppConfig
) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    /**
     * Load index for project
     */
    fun loadIndex(projectInfo: ProjectInfo): RagIndex? {
        return try {
            val indexFile = config.getProjectIndexFile(projectInfo.projectHash)

            if (!indexFile.exists()) {
                logger.info { "No index found for project ${projectInfo.projectHash}" }
                return null
            }

            val indexJson = indexFile.readText()
            val index = json.decodeFromString<RagIndex>(indexJson)

            logger.info { "Loaded index for project ${projectInfo.projectHash}: ${index.documents.size} documents, ${index.embeddings.size} embeddings" }
            index

        } catch (e: Exception) {
            logger.error(e) { "Error loading index for project ${projectInfo.projectHash}" }
            null
        }
    }

    /**
     * Save index for project
     */
    fun saveIndex(projectInfo: ProjectInfo, index: RagIndex): Result<Unit> {
        return try {
            val indexFile = config.getProjectIndexFile(projectInfo.projectHash)

            // Ensure directory exists
            indexFile.parentFile?.mkdirs()

            // Save index
            val indexJson = json.encodeToString(RagIndex.serializer(), index)
            indexFile.writeText(indexJson)

            logger.info { "Saved index for project ${projectInfo.projectHash} to ${indexFile.absolutePath}" }
            Result.success(Unit)

        } catch (e: Exception) {
            logger.error(e) { "Error saving index for project ${projectInfo.projectHash}" }
            Result.failure(e)
        }
    }

    /**
     * Check if index exists for project
     */
    fun hasIndex(projectInfo: ProjectInfo): Boolean {
        val indexFile = config.getProjectIndexFile(projectInfo.projectHash)
        return indexFile.exists()
    }

    /**
     * Delete index for project
     */
    fun deleteIndex(projectInfo: ProjectInfo): Result<Unit> {
        return try {
            val indexDir = config.getProjectIndexDir(projectInfo.projectHash)

            if (indexDir.exists()) {
                indexDir.deleteRecursively()
                logger.info { "Deleted index for project ${projectInfo.projectHash}" }
            }

            Result.success(Unit)

        } catch (e: Exception) {
            logger.error(e) { "Error deleting index for project ${projectInfo.projectHash}" }
            Result.failure(e)
        }
    }

    /**
     * Get index file size
     */
    fun getIndexSize(projectInfo: ProjectInfo): Long {
        val indexFile = config.getProjectIndexFile(projectInfo.projectHash)
        return if (indexFile.exists()) indexFile.length() else 0
    }

    /**
     * List all cached projects
     */
    fun listCachedProjects(): List<String> {
        val indexesDir = File(config.cacheDir, "indexes")
        if (!indexesDir.exists()) {
            return emptyList()
        }

        return indexesDir.listFiles()
            ?.filter { it.isDirectory }
            ?.map { it.name }
            ?: emptyList()
    }

    /**
     * Clear all cached indexes
     */
    fun clearAllCaches(): Result<Unit> {
        return try {
            val indexesDir = File(config.cacheDir, "indexes")
            if (indexesDir.exists()) {
                indexesDir.deleteRecursively()
                logger.info { "Cleared all cached indexes" }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            logger.error(e) { "Error clearing caches" }
            Result.failure(e)
        }
    }
}