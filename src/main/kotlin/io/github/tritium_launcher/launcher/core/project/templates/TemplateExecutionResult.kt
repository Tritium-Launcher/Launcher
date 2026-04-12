package io.github.tritium_launcher.launcher.core.project.templates

import io.github.tritium_launcher.launcher.core.project.templates.generation.StepExecutionResult
import java.time.Instant

/**
 * Result of executing a project template.
 */
data class TemplateExecutionResult(
    val templateId: String,
    val projectRoot: String,
    val startTime: Instant,
    var endTime: Instant? = null,
    val successful: Boolean,
    val snapshotPath: String?,
    val stepResults: List<StepExecutionResult>,
    val logs: List<String>
)
