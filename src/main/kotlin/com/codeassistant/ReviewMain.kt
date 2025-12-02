package com.codeassistant

import com.codeassistant.config.AppConfig
import com.codeassistant.github.GitHubTool
import com.codeassistant.llm.ClaudeClient
import com.codeassistant.llm.OllamaClient
import com.codeassistant.project.ProjectDetector
import com.codeassistant.rag.RagService
import com.codeassistant.rag.TextChunker
import com.codeassistant.review.CodeReviewService
import com.codeassistant.review.ReviewFormatter
import com.codeassistant.review.ReviewPromptBuilder
import com.codeassistant.scanner.CodeScanner
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.system.exitProcess

/**
 * Entry point for code review mode (GitHub Action)
 */
fun main(args: Array<String>) = runBlocking {
    try {
        println("🤖 Code Assistant - Code Review Mode")
        println()

        // Parse arguments
        val argsMap = parseArguments(args)
        val mode = argsMap["--mode"] ?: System.getenv("MODE")
        val prNumber = argsMap["--pr"] ?: System.getenv("PR_NUMBER")
        val repo = argsMap["--repo"] ?: System.getenv("REPOSITORY")

        // Validate arguments
        if (mode != "review") {
            printUsage()
            exitProcess(1)
        }

        if (prNumber == null || repo == null) {
            println("❌ Error: Missing required parameters")
            println("  --pr <number> or PR_NUMBER environment variable")
            println("  --repo <owner/repo> or REPOSITORY environment variable")
            println()
            printUsage()
            exitProcess(1)
        }

        val workingDir = File(System.getProperty("user.dir"))

        // Load configuration
        val config = try {
            AppConfig.fromEnvironment()
        } catch (e: Exception) {
            println("❌ Error: ${e.message}")
            println()
            println("Required environment variables:")
            println("  CLAUDE_API_KEY - Claude API key")
            println("  GITHUB_TOKEN - GitHub token (usually automatically set in Actions)")
            println()
            println("Optional:")
            println("  OLLAMA_URL - Ollama server URL (default: http://localhost:11434)")
            exitProcess(1)
        }

        println("📂 Working directory: ${workingDir.absolutePath}")
        println("🔍 Reviewing PR #$prNumber in $repo")
        println()

        // Create HTTP client
        val httpClient = HttpClient(CIO) {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    prettyPrint = true
                })
            }
            engine {
                requestTimeout = 120_000 // 2 minutes for large diffs
            }
        }

        // Initialize components
        println("⚙️ Initializing components...")
        val projectDetector = ProjectDetector(workingDir)
        val ollamaClient = OllamaClient(httpClient, config.ollamaUrl)
        val textChunker = TextChunker()
        val ragService = RagService(ollamaClient, textChunker, config.embeddingModel)
        val claudeClient = ClaudeClient(httpClient, config.claudeApiKey)
        val githubTool = GitHubTool(workingDir)
        val codeScanner = CodeScanner(workingDir)

        // Check gh CLI authentication
        println("🔐 Checking GitHub CLI authentication...")
        val authResult = githubTool.checkAuth()
        if (authResult.isFailure || authResult.getOrNull() == false) {
            println("⚠️  Warning: GitHub CLI may not be authenticated")
            println("   Make sure GITHUB_TOKEN is set in environment")
        }

        // Detect project
        println("📋 Detecting project...")
        val projectInfo = projectDetector.detectProject().getOrElse {
            println("❌ Error detecting project: ${it.message}")
            exitProcess(1)
        }
        println("   Project: ${projectInfo.rootDir.name}")
        println("   Documents: ${projectInfo.documents.size}")
        println()

        // Index project documentation
        println("📚 Indexing project documentation...")
        projectInfo.documents.forEach { doc ->
            val content = doc.file.readText()
            ragService.indexDocument(
                title = doc.title,
                content = content,
                filePath = doc.relativePath,
                metadata = mapOf("type" to "documentation")
            )
        }
        println("   Indexed ${projectInfo.documents.size} documentation files")

        // Scan and index source code
        println("📝 Scanning source code...")
        val sourceFiles = codeScanner.scanSourceCode()
        println("   Found ${sourceFiles.size} source files")

        println("🔄 Indexing source code (this may take a while)...")
        var indexed = 0
        sourceFiles.forEach { file ->
            try {
                val content = file.file.readText()
                // Skip very large files
                if (content.length < 100_000) {
                    ragService.indexDocument(
                        title = file.title,
                        content = content,
                        filePath = file.relativePath,
                        metadata = mapOf(
                            "type" to "source_code",
                            "language" to file.file.extension
                        )
                    )
                    indexed++
                    if (indexed % 10 == 0) {
                        print(".")
                    }
                }
            } catch (e: Exception) {
                println()
                println("   ⚠️ Warning: Failed to index ${file.relativePath}: ${e.message}")
            }
        }
        println()
        println("   Indexed $indexed source files")
        println()

        // Create review service
        val reviewService = CodeReviewService(
            ragService = ragService,
            claudeClient = claudeClient,
            githubTool = githubTool,
            promptBuilder = ReviewPromptBuilder(),
            formatter = ReviewFormatter()
        )

        // Run code review
        println("🔍 Starting code review...")
        println()
        val reviewResult = reviewService.reviewPullRequest(prNumber, repo)

        if (reviewResult.isSuccess) {
            println()
            println("✅ Code review completed successfully!")
            println()
            println("Review has been posted to PR #$prNumber")
        } else {
            println()
            println("❌ Code review failed: ${reviewResult.exceptionOrNull()?.message}")
            reviewResult.exceptionOrNull()?.printStackTrace()
            exitProcess(1)
        }

        // Cleanup
        httpClient.close()

    } catch (e: Exception) {
        println()
        println("❌ Fatal error: ${e.message}")
        e.printStackTrace()
        exitProcess(1)
    }
}

/**
 * Parse command line arguments
 */
private fun parseArguments(args: Array<String>): Map<String, String> {
    val map = mutableMapOf<String, String>()
    var i = 0
    while (i < args.size) {
        if (args[i].startsWith("--") && i + 1 < args.size) {
            map[args[i]] = args[i + 1]
            i += 2
        } else {
            i++
        }
    }
    return map
}

/**
 * Print usage information
 */
private fun printUsage() {
    println("Usage: code-assistant --mode review --pr <number> --repo <owner/repo>")
    println()
    println("Arguments:")
    println("  --mode review         Run in code review mode")
    println("  --pr <number>         Pull request number")
    println("  --repo <owner/repo>   Repository in format owner/repo")
    println()
    println("Environment variables:")
    println("  CLAUDE_API_KEY        Claude API key (required)")
    println("  GITHUB_TOKEN          GitHub token (required for posting comments)")
    println("  OLLAMA_URL            Ollama server URL (optional)")
    println("  PR_NUMBER             Alternative to --pr")
    println("  REPOSITORY            Alternative to --repo")
    println()
    println("Example:")
    println("  java -jar code-assistant.jar --mode review --pr 123 --repo owner/repo")
}