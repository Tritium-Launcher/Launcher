package io.github.tritium_launcher.launcher.io

import java.nio.file.Files
import java.util.jar.JarFile

/**
 * Materialize [cache] into [dest] using a symlink when possible, falling back to a copy.
 */
fun linkOrCopyFromCache(cache: VPath, dest: VPath): Boolean {
    if (!cache.exists()) return false
    if (!isUsableCachedArtifact(cache)) {
        try {
            cache.delete()
        } catch (_: Throwable) {
        }
        return false
    }

    val destPath = dest.toJPath()
    if (Files.exists(destPath)) {
        if (isUsableCachedArtifact(dest)) return true
    }

    if (Files.isSymbolicLink(destPath)) {
        try {
            Files.deleteIfExists(destPath)
        } catch (_: Throwable) {
        }
    } else if (dest.exists()) {
        try {
            dest.delete()
        } catch (_: Throwable) {
        }
    }

    dest.parent().mkdirs()
    return try {
        Files.createSymbolicLink(destPath, cache.toJPath())
        isUsableCachedArtifact(dest)
    } catch (_: Throwable) {
        try {
            dest.writeBytes(cache.bytesOrNothing())
            isUsableCachedArtifact(dest)
        } catch (_: Throwable) {
            false
        }
    }
}

private fun isUsableCachedArtifact(path: VPath): Boolean {
    if (!path.exists()) return false
    val size = path.sizeOrNull() ?: return false
    if (size <= 0L) return false

    if (!path.fileName().endsWith(".jar", ignoreCase = true)) {
        return true
    }

    return try {
        JarFile(path.toJFile()).use { true }
    } catch (_: Throwable) {
        false
    }
}
