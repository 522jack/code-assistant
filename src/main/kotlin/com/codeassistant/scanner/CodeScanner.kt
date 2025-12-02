package com.codeassistant.scanner

import com.codeassistant.project.DocumentFile
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File

private val logger = KotlinLogging.logger {}

/**
 * Scanner for source code files (.kt, .java)
 */
class CodeScanner(
    private val rootPath: File
) {
    companion object {
        val DEFAULT_EXTENSIONS = listOf("kt", "java")
        val DEFAULT_EXCLUDE_DIRS = listOf("build", ".gradle", "node_modules", ".git", "out", "target", ".idea")
    }

    /**
     * Scan source code files in the project
     */
    fun scanSourceCode(
        extensions: List<String> = DEFAULT_EXTENSIONS,
        excludeDirs: List<String> = DEFAULT_EXCLUDE_DIRS
    ): List<DocumentFile> {
        logger.info { "Scanning source code in: ${rootPath.absolutePath}" }

        val files = rootPath.walkTopDown()
            .onEnter { dir ->
                // Skip excluded directories
                val shouldEnter = excludeDirs.none { excluded ->
                    dir.name == excluded || dir.path.contains("/$excluded/")
                }
                if (!shouldEnter) {
                    logger.debug { "Skipping directory: ${dir.name}" }
                }
                shouldEnter
            }
            .filter { it.isFile }
            .filter { file -> extensions.any { file.extension == it } }
            .map { file ->
                val relativePath = file.relativeTo(rootPath).path
                val title = file.nameWithoutExtension

                DocumentFile(
                    file = file,
                    relativePath = relativePath,
                    title = title
                )
            }
            .toList()

        logger.info { "Found ${files.size} source code files" }
        return files
    }

    /**
     * Scan only specific directories for source code
     */
    fun scanDirectories(
        directories: List<String>,
        extensions: List<String> = DEFAULT_EXTENSIONS,
        excludeDirs: List<String> = DEFAULT_EXCLUDE_DIRS
    ): List<DocumentFile> {
        logger.info { "Scanning specific directories: $directories" }

        val allFiles = mutableListOf<DocumentFile>()

        for (dirPath in directories) {
            val dir = File(rootPath, dirPath)
            if (!dir.exists() || !dir.isDirectory) {
                logger.warn { "Directory not found or not a directory: $dirPath" }
                continue
            }

            val files = dir.walkTopDown()
                .onEnter { subDir ->
                    val shouldEnter = excludeDirs.none { excluded ->
                        subDir.name == excluded || subDir.path.contains("/$excluded/")
                    }
                    shouldEnter
                }
                .filter { it.isFile }
                .filter { file -> extensions.any { file.extension == it } }
                .map { file ->
                    val relativePath = file.relativeTo(rootPath).path
                    val title = file.nameWithoutExtension

                    DocumentFile(
                        file = file,
                        relativePath = relativePath,
                        title = title
                    )
                }
                .toList()

            allFiles.addAll(files)
        }

        logger.info { "Found ${allFiles.size} source code files in specified directories" }
        return allFiles
    }

    /**
     * Get statistics about scanned files
     */
    fun getStatistics(files: List<DocumentFile>): ScanStatistics {
        val totalLines = files.sumOf { file ->
            try {
                file.file.readLines().size
            } catch (e: Exception) {
                logger.warn(e) { "Failed to count lines in ${file.relativePath}" }
                0
            }
        }

        val byExtension = files.groupBy { it.file.extension }
            .mapValues { (_, files) -> files.size }

        return ScanStatistics(
            totalFiles = files.size,
            totalLines = totalLines,
            filesByExtension = byExtension
        )
    }
}

/**
 * Statistics about scanned code
 */
data class ScanStatistics(
    val totalFiles: Int,
    val totalLines: Int,
    val filesByExtension: Map<String, Int>
) {
    override fun toString(): String {
        return buildString {
            appendLine("Total files: $totalFiles")
            appendLine("Total lines: $totalLines")
            appendLine("By extension:")
            filesByExtension.forEach { (ext, count) ->
                appendLine("  .$ext: $count files")
            }
        }
    }
}