package io.github.tritium_launcher.launcher.core.modpack.modrinth.api

import io.github.tritium_launcher.launcher.core.modpack.ReleaseType
import kotlinx.serialization.Serializable

/**
 * For lookup only
 */
@Serializable
data class ModrinthVersion(
    val name: String,
    val version_number: String,
    val changelog: String?,
    val dependencies: List<Dependency>,
    val game_versions: List<String>,
    val version_type: ReleaseType,
    val loaders: List<String>,
    val featured: Boolean,
    val status: Status,
    val requested_status: RequestedStatus?,
    val id: String,
    val project_id: String,
    val author_id: String,
    val date_published: String,
    val downloads: Int,
    val changelog_url: String?,
    val files: List<MRFile>
) {
    /**
     * @param primary: Whether this file is the primary one for the version, or the first file if there is no primary.
     */
    @Serializable
    data class MRFile(
        val hashes: Hashes,
        val url: String,
        val filename: String,
        val primary: Boolean,
        val size: Int
    ) {
        @Serializable
        data class Hashes(
            val sha512: String,
            val sha1: String
        )
    }
}

/**
 * For creation only
 */
@Serializable
data class ModrinthVersionCreate(
    val name: String,
    val version_number: String,
    val changelog: String?,
    val dependencies: List<Dependency>,
    val game_versions: List<String>,
    val version_type: ReleaseType,
    val loaders: List<String>,
    val featured: Boolean,
    val status: Status,
    val requested_status: RequestedStatus?,
    val project_id: String,
    val file_parts: List<String>,
    val primary_file: String
)