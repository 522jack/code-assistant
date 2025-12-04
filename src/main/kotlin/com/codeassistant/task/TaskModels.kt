package com.codeassistant.task

import kotlinx.serialization.Serializable

/**
 * Represents a task with priority and status
 */
@Serializable
data class Task(
    val id: String,
    val title: String,
    val description: String,
    val priority: TaskPriority,
    val status: TaskStatus,
    val tags: List<String> = emptyList(),
    val createdAt: Long,
    val updatedAt: Long,
    val completedAt: Long? = null,
    val relatedFiles: List<String> = emptyList(),
    val metadata: Map<String, String> = emptyMap()
)

/**
 * Task priority levels
 */
@Serializable
enum class TaskPriority {
    HIGH,
    MEDIUM,
    LOW;

    companion object {
        fun fromString(value: String): TaskPriority? {
            return entries.find { it.name.equals(value, ignoreCase = true) }
        }

        fun fromNumber(value: Int): TaskPriority? {
            return when (value) {
                1 -> HIGH
                2 -> MEDIUM
                3 -> LOW
                else -> null
            }
        }
    }
}

/**
 * Task status
 */
@Serializable
enum class TaskStatus {
    TODO,
    IN_PROGRESS,
    DONE,
    BLOCKED;

    companion object {
        fun fromString(value: String): TaskStatus? {
            return entries.find { it.name.equals(value, ignoreCase = true) }
        }
    }
}

/**
 * Represents the task index for a project
 */
@Serializable
data class TaskIndex(
    val tasks: List<Task>,
    val lastUpdated: Long,
    val projectHash: String,
    val version: Int = 1
)

/**
 * Task recommendation with score and reasoning
 */
data class TaskRecommendation(
    val task: Task,
    val score: Double,
    val reasoning: String,
    val relevantContext: List<String> = emptyList()
)

/**
 * Filter for querying tasks
 */
data class TaskFilter(
    val priority: TaskPriority? = null,
    val status: TaskStatus? = null,
    val tags: List<String> = emptyList(),
    val searchQuery: String? = null,
    val limit: Int = 50,
    val offset: Int = 0
)

/**
 * Update data for tasks
 */
data class TaskUpdate(
    val title: String? = null,
    val description: String? = null,
    val priority: TaskPriority? = null,
    val status: TaskStatus? = null,
    val tags: List<String>? = null,
    val relatedFiles: List<String>? = null
)

/**
 * Project context for recommendations
 */
data class ProjectContext(
    val gitInfo: GitInfo,
    val recentFiles: List<String>,
    val recentWork: String
)

/**
 * Git information for context
 */
data class GitInfo(
    val currentBranch: String,
    val lastCommit: String,
    val status: String
)
