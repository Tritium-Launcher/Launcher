package io.github.tritium_launcher.launcher.core.project

import io.github.tritium_launcher.launcher.core.project.templates.generation.GeneratorStepDescriptor
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

object StandardProjectSteps {

    fun metadataStep(id: String, manifest: String): GeneratorStepDescriptor = GeneratorStepDescriptor(
        id,
        "createFile",
        JsonObject(mapOf(
            "path" to JsonPrimitive("trmodpack.json"),
            "template" to JsonPrimitive(manifest),
            "overwrite" to JsonPrimitive(true)
        )),
        affects = listOf("trmodpack.json")
    )

    fun exportRulesStep(id: String = "create-export-rules"): GeneratorStepDescriptor = GeneratorStepDescriptor(
        id,
        "createFile",
        JsonObject(mapOf(
            "path" to JsonPrimitive("trexportrules.json"),
            "template" to JsonPrimitive("{}"),
            "overwrite" to JsonPrimitive(false)
        )),
        affects = listOf("trexportrules.json")
    )

    fun placeholderSteps(): List<GeneratorStepDescriptor> {
        fun placeholder(dir: String) = GeneratorStepDescriptor(
            "placeholder-$dir",
            "createFile",
            JsonObject(mapOf(
                "path" to JsonPrimitive("$dir/.placeholder"),
                "template" to JsonPrimitive("# placeholder to keep folder in VCS"),
                "overwrite" to JsonPrimitive(false)
            )),
            affects = listOf("$dir/**")
        )
        return listOf("mods", "config", "defaultconfigs", "logs", "saves").map { placeholder(it) }
    }

    fun iconStep(iconPath: String): GeneratorStepDescriptor? {
        if (iconPath.isBlank()) return null
        val normalizedFileUrl = if (iconPath.startsWith("file://")) iconPath else "file://$iconPath"
        return GeneratorStepDescriptor(
            "copy-icon",
            "fetch",
            JsonObject(mapOf(
                "url" to JsonPrimitive(normalizedFileUrl),
                "dest" to JsonPrimitive("icon.png")
            )),
            affects = listOf("icon.png")
        )
    }

    fun gitignoreStep(id: String = "gitignore"): GeneratorStepDescriptor = GeneratorStepDescriptor(
        id,
        "createFile",
        JsonObject(mapOf(
            "path" to JsonPrimitive(".gitignore"),
            "template" to JsonPrimitive(".tr/\ntr*.json\n"),
            "overwrite" to JsonPrimitive(false)
        )),
        affects = listOf(".gitignore")
    )
}
