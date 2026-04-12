package io.github.tritium_launcher.launcher.core.project

import io.github.tritium_launcher.launcher.core.project.templates.ProjectFileLoader
import io.github.tritium_launcher.launcher.core.project.templates.TemplateDescriptor
import io.github.tritium_launcher.launcher.io.VPath
import io.github.tritium_launcher.launcher.ui.theme.TIcons
import kotlinx.serialization.json.*

/**
 * Describes how to load Modpack projects from disk.
 */
object ModpackTemplateDescriptor : TemplateDescriptor<ModpackMeta>, ProjectFileLoader {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    override val typeId: String = "modpack"
    override val serializer = ModpackMeta.serializer()
    override val projectName: String = "Modpack"
    override val defaultIcon: String = TIcons.defaultProjectIcon
    override val currentSchema: Int = 1

    /**
     * Create a typed project from modpack metadata.
     */
    override fun createProjectFromMeta(meta: ModpackMeta, schemaVersion: Int, projectDir: VPath): ProjectBase {
        val rawMeta: JsonObject = json.encodeToJsonElement(serializer, meta).jsonObject
        return Project(meta = meta, rawMeta = rawMeta, projectDir = projectDir)
    }

    /**
     * Load a project using the standard project definition file (trproj.json).
     */
    override fun loadFromProjectFile(projectFile: TrProjectFile, projectDir: VPath): ProjectBase {
        val metaPath = (projectFile.meta as? JsonObject)?.get("metaPath")?.jsonPrimitive?.contentOrNull
        val meta = if(metaPath != null) {
            val file = projectDir.resolve(metaPath)
            try {
                val text = file.readTextOrNull()
                if(text.isNullOrBlank()) null else json.decodeFromString(serializer, text)
            } catch (_: Throwable) {
                null
            }
        } else null

        val resolvedMeta = meta ?: ModpackMeta(
            id = projectFile.name.ifBlank { projectDir.fileName() },
            minecraftVersion = "unknown",
            loader = "unknown",
            loaderVersion = "unknown",
            source = "unknown"
        )
        if(projectFile.icon.isNotBlank()) {
            return Project(
                meta = resolvedMeta.copy(icon = projectFile.icon),
                rawMeta = json.encodeToJsonElement(serializer, resolvedMeta).jsonObject,
                projectDir = projectDir
            )
        }
        return createProjectFromMeta(resolvedMeta, currentSchema, projectDir)
    }


    private fun Project(
        meta: ModpackMeta,
        rawMeta: JsonObject,
        projectDir: VPath
    ): Project<ModpackMeta> = Project(
        typeId = typeId,
        projectDir = projectDir,
        name = meta.id,
        icon = meta.icon ?: defaultIcon,
        rawMeta = rawMeta,
        typedMeta = meta
    )
}
