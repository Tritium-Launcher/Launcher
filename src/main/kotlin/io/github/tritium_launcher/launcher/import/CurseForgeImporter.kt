/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.import

import io.github.tritium_launcher.api.io.VPath
import io.github.tritium_launcher.api.logger
import io.github.tritium_launcher.launcher.core.mod.ModSide
import io.github.tritium_launcher.launcher.core.source.CurseForge
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.URLDecoder
import java.nio.file.Files
import java.util.zip.ZipFile

/**
 * A single file entry in the CurseForge modpack manifest.
 *
 * @param projectID CurseForge project ID.
 * @param fileID Specific file ID for the version to download.
 * @param downloadUrl Direct download URL for the jar.
 * @param required Whether this file is required for the pack to function.
 */
@Serializable
data class CurseFile(
    val projectID: Long,
    val fileID: Long,
    val downloadUrl: String? = null,
    val required: Boolean = true
)

/**
 * Mod loader entry in the CurseForge manifest.
 *
 * @param id Loader identifier.
 * @param primary Whether this is the primary loader for the pack.
 */
@Serializable
data class CurseModLoader(
    val id: String,
    val primary: Boolean = false
)

/**
 * Minecraft section of the CurseForge manifest.
 *
 * @param version Minecraft version string.
 * @param modLoaders List of mod loaders configured for the pack.
 */
@Serializable
data class CurseMinecraft(
    val version: String,
    val modLoaders: List<CurseModLoader> = emptyList()
)

/**
 * Root structure of a CurseForge modpack `manifest.json`.
 *
 * @param manifestType Always "minecraftModpack".
 * @param manifestVersion Schema version of the manifest format.
 * @param name Display name of the modpack.
 * @param version Version string for this pack release.
 * @param author Pack author name.
 * @param overrides Directory name within the zip containing override files.
 * @param minecraft Minecraft version and loader configuration.
 * @param files List of mod files to download.
 */
@Serializable
data class CurseManifest(
    val manifestType: String = "minecraftModpack",
    val manifestVersion: Int = 1,
    val name: String = "",
    val version: String = "",
    val author: String? = null,
    val overrides: String = "overrides",
    val minecraft: CurseMinecraft? = null,
    val files: List<CurseFile> = emptyList()
)

/**
 * Result of a successful CurseForge pack extraction.
 *
 * @param instance A [DetectedInstance] representing the extracted pack.
 * @param tempDir The temporary directory containing extracted files.
 * @param modEntries Pre-built [ImportableMod] entries derived from the manifest for preview.
 */
data class CursePackResult(
    val instance: DetectedInstance,
    val tempDir: VPath,
    val modEntries: List<ImportableMod>
)

val curseJson = Json { ignoreUnknownKeys = true }

/**
 * Extracts a CurseForge modpack zip archive into a temporary directory and returns a
 * [CursePackResult].
 *
 * The extraction process:
 * 1. Reads `manifest.json` from the zip.
 * 2. Extracts override files from the configured overrides directory,
 *    stripping that prefix so files land at the correct relative paths.
 * 3. Saves the manifest as `curse-manifest.json` in the temp directory so it is
 *    available to [downloadCursePackMods] during project generation.
 *
 * @param path Absolute path to the CurseForge `.zip` file.
 * @param curseForge Optional [CurseForge] source instance for resolving mod names
 *   and icons from the API.
 * @return A [CursePackResult] with the parsed instance metadata and temp directory, or
 *   `null` when the manifest is missing or unreadable.
 */
