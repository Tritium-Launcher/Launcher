package io.github.tritium_launcher.launcher.import

import io.github.tritium_launcher.launcher.fromTR
import io.github.tritium_launcher.launcher.io.VPath
import io.github.tritium_launcher.launcher.logger
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest

private val cacheLog = logger("ImportCache")
private val cacheJson = Json { prettyPrint = true }

/**
 * Cache of imported mod metadata for a specific instance and source combination.
 *
 * Written to disk after source validation completes so that subsequent scans of the same
 * instance can skip re-querying the source. The cache is invalidated when the jar set or
 * any jar content (SHA-1) changes.
 *
 * @param cacheVersion Schema version for forward-compatibility (currently 1).
 * @param instancePath Absolute path of the instance's minecraft directory.
 * @param instanceGameVersion Minecraft version at cache time.
 * @param instanceLoader Loader display name at cache time.
 * @param instanceLoaderVersion Loader version at cache time.
 * @param sourceId ID of the mod source the cache applies to.
 * @param mods Per-mod cache entries.
 * @param cachedAt Timestamp (epoch millis) when this cache was written.
 * @see tryLoadImportCache
 * @see saveImportCache
 */
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

/**
 * Per-mod snapshot stored in [ImportModCache].
 *
 * @param jarFile Jar filename (e.g. "my-mod-1.0.jar").
 * @param modId Mod identifier from metadata.
 * @param displayName Human-readable mod name.
 * @param sha1Hash SHA-1 digest used for cache validation.
 * @param fileFingerprint Source-specific fingerprint for fast matching.
 * @param sourceProjectId ID of the matched source project.
 * @param sourceIconUrl Project icon URL on the source.
 * @param sourceAvailable Whether the mod was found on the source.
 * @param sourceStatus Status at cache time ("Available", "Matched by file hash", etc.).
 * @param dependencyIds Project IDs of required dependencies from the matched version.
 */
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

/**
 * Derives a cache key from an instance + source combination.
 *
 * @param instance The detected instance.
 * @param sourceId The mod source identifier.
 * @return SHA-256 hex string used as the cache filename.
 */
fun cacheKey(instance: DetectedInstance, sourceId: String): String {
    val input = instance.minecraftDir.toAbsolute().toString() + "|" + sourceId
    val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
    return digest.joinToString("") { "%02x".format(it) }
}

/**
 * Resolves the cache file path for a given instance + source.
 *
 * @param instance The detected instance.
 * @param sourceId The mod source identifier.
 * @return Path under `cache/mod-import/<key>.json`.
 */
fun cacheFilePath(instance: DetectedInstance, sourceId: String): VPath {
    return fromTR("cache", "mod-import").resolve("${cacheKey(instance, sourceId)}.json")
}

/**
 * Attempts to load and validate a previously saved import cache.
 *
 * The cache is considered valid only when:
 * - The cache schema version matches.
 * - The number of cached mods matches the scanned mod count.
 * - Every scanned mod has a corresponding cache entry with a matching SHA-1 hash.
 *
 * When valid, the cached source metadata (fingerprints, project IDs, status) is applied
 * back onto the scanned mod list so that source queries can be skipped.
 *
 * @param instance The detected instance.
 * @param sourceId The mod source identifier.
 * @param scanned Currently scanned mods to validate against.
 * @return A copy of [scanned] with source fields populated from cache, or `null` if the
 *   cache is missing, outdated, or corrupted.
 */
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

/**
 * Persists source validation results to disk for later reuse.
 *
 * @param instance The detected instance.
 * @param sourceId The mod source identifier.
 * @param mods The fully resolved mod list.
 */
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

/**
 * Removes the cached import data for a given instance + source.
 *
 * @param instance The detected instance.
 * @param sourceId The mod source identifier.
 */
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
