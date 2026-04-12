package io.github.tritium_launcher.launcher.core.project

import io.github.tritium_launcher.launcher.fromHome
import io.github.tritium_launcher.launcher.io.VPath
import kotlinx.serialization.json.JsonObject
import java.time.Instant

/**
 * Typed project with decoded metadata.
 *
 * @param typeId Project type id registered in [TemplateRegistry].
 * @param projectDir Root directory of the project.
 * @param name Display name shown in the dashboard.
 * @param icon Icon path (absolute or project-relative).
 * @param rawMeta Raw metadata JSON used for display or fallback.
 * @param typedMeta Strongly typed metadata for the project type.
 */
class Project<T: Any>(
    typeId: String,
    projectDir: VPath,
    name: String,
    icon: String,
    rawMeta: JsonObject,
    val typedMeta: T
): ProjectBase(typeId, projectDir, name, icon, rawMeta)

/**
 * Base representation of a project on disk.
 */
open class ProjectBase(
    val typeId: String,
    val projectDir: VPath,
    val name: String,
    val icon: String,
    val rawMeta: JsonObject,
) {
    var lastAccessed: Instant? = null

    val path: VPath
        get() = projectDir.toAbsolute()

    /**
     * Resolve the icon path, expanding a leading "~" to the user home directory.
     */
    fun getIconPath(): String {
        if(icon.startsWith("~")) {
            val removed = icon.removePrefix("~")
            return fromHome(removed).normalize().toString()
        }

        val v = VPath.get(icon)
        return if(v.isAbsolute) {
            v.toAbsolute().toString()
        } else {
            projectDir.resolve(icon).toAbsolute().toString()
        }
    }
}
