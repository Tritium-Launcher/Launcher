package io.github.tritium_launcher.launcher.ui.helpers

import io.github.tritium_launcher.launcher.io.VPath
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.concurrent.ConcurrentHashMap

object CacheManager {
    data class CacheConfig(
        val maxBytes: Long,
        val maxAgeDays: Long = -1,
        val checkEvery: Int = 5
    )

    private val configs = mapOf(
        "categories" to CacheConfig(maxBytes = 5L * 1024 * 1024),
        "items" to CacheConfig(maxBytes = 100L * 1024 * 1024),
        "descriptions" to CacheConfig(maxBytes = 50L * 1024 * 1024, maxAgeDays = 7),
    )

    private val writeCounters = ConcurrentHashMap<String, Int>()

    fun touch(file: VPath) {
        runCatching { Files.setLastModifiedTime(file.toJPath(), FileTime.fromMillis(System.currentTimeMillis())) }
    }

    fun evict(cacheDir: VPath, subdir: String) {
        val config = configs[subdir] ?: return
        val dir = cacheDir.resolve(subdir)
        if (!dir.exists()) return

        val entries = mutableListOf<Pair<Path, Long>>()
        var totalSize = 0L
        val cutoff = if (config.maxAgeDays > 0)
            System.currentTimeMillis() - config.maxAgeDays * 24L * 60 * 60 * 1000L
        else -1L

        try {
            Files.walk(dir.toJPath()).use { stream ->
                stream.filter { !Files.isDirectory(it) }.forEach { path ->
                    val mtime = Files.getLastModifiedTime(path).toMillis()
                    if (cutoff > 0 && mtime < cutoff) {
                        runCatching { Files.deleteIfExists(path) }
                        return@forEach
                    }
                    totalSize += Files.size(path)
                    entries.add(path to mtime)
                }
            }
        } catch (_: Exception) { return }

        if (totalSize <= config.maxBytes) return

        entries.sortBy { it.second }
        val target = (config.maxBytes * 0.8).toLong()
        for ((path, _) in entries) {
            if (totalSize <= target) break
            val size = runCatching { Files.size(path) }.getOrNull() ?: continue
            if (runCatching { Files.deleteIfExists(path) }.getOrDefault(false)) {
                totalSize -= size
            }
        }
    }

    fun evictIfNeeded(cacheDir: VPath, subdir: String) {
        val config = configs[subdir] ?: return
        val count = writeCounters.merge(subdir, 1) { old, _ -> old + 1 } ?: return
        if (count % config.checkEvery != 0) return
        evict(cacheDir, subdir)
    }
}
