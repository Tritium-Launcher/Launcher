package io.github.tritium_launcher.launcher.import

import io.github.tritium_launcher.launcher.io.VPath
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile

/**
 * Describes a single file entry in the Modrinth pack index.
 *
 * @param path Relative path within the pack (e.g. "mods/example.jar").
 * @param downloads List of download URLs for the file.
 */
@Serializable
data class MrpackFile(
    val path: String,
    val downloads: List<String> = emptyList()
)

/**
 * Root structure of `modrinth.index.json`.
 *
 * @param name Display name of the modpack.
 * @param summary Short description.
 * @param versionId Version string for this pack release.
 * @param dependencies Map of dependency type to version (e.g. "minecraft" -> "1.20.1").
 * @param files List of files to download from the source.
 */
@Serializable
data class MrpackIndex(
    val name: String = "",
    val summary: String? = null,
    @SerialName("versionId") val versionId: String = "",
    val dependencies: Map<String, String> = emptyMap(),
    val files: List<MrpackFile> = emptyList()
)

/**
 * Result of a successful mrpack extraction.
 *
 * @param instance A [DetectedInstance] representing the extracted pack.
 * @param tempDir The temporary directory containing extracted files.
 */
data class MrpackResult(
    val instance: DetectedInstance,
    val tempDir: VPath
)

private val mrpackJson = Json { ignoreUnknownKeys = true }

/**
 * Extracts a `.mrpack` archive into a temporary directory and returns a [MrpackResult].
 *
 * The extraction process:
 * 1. Reads `modrinth.index.json` from the zip.
 * 2. Extracts override files (`overrides/` and `client-overrides/`), stripping those
 *    prefixes so files land at the correct relative paths.
 * 3. Downloads index-referenced files from their URLs.
 *
 * @param path Absolute path to the `.mrpack` file.
 * @param httpClient HTTP client used for downloading index-referenced files.
 * @return A [MrpackResult] with the parsed instance metadata and temp directory, or `null`
 *   when the index is missing or unreadable.
 */
suspend fun extractAndPrepareMrpack(
    path: String,
    httpClient: HttpClient
): MrpackResult? {
    val (index, tempVPath) = withContext(Dispatchers.IO) {
        val zip = ZipFile(path)
        zip.use { zip ->
            val indexEntry = zip.getEntry("modrinth.index.json") ?: return@withContext null
            val indexContent = zip.getInputStream(indexEntry).readAllBytes().decodeToString()
            val idx = mrpackJson.decodeFromString<MrpackIndex>(indexContent)

            val tempDir = Files.createTempDirectory("tritium-mrpack-").toFile()
            val tvp = VPath.get(tempDir.absolutePath)

            val entries = zip.entries()
            val canonicalBase = tempDir.canonicalPath + File.separator
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory || entry.name == "modrinth.index.json") continue

                val relativeName = entry.name
                    .removePrefix("overrides/")
                    .removePrefix("client-overrides/")
                if (relativeName == entry.name) continue
                if (relativeName.isBlank()) continue

                val outFile = File(tempDir, relativeName)
                if (!outFile.canonicalPath.startsWith(canonicalBase)) continue
                outFile.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input -> outFile.outputStream().use { input.copyTo(it) } }
            }

            Pair(idx, tvp)
        }
    } ?: return null

    val gameVer = index.dependencies["minecraft"]
    val loaderKey = listOf("fabric-loader", "neoforge", "forge", "quilt-loader").firstOrNull { it in index.dependencies }
    val loaderVer = if (loaderKey != null) index.dependencies[loaderKey] else null
    val displayLoader = mapOf(
        "fabric-loader" to "Fabric", "neoforge" to "NeoForge",
        "forge" to "Forge", "quilt-loader" to "Quilt"
    )[loaderKey]

    for (file in index.files) {
        val url = file.downloads.firstOrNull() ?: continue
        try {
            val bytes = httpClient.get(url).bodyAsBytes()
            val resolved = VPath.get(tempVPath, file.path).normalize()
            if (!resolved.startsWith(tempVPath)) continue
            val outFile = resolved.toJFile()
            outFile.parentFile?.mkdirs()
            withContext(Dispatchers.IO) {
                outFile.outputStream().use { it.write(bytes) }
            }
        } catch (_: Exception) {}
    }

    val instance = DetectedInstance(
        launcher = KnownLauncher.BROWSE_FOLDER,
        name = index.name,
        instanceDir = tempVPath,
        minecraftDir = tempVPath,
        gameVersion = gameVer,
        loader = displayLoader,
        loaderVersion = loaderVer
    )
    return MrpackResult(instance, tempVPath)
}

/**
 * Deletes the temporary directory created during mrpack extraction.
 *
 * @param mrpackTempDir The temp directory to remove, or `null`.
 */
fun cleanupMrpackTemp(mrpackTempDir: VPath?) {
    mrpackTempDir?.toJFile()?.deleteRecursively()
}
