package com.codeassistant.github

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

private val logger = KotlinLogging.logger {}

/**
 * Tool for GitHub operations via gh CLI (Model Context Protocol)
 */
class GitHubTool(
    private val workingDir: File = File(System.getProperty("user.dir"))
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Get Pull Request information
     */
    fun getPullRequest(prNumber: String, repo: String): Result<PullRequestInfo> {
        return try {
            logger.info { "Fetching PR #$prNumber from $repo" }

            val process = ProcessBuilder(
                "gh", "pr", "view", prNumber,
                "--repo", repo,
                "--json", "number,title,body,headRefName,baseRefName,files"
            )
                .directory(workingDir)
                .redirectOutput(ProcessBuilder.Redirect.PIPE)
                .redirectError(ProcessBuilder.Redirect.PIPE)
                .start()

            val output = process.inputStream.bufferedReader().readText().trim()
            val error = process.errorStream.bufferedReader().readText().trim()
            val exitCode = process.waitFor()

            if (exitCode == 0 && output.isNotEmpty()) {
                val jsonElement = json.parseToJsonElement(output).jsonObject

                val number = jsonElement["number"]?.jsonPrimitive?.content?.toInt() ?: 0
                val title = jsonElement["title"]?.jsonPrimitive?.content ?: ""
                val body = jsonElement["body"]?.jsonPrimitive?.content ?: ""
                val headBranch = jsonElement["headRefName"]?.jsonPrimitive?.content ?: ""
                val baseBranch = jsonElement["baseRefName"]?.jsonPrimitive?.content ?: ""
                val filesArray = jsonElement["files"]?.jsonArray ?: emptyList()
                val changedFiles = filesArray.map {
                    it.jsonObject["path"]?.jsonPrimitive?.content ?: ""
                }

                val prInfo = PullRequestInfo(
                    number = number,
                    title = title,
                    body = body,
                    headBranch = headBranch,
                    baseBranch = baseBranch,
                    changedFiles = changedFiles
                )

                logger.info { "Successfully fetched PR #$prNumber: $title" }
                Result.success(prInfo)
            } else {
                val errorMsg = if (error.isNotEmpty()) error else "Failed to fetch PR info"
                logger.error { "GitHub CLI error: $errorMsg" }
                Result.failure(Exception(errorMsg))
            }

        } catch (e: Exception) {
            logger.error(e) { "Error fetching PR #$prNumber" }
            Result.failure(e)
        }
    }

    /**
     * Get Pull Request diff
     */
    fun getPullRequestDiff(prNumber: String): Result<String> {
        return try {
            logger.info { "Fetching diff for PR #$prNumber" }

            val process = ProcessBuilder("gh", "pr", "diff", prNumber)
                .directory(workingDir)
                .redirectOutput(ProcessBuilder.Redirect.PIPE)
                .redirectError(ProcessBuilder.Redirect.PIPE)
                .start()

            val output = process.inputStream.bufferedReader().readText()
            val error = process.errorStream.bufferedReader().readText().trim()
            val exitCode = process.waitFor()

            if (exitCode == 0) {
                logger.info { "Successfully fetched diff (${output.length} chars)" }
                Result.success(output)
            } else {
                val errorMsg = if (error.isNotEmpty()) error else "Failed to fetch PR diff"
                logger.error { "GitHub CLI error: $errorMsg" }
                Result.failure(Exception(errorMsg))
            }

        } catch (e: Exception) {
            logger.error(e) { "Error fetching PR diff" }
            Result.failure(e)
        }
    }

    /**
     * Get list of changed files in PR
     */
    fun getChangedFiles(prNumber: String): Result<List<String>> {
        return try {
            logger.info { "Fetching changed files for PR #$prNumber" }

            val process = ProcessBuilder(
                "gh", "pr", "view", prNumber,
                "--json", "files",
                "--jq", ".files[].path"
            )
                .directory(workingDir)
                .redirectOutput(ProcessBuilder.Redirect.PIPE)
                .redirectError(ProcessBuilder.Redirect.PIPE)
                .start()

            val output = process.inputStream.bufferedReader().readText().trim()
            val error = process.errorStream.bufferedReader().readText().trim()
            val exitCode = process.waitFor()

            if (exitCode == 0 && output.isNotEmpty()) {
                val files = output.split("\n").filter { it.isNotBlank() }
                logger.info { "Found ${files.size} changed files" }
                Result.success(files)
            } else {
                val errorMsg = if (error.isNotEmpty()) error else "Failed to fetch changed files"
                logger.error { "GitHub CLI error: $errorMsg" }
                Result.failure(Exception(errorMsg))
            }

        } catch (e: Exception) {
            logger.error(e) { "Error fetching changed files" }
            Result.failure(e)
        }
    }

    /**
     * Get file content from PR head branch
     */
    fun getFileContent(filePath: String, branch: String): Result<String> {
        return try {
            logger.info { "Fetching content for file: $filePath from branch: $branch" }

            val process = ProcessBuilder("git", "show", "$branch:$filePath")
                .directory(workingDir)
                .redirectOutput(ProcessBuilder.Redirect.PIPE)
                .redirectError(ProcessBuilder.Redirect.PIPE)
                .start()

            val output = process.inputStream.bufferedReader().readText()
            val error = process.errorStream.bufferedReader().readText().trim()
            val exitCode = process.waitFor()

            if (exitCode == 0) {
                logger.info { "Successfully fetched file content (${output.length} chars)" }
                Result.success(output)
            } else {
                val errorMsg = if (error.isNotEmpty()) error else "Failed to fetch file content"
                logger.error { "Git error: $errorMsg" }
                Result.failure(Exception(errorMsg))
            }

        } catch (e: Exception) {
            logger.error(e) { "Error fetching file content" }
            Result.failure(e)
        }
    }

    /**
     * Post comment to Pull Request
     */
    fun postComment(prNumber: String, repo: String, comment: String): Result<Unit> {
        return try {
            logger.info { "Posting comment to PR #$prNumber" }

            val process = ProcessBuilder(
                "gh", "pr", "comment", prNumber,
                "--repo", repo,
                "--body", comment
            )
                .directory(workingDir)
                .redirectOutput(ProcessBuilder.Redirect.PIPE)
                .redirectError(ProcessBuilder.Redirect.PIPE)
                .start()

            val error = process.errorStream.bufferedReader().readText().trim()
            val exitCode = process.waitFor()

            if (exitCode == 0) {
                logger.info { "Successfully posted comment to PR #$prNumber" }
                Result.success(Unit)
            } else {
                val errorMsg = if (error.isNotEmpty()) error else "Failed to post comment"
                logger.error { "GitHub CLI error: $errorMsg" }
                Result.failure(Exception(errorMsg))
            }

        } catch (e: Exception) {
            logger.error(e) { "Error posting comment to PR" }
            Result.failure(e)
        }
    }

    /**
     * Check if gh CLI is installed and authenticated
     */
    fun checkAuth(): Result<Boolean> {
        return try {
            val process = ProcessBuilder("gh", "auth", "status")
                .directory(workingDir)
                .redirectOutput(ProcessBuilder.Redirect.PIPE)
                .redirectError(ProcessBuilder.Redirect.PIPE)
                .start()

            val exitCode = process.waitFor()
            val isAuthenticated = exitCode == 0

            if (isAuthenticated) {
                logger.info { "GitHub CLI is authenticated" }
            } else {
                logger.warn { "GitHub CLI is not authenticated" }
            }

            Result.success(isAuthenticated)
        } catch (e: Exception) {
            logger.error(e) { "Error checking GitHub CLI auth" }
            Result.failure(e)
        }
    }
}