package io.github.tritium_launcher.launcher.core.project.templates

import io.github.tritium_launcher.launcher.core.project.templates.generation.GeneratorStepDescriptor
import kotlinx.serialization.Serializable

/**
 * Serializable project template used to describe generator steps and variables.
 */
@Serializable
data class ProjectTemplate(
    val id: String,
    val name: String,
    val description: String,
    val variables: List<TemplateVariable> = emptyList(),
    val genSteps: List<GeneratorStepDescriptor> = emptyList()
)
