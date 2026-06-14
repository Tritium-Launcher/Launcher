package io.github.tritium_launcher.launcher.import

import io.github.tritium_launcher.launcher.core.project.ProjectBase
import io.github.tritium_launcher.launcher.extension.core.BuiltinRegistries
import io.github.tritium_launcher.launcher.io.VPath
import io.github.tritium_launcher.launcher.ui.project.editor.file.FileTypeDescriptor
import io.github.tritium_launcher.launcher.ui.theme.TIcons
import io.github.tritium_launcher.launcher.ui.theme.qt.icon
import io.qt.gui.QIcon
import io.qt.gui.QPixmap
import java.security.MessageDigest

fun computeSha1(bytes: ByteArray): String? {
    return try {
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update(bytes)
        digest.digest().joinToString("") { "%02x".format(it) }
    } catch (_: Exception) { null }
}

fun iconForLauncher(launcher: KnownLauncher, size: Int): QPixmap {
    val icon = launcher.icon
    return icon.pixmap(size, size)
}

fun iconForFile(path: VPath, dummyProject: ProjectBase): QIcon {
    val descriptor = FileTypeDescriptor.primary(path, dummyProject)
    return descriptor?.icon ?: TIcons.File.icon
}

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
