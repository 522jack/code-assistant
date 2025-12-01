package com.codeassistant.cli

import com.codeassistant.config.AppConfig
import com.codeassistant.git.GitTool
import com.codeassistant.index.IndexManager
import com.codeassistant.llm.ClaudeClient
import com.codeassistant.llm.ClaudeMessage
import com.codeassistant.llm.OllamaClient
import com.codeassistant.project.ProjectDetector
import com.codeassistant.project.ProjectInfo
import com.codeassistant.rag.RagService
import com.codeassistant.rag.TextChunker
import kotlinx.coroutines.runBlocking
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jline.reader.LineReaderBuilder
import org.jline.terminal.TerminalBuilder
import java.io.File

private val logger = KotlinLogging.logger {}

/**
 * CLI interface for code assistant
 */
class CodeAssistantCli(
    private val config: AppConfig,
    private val projectDetector: ProjectDetector,
    private val indexManager: IndexManager,
    private val ragService: RagService,
    private val claudeClient: ClaudeClient,
    private val gitTool: GitTool
) {
    private var projectInfo: ProjectInfo? = null
    private val conversationHistory = mutableListOf<ClaudeMessage>()

    companion object {
        const val HELP_SYSTEM_PROMPT = """You are a helpful code assistant for developers.
Your role is to answer questions about the project structure, architecture, and code style.
Use the provided context from documentation to give accurate and specific answers.
When referencing files or code, provide concrete examples and file paths when possible.
Keep your answers concise and practical."""
    }

    /**
     * Start the CLI REPL
     */
    fun start() {
        println("╔════════════════════════════════════════════════════════════════╗")
        println("║                    Code Assistant v0.1.0                       ║")
        println("║              AI-powered project documentation helper           ║")
        println("╚════════════════════════════════════════════════════════════════╝")
        println()

        // Initialize project
        val initResult = runBlocking { initialize() }
        if (initResult.isFailure) {
            println("❌ Error: ${initResult.exceptionOrNull()?.message}")
            return
        }

        println()
        println("Commands:")
        println("  /help [question]  - Ask a question about the project")
        println("  /branch           - Show current git branch")
        println("  /reindex          - Reindex project documentation")
        println("  /info             - Show project information")
        println("  /clear            - Clear conversation history")
        println("  /exit, /quit      - Exit the assistant")
        println()

        // Start REPL
        val terminal = TerminalBuilder.builder().build()
        val lineReader = LineReaderBuilder.builder()
            .terminal(terminal)
            .build()

        while (true) {
            try {
                val line = lineReader.readLine("> ").trim()

                if (line.isEmpty()) continue

                when {
                    line.startsWith("/exit") || line.startsWith("/quit") -> {
                        println("👋 Goodbye!")
                        break
                    }
                    line.startsWith("/help") -> {
                        val question = line.removePrefix("/help").trim()
                        if (question.isEmpty()) {
                            println("Usage: /help [your question about the project]")
                        } else {
                            runBlocking { handleHelpCommand(question) }
                        }
                    }
                    line.startsWith("/branch") -> {
                        handleBranchCommand()
                    }
                    line.startsWith("/reindex") -> {
                        runBlocking { handleReindexCommand() }
                    }
                    line.startsWith("/info") -> {
                        handleInfoCommand()
                    }
                    line.startsWith("/clear") -> {
                        conversationHistory.clear()
                        println("✓ Conversation history cleared")
                    }
                    else -> {
                        println("Unknown command. Type /help to see available commands.")
                    }
                }

            } catch (e: org.jline.reader.UserInterruptException) {
                println("\n👋 Goodbye!")
                break
            } catch (e: org.jline.reader.EndOfFileException) {
                println("\n👋 Goodbye!")
                break
            } catch (e: Exception) {
                logger.error(e) { "Error in REPL" }
                println("❌ Error: ${e.message}")
            }
        }
    }

    /**
     * Initialize project detection and indexing
     */
    private suspend fun initialize(): Result<Unit> {
        return try {
            println("🔍 Detecting project...")

            // Detect project
            val projectResult = projectDetector.detectProject()
            if (projectResult.isFailure) {
                return projectResult.map { }
            }

            projectInfo = projectResult.getOrThrow()
            val project = projectInfo!!

            println("✓ Project detected: ${project.rootDir.name}")
            println("  Root: ${project.rootDir.absolutePath}")
            println("  Hash: ${project.projectHash}")
            if (project.gitRemoteUrl != null) {
                println("  Remote: ${project.gitRemoteUrl}")
            }

            // Check if index exists
            if (indexManager.hasIndex(project)) {
                println("\n📚 Loading existing index...")
                val index = indexManager.loadIndex(project)
                if (index != null) {
                    ragService.loadIndex(index)
                    println("✓ Loaded ${index.documents.size} documents from cache")
                } else {
                    // Index not loaded, reindex
                    indexProject(project)
                }
            } else {
                // No index, create new one
                indexProject(project)
            }

            Result.success(Unit)

        } catch (e: Exception) {
            logger.error(e) { "Error initializing" }
            Result.failure(e)
        }
    }

    /**
     * Index project documentation
     */
    private suspend fun indexProject(project: ProjectInfo) {
        println("\n📇 Indexing project documentation...")
        println("  Found ${project.documents.size} documentation files")

        project.documents.forEachIndexed { index, doc ->
            println("  [${index + 1}/${project.documents.size}] Indexing ${doc.relativePath}...")

            try {
                val content = doc.file.readText()
                val result = ragService.indexDocument(
                    title = doc.title,
                    content = content,
                    filePath = doc.relativePath,
                    metadata = mapOf(
                        "path" to doc.relativePath,
                        "file" to doc.file.absolutePath
                    )
                )

                if (result.isFailure) {
                    println("    ⚠ Warning: Failed to index ${doc.relativePath}: ${result.exceptionOrNull()?.message}")
                }
            } catch (e: Exception) {
                println("    ⚠ Warning: Error reading ${doc.relativePath}: ${e.message}")
            }
        }

        // Save index
        val index = ragService.getIndex()
        if (index != null) {
            val updatedIndex = index.copy(projectHash = project.projectHash)
            indexManager.saveIndex(project, updatedIndex)
            println("✓ Index saved successfully")
        }
    }

    /**
     * Handle /help command
     */
    private suspend fun handleHelpCommand(question: String) {
        println("\n🤔 Thinking...")

        try {
            // Search for relevant documentation
            val searchResult = ragService.search(query = question)

            if (searchResult.isFailure) {
                println("❌ Error searching documentation: ${searchResult.exceptionOrNull()?.message}")
                return
            }

            val results = searchResult.getOrThrow()

            if (results.isEmpty()) {
                println("⚠ No relevant documentation found for your question.")
                println("  Try rephrasing or check if documentation is indexed.")
                return
            }

            // Build context from search results
            val context = buildString {
                appendLine("Based on the project documentation:\n")
                results.forEachIndexed { index, result ->
                    appendLine("--- Document ${index + 1}: ${result.documentTitle} (${result.filePath}) ---")
                    appendLine(result.chunk.content)
                    appendLine()
                }
            }

            // Build prompt with context
            val userMessage = """$context

Question: $question

Please answer the question based on the documentation provided above."""

            // Add to conversation history
            conversationHistory.add(ClaudeMessage(role = "user", content = userMessage))

            // Call Claude
            val response = claudeClient.sendMessage(
                messages = conversationHistory,
                systemPrompt = HELP_SYSTEM_PROMPT
            )

            if (response.isFailure) {
                println("❌ Error getting response from Claude: ${response.exceptionOrNull()?.message}")
                return
            }

            val claudeResponse = response.getOrThrow()
            val answerText = claudeResponse.content.firstOrNull { it.type == "text" }?.text
                ?: "No response received"

            // Add assistant response to history
            conversationHistory.add(ClaudeMessage(role = "assistant", content = answerText))

            // Display answer
            println("\n💡 Answer:\n")
            println(answerText)
            println()

            // Show sources
            println("📚 Sources:")
            results.take(3).forEach { result ->
                println("  • ${result.documentTitle} (${result.filePath}) - similarity: ${String.format("%.2f", result.similarity)}")
            }
            println()

        } catch (e: Exception) {
            logger.error(e) { "Error handling help command" }
            println("❌ Error: ${e.message}")
        }
    }

    /**
     * Handle /branch command
     */
    private fun handleBranchCommand() {
        println()
        val result = gitTool.getCurrentBranch()

        if (result.isSuccess) {
            println("🌿 Current branch: ${result.getOrThrow()}")
        } else {
            println("❌ Error: ${result.exceptionOrNull()?.message}")
        }
        println()
    }

    /**
     * Handle /reindex command
     */
    private suspend fun handleReindexCommand() {
        println()
        val project = projectInfo
        if (project == null) {
            println("❌ No project loaded")
            return
        }

        println("🔄 Reindexing project documentation...")
        ragService.clearIndex()
        indexProject(project)
        println("✓ Reindexing complete")
        println()
    }

    /**
     * Handle /info command
     */
    private fun handleInfoCommand() {
        println()
        val project = projectInfo
        if (project == null) {
            println("❌ No project loaded")
            return
        }

        println("📋 Project Information:")
        println("  Name: ${project.rootDir.name}")
        println("  Path: ${project.rootDir.absolutePath}")
        println("  Hash: ${project.projectHash}")
        println("  Remote: ${project.gitRemoteUrl ?: "none"}")
        println("  Documents: ${project.documents.size}")
        println()

        val index = ragService.getIndex()
        if (index != null) {
            println("  Indexed documents: ${index.documents.size}")
            println("  Indexed chunks: ${index.embeddings.size}")
            println("  Last updated: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date(index.lastUpdated))}")
        }

        val indexSize = indexManager.getIndexSize(project)
        if (indexSize > 0) {
            println("  Index size: ${indexSize / 1024} KB")
        }

        println()
    }
}