package com.codeassistant.config

import java.io.File
import java.nio.file.Paths

/**
 * Application configuration
 */
data class AppConfig(
    val claudeApiKey: String,
    val ollamaUrl: String = "http://localhost:11434",
    val embeddingModel: String = "nomic-embed-text",
    val claudeModel: String = "claude-sonnet-4-5-20250929",
    val cacheDir: File = defaultCacheDir(),
    val githubToken: String? = null,  // GitHub token for API access
    val telegramBotToken: String? = null,  // Telegram bot token for notifications
    val telegramChatId: String? = null  // Default Telegram chat ID for notifications
) {
    companion object {
        fun defaultCacheDir(): File {
            val home = System.getProperty("user.home")
            return File(home, ".code-assistant")
        }

        /**
         * Load configuration from environment variables
         */
        fun fromEnvironment(): AppConfig {
            val apiKey = System.getenv("CLAUDE_API_KEY")
                ?: throw IllegalStateException("CLAUDE_API_KEY environment variable is required")

            val ollamaUrl = System.getenv("OLLAMA_URL") ?: "http://localhost:11434"
            val githubToken = System.getenv("GITHUB_TOKEN")
            val telegramBotToken = System.getenv("TELEGRAM_BOT_TOKEN")
            val telegramChatId = System.getenv("TELEGRAM_CHAT_ID")

            return AppConfig(
                claudeApiKey = apiKey,
                ollamaUrl = ollamaUrl,
                githubToken = githubToken,
                telegramBotToken = telegramBotToken,
                telegramChatId = telegramChatId
            )
        }
    }

    /**
     * Get index directory for a specific project
     */
    fun getProjectIndexDir(projectHash: String): File {
        return File(cacheDir, "indexes/$projectHash").apply {
            mkdirs()
        }
    }

    /**
     * Get index file for a specific project
     */
    fun getProjectIndexFile(projectHash: String): File {
        return File(getProjectIndexDir(projectHash), "rag_index.json")
    }

    /**
     * Get tasks directory for a specific project
     */
    fun getProjectTasksDir(projectHash: String): File {
        return File(cacheDir, "tasks/$projectHash").apply {
            mkdirs()
        }
    }

    /**
     * Get tasks file for a specific project
     */
    fun getProjectTasksFile(projectHash: String): File {
        return File(getProjectTasksDir(projectHash), "tasks.json")
    }
}

/**
 * Project-specific configuration (optional .code-assistant.yml)
 */
data class ProjectConfig(
    val docsPaths: List<String> = listOf("README.md", "docs/", "project/docs/"),
    val excludePatterns: List<String> = listOf("*.tmp", ".git", "node_modules", "build", "target"),
    val chunkSize: Int = 500,
    val chunkOverlap: Int = 50
) {
    companion object {
        fun default() = ProjectConfig()
    }
}