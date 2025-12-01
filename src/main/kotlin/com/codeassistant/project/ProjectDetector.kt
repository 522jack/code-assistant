package com.codeassistant.project

import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File
import java.security.MessageDigest

private val logger = KotlinLogging.logger {}

/**
 * Information about detected project
 */
data class ProjectInfo(
    val rootDir: File,
    val projectHash: String,
    val gitRemoteUrl: String?,
    val documents: List<DocumentFile>
)

/**
 * Information about a document file
 */
data class DocumentFile(
    val file: File,
    val relativePath: String,
    val title: String
)

/**
 * Detector for project information and documentation
 */
class ProjectDetector(
    private val workingDir: File = File(System.getProperty("user.dir"))
) {
    /**
     * Detect project information from working directory
     */
    fun detectProject(): Result<ProjectInfo> {
        return try {
            logger.info { "Detecting project in: ${workingDir.absolutePath}" }

            // Find git root
            val gitRoot = findGitRoot(workingDir)
            if (gitRoot == null) {
                return Result.failure(Exception("Not a git repository. Please run code-assistant from within a git project."))
            }

            logger.info { "Git root found: ${gitRoot.absolutePath}" }

            // Get git remote URL
            val gitRemoteUrl = getGitRemoteUrl(gitRoot)
            logger.info { "Git remote URL: ${gitRemoteUrl ?: "none"}" }

            // Generate project hash
            val projectHash = generateProjectHash(gitRoot, gitRemoteUrl)
            logger.info { "Project hash: $projectHash" }

            // Scan for documentation
            val documents = scanDocumentation(gitRoot)
            logger.info { "Found ${documents.size} documentation files" }

            Result.success(
                ProjectInfo(
                    rootDir = gitRoot,
                    projectHash = projectHash,
                    gitRemoteUrl = gitRemoteUrl,
                    documents = documents
                )
            )
        } catch (e: Exception) {
            logger.error(e) { "Error detecting project" }
            Result.failure(e)
        }
    }

    /**
     * Find git root directory from current directory
     */
    private fun findGitRoot(dir: File): File? {
        var current: File? = dir.absoluteFile

        while (current != null) {
            val gitDir = File(current, ".git")
            if (gitDir.exists() && gitDir.isDirectory) {
                return current
            }
            current = current.parentFile
        }

        return null
    }

    /**
     * Get git remote URL
     */
    private fun getGitRemoteUrl(gitRoot: File): String? {
        return try {
            val process = ProcessBuilder("git", "config", "--get", "remote.origin.url")
                .directory(gitRoot)
                .redirectOutput(ProcessBuilder.Redirect.PIPE)
                .redirectError(ProcessBuilder.Redirect.PIPE)
                .start()

            val output = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()

            if (output.isNotEmpty()) output else null
        } catch (e: Exception) {
            logger.warn(e) { "Failed to get git remote URL" }
            null
        }
    }

    /**
     * Generate unique hash for project
     */
    private fun generateProjectHash(gitRoot: File, gitRemoteUrl: String?): String {
        val identifier = gitRemoteUrl ?: gitRoot.absolutePath
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(identifier.toByteArray())
        return hash.joinToString("") { "%02x".format(it) }.take(16)
    }

    /**
     * Scan for documentation files
     */
    private fun scanDocumentation(gitRoot: File): List<DocumentFile> {
        val documents = mutableListOf<DocumentFile>()

        // README files
        val readmePatterns = listOf("README.md", "README.MD", "readme.md", "Readme.md")
        for (pattern in readmePatterns) {
            val readmeFile = File(gitRoot, pattern)
            if (readmeFile.exists() && readmeFile.isFile) {
                documents.add(
                    DocumentFile(
                        file = readmeFile,
                        relativePath = pattern,
                        title = "README"
                    )
                )
                break // Only add one README
            }
        }

        // docs/ directory
        val docsDirs = listOf("docs", "project/docs")
        for (docsPath in docsDirs) {
            val docsDir = File(gitRoot, docsPath)
            if (docsDir.exists() && docsDir.isDirectory) {
                val docsFiles = scanDirectory(docsDir, gitRoot)
                documents.addAll(docsFiles)
            }
        }

        return documents
    }

    /**
     * Recursively scan directory for markdown files
     */
    private fun scanDirectory(dir: File, rootDir: File): List<DocumentFile> {
        val documents = mutableListOf<DocumentFile>()

        dir.listFiles()?.forEach { file ->
            when {
                file.isDirectory -> {
                    // Recursively scan subdirectories
                    documents.addAll(scanDirectory(file, rootDir))
                }
                file.isFile && (file.extension == "md" || file.extension == "MD") -> {
                    val relativePath = file.relativeTo(rootDir).path
                    val title = file.nameWithoutExtension
                        .replace("-", " ")
                        .replace("_", " ")
                        .split(" ")
                        .joinToString(" ") { it.replaceFirstChar { char -> char.uppercase() } }

                    documents.add(
                        DocumentFile(
                            file = file,
                            relativePath = relativePath,
                            title = title
                        )
                    )
                }
            }
        }

        return documents
    }
}