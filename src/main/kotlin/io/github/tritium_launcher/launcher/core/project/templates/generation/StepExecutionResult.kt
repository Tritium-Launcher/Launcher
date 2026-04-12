package io.github.tritium_launcher.launcher.core.project.templates.generation

/**
 * Result of executing a generator step.
 */
data class StepExecutionResult(
    val stepId: String,
    val stepType: String,
    val success: Boolean,
    val message: String? = null,
    val createdFiles: List<String> = emptyList(),
    val modifiedFiles: List<String> = emptyList()
)
