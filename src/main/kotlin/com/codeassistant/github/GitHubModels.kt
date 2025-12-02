package com.codeassistant.github

import kotlinx.serialization.Serializable

/**
 * Information about a Pull Request
 */
@Serializable
data class PullRequestInfo(
    val number: Int,
    val title: String,
    val body: String,
    val headBranch: String,
    val baseBranch: String,
    val changedFiles: List<String>
)

/**
 * Information about a file changed in PR
 */
@Serializable
data class DiffFile(
    val path: String,
    val additions: Int,
    val deletions: Int,
    val patch: String
)

/**
 * GitHub repository information
 */
data class RepositoryInfo(
    val owner: String,
    val name: String
) {
    val fullName: String get() = "$owner/$name"

    companion object {
        fun parse(repoString: String): RepositoryInfo {
            val parts = repoString.split("/")
            require(parts.size == 2) { "Repository must be in format 'owner/name'" }
            return RepositoryInfo(parts[0], parts[1])
        }
    }
}