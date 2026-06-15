package io.github.tritium_launcher.launcher.import

import io.github.tritium_launcher.launcher.core.mod.ModSide
import io.github.tritium_launcher.launcher.io.VPath

/**
 * Represents a mod discovered during instance scanning that can be imported.
 *
 * Each instance maintains a mutable copy so that UI state (checked, source metadata) can be
 * updated without copying the entire list on every change. Fields like [sha1Hash] and
 * [fileFingerprint] are populated during scanning; source-related fields are filled in later
 * by [ModSearchHelper] when the user selects a mod source.
 *
 * @param jarPath Absolute path to the .jar file on disk.
 * @param modId Identifier extracted from the jar metadata.
 * @param displayName Human-readable name from metadata, falling back to [modId].
 * @param fileName Jar filename (e.g. "my-mod-1.0.jar").
 * @param side Side constraint from the jar manifest.
 * @param iconBytes Raw PNG bytes of the jar icon, or null.
 * @param sha1Hash SHA-1 digest of the jar contents for cache matching.
 * @param fileFingerprint Source-specific fingerprint (e.g. Modrinth FP).
 * @param sourceProjectId ID of the matching project on the chosen mod source.
 * @param sourceIconUrl URL to the project icon on the source.
 * @param sourceAvailable Whether a matching version was found on the source.
 * @param sourceStatus Human-readable status string ("Available", "Not Available", etc.).
 * @param checked Whether the user has checked this mod for import.
 * @param dependencyIds Project IDs of required dependencies from the matched source version.
 */
data class ImportableMod(
    val jarPath: VPath,
    val modId: String,
    val displayName: String,
    val fileName: String,
    val side: ModSide,
    val iconBytes: ByteArray?,
    var sha1Hash: String? = null,
    var fileFingerprint: Long? = null,
    var sourceProjectId: String? = null,
    var sourceIconUrl: String? = null,
    var sourceAvailable: Boolean? = null,
    var sourceStatus: String? = null,
    var checked: Boolean = true,
    var dependencyIds: List<String> = emptyList()
)
