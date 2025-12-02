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
    val githubToken: String? = null  // GitHub token for API access
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

            return AppConfig(
                claudeApiKey = apiKey,
                ollamaUrl = ollamaUrl,
                githubToken = githubToken
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