package io.github.tritium_launcher.launcher.accounts

import kotlinx.serialization.Serializable

@Serializable
data class AccountDescriptor(
    val id: String,
    val username: String? = null,
    val subtitle: String? = null,
    val avatarUrl: String? = null,
    val label: String? = null
)

enum class AccountCapability {
    UPLOAD,
    VIEW_PROJECTS
}