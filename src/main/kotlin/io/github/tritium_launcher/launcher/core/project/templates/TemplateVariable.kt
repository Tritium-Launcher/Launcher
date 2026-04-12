package io.github.tritium_launcher.launcher.core.project.templates

import kotlinx.serialization.Serializable

/**
 * Variable definition used by project templates.
 */
@Serializable
data class TemplateVariable(
    val id: String,
    val type: String = "string",
    val default: String? = null,
    val description: String? = null,
    val required: Boolean = false
)
