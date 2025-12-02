package com.codeassistant.review

import kotlinx.serialization.Serializable

/**
 * Severity level of code review issue
 */
enum class Severity {
    CRITICAL,  // Security vulnerabilities, data loss risks
    HIGH,      // Bugs, logic errors, performance issues
    MEDIUM,    // Code style violations, missing tests
    LOW,       // Minor improvements, suggestions
    INFO       // Informational notes
}

/**
 * Category of code review issue
 */
enum class Category {
    BUG,              // Potential bugs and logic errors
    SECURITY,         // Security vulnerabilities
    PERFORMANCE,      // Performance issues
    CODE_STYLE,       // Code style and formatting
    BEST_PRACTICES,   // Best practices violations
    ARCHITECTURE,     // Architectural concerns
    DOCUMENTATION,    // Missing or incorrect documentation
    TESTING           // Testing related issues
}

/**
 * Individual code review issue
 */
@Serializable
data class ReviewIssue(
    val severity: String,           // Severity enum as string for serialization
    val category: String,           // Category enum as string
    val filePath: String,
    val message: String,
    val suggestion: String? = null,
    val lineNumber: Int? = null     // Optional line number if available
) {
    companion object {
        fun create(
            severity: Severity,
            category: Category,
            filePath: String,
            message: String,
            suggestion: String? = null,
            lineNumber: Int? = null
        ): ReviewIssue {
            return ReviewIssue(
                severity = severity.name,
                category = category.name,
                filePath = filePath,
                message = message,
                suggestion = suggestion,
                lineNumber = lineNumber
            )
        }
    }

    fun getSeverity(): Severity = Severity.valueOf(severity)
    fun getCategory(): Category = Category.valueOf(category)
}

/**
 * Complete code review result
 */
@Serializable
data class CodeReviewResult(
    val summary: String,
    val issues: List<ReviewIssue>,
    val positives: List<String>,
    val overallAssessment: String,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Configuration for code review
 */
data class ReviewConfig(
    val ragTopK: Int = 5,                    // Top K RAG results
    val ragMinSimilarity: Double = 0.7,      // Minimum similarity for code
    val maxDiffChars: Int = 10000,           // Maximum diff characters for Claude
    val codeChunkSize: Int = 800,            // Chunk size for code
    val codeChunkOverlap: Int = 100,         // Overlap for code chunks
    val includePositives: Boolean = true,    // Include positive aspects
    val severityFilter: List<Severity> = Severity.values().toList()  // Which severities to include
)

/**
 * Statistics about code review
 */
data class ReviewStatistics(
    val totalIssues: Int,
    val criticalCount: Int,
    val highCount: Int,
    val mediumCount: Int,
    val lowCount: Int,
    val infoCount: Int,
    val issuesByCategory: Map<Category, Int>,
    val filesReviewed: Int
) {
    companion object {
        fun fromReview(result: CodeReviewResult, filesReviewed: Int): ReviewStatistics {
            val issues = result.issues.map { ReviewIssue(it.severity, it.category, it.filePath, it.message, it.suggestion, it.lineNumber) }

            return ReviewStatistics(
                totalIssues = issues.size,
                criticalCount = issues.count { it.getSeverity() == Severity.CRITICAL },
                highCount = issues.count { it.getSeverity() == Severity.HIGH },
                mediumCount = issues.count { it.getSeverity() == Severity.MEDIUM },
                lowCount = issues.count { it.getSeverity() == Severity.LOW },
                infoCount = issues.count { it.getSeverity() == Severity.INFO },
                issuesByCategory = issues.groupBy { it.getCategory() }
                    .mapValues { (_, issueList) -> issueList.size },
                filesReviewed = filesReviewed
            )
        }
    }
}