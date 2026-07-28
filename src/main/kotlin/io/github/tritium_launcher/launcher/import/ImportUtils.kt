/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.import

import io.github.tritium_launcher.api.BuiltinRegistries
import io.github.tritium_launcher.api.core.project.ProjectBase
import io.github.tritium_launcher.api.file.FileTypeDescriptor
import io.github.tritium_launcher.api.io.VPath
import io.github.tritium_launcher.launcher.ui.theme.TIcons
import io.github.tritium_launcher.launcher.ui.theme.qt.icon
import io.qt.gui.QIcon
import io.qt.gui.QPixmap
import java.security.MessageDigest

/**
 * Computes the SHA-1 hex digest of a byte array.
 *
 * @param bytes Input state.
 * @return Lowercase hex string, or `null` if the algorithm is unavailable.
 */
fun computeSha1(bytes: ByteArray): String? {
    return try {
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update(bytes)
        digest.digest().joinToString("") { "%02x".format(it) }
    } catch (_: Exception) { null }
}

/**
 * Returns a scaled [QPixmap] of the icon associated with a [KnownLauncher].
 *
 * @param launcher The launcher whose icon to fetch.
 * @param size Desired width and height in pixels.
 * @return Pixmap of the launcher icon.
 */
fun iconForLauncher(launcher: KnownLauncher, size: Int): QPixmap {
    val icon = launcher.icon
    return icon.pixmap(size, size)
}

/**
 * Returns the [QIcon] that best represents a file path, using [FileTypeDescriptor].
 *
 * @param path The file to look up an icon for.
 * @param dummyProject A lightweight project instance required for descriptor lookups.
 * @return Determined file-type icon, falling back to a generic file icon.
 */
fun iconForFile(path: VPath, dummyProject: ProjectBase): QIcon {
    val descriptor = FileTypeDescriptor.primary(path, dummyProject)
    return descriptor?.icon ?: TIcons.File.icon
}

/**
 * Converts a loader display name (e.g. "Fabric", "NeoForge") to its registry ID.
 *
 * Falls back to matching against [BuiltinRegistries.ModLoader] by id or display name.
 *
 * @param displayName Loader display name (might be `null`).
 * @return Normalized loader ID such as "fabric", "neoforge", "forge", "quilt", or `null`.
 */
fun mapLoaderId(displayName: String?): String? {
    if (displayName == null) return null
    val loaderNameToId = mapOf(
        "Fabric" to "fabric",
        "NeoForge" to "neoforge",
        "Forge" to "forge",
        "Quilt" to "quilt"
    )
    return loaderNameToId[displayName]
        ?: BuiltinRegistries.ModLoader.all().firstOrNull { it.id.equals(displayName, ignoreCase = true) }?.id
        ?: BuiltinRegistries.ModLoader.all().firstOrNull { it.displayName.equals(displayName, ignoreCase = true) }?.id
}
