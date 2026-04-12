package io.github.tritium_launcher.launcher.core.project

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Full contents of the Tritium project definition file (`trproj.json`) on disk.
 */
@Serializable
data class TrProjectFile(
    val type: String = "",
    val name: String = "",
    val icon: String = "",
    val schemaVersion: Int = 1,
    val meta: JsonElement = JsonObject(emptyMap())
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("type", type)
        put("name", name)
        put("icon", icon)
        put("schemaVersion", schemaVersion)
        put("meta", meta)
    }
}
