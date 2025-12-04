package com.codeassistant.task

import com.codeassistant.git.GitTool
import com.codeassistant.llm.ClaudeClient
import com.codeassistant.rag.RagSearchConfig
import com.codeassistant.rag.RagSearchResult
import com.codeassistant.rag.RagService
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.UUID

private val logger = KotlinLogging.logger {}

/**
 * Service for managing tasks
 * Provides CRUD operations, filtering, and AI-powered recommendations
 */
class TaskService(
    private val ragService: RagService,
    private val claudeClient: ClaudeClient,
    private val gitTool: GitTool
) {
    private var currentIndex: TaskIndex? = null

    /**
     * Create a new task
     */
    suspend fun createTask(
        title: String,
        description: String,
        priority: TaskPriority,
        tags: List<String> = emptyList(),
        relatedFiles: List<String> = emptyList()
    ): Result<Task> {
        return try {
            // Validate input
            if (title.isBlank()) {
                return Result.failure(IllegalArgumentException("Task title cannot be empty"))
            }
            if (title.length > 200) {
                return Result.failure(IllegalArgumentException("Task title too long (max 200 chars)"))
            }
            if (description.length > 5000) {
                return Result.failure(IllegalArgumentException("Task description too long (max 5000 chars)"))
            }

            val now = System.currentTimeMillis()
            val task = Task(
                id = UUID.randomUUID().toString().substring(0, 8),
                title = title,
                description = description,
                priority = priority,
                status = TaskStatus.TODO,
                tags = tags,
                createdAt = now,
                updatedAt = now,
                relatedFiles = relatedFiles
            )

            // Add to index
            val index = currentIndex ?: TaskIndex(
                tasks = emptyList(),
                lastUpdated = now,
                projectHash = ""
            )

            val updatedIndex = index.copy(
                tasks = index.tasks + task,
                lastUpdated = now
            )
            currentIndex = updatedIndex

            logger.info { "Created task: ${task.id} - ${task.title}" }
            Result.success(task)

        } catch (e: Exception) {
            logger.error(e) { "Error creating task" }
            Result.failure(e)
        }
    }

    /**
     * Get task by ID
     */
    fun getTask(id: String): Task? {
        val index = currentIndex ?: return null
        return index.tasks.find { it.id == id || it.id.startsWith(id) }
    }

    /**
     * List all tasks with optional filtering
     */
    fun listTasks(filter: TaskFilter = TaskFilter()): List<Task> {
        val index = currentIndex ?: return emptyList()

        return index.tasks
            .filter { task ->
                (filter.priority == null || task.priority == filter.priority) &&
                (filter.status == null || task.status == filter.status) &&
                (filter.tags.isEmpty() || task.tags.any { it in filter.tags }) &&
                (filter.searchQuery == null ||
                    task.title.contains(filter.searchQuery, ignoreCase = true) ||
                    task.description.contains(filter.searchQuery, ignoreCase = true))
            }
            .sortedWith(
                compareByDescending<Task> { it.priority.ordinal }
                    .thenByDescending { it.status == TaskStatus.IN_PROGRESS }
                    .thenBy { it.createdAt }
            )
            .drop(filter.offset)
            .take(filter.limit)
    }

    /**
     * Update task status
     */
    fun updateTaskStatus(id: String, status: TaskStatus): Result<Task> {
        return try {
            val task = getTask(id)
                ?: return Result.failure(IllegalArgumentException("Task not found: $id"))

            val now = System.currentTimeMillis()
            val updatedTask = task.copy(
                status = status,
                updatedAt = now,
                completedAt = if (status == TaskStatus.DONE) now else task.completedAt
            )

            // Update in index
            val index = currentIndex!!
            val updatedIndex = index.copy(
                tasks = index.tasks.map { if (it.id == task.id) updatedTask else it },
                lastUpdated = now
            )
            currentIndex = updatedIndex

            logger.info { "Updated task ${task.id} status: ${task.status} -> $status" }
            Result.success(updatedTask)

        } catch (e: Exception) {
            logger.error(e) { "Error updating task status" }
            Result.failure(e)
        }
    }

    /**
     * Update task fields
     */
    fun updateTask(id: String, update: TaskUpdate): Result<Task> {
        return try {
            val task = getTask(id)
                ?: return Result.failure(IllegalArgumentException("Task not found: $id"))

            val now = System.currentTimeMillis()
            val updatedTask = task.copy(
                title = update.title ?: task.title,
                description = update.description ?: task.description,
                priority = update.priority ?: task.priority,
                status = update.status ?: task.status,
                tags = update.tags ?: task.tags,
                relatedFiles = update.relatedFiles ?: task.relatedFiles,
                updatedAt = now,
                completedAt = if (update.status == TaskStatus.DONE && task.completedAt == null) now else task.completedAt
            )

            // Update in index
            val index = currentIndex!!
            val updatedIndex = index.copy(
                tasks = index.tasks.map { if (it.id == task.id) updatedTask else it },
                lastUpdated = now
            )
            currentIndex = updatedIndex

            logger.info { "Updated task ${task.id}" }
            Result.success(updatedTask)

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
            val task = getTask(id)
                ?: return Result.failure(IllegalArgumentException("Task not found: $id"))

            // Remove from index
            val index = currentIndex!!
            val updatedIndex = index.copy(
                tasks = index.tasks.filter { it.id != task.id },
                lastUpdated = System.currentTimeMillis()
            )
            currentIndex = updatedIndex

            logger.info { "Deleted task ${task.id}" }
            Result.success(Unit)

        } catch (e: Exception) {
            logger.error(e) { "Error deleting task" }
            Result.failure(e)
        }
    }

    /**
     * Analyze task using RAG to find relevant code/documentation
     */
    suspend fun analyzeTaskWithRAG(task: Task): Result<List<RagSearchResult>> {
        return try {
            // Search for relevant context
            val searchQuery = "${task.title} ${task.description}"
            val ragResults = ragService.search(
                query = searchQuery,
                config = RagSearchConfig(topK = 5, minSimilarity = 0.5)
            )

            if (ragResults.isFailure) {
                logger.warn { "RAG search failed for task ${task.id}: ${ragResults.exceptionOrNull()?.message}" }
                return Result.success(emptyList())
            }

            val results = ragResults.getOrThrow()
            logger.info { "Found ${results.size} relevant documents for task ${task.id}" }

            Result.success(results)

        } catch (e: Exception) {
            logger.error(e) { "Error analyzing task with RAG" }
            Result.success(emptyList()) // Don't fail, just return empty list
        }
    }

    /**
     * Get task recommendations (placeholder - will be implemented with TaskRecommendationEngine)
     */
    suspend fun getRecommendations(config: RecommendationConfig = RecommendationConfig()): Result<List<TaskRecommendation>> {
        return try {
            val index = currentIndex
            if (index == null || index.tasks.isEmpty()) {
                return Result.success(emptyList())
            }

            // Filter tasks
            val tasks = index.tasks.filter {
                it.status != TaskStatus.DONE &&
                (config.priorityFilter == null || it.priority == config.priorityFilter)
            }

            if (tasks.isEmpty()) {
                return Result.success(emptyList())
            }

            // Simple scoring for now (will be replaced by TaskRecommendationEngine)
            val recommendations = tasks.map { task ->
                val score = calculateSimpleScore(task)
                TaskRecommendation(
                    task = task,
                    score = score,
                    reasoning = "Score based on priority (${task.priority}) and status (${task.status})",
                    relevantContext = emptyList()
                )
            }
                .sortedByDescending { it.score }
                .take(config.topK)

            Result.success(recommendations)

        } catch (e: Exception) {
            logger.error(e) { "Error generating recommendations" }
            Result.failure(e)
        }
    }

    /**
     * Simple scoring algorithm (will be enhanced in TaskRecommendationEngine)
     */
    private fun calculateSimpleScore(task: Task): Double {
        var score = 0.0

        // Priority weight
        score += when (task.priority) {
            TaskPriority.HIGH -> 3.0
            TaskPriority.MEDIUM -> 2.0
            TaskPriority.LOW -> 1.0
        }

        // Status weight
        score += when (task.status) {
            TaskStatus.IN_PROGRESS -> 2.5 // Complete ongoing work
            TaskStatus.TODO -> 2.0
            TaskStatus.BLOCKED -> 0.5
            TaskStatus.DONE -> 0.0
        }

        // Age factor (older tasks get slight boost)
        val daysSinceCreation = (System.currentTimeMillis() - task.createdAt) / (1000 * 60 * 60 * 24)
        val ageFactor = kotlin.math.min(daysSinceCreation / 30.0, 1.0) // Cap at 30 days
        score += ageFactor * 0.5

        return score
    }

    /**
     * Load task index
     */
    fun loadIndex(index: TaskIndex) {
        currentIndex = index
        logger.info { "Loaded task index: ${index.tasks.size} tasks" }
    }

    /**
     * Get current task index
     */
    fun getIndex(): TaskIndex? {
        return currentIndex
    }

    /**
     * Clear task index
     */
    fun clearIndex() {
        currentIndex = null
        logger.info { "Cleared task index" }
    }

    /**
     * Get task statistics
     */
    fun getStatistics(): TaskStatistics {
        val index = currentIndex ?: return TaskStatistics()

        return TaskStatistics(
            total = index.tasks.size,
            byPriority = mapOf(
                TaskPriority.HIGH to index.tasks.count { it.priority == TaskPriority.HIGH },
                TaskPriority.MEDIUM to index.tasks.count { it.priority == TaskPriority.MEDIUM },
                TaskPriority.LOW to index.tasks.count { it.priority == TaskPriority.LOW }
            ),
            byStatus = mapOf(
                TaskStatus.TODO to index.tasks.count { it.status == TaskStatus.TODO },
                TaskStatus.IN_PROGRESS to index.tasks.count { it.status == TaskStatus.IN_PROGRESS },
                TaskStatus.DONE to index.tasks.count { it.status == TaskStatus.DONE },
                TaskStatus.BLOCKED to index.tasks.count { it.status == TaskStatus.BLOCKED }
            )
        )
    }
}

/**
 * Configuration for recommendations
 */
data class RecommendationConfig(
    val topK: Int = 3,
    val priorityFilter: TaskPriority? = null,
    val includeBlocked: Boolean = false
)

/**
 * Task statistics
 */
data class TaskStatistics(
    val total: Int = 0,
    val byPriority: Map<TaskPriority, Int> = emptyMap(),
    val byStatus: Map<TaskStatus, Int> = emptyMap()
)
