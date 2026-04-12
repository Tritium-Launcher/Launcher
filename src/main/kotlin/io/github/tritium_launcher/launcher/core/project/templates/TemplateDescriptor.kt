package io.github.tritium_launcher.launcher.core.project.templates

import io.github.tritium_launcher.launcher.core.project.ProjectBase
import io.github.tritium_launcher.launcher.io.VPath
import kotlinx.serialization.KSerializer

/**
 * Describes how to decode a project's metadata and create a typed project.
 */
interface TemplateDescriptor<T: Any> {
    /**
     * Project type id that matches `type` in the project definition file.
     */
    val typeId: String
    /**
     * Serializer used to decode `meta` into [T].
     */
    val serializer: KSerializer<T>
    /**
     * Default display name for this project type.
     */
    val projectName: String
    /**
     * Default icon path for this project type.
     */
    val defaultIcon: String
    /**
     * Latest schema version for this project's metadata.
     */
    val currentSchema: Int

    /**
     * Create a project instance from metadata.
     *
     * @param meta Typed metadata.
     * @param schemaVersion Schema version for the metadata on disk.
     * @param projectDir Root directory for the project.
     */
    fun createProjectFromMeta(meta: T, schemaVersion: Int, projectDir: VPath): ProjectBase
}
