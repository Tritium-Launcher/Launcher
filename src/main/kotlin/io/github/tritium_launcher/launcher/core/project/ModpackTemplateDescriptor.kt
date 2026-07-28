/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.core.project

import io.github.tritium_launcher.api.core.project.ProjectBase
import io.github.tritium_launcher.api.io.VPath
import io.github.tritium_launcher.api.modpack.ModpackMeta
import io.github.tritium_launcher.api.project.TrProjectFile
import io.github.tritium_launcher.api.project.template.TemplateDescriptor
import io.github.tritium_launcher.launcher.core.project.templates.ProjectFileLoader
import io.github.tritium_launcher.launcher.ui.theme.TIcons
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Describes how to load Modpack projects from disk.
 */
object ModpackTemplateDescriptor : TemplateDescriptor<ModpackMeta>, ProjectFileLoader {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    override val id: String = "source"
    override val serializer = ModpackMeta.serializer()
    override val projectName: String = "Modpack"
    override val defaultIcon: String = TIcons.defaultProjectIcon
    override val currentSchema: Int = 1

    /**
     * Create a typed project from source metadata.
     */
    override fun createProjectFromMeta(meta: ModpackMeta, schemaVersion: Int, projectDir: VPath): ProjectBase {
        val rawMeta: JsonObject = json.encodeToJsonElement(serializer, meta).jsonObject
        return Project(meta = meta, rawMeta = rawMeta, projectDir = projectDir)
    }

    /**
     * Load a project using the standard project definition file (.trproj).
     */
    override fun loadFromProjectFile(projectFile: TrProjectFile, projectDir: VPath): ProjectBase {
        val metaPath = projectFile.metaPath
            .takeIf { it.isNotBlank() }
            ?: "trmodpack.toml"

        fun tryReadMeta(path: String): ModpackMeta? {
            val file = projectDir.resolve(path)
            val text = file.readTextOrNull()
            if (text.isNullOrBlank()) return null
            return if (path.endsWith(".toml")) {
                runCatching { ProjectFiles.toml.decodeFromString(serializer, text) }.getOrNull()
            } else {
                runCatching { json.decodeFromString(serializer, text) }.getOrNull()
            }
        }

        val meta = tryReadMeta(metaPath) ?: run {
            val alt = if (metaPath.endsWith(".toml"))
                metaPath.removeSuffix(".toml") + ".json"
            else
                metaPath.removeSuffix(".json") + ".toml"
            tryReadMeta(alt)
        }

        val resolvedMeta = meta ?: ModpackMeta(
            id = projectFile.name.ifBlank { projectDir.fileName() },
            minecraftVersion = "unknown",
            loader = "unknown",
            loaderVersion = "unknown",
            source = "unknown"
        )
        if (projectFile.icon.isNotBlank()) {
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
        typeId = id,
        projectDir = projectDir,
        name = meta.id,
        icon = meta.icon ?: defaultIcon,
        rawMeta = rawMeta,
        typedMeta = meta
    )
}
