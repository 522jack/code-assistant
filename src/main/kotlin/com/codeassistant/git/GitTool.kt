package com.codeassistant.git

import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File

private val logger = KotlinLogging.logger {}

/**
 * Git information result
 */
data class GitInfo(
    val currentBranch: String,
    val status: String? = null,
    val lastCommit: String? = null
)

/**
 * Tool for Git operations via MCP
 */
class GitTool(
    private val workingDir: File = File(System.getProperty("user.dir"))
) {
    /**
     * Get current git branch
     */
    fun getCurrentBranch(): Result<String> {
        return try {
            val process = ProcessBuilder("git", "branch", "--show-current")
                .directory(workingDir)
                .redirectOutput(ProcessBuilder.Redirect.PIPE)
                .redirectError(ProcessBuilder.Redirect.PIPE)
                .start()

            val output = process.inputStream.bufferedReader().readText().trim()
            val error = process.errorStream.bufferedReader().readText().trim()
            val exitCode = process.waitFor()

            if (exitCode == 0 && output.isNotEmpty()) {
                logger.info { "Current branch: $output" }
                Result.success(output)
            } else {
                val errorMsg = if (error.isNotEmpty()) error else "Failed to get current branch"
                logger.error { "Git error: $errorMsg" }
                Result.failure(Exception(errorMsg))
            }

        } catch (e: Exception) {
            logger.error(e) { "Error getting current branch" }
            Result.failure(e)
        }
    }

    /**
     * Get git status
     */
    fun getStatus(): Result<String> {
        return try {
            val process = ProcessBuilder("git", "status", "--short")
                .directory(workingDir)
                .redirectOutput(ProcessBuilder.Redirect.PIPE)
                .redirectError(ProcessBuilder.Redirect.PIPE)
                .start()

            val output = process.inputStream.bufferedReader().readText().trim()
            val exitCode = process.waitFor()

            if (exitCode == 0) {
                val status = if (output.isEmpty()) "Clean working directory" else output
                logger.info { "Git status: $status" }
                Result.success(status)
            } else {
                val error = process.errorStream.bufferedReader().readText().trim()
                logger.error { "Git error: $error" }
                Result.failure(Exception(error))
            }

        } catch (e: Exception) {
            logger.error(e) { "Error getting git status" }
            Result.failure(e)
        }
    }

    /**
     * Get last commit message
     */
    fun getLastCommit(): Result<String> {
        return try {
            val process = ProcessBuilder("git", "log", "-1", "--pretty=%B")
                .directory(workingDir)
                .redirectOutput(ProcessBuilder.Redirect.PIPE)
                .redirectError(ProcessBuilder.Redirect.PIPE)
                .start()

            val output = process.inputStream.bufferedReader().readText().trim()
            val exitCode = process.waitFor()

            if (exitCode == 0 && output.isNotEmpty()) {
                logger.info { "Last commit: $output" }
                Result.success(output)
            } else {
                val error = process.errorStream.bufferedReader().readText().trim()
                logger.error { "Git error: $error" }
                Result.failure(Exception(error))
            }

        } catch (e: Exception) {
            logger.error(e) { "Error getting last commit" }
            Result.failure(e)
        }
    }

    /**
     * Get comprehensive git info
     */
    fun getGitInfo(): Result<GitInfo> {
        return try {
            val branchResult = getCurrentBranch()
            if (branchResult.isFailure) {
                return Result.failure(branchResult.exceptionOrNull() ?: Exception("Failed to get branch"))
            }

            val branch = branchResult.getOrThrow()
            val status = getStatus().getOrNull()
            val lastCommit = getLastCommit().getOrNull()

            Result.success(
                GitInfo(
                    currentBranch = branch,
                    status = status,
                    lastCommit = lastCommit
                )
            )

        } catch (e: Exception) {
            logger.error(e) { "Error getting git info" }
            Result.failure(e)
        }
    }
}