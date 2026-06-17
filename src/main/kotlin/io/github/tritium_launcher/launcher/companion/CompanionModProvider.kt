package io.github.tritium_launcher.launcher.companion

import io.github.tritium_launcher.launcher.core.mod.InstalledMod
import io.github.tritium_launcher.launcher.core.mod.ModDatabase
import io.github.tritium_launcher.launcher.core.mod.ModSide
import io.github.tritium_launcher.launcher.core.source.ModVersionOption
import io.github.tritium_launcher.launcher.fromTR
import io.github.tritium_launcher.launcher.io.VPath
import io.github.tritium_launcher.launcher.logger
import io.github.tritium_launcher.launcher.redactUserPath
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import kotlin.time.Clock

@Serializable
data class CompanionManifest(
    val schema: Int,
    val project: CompanionProjectMeta? = null,
    val entries: List<CompanionManifestEntry>
)

@Serializable
data class CompanionProjectMeta(
    val title: String = "Tritium Companion Mod",
    val summary: String = "Bridges Tritium launcher features into Minecraft.",
    val description: String = "No description available.",
    val descriptionUrl: String? = null,
    val iconUrl: String? = null,
    val author: String? = null,
    val website: String? = null,
)

@Serializable
data class CompanionManifestEntry(
    val mcVersion: String,
    val loaders: List<String>,
    val modVersion: String,
    val displayName: String? = null,
    val releaseType: String? = null,
    val changelog: String? = null,
    val jars: Map<String, CompanionManifestJar>
)

@Serializable
data class CompanionManifestJar(
    val url: String,
    val sha256: String,
    val fileName: String
)

object CompanionModProvider {
    private const val DEFAULT_MANIFEST_URL = "https://raw.githubusercontent.com/Tritium-Launcher/Tritium-Companion/gh-pages/companion-versions.json"
    private val json = Json { ignoreUnknownKeys = true }
    private val logger = logger()
    private val sharedClient = HttpClient(CIO)
    private val CACHE_DIR: VPath = fromTR("cache", "companion-mods")

    const val COMPANION_MOD_ID = "tritium-companion"
    const val COMPANION_SOURCE = "tritium-companion"
    private const val COMPANION_DISPLAY_NAME = "Tritium Companion Mod"
    const val COMPANION_FILE_NAME = "$COMPANION_MOD_ID.jar"

    fun jarExists(projectRoot: VPath): Boolean =
        projectRoot.resolve("mods").resolve(COMPANION_FILE_NAME).exists()

    suspend fun checkUpdate(companionMod: InstalledMod): ModVersionOption? {
        if (companionMod.source != COMPANION_SOURCE) return null
        val prefix = "$COMPANION_MOD_ID-"
        val suffix = companionMod.projectId.removePrefix(prefix)
        val parts = suffix.split("-", limit = 2)
        if (parts.size != 2) return null
        val (mcVersion, loaderId) = parts
        val entries = allVersions(mcVersion, loaderId)
        val latest = entries.firstOrNull() ?: return null
        if (latest.modVersion == companionMod.versionId) return null
        return ModVersionOption(
            id = latest.modVersion,
            label = latest.displayName ?: latest.modVersion,
        )
    }

    suspend fun fetchProjectMeta(): CompanionProjectMeta? {
        return try {
            val body = sharedClient.get(DEFAULT_MANIFEST_URL).bodyAsText()
            val manifest = json.decodeFromString<CompanionManifest>(body)
            manifest.project
        } catch (t: Throwable) {
            logger.warn("Failed to fetch companion project meta", t)
            null
        }
    }

    suspend fun allVersions(mcVersion: String, loaderId: String): List<CompanionManifestEntry> {
        return try {
            val body = sharedClient.get(DEFAULT_MANIFEST_URL).bodyAsText()
            val manifest = json.decodeFromString<CompanionManifest>(body)
            manifest.entries
                .filter { it.mcVersion == mcVersion && loaderId in it.loaders }
                .sortedByDescending { it.modVersion }
        } catch (t: Throwable) {
            logger.warn("Failed to fetch companion mod versions", t)
            emptyList()
        }
    }

    suspend fun resolveEntry(mcVersion: String, loaderId: String, versionId: String): CompanionManifestEntry? {
        return allVersions(mcVersion, loaderId).find { it.modVersion == versionId }
    }

    private suspend fun resolveEntry(mcVersion: String, loaderId: String): CompanionManifestEntry? =
        allVersions(mcVersion, loaderId).firstOrNull()

    suspend fun installIfNeeded(
        projectRoot: VPath,
        mcVersion: String,
        loaderId: String
    ) {
        val projectId = "$COMPANION_MOD_ID-$mcVersion-$loaderId"
        val modsDir = projectRoot.resolve("mods")
        val destJar = modsDir.resolve(COMPANION_FILE_NAME)

        if (destJar.exists()) {
            logger.debug("Companion mod already installed at {}", destJar.toString().redactUserPath())
            return
        }

        val entry = resolveEntry(mcVersion, loaderId) ?: run {
            logger.warn("No companion mod entry found for MC {} / loader {}", mcVersion, loaderId)
            return
        }

        val jar = entry.jars[loaderId] ?: run {
            logger.warn("No companion mod JAR for loader {} (available: {})", loaderId, entry.jars.keys)
            return
        }

        CACHE_DIR.mkdirs()
        val cacheJar = CACHE_DIR.resolve("${jar.sha256}.jar")

        val bytes: ByteArray
        if (cacheJar.exists()) {
            logger.info("Using cached companion mod v{} for MC {} / {}", entry.modVersion, mcVersion, loaderId)
            val cached = cacheJar.bytesOrNull()
            if (cached != null) {
                bytes = cached
            } else {
                logger.warn("Failed to read cached companion mod, re-downloading")
                cacheJar.delete()
                bytes = downloadJar(jar)
                cacheJar.writeBytesAtomic(bytes)
            }
        } else {
            bytes = downloadJar(jar)
            cacheJar.writeBytesAtomic(bytes)
        }

        val actualSha256 = sha256(bytes)
        if (actualSha256 != jar.sha256) {
            logger.warn("Companion mod SHA-256 mismatch: expected {}, got {}", jar.sha256, actualSha256)
            cacheJar.delete()
            return
        }

        modsDir.mkdirs()
        destJar.writeBytesAtomic(bytes)

        val installedMod = InstalledMod(
            projectId = projectId,
            modId = COMPANION_MOD_ID,
            fileName = COMPANION_FILE_NAME,
            displayName = COMPANION_DISPLAY_NAME,
            side = ModSide.BOTH,
            releaseType = "release",
            source = COMPANION_SOURCE,
            versionId = entry.modVersion,
            versionLabel = entry.modVersion,
            fileHash = actualSha256,
            installedAt = Clock.System.now(),
            enabled = true,
            excludedFromRelease = false,
            localOnly = true,
            requiresManualDownload = false
        )
        ModDatabase(projectRoot).use { db -> db.install(installedMod) }

        logger.info("Companion mod v{} installed for MC {} / {}", entry.modVersion, mcVersion, loaderId)
    }

    private suspend fun downloadJar(jar: CompanionManifestJar): ByteArray {
        logger.info("Downloading companion mod from {}", jar.url.redactUserPath())
        val response = sharedClient.get(jar.url)
        return response.body()
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
