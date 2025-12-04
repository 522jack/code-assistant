package com.codeassistant

import com.codeassistant.cli.CodeAssistantCli
import com.codeassistant.config.AppConfig
import com.codeassistant.git.GitTool
import com.codeassistant.index.IndexManager
import com.codeassistant.llm.ClaudeClient
import com.codeassistant.llm.OllamaClient
import com.codeassistant.project.ProjectDetector
import com.codeassistant.rag.RagService
import com.codeassistant.rag.TextChunker
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    try {
        // Parse arguments
        val workingDir = if (args.isNotEmpty()) {
            File(args[0]).also {
                if (!it.exists() || !it.isDirectory) {
                    println("❌ Error: Directory not found: ${args[0]}")
                    exitProcess(1)
                }
            }
        } else {
            File(System.getProperty("user.dir"))
        }

        // Load configuration
        val config = try {
            AppConfig.fromEnvironment()
        } catch (e: Exception) {
            println("❌ Error: ${e.message}")
            println()
            println("Please set the CLAUDE_API_KEY environment variable:")
            println("  export CLAUDE_API_KEY='your-api-key-here'")
            println()
            println("Optional environment variables:")
            println("  OLLAMA_URL='http://localhost:11434'")
            exitProcess(1)
        }

        // Create HTTP client
        val httpClient = HttpClient(CIO) {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    prettyPrint = true
                })
            }
            engine {
                requestTimeout = 60_000 // 60 seconds
            }
        }

        // Initialize components
        val projectDetector = ProjectDetector(workingDir)
        val indexManager = IndexManager(config)
        val ollamaClient = OllamaClient(httpClient, config.ollamaUrl)
        val textChunker = TextChunker()
        val ragService = RagService(ollamaClient, textChunker, config.embeddingModel)
        val claudeClient = ClaudeClient(httpClient, config.claudeApiKey)
        val gitTool = GitTool(workingDir)

        // Initialize task management components
        val taskManager = com.codeassistant.task.TaskManager(config)
        val taskService = com.codeassistant.task.TaskService(ragService, claudeClient, gitTool)
        val taskTool = com.codeassistant.task.TaskTool(taskService)

        // Load existing tasks if available
        val projectInfo = projectDetector.detectProject().getOrNull()
        if (projectInfo != null && taskManager.hasTasks(projectInfo)) {
            val taskIndex = taskManager.loadTasks(projectInfo)
            if (taskIndex != null) {
                taskService.loadIndex(taskIndex)
            }
        }

        // Create and start CLI
        val cli = CodeAssistantCli(
            config = config,
            projectDetector = projectDetector,
            indexManager = indexManager,
            ragService = ragService,
            claudeClient = claudeClient,
            gitTool = gitTool,
            taskTool = taskTool
        )

        cli.start()

        // Save tasks before exiting
        if (projectInfo != null) {
            val taskIndex = taskService.getIndex()
            if (taskIndex != null) {
                val updatedIndex = taskIndex.copy(projectHash = projectInfo.projectHash)
                taskManager.saveTasks(projectInfo, updatedIndex)
            }
        }

        // Cleanup
        httpClient.close()

    } catch (e: Exception) {
        println("❌ Fatal error: ${e.message}")
        e.printStackTrace()
        exitProcess(1)
    }
}