package com.codeassistant.task

import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * MCP Tool interface for task operations
 * Follows the same pattern as GitTool
 */
class TaskTool(
    private val taskService: TaskService
) {
    /**
     * Create a new task
     */
    suspend fun createTask(
        title: String,
        description: String = "",
        priority: TaskPriority = TaskPriority.MEDIUM,
        tags: List<String> = emptyList()
    ): Result<Task> {
        return try {
            logger.info { "Creating task: $title" }
            taskService.createTask(title, description, priority, tags)
        } catch (e: Exception) {
            logger.error(e) { "Error creating task" }
            Result.failure(e)
        }
    }

    /**
     * Get task by ID
     */
    fun getTask(id: String): Result<Task> {
        return try {
            val task = taskService.getTask(id)
            if (task != null) {
                Result.success(task)
            } else {
                Result.failure(IllegalArgumentException("Task not found: $id"))
            }
        } catch (e: Exception) {
            logger.error(e) { "Error getting task" }
            Result.failure(e)
        }
    }

    /**
     * List tasks with optional filtering
     */
    fun listTasks(
        priority: TaskPriority? = null,
        status: TaskStatus? = null,
        limit: Int = 50
    ): Result<List<Task>> {
        return try {
            val filter = TaskFilter(
                priority = priority,
                status = status,
                limit = limit
            )
            val tasks = taskService.listTasks(filter)
            Result.success(tasks)
        } catch (e: Exception) {
            logger.error(e) { "Error listing tasks" }
            Result.failure(e)
        }
    }

    /**
     * Update task status
     */
    fun updateTaskStatus(id: String, status: TaskStatus): Result<Task> {
        return try {
            logger.info { "Updating task $id status to $status" }
            taskService.updateTaskStatus(id, status)
        } catch (e: Exception) {
            logger.error(e) { "Error updating task status" }
            Result.failure(e)
        }
    }

    /**
     * Update task fields
     */
    fun updateTask(
        id: String,
        title: String? = null,
        description: String? = null,
        priority: TaskPriority? = null,
        status: TaskStatus? = null,
        tags: List<String>? = null
    ): Result<Task> {
        return try {
            logger.info { "Updating task $id" }
            val update = TaskUpdate(
                title = title,
                description = description,
                priority = priority,
                status = status,
                tags = tags
            )
            taskService.updateTask(id, update)
        } catch (e: Exception) {
            logger.error(e) { "Error updating task" }
            Result.failure(e)
        }
    }

    /**
     * Delete task
     */
    fun deleteTask(id: String): Result<Unit> {
        return try {
            logger.info { "Deleting task $id" }
            taskService.deleteTask(id)
        } catch (e: Exception) {
            logger.error(e) { "Error deleting task" }
            Result.failure(e)
        }
    }

    /**
     * Get task recommendations
     */
    suspend fun getRecommendations(topK: Int = 3): Result<List<TaskRecommendation>> {
        return try {
            logger.info { "Generating task recommendations" }
            val config = RecommendationConfig(topK = topK)
            taskService.getRecommendations(config)
        } catch (e: Exception) {
            logger.error(e) { "Error generating recommendations" }
            Result.failure(e)
        }
    }

    /**
     * Get task statistics
     */
    fun getStatistics(): Result<TaskStatistics> {
        return try {
            val stats = taskService.getStatistics()
            Result.success(stats)
        } catch (e: Exception) {
            logger.error(e) { "Error getting statistics" }
            Result.failure(e)
        }
    }
}
