package io.github.tritium_launcher.launcher.import

import io.github.tritium_launcher.launcher.fromTR
import io.github.tritium_launcher.launcher.io.VPath
import io.github.tritium_launcher.launcher.logger
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest

private val cacheLog = logger("ImportCache")
private val cacheJson = Json { prettyPrint = true }

@Serializable
data class ImportModCache(
    val cacheVersion: Int = 1,
    val instancePath: String,
    val instanceGameVersion: String?,
    val instanceLoader: String?,
    val instanceLoaderVersion: String?,
    val sourceId: String,
    val mods: List<CachedMod>,
    val cachedAt: Long,
)

@Serializable
data class CachedMod(
    val jarFile: String,
    val modId: String,
    val displayName: String,
    val sha1Hash: String?,
    val fileFingerprint: Long? = null,
    val sourceProjectId: String?,
    val sourceIconUrl: String?,
    val sourceAvailable: Boolean?,
    val sourceStatus: String?,
    val dependencyIds: List<String> = emptyList(),
)

fun cacheKey(instance: DetectedInstance, sourceId: String): String {
    val input = instance.minecraftDir.toAbsolute().toString() + "|" + sourceId
    val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
    return digest.joinToString("") { "%02x".format(it) }
}

fun cacheFilePath(instance: DetectedInstance, sourceId: String): VPath {
    return fromTR("cache", "mod-import").resolve("${cacheKey(instance, sourceId)}.json")
}

fun tryLoadImportCache(
    instance: DetectedInstance,
    sourceId: String,
    scanned: List<ImportableMod>
): List<ImportableMod>? {
    val file = cacheFilePath(instance, sourceId)
    if (!file.exists()) return null
    return try {
        val jsonBytes = file.bytesOrNull() ?: return null
        val cache = cacheJson.decodeFromString<ImportModCache>(jsonBytes.decodeToString())
        if (cache.cacheVersion != 1) return null
        if (cache.mods.size != scanned.size) return null
        val cacheModMap = cache.mods.associateBy { it.jarFile }
        for (mod in scanned) {
            val cached = cacheModMap[mod.fileName] ?: return null
            if (cached.sha1Hash != mod.sha1Hash) return null
        }
        scanned.map { original ->
            val cached = cacheModMap[original.fileName]!!
            original.copy(
                fileFingerprint = cached.fileFingerprint,
                sourceProjectId = cached.sourceProjectId,
                sourceIconUrl = cached.sourceIconUrl,
                sourceAvailable = cached.sourceAvailable,
                sourceStatus = cached.sourceStatus,
                dependencyIds = cached.dependencyIds,
            )
        }
    } catch (t: Throwable) {
        cacheLog.warn("Failed to load import cache: {}", t.message)
        null
    }
}

fun saveImportCache(instance: DetectedInstance, sourceId: String, mods: List<ImportableMod>) {
    val cache = ImportModCache(
        instancePath = instance.minecraftDir.toAbsolute().toString(),
        instanceGameVersion = instance.gameVersion,
        instanceLoader = instance.loader,
        instanceLoaderVersion = instance.loaderVersion,
        sourceId = sourceId,
        mods = mods.map { m ->
            CachedMod(
                jarFile = m.fileName,
                modId = m.modId,
                displayName = m.displayName,
                sha1Hash = m.sha1Hash,
                fileFingerprint = m.fileFingerprint,
                sourceProjectId = m.sourceProjectId,
                sourceIconUrl = m.sourceIconUrl,
                sourceAvailable = m.sourceAvailable,
                sourceStatus = m.sourceStatus,
                dependencyIds = m.dependencyIds,
            )
        },
        cachedAt = System.currentTimeMillis()
    )
    try {
        val jsonStr = cacheJson.encodeToString(ImportModCache.serializer(), cache)
        val file = cacheFilePath(instance, sourceId)
        file.parent().mkdirs()
        file.writeBytesAtomic(jsonStr.toByteArray())
        cacheLog.warn("Saved import cache for {} -> {} ({} mods)", instance.name, sourceId, mods.size)
    } catch (t: Throwable) {
        cacheLog.warn("Failed to save import cache: {}", t.message)
    }
}

fun deleteImportCache(instance: DetectedInstance, sourceId: String) {
    val file = cacheFilePath(instance, sourceId)
    try {
        if (file.exists()) {
            file.delete()
            cacheLog.warn("Deleted import cache for {} -> {}", instance.name, sourceId)
        }
    } catch (t: Throwable) {
        cacheLog.warn("Failed to delete import cache: {}", t.message)
    }
}