suspend fun extractAndPrepareCursePack(
    path: String,
    curseForge: CurseForge? = null
): CursePackResult? {
    val (manifest, tempVPath) = withContext(Dispatchers.IO) {
        val zip = ZipFile(path)
        zip.use { zip ->
            val manifestEntry = zip.getEntry("manifest.json") ?: return@withContext null
            val manifestContent = zip.getInputStream(manifestEntry).readAllBytes().decodeToString()
            val mf = curseJson.decodeFromString<CurseManifest>(manifestContent)

            val tempDir = Files.createTempDirectory("tritium-curse-").toFile()
            val tvp = VPath.get(tempDir.absolutePath)

            val overridePrefix = "${mf.overrides}/"
            val entries = zip.entries()
            val canonicalBase = tempDir.canonicalPath + File.separator
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory || entry.name == "manifest.json") continue

                val relativeName = entry.name.removePrefix(overridePrefix)
                if (relativeName == entry.name) continue
                if (relativeName.isBlank()) continue

                val outFile = File(tempDir, relativeName)
                if (!outFile.canonicalPath.startsWith(canonicalBase)) continue
                outFile.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input -> outFile.outputStream().use { input.copyTo(it) } }
            }

            // Save manifest for later use during import
            val manifestFile = File(tempDir, "curse-manifest.json")
            manifestFile.writeText(manifestContent)

            Pair(mf, tvp)
        }
    } ?: return null

    val mcSection = manifest.minecraft
    val gameVer = mcSection?.version
    val primaryLoader = mcSection?.modLoaders?.firstOrNull { it.primary } ?: mcSection?.modLoaders?.firstOrNull()
    val loaderId = primaryLoader?.id ?: ""
    val (loaderName, loaderVersion) = parseLoaderString(loaderId)

    val instance = DetectedInstance(
        launcher = KnownLauncher.BROWSE_FOLDER,
        name = manifest.name,
        instanceDir = tempVPath,
        minecraftDir = tempVPath,
        gameVersion = gameVer,
        loader = loaderName,
        loaderVersion = loaderVersion
    )

    val modEntries = manifest.files.map { file ->
        val fileName = if (!file.downloadUrl.isNullOrBlank()) {
            file.downloadUrl.substringAfterLast('/').substringBefore('?')
        } else {
            "mod-${file.projectID}-${file.fileID}.jar"
        }
        ImportableMod(
            jarPath = tempVPath.resolve("mods/$fileName"),
            modId = fileName.removeSuffix(".jar"),
            displayName = fileName.removeSuffix(".jar"),
            fileName = fileName,
            side = ModSide.BOTH,
            iconBytes = null,
            checked = file.required,
            sourceProjectId = file.projectID.toString(),
            sourceVersionId = file.fileID.toString()
        )
    }.toMutableList()

    if (curseForge != null && modEntries.isNotEmpty()) {
        val projectIds = manifest.files.map { it.projectID }.distinct()
        val batchInfo = curseForge.batchModDetails(projectIds)
        for (i in modEntries.indices) {
            val file = manifest.files.getOrNull(i) ?: continue
            val brief = batchInfo[file.projectID]
            if (brief != null) {
                val entry = modEntries[i]
                modEntries[i] = entry.copy(
                    displayName = brief.name,
                    modId = file.projectID.toString(),
                    sourceProjectId = file.projectID.toString(),
                    sourceIconUrl = brief.iconUrl,
                    sourceAvailable = true,
                    sourceStatus = "Available"
                )
            }
        }
    }

    return CursePackResult(instance, tempVPath, modEntries)
}

/**
 * Downloads mod files from a list of [CurseFile] entries into the temp directory's `mods/`
 * folder.
 *
 * Call this during the import phase for only the files the user has checked.
 *
 * @param cursePackTempDir The temp directory returned by [extractAndPrepareCursePack].
 * @param httpClient HTTP client used for downloading mod files.
 * @param files The manifest file entries to download. If empty, downloads nothing.
 */
suspend fun downloadCursePackMods(cursePackTempDir: VPath, httpClient: HttpClient, files: List<CurseFile>) {
    val downloadSemaphore = Semaphore(6)
    coroutineScope {
        files
            .map { file ->
                async(Dispatchers.IO) {
                    val url = file.downloadUrl?.takeIf { it.isNotBlank() }
                        ?: "https://www.curseforge.com/api/v1/mods/${file.projectID}/files/${file.fileID}/download"

                    downloadSemaphore.withPermit {
                        try {
                            val response = httpClient.get(url)
                            val bytes = response.bodyAsBytes()
                            val fileName = if (!file.downloadUrl.isNullOrBlank()) {
                                file.downloadUrl.substringAfterLast('/').substringBefore('?')
                            } else {
                                val finalPath = response.call.request.url.encodedPath
                                val raw = finalPath.substringAfterLast('/').substringBefore('?')
                                URLDecoder.decode(raw, "UTF-8").ifEmpty { "mod-${file.projectID}-${file.fileID}.jar" }
                            }
                            if (fileName.isNotBlank()) {
                                val outFile = File(cursePackTempDir.toJFile(), "mods/$fileName")
                                outFile.parentFile?.mkdirs()
                                outFile.outputStream().use { it.write(bytes) }
                            }
                        } catch (_: Exception) {
                            curseLog.warn("Failed to download mod file: {}", url)
                        }
                    }
                }
            }
            .awaitAll()
    }
}

/**
 * Deletes the temporary directory created during CurseForge pack extraction.
 *
 * Safe to call with `null` (no-op).
 *
 * @param cursePackTempDir The temp directory to remove, or `null`.
 */
fun cleanupCursePackTemp(cursePackTempDir: VPath?) {
    cursePackTempDir?.toJFile()?.deleteRecursively()
}

/**
 * Parses a CurseForge loader string into a display name and version.
 *
 * @param loaderId The raw loader identifier from the manifest.
 * @return A pair of (loader display name, loader version), or (null, null) if unparseable.
 */
private fun parseLoaderString(loaderId: String): Pair<String?, String?> {
    if (loaderId.isBlank()) return null to null
    val known = mapOf(
        "forge" to "Forge",
        "neoforge" to "NeoForge",
        "fabric" to "Fabric",
        "quilt" to "Quilt"
    )
    val lower = loaderId.lowercase()
    for ((key, display) in known) {
        if (lower.startsWith(key)) {
            val version = loaderId.removePrefix(key).removePrefix("-").ifBlank { null }
            return display to version
        }
    }
    return null to null
}

private val curseLog = logger("CurseForgeImporter")
