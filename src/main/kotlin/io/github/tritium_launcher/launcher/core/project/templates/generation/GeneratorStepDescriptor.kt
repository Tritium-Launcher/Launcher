package io.github.tritium_launcher.launcher.core.project.templates.generation

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Serializable descriptor for a generator step.
 */
@Serializable
data class GeneratorStepDescriptor(
    val id: String,
    val type: String,
    val meta: JsonObject = JsonObject(emptyMap()),
    val affects: List<String> = emptyList()
)
