package com.codeassistant.review

import com.codeassistant.github.PullRequestInfo
import com.codeassistant.rag.RagSearchResult

/**
 * Builder for code review prompts
 */
class ReviewPromptBuilder(
    private val config: ReviewConfig = ReviewConfig()
) {
    /**
     * Build complete review prompt with RAG context
     */
    fun buildReviewPrompt(
        prInfo: PullRequestInfo,
        diff: String,
        ragContext: List<RagSearchResult>
    ): String {
        val limitedDiff = if (diff.length > config.maxDiffChars) {
            diff.take(config.maxDiffChars) + "\n\n... (diff truncated due to size)"
        } else {
            diff
        }

        return """
# Code Review Task

You are an expert code reviewer analyzing a Pull Request.

## Pull Request Information
- **Number**: #${prInfo.number}
- **Title**: ${prInfo.title}
- **Description**: ${if (prInfo.body.isNotBlank()) prInfo.body else "No description provided"}
- **Branch**: ${prInfo.headBranch} → ${prInfo.baseBranch}
- **Changed files**: ${prInfo.changedFiles.size}
  ${prInfo.changedFiles.take(10).joinToString("\n  ") { "- $it" }}
  ${if (prInfo.changedFiles.size > 10) "  ... and ${prInfo.changedFiles.size - 10} more" else ""}

## Project Context (from documentation and existing code)

${formatRagContext(ragContext)}

## Changes to Review

\`\`\`diff
$limitedDiff
\`\`\`

## Your Task

Analyze the code changes above and provide a comprehensive code review.

### Review Guidelines

1. **Find Issues** and categorize them by severity:
   - **CRITICAL**: Security vulnerabilities, data loss risks, breaking changes
   - **HIGH**: Bugs, logic errors, major performance issues
   - **MEDIUM**: Code style violations, missing tests, moderate improvements
   - **LOW**: Minor improvements, suggestions, optimization opportunities
   - **INFO**: Informational notes, questions

2. **Categorize Issues**:
   - **BUG**: Potential bugs and logic errors
   - **SECURITY**: Security vulnerabilities or risks
   - **PERFORMANCE**: Performance issues or inefficiencies
   - **CODE_STYLE**: Code style and formatting issues
   - **BEST_PRACTICES**: Violations of best practices
   - **ARCHITECTURE**: Architectural concerns
   - **DOCUMENTATION**: Missing or incorrect documentation
   - **TESTING**: Testing related issues

3. **Identify Positive Aspects**: What is done well in this PR?

4. **Provide Overall Assessment**: Summary and recommendations

### Output Format

Provide your response in JSON format:

\`\`\`json
{
  "summary": "Brief summary of the changes (2-3 sentences)",
  "issues": [
    {
      "severity": "HIGH",
      "category": "BUG",
      "filePath": "src/main/kotlin/Example.kt",
      "message": "Description of the issue",
      "suggestion": "How to fix it",
      "lineNumber": 42
    }
  ],
  "positives": [
    "Positive aspect 1",
    "Positive aspect 2"
  ],
  "overallAssessment": "Overall evaluation and recommendations"
}
\`\`\`

Be specific, constructive, and provide actionable feedback.
        """.trimIndent()
    }

    /**
     * Format RAG context from search results
     */
    private fun formatRagContext(results: List<RagSearchResult>): String {
        if (results.isEmpty()) {
            return "No relevant project context found."
        }

        return results.take(5).joinToString("\n\n") { result ->
            """
### ${result.documentTitle} (relevance: ${String.format("%.2f", result.similarity)})
**File**: ${result.filePath}

${result.chunk.content.take(500)}${if (result.chunk.content.length > 500) "..." else ""}
            """.trimIndent()
        }
    }

    companion object {
        /**
         * System prompt for code review
         */
        const val REVIEW_SYSTEM_PROMPT = """You are an expert code reviewer with deep knowledge of:
- Software engineering best practices
- Security vulnerabilities and secure coding
- Performance optimization
- Clean code principles
- Testing strategies
- Design patterns and architecture

Your role is to provide constructive, actionable feedback on code changes.

Guidelines:
- Be specific and reference exact code locations when possible
- Focus on significant issues that impact correctness, security, or maintainability
- Provide concrete suggestions for improvements
- Acknowledge good practices and well-written code
- Consider the project context and existing patterns
- Be respectful and constructive in your feedback
- Prioritize critical issues like security vulnerabilities and bugs

When analyzing code:
1. Check for bugs and logic errors
2. Identify security vulnerabilities
3. Evaluate performance implications
4. Assess code readability and maintainability
5. Verify adherence to project standards (from context)
6. Consider testing coverage and quality
7. Review error handling and edge cases

Return your analysis in the requested JSON format."""
    }
}