package io.github.tritium_launcher.launcher.core.modpack.modrinth.api

import kotlinx.serialization.Serializable

@Serializable
data class Dependency(
    val version_id: String?,
    val project_id: String?,
    val file_name: String?,
    val dependency_type: DependencyType
)