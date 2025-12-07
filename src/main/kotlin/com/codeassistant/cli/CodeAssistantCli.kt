package com.codeassistant.cli

import com.codeassistant.cicd.CiCdService
import com.codeassistant.config.AppConfig
import com.codeassistant.git.GitTool
import com.codeassistant.index.IndexManager
import com.codeassistant.llm.ClaudeClient
import com.codeassistant.llm.ClaudeMessage
import com.codeassistant.llm.OllamaClient
import com.codeassistant.notifications.NotificationService
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
    private val gitTool: GitTool,
    private val taskTool: com.codeassistant.task.TaskTool,
    private val cicdService: CiCdService,
    private val notificationService: NotificationService
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
        println("  /help [question]         - Ask a question about the project")
        println("  /branch                  - Show current git branch")
        println("  /reindex                 - Reindex project documentation")
        println("  /info                    - Show project information")
        println("  /clear                   - Clear conversation history")
        println()
        println("Task Management:")
        println("  /task create [title]     - Create a new task")
        println("  /tasks [filter]          - List tasks (all, high, medium, low, todo, done)")
        println("  /task [id]               - Show task details")
        println("  /task done [id]          - Mark task as done")
        println("  /recommend               - Get AI task recommendations")
        println()
        println("CI/CD & Notifications:")
        println("  /build [owner/repo] [workflow] [branch]  - Trigger GitHub Actions workflow")
        println("  /status [owner/repo] [run-id]            - Check build status")
        println("  /notify [message]                        - Send Telegram notification")
        println()
        println("  /exit, /quit             - Exit the assistant")
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
                    line.startsWith("/task create") -> {
                        val title = line.removePrefix("/task create").trim()
                        runBlocking { handleTaskCreateCommand(title) }
                    }
                    line.startsWith("/tasks") -> {
                        val filter = line.removePrefix("/tasks").trim()
                        handleTasksListCommand(filter)
                    }
                    line.startsWith("/task done") -> {
                        val id = line.removePrefix("/task done").trim()
                        handleTaskDoneCommand(id)
                    }
                    line.startsWith("/task") -> {
                        val id = line.removePrefix("/task").trim()
                        handleTaskViewCommand(id)
                    }
                    line.startsWith("/recommend") -> {
                        runBlocking { handleRecommendCommand() }
                    }
                    line.startsWith("/build") -> {
                        val args = line.removePrefix("/build").trim().split("\\s+".toRegex())
                        runBlocking { handleBuildCommand(args) }
                    }
                    line.startsWith("/status") -> {
                        val args = line.removePrefix("/status").trim().split("\\s+".toRegex())
                        runBlocking { handleStatusCommand(args) }
                    }
                    line.startsWith("/notify") -> {
                        val message = line.removePrefix("/notify").trim()
                        runBlocking { handleNotifyCommand(message) }
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

    /**
     * Handle /task create command
     */
    private suspend fun handleTaskCreateCommand(title: String) {
        println()

        if (title.isEmpty()) {
            println("Usage: /task create [title]")
            println("Example: /task create Implement user authentication")
            return
        }

        try {
            println("📝 Creating new task...")
            println()

            // Get description
            print("Description (optional, press Enter to skip): ")
            val description = readlnOrNull()?.trim() ?: ""

            // Get priority
            print("Priority? (1=high, 2=medium, 3=low) [2]: ")
            val priorityInput = readlnOrNull()?.trim() ?: "2"
            val priority = com.codeassistant.task.TaskPriority.fromNumber(priorityInput.toIntOrNull() ?: 2)
                ?: com.codeassistant.task.TaskPriority.MEDIUM

            // Get tags
            print("Tags (comma-separated, optional): ")
            val tagsInput = readlnOrNull()?.trim() ?: ""
            val tags = if (tagsInput.isNotEmpty()) {
                tagsInput.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            } else {
                emptyList()
            }

            // Create task
            val result = taskTool.createTask(title, description, priority, tags)

            if (result.isSuccess) {
                val task = result.getOrThrow()
                println()
                println("✓ Task created successfully!")
                println("  ID: ${task.id}")
                println("  Title: ${task.title}")
                println("  Priority: ${task.priority}")
                println("  Status: ${task.status}")
            } else {
                println("❌ Error: ${result.exceptionOrNull()?.message}")
            }

        } catch (e: Exception) {
            logger.error(e) { "Error creating task" }
            println("❌ Error: ${e.message}")
        }

        println()
    }

    /**
     * Handle /tasks list command
     */
    private fun handleTasksListCommand(filter: String) {
        println()

        try {
            val priority = when (filter.lowercase()) {
                "high" -> com.codeassistant.task.TaskPriority.HIGH
                "medium" -> com.codeassistant.task.TaskPriority.MEDIUM
                "low" -> com.codeassistant.task.TaskPriority.LOW
                else -> null
            }

            val status = when (filter.lowercase()) {
                "todo" -> com.codeassistant.task.TaskStatus.TODO
                "done" -> com.codeassistant.task.TaskStatus.DONE
                "in_progress", "inprogress" -> com.codeassistant.task.TaskStatus.IN_PROGRESS
                "blocked" -> com.codeassistant.task.TaskStatus.BLOCKED
                else -> null
            }

            val result = taskTool.listTasks(priority = priority, status = status)

            if (result.isFailure) {
                println("❌ Error: ${result.exceptionOrNull()?.message}")
                return
            }

            val tasks = result.getOrThrow()

            if (tasks.isEmpty()) {
                println("📋 No tasks found")
                if (filter.isNotEmpty()) {
                    println("  Filter: $filter")
                }
                println()
                return
            }

            // Group by priority
            val filterText = if (filter.isNotEmpty()) " ($filter)" else ""
            println("📋 Tasks$filterText (${tasks.size} total)")
            println()

            val byPriority = tasks.groupBy { it.priority }

            listOf(
                com.codeassistant.task.TaskPriority.HIGH,
                com.codeassistant.task.TaskPriority.MEDIUM,
                com.codeassistant.task.TaskPriority.LOW
            ).forEach { pri ->
                val priTasks = byPriority[pri] ?: emptyList()
                if (priTasks.isNotEmpty()) {
                    println("${pri.name} PRIORITY (${priTasks.size})")
                    priTasks.forEach { task ->
                        val statusIcon = when (task.status) {
                            com.codeassistant.task.TaskStatus.TODO -> "○"
                            com.codeassistant.task.TaskStatus.IN_PROGRESS -> "◐"
                            com.codeassistant.task.TaskStatus.DONE -> "●"
                            com.codeassistant.task.TaskStatus.BLOCKED -> "✗"
                        }
                        println("  $statusIcon [${task.id}] ${task.title}")
                        println("     Status: ${task.status} | Created: ${formatDate(task.createdAt)}")
                        if (task.tags.isNotEmpty()) {
                            println("     Tags: ${task.tags.joinToString(", ")}")
                        }
                    }
                    println()
                }
            }

            println("Use /task [id] to view details")

        } catch (e: Exception) {
            logger.error(e) { "Error listing tasks" }
            println("❌ Error: ${e.message}")
        }

        println()
    }

    /**
     * Handle /task view command
     */
    private fun handleTaskViewCommand(id: String) {
        println()

        if (id.isEmpty()) {
            println("Usage: /task [id]")
            println("Example: /task a7b3c4d5")
            return
        }

        try {
            val result = taskTool.getTask(id)

            if (result.isFailure) {
                println("❌ Error: ${result.exceptionOrNull()?.message}")
                println()
                return
            }

            val task = result.getOrThrow()

            println("📌 Task Details")
            println()
            println("Title: ${task.title}")
            println("ID: ${task.id}")
            println("Priority: ${task.priority}")
            println("Status: ${task.status}")
            println("Created: ${formatDate(task.createdAt)}")
            println("Updated: ${formatDate(task.updatedAt)}")

            if (task.completedAt != null) {
                println("Completed: ${formatDate(task.completedAt)}")
            }

            if (task.description.isNotEmpty()) {
                println()
                println("Description:")
                println(task.description)
            }

            if (task.tags.isNotEmpty()) {
                println()
                println("Tags: ${task.tags.joinToString(", ")}")
            }

            if (task.relatedFiles.isNotEmpty()) {
                println()
                println("Related Files:")
                task.relatedFiles.forEach { file ->
                    println("  - $file")
                }
            }

            println()
            println("Commands:")
            println("  /task done ${task.id}    - Mark as done")

        } catch (e: Exception) {
            logger.error(e) { "Error viewing task" }
            println("❌ Error: ${e.message}")
        }

        println()
    }

    /**
     * Handle /task done command
     */
    private fun handleTaskDoneCommand(id: String) {
        println()

        if (id.isEmpty()) {
            println("Usage: /task done [id]")
            println("Example: /task done a7b3c4d5")
            return
        }

        try {
            val result = taskTool.updateTaskStatus(id, com.codeassistant.task.TaskStatus.DONE)

            if (result.isSuccess) {
                val task = result.getOrThrow()
                println("✓ Task marked as DONE!")
                println("  Title: ${task.title}")
                println("  Completed: ${formatDate(task.completedAt!!)}")
            } else {
                println("❌ Error: ${result.exceptionOrNull()?.message}")
            }

        } catch (e: Exception) {
            logger.error(e) { "Error marking task as done" }
            println("❌ Error: ${e.message}")
        }

        println()
    }

    /**
     * Handle /recommend command
     */
    private suspend fun handleRecommendCommand() {
        println()
        println("🤖 Analyzing tasks...")

        try {
            val result = taskTool.getRecommendations(topK = 3)

            if (result.isFailure) {
                println("❌ Error: ${result.exceptionOrNull()?.message}")
                return
            }

            val recommendations = result.getOrThrow()

            if (recommendations.isEmpty()) {
                println("📋 No tasks to recommend")
                println("  Create tasks with /task create")
                println()
                return
            }

            println()
            println("💡 Recommended Tasks (Top ${recommendations.size})")
            println()

            recommendations.forEachIndexed { index, rec ->
                println("${index + 1}. [${rec.task.id}] ${rec.task.title} (${rec.task.priority})")
                println("   Score: ${String.format("%.1f", rec.score)}/10")
                println()
                println("   Reasoning: ${rec.reasoning}")
                println()

                if (rec.relevantContext.isNotEmpty()) {
                    println("   Context:")
                    rec.relevantContext.take(2).forEach { ctx ->
                        println("   - $ctx")
                    }
                    println()
                }
            }

            println("Use /task [id] to view full details")

        } catch (e: Exception) {
            logger.error(e) { "Error generating recommendations" }
            println("❌ Error: ${e.message}")
        }

        println()
    }

    /**
     * Format timestamp to readable date
     */
    private fun formatDate(timestamp: Long): String {
        return java.text.SimpleDateFormat("yyyy-MM-dd").format(java.util.Date(timestamp))
    }

    /**
     * Handle build command
     */
    private suspend fun handleBuildCommand(args: List<String>) {
        try {
            if (!cicdService.isConfigured()) {
                println("❌ CI/CD not configured. Please set GITHUB_TOKEN environment variable.")
                return
            }

            // Parse arguments
            if (args.isEmpty() || args[0].isEmpty()) {
                println("Usage: /build [owner/repo] [workflow] [branch]")
                println("Example: /build anthropics/claude-code build.yml main")
                return
            }

            val repoPath = args[0].split("/")
            if (repoPath.size != 2) {
                println("❌ Invalid repository format. Use: owner/repo")
                return
            }

            val owner = repoPath[0]
            val repo = repoPath[1]
            val workflow = if (args.size > 1) args[1] else "build.yml"
            val branch = if (args.size > 2) args[2] else null

            println("🚀 Triggering build for $owner/$repo...")
            println("   Workflow: $workflow")
            if (branch != null) {
                println("   Branch: $branch")
            }
            println()

            val result = cicdService.triggerBuild(
                owner = owner,
                repo = repo,
                workflowId = workflow,
                ref = branch,
                waitForCompletion = false,
                sendNotification = notificationService.isTelegramConfigured()
            )

            if (result.isFailure) {
                println("❌ Error: ${result.exceptionOrNull()?.message}")
                return
            }

            val triggerResult = result.getOrThrow()

            if (triggerResult.success) {
                println("✅ ${triggerResult.message}")
                if (triggerResult.workflowRunId != null) {
                    println()
                    println("Use /status $owner/$repo ${triggerResult.workflowRunId} to check status")
                }
            } else {
                println("❌ ${triggerResult.message}")
            }

        } catch (e: Exception) {
            logger.error(e) { "Error triggering build" }
            println("❌ Error: ${e.message}")
        }

        println()
    }

    /**
     * Handle status command
     */
    private suspend fun handleStatusCommand(args: List<String>) {
        try {
            if (!cicdService.isConfigured()) {
                println("❌ CI/CD not configured. Please set GITHUB_TOKEN environment variable.")
                return
            }

            // Parse arguments
            if (args.size < 2 || args[0].isEmpty() || args[1].isEmpty()) {
                println("Usage: /status [owner/repo] [run-id]")
                println("Example: /status anthropics/claude-code 123456789")
                return
            }

            val repoPath = args[0].split("/")
            if (repoPath.size != 2) {
                println("❌ Invalid repository format. Use: owner/repo")
                return
            }

            val owner = repoPath[0]
            val repo = repoPath[1]
            val runId = args[1].toLongOrNull()

            if (runId == null) {
                println("❌ Invalid run ID. Must be a number.")
                return
            }

            println("🔍 Checking build status for $owner/$repo run #$runId...")
            println()

            val result = cicdService.checkBuildStatus(
                owner = owner,
                repo = repo,
                runId = runId,
                sendNotification = false
            )

            if (result.isFailure) {
                println("❌ Error: ${result.exceptionOrNull()?.message}")
                return
            }

            val status = result.getOrThrow()

            println("📊 Build Status")
            println("   Status: ${status.status}")
            if (status.conclusion != null) {
                val emoji = when (status.conclusion) {
                    "success" -> "✅"
                    "failure" -> "❌"
                    "cancelled" -> "🚫"
                    else -> "⚠️"
                }
                println("   Conclusion: $emoji ${status.conclusion}")
            }
            println("   URL: ${status.htmlUrl}")

            if (status.duration != null) {
                val minutes = status.duration / 60
                val seconds = status.duration % 60
                println("   Duration: ${minutes}m ${seconds}s")
            }

            if (status.artifacts.isNotEmpty()) {
                println()
                println("📦 Artifacts (${status.artifacts.size})")
                status.artifacts.take(5).forEach { artifact ->
                    val sizeMB = artifact.sizeInBytes / (1024.0 * 1024.0)
                    println("   - ${artifact.name} (${String.format("%.2f", sizeMB)} MB)")
                }
            }

            if (status.failedSteps.isNotEmpty()) {
                println()
                println("❌ Failed Steps:")
                status.failedSteps.forEach { step ->
                    println("   - $step")
                }
            }

        } catch (e: Exception) {
            logger.error(e) { "Error checking status" }
            println("❌ Error: ${e.message}")
        }

        println()
    }

    /**
     * Handle notify command
     */
    private suspend fun handleNotifyCommand(message: String) {
        try {
            if (!notificationService.isTelegramConfigured()) {
                println("❌ Telegram not configured. Please set TELEGRAM_BOT_TOKEN and TELEGRAM_CHAT_ID.")
                println()
                println("Environment variables needed:")
                println("  export TELEGRAM_BOT_TOKEN=8290382294:AAFZoFAtwtJ39mY_z3Irr7ACi57pBrVx6vk")
                println("  export TELEGRAM_CHAT_ID=-1001234567890")
                return
            }

            if (message.isEmpty()) {
                println("Usage: /notify [message]")
                println("Example: /notify Build completed successfully!")
                return
            }

            println("📤 Sending notification to Telegram...")

            val result = notificationService.sendMessage(message)

            if (result.isSuccess) {
                println("✅ Notification sent successfully!")
            } else {
                println("❌ Error: ${result.exceptionOrNull()?.message}")
            }

        } catch (e: Exception) {
            logger.error(e) { "Error sending notification" }
            println("❌ Error: ${e.message}")
        }

        println()
    }
}