package com.codeassistant.task

import com.codeassistant.config.AppConfig
import com.codeassistant.project.ProjectInfo
import kotlinx.serialization.json.Json
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File

private val logger = KotlinLogging.logger {}

/**
 * Manager for storing and loading task indexes
 * Follows the same pattern as IndexManager
 */
class TaskManager(
    private val config: AppConfig
) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    /**
     * Load task index for project
     */
    fun loadTasks(projectInfo: ProjectInfo): TaskIndex? {
        return try {
            val tasksFile = config.getProjectTasksFile(projectInfo.projectHash)

            if (!tasksFile.exists()) {
                logger.info { "No tasks found for project ${projectInfo.projectHash}" }
                return null
            }

            val tasksJson = tasksFile.readText()
            val index = json.decodeFromString<TaskIndex>(tasksJson)

            logger.info { "Loaded tasks for project ${projectInfo.projectHash}: ${index.tasks.size} tasks" }
            index

        } catch (e: Exception) {
            logger.error(e) { "Error loading tasks for project ${projectInfo.projectHash}" }

            // Try to create backup of corrupted file
            try {
                val tasksFile = config.getProjectTasksFile(projectInfo.projectHash)
                val backupFile = File(tasksFile.parent, "tasks.json.backup")
                tasksFile.copyTo(backupFile, overwrite = true)
                logger.warn { "Created backup of corrupted tasks file at ${backupFile.absolutePath}" }
            } catch (backupError: Exception) {
                logger.error(backupError) { "Failed to create backup of corrupted tasks file" }
            }

            null
        }
    }

    /**
     * Save task index for project
     */
    fun saveTasks(projectInfo: ProjectInfo, index: TaskIndex): Result<Unit> {
        return try {
            val tasksFile = config.getProjectTasksFile(projectInfo.projectHash)

            // Ensure directory exists
            tasksFile.parentFile?.mkdirs()

            // Save tasks
            val tasksJson = json.encodeToString(TaskIndex.serializer(), index)
            tasksFile.writeText(tasksJson)

            logger.info { "Saved tasks for project ${projectInfo.projectHash} to ${tasksFile.absolutePath}" }
            Result.success(Unit)

        } catch (e: Exception) {
            logger.error(e) { "Error saving tasks for project ${projectInfo.projectHash}" }
            Result.failure(e)
        }
    }

    /**
     * Check if tasks exist for project
     */
    fun hasTasks(projectInfo: ProjectInfo): Boolean {
        val tasksFile = config.getProjectTasksFile(projectInfo.projectHash)
        return tasksFile.exists()
    }

    /**
     * Delete tasks for project
     */
    fun deleteTasks(projectInfo: ProjectInfo): Result<Unit> {
        return try {
            val tasksDir = config.getProjectTasksDir(projectInfo.projectHash)

            if (tasksDir.exists()) {
                tasksDir.deleteRecursively()
                logger.info { "Deleted tasks for project ${projectInfo.projectHash}" }
            }

            Result.success(Unit)

        } catch (e: Exception) {
            logger.error(e) { "Error deleting tasks for project ${projectInfo.projectHash}" }
            Result.failure(e)
        }
    }

    /**
     * Get tasks file size
     */
    fun getTasksSize(projectInfo: ProjectInfo): Long {
        val tasksFile = config.getProjectTasksFile(projectInfo.projectHash)
        return if (tasksFile.exists()) tasksFile.length() else 0
    }

    /**
     * List all cached projects with tasks
     */
    fun listCachedProjects(): List<String> {
        val tasksDir = File(config.cacheDir, "tasks")
        if (!tasksDir.exists()) {
            return emptyList()
        }

        return tasksDir.listFiles()
            ?.filter { it.isDirectory }
            ?.map { it.name }
            ?: emptyList()
    }

    /**
     * Clear all cached tasks
     */
    fun clearAllCaches(): Result<Unit> {
        return try {
            val tasksDir = File(config.cacheDir, "tasks")
            if (tasksDir.exists()) {
                tasksDir.deleteRecursively()
                logger.info { "Cleared all cached tasks" }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            logger.error(e) { "Error clearing task caches" }
            Result.failure(e)
        }
    }
}
