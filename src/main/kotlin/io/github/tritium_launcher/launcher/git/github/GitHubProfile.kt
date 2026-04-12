package io.github.tritium_launcher.launcher.git.github

data class GitHubProfile(
    val id: String,
    val login: String,
    val name: String?,
    val avatarUrl: String?
)
