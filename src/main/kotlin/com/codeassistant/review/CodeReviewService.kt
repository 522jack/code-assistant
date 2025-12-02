package com.codeassistant.review

import com.codeassistant.github.GitHubTool
import com.codeassistant.llm.ClaudeClient
import com.codeassistant.llm.ClaudeMessage
import com.codeassistant.rag.RagSearchConfig
import com.codeassistant.rag.RagService
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.Json

private val logger = KotlinLogging.logger {}

/**
 * Service for conducting code reviews using RAG + Claude
 */
class CodeReviewService(
    private val ragService: RagService,
    private val claudeClient: ClaudeClient,
    private val githubTool: GitHubTool,
    private val promptBuilder: ReviewPromptBuilder = ReviewPromptBuilder(),
    private val formatter: ReviewFormatter = ReviewFormatter(),
    private val config: ReviewConfig = ReviewConfig()
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Review a Pull Request and post comment
     */
    suspend fun reviewPullRequest(
        prNumber: String,
        repo: String
    ): Result<String> {
        return try {
            logger.info { "Starting code review for PR #$prNumber in $repo" }

            // 1. Get PR information
            logger.info { "Fetching PR information..." }
            val prInfo = githubTool.getPullRequest(prNumber, repo).getOrElse {
                logger.error { "Failed to fetch PR info: ${it.message}" }
                return Result.failure(it)
            }
            logger.info { "PR #${prInfo.number}: ${prInfo.title}" }
            logger.info { "Changed files: ${prInfo.changedFiles.size}" }

            // 2. Get PR diff
            logger.info { "Fetching PR diff..." }
            val diff = githubTool.getPullRequestDiff(prNumber).getOrElse {
                logger.error { "Failed to fetch diff: ${it.message}" }
                return Result.failure(it)
            }
            logger.info { "Diff size: ${diff.length} characters" }

            // 3. Get context from RAG for changed files
            logger.info { "Searching for relevant context from RAG..." }
            val ragContext = mutableListOf<com.codeassistant.rag.RagSearchResult>()

            // Search for architecture and patterns
            val archSearch = ragService.search(
                query = "project architecture patterns best practices",
                config = RagSearchConfig(topK = config.ragTopK, minSimilarity = config.ragMinSimilarity)
            )
            archSearch.getOrNull()?.let { ragContext.addAll(it) }

            // Search for context related to changed files
            prInfo.changedFiles.take(5).forEach { filePath ->
                val fileSearch = ragService.search(
                    query = "implementation patterns for ${filePath.substringAfterLast('/')}",
                    config = RagSearchConfig(topK = 2, minSimilarity = config.ragMinSimilarity)
                )
                fileSearch.getOrNull()?.let { ragContext.addAll(it) }
            }

            logger.info { "Found ${ragContext.size} relevant context fragments" }

            // 4. Build review prompt
            logger.info { "Building review prompt..." }
            val prompt = promptBuilder.buildReviewPrompt(
                prInfo = prInfo,
                diff = diff,
                ragContext = ragContext.distinctBy { it.chunk.chunkId }
            )

            // 5. Send to Claude API
            logger.info { "Sending request to Claude API..." }
            val claudeResponse = claudeClient.sendMessage(
                messages = listOf(ClaudeMessage("user", prompt)),
                systemPrompt = ReviewPromptBuilder.REVIEW_SYSTEM_PROMPT,
                maxTokens = 4096
            ).getOrElse {
                logger.error { "Failed to get Claude response: ${it.message}" }
                return Result.failure(it)
            }

            val claudeText = claudeResponse.content.firstOrNull { it.type == "text" }?.text
                ?: return Result.failure(Exception("No text content in Claude response"))

            logger.info { "Received response from Claude (${claudeText.length} chars)" }

            // 6. Parse Claude response
            logger.info { "Parsing review result..." }
            val reviewResult = parseClaudeReview(claudeText)

            // 7. Format as markdown
            logger.info { "Formatting review result..." }
            val markdown = formatter.formatReview(reviewResult, prInfo)

            // 8. Post comment to PR
            logger.info { "Posting comment to PR..." }
            githubTool.postComment(prNumber, repo, markdown).getOrElse {
                logger.error { "Failed to post comment: ${it.message}" }
                return Result.failure(it)
            }

            logger.info { "✅ Code review completed successfully" }
            logger.info { "Summary: ${formatter.formatSummary(reviewResult)}" }

            Result.success(markdown)

        } catch (e: Exception) {
            logger.error(e) { "Error during code review" }
            Result.failure(e)
        }
    }

    /**
     * Parse Claude's review response (JSON format)
     */
    private fun parseClaudeReview(content: String): CodeReviewResult {
        return try {
            // Extract JSON from markdown code blocks if present
            val jsonContent = if (content.contains("```json")) {
                content.substringAfter("```json")
                    .substringBefore("```")
                    .trim()
            } else if (content.contains("```")) {
                content.substringAfter("```")
                    .substringBefore("```")
                    .trim()
            } else {
                content.trim()
            }

            logger.debug { "Parsing JSON: ${jsonContent.take(200)}..." }
            json.decodeFromString<CodeReviewResult>(jsonContent)

        } catch (e: Exception) {
            logger.warn(e) { "Failed to parse Claude response as JSON, using fallback parsing" }

            // Fallback: extract information from text
            parseFallback(content)
        }
    }

    /**
     * Fallback parser if Claude doesn't return valid JSON
     */
    private fun parseFallback(content: String): CodeReviewResult {
        logger.info { "Using fallback parser for Claude response" }

        val issues = mutableListOf<ReviewIssue>()
        val positives = mutableListOf<String>()

        // Extract summary (first paragraph)
        val summary = content.lines()
            .dropWhile { it.isBlank() }
            .takeWhile { it.isNotBlank() }
            .joinToString(" ")
            .take(300)

        // Try to extract issues from bullet points or numbered lists
        val lines = content.lines()
        for (i in lines.indices) {
            val line = lines[i].trim()

            if (line.matches(Regex("^[•\\-*].*")) || line.matches(Regex("^\\d+\\..*"))) {
                // This might be an issue
                val message = line.replaceFirst(Regex("^[•\\-*\\d.]+\\s*"), "")
                if (message.isNotBlank()) {
                    issues.add(
                        ReviewIssue.create(
                            severity = Severity.MEDIUM,
                            category = Category.BEST_PRACTICES,
                            filePath = "multiple files",
                            message = message
                        )
                    )
                }
            }
        }

        // Overall assessment is the last paragraph
        val overallAssessment = content.lines()
            .dropLastWhile { it.isBlank() }
            .takeLastWhile { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { "The code changes look reasonable. Please review the comments above." }

        return CodeReviewResult(
            summary = summary.ifBlank { "Code review completed" },
            issues = issues,
            positives = positives,
            overallAssessment = overallAssessment
        )
    }
}