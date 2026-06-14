package io.github.tritium_launcher.launcher.import

import io.github.tritium_launcher.launcher.core.mod.ModSide
import io.github.tritium_launcher.launcher.io.VPath

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
