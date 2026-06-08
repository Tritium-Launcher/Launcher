package io.github.tritium_launcher.launcher.core.mod

import io.github.tritium_launcher.launcher.io.VPath
import io.github.tritium_launcher.launcher.logger
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@Serializable
data class ModRegistryEntry(
    val projectId: String,
    val modId: String,
    val displayName: String,
    val fileName: String,
    val source: String,
    val versionId: String,
    val versionLabel: String,
    val iconPath: String? = null,
    val projectUrl: String? = null,
    val fileHash: String? = null,
    val installedAt: Long? = null,
    val enabled: Boolean = true,
    val excludedFromRelease: Boolean = false,
    val side: String = "BOTH",
    val releaseType: String = "release",
    val requiresManualDownload: Boolean = false,
    val dependencies: List<String> = emptyList(),
)

@Serializable
data class ModRegistryData(
    val version: Int = 2,
    val mods: Map<String, ModRegistryEntry> = emptyMap(),
)

@OptIn(ExperimentalTime::class)
class ModRegistryStore(projectDir: VPath) {
    private val logger = logger()
    private val registryPath: VPath = projectDir.resolve(".tr/mod-registry.json")

    fun load(): ModRegistryData {
        return try {
            val text = registryPath.readTextOrNull() ?: return ModRegistryData()
            json.decodeFromString<ModRegistryData>(text)
        } catch (e: Exception) {
            logger.warn("Failed to load mod registry, starting fresh", e)
            ModRegistryData()
        }
    }

    fun save(data: ModRegistryData) {
        try {
            registryPath.parent().mkdirs()
            registryPath.writeTextAtomic(json.encodeToString(data))
        } catch (e: Exception) {
            logger.warn("Failed to save mod registry", e)
        }
    }

    fun updateEntry(entry: ModRegistryEntry) {
        val data = load()
        val updated = data.copy(
            mods = data.mods + (entry.projectId to entry)
        )
        save(updated)
    }

    fun removeEntry(projectId: String) {
        val data = load()
        save(data.copy(mods = data.mods - projectId))
    }

    fun getEntry(projectId: String): ModRegistryEntry? = load().mods[projectId]

    fun getEntryByModId(modId: String): ModRegistryEntry? =
        load().mods.values.firstOrNull { it.modId == modId }

    fun getAllEntries(): Collection<ModRegistryEntry> = load().mods.values

    fun toInstalledMod(entry: ModRegistryEntry): InstalledMod = InstalledMod(
        projectId = entry.projectId,
        modId = entry.modId,
        fileName = entry.fileName,
        displayName = entry.displayName,
        side = try { ModSide.valueOf(entry.side.uppercase()) } catch (_: Exception) { ModSide.BOTH },
        releaseType = entry.releaseType,
        source = entry.source,
        versionId = entry.versionId,
        versionLabel = entry.versionLabel,
        iconPath = entry.iconPath,
        projectUrl = entry.projectUrl,
        fileHash = entry.fileHash,
        installedAt = entry.installedAt?.let { Instant.fromEpochMilliseconds(it) },
        enabled = entry.enabled,
        excludedFromRelease = entry.excludedFromRelease,
        requiresManualDownload = entry.requiresManualDownload,
        dependencies = entry.dependencies,
    )

    fun entryFromInstalledMod(mod: InstalledMod): ModRegistryEntry = ModRegistryEntry(
        projectId = mod.projectId,
        modId = mod.modId,
        displayName = mod.displayName,
        fileName = mod.fileName,
        source = mod.source,
        versionId = mod.versionId,
        versionLabel = mod.versionLabel,
        iconPath = mod.iconPath,
        projectUrl = mod.projectUrl,
        fileHash = mod.fileHash,
        installedAt = mod.installedAt?.toEpochMilliseconds(),
        enabled = mod.enabled,
        excludedFromRelease = mod.excludedFromRelease,
        side = mod.side.name,
        releaseType = mod.releaseType,
        requiresManualDownload = mod.requiresManualDownload,
        dependencies = mod.dependencies,
    )

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            prettyPrint = true
        }
    }
}
