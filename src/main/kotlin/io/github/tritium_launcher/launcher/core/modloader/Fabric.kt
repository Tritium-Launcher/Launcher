package io.github.tritium_launcher.launcher.core.modloader

import io.github.tritium_launcher.launcher.TConstants
import io.github.tritium_launcher.launcher.core.modloader.fabric.LoaderCompatibility
import io.github.tritium_launcher.launcher.fromTR
import io.github.tritium_launcher.launcher.io.IODispatchers
import io.github.tritium_launcher.launcher.io.VPath
import io.github.tritium_launcher.launcher.io.linkOrCopyFromCache
import io.github.tritium_launcher.launcher.logger
import io.github.tritium_launcher.launcher.platform.ClientIdentity
import io.github.tritium_launcher.launcher.registry.Registrable
import io.github.tritium_launcher.launcher.toURI
import io.github.tritium_launcher.launcher.ui.theme.TIcons
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.qt.gui.QPixmap
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URI
import java.util.jar.JarFile
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Fabric loader integration:
 * - caches installer jars under `.tr/loaders/fabric`,
 * - fetches compatible versions from Fabric meta,
 * - installs required libraries + launcher metadata into an instance.
 */
class Fabric : ModLoader(), Registrable {
    private companion object {
        const val LIBS_CONCURRENCY_MIN = 8
        const val LIBS_CONCURRENCY_MAX = 20
    }

    override val id: String = "fabric"
    override val displayName: String = "Fabric"
    override val repository: URI = "https://maven.fabricmc.net/net/fabricmc/fabric-loader/".toURI()
    override val oldestVersion: String = "1.14"
    override val icon: QPixmap
        get() = TIcons.Fabric
    override val order: Int = 1

    val dir: VPath = fromTR(INSTALL_DIR, id)
    private val json = Json {
        ignoreUnknownKeys = true
    }

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            requestTimeoutMillis = 60_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 60_000
        }
        install(HttpRequestRetry) {
            maxRetries = 3
            retryOnExceptionOrServerErrors(maxRetries = 3)
            retryIf { _, response ->
                response.status.value == 429 || response.status.value == 408
            }
            delayMillis { retry ->
                (500L * (1L shl (retry - 1))).coerceAtMost(5000L)
            }
        }
        defaultRequest {
            header("User-Agent", ClientIdentity.userAgent)
            header("X-Client-Info", ClientIdentity.clientInfoHeader)
        }
    }
    private val logger = logger()

    init {
        if(!dir.exists()) {
            if(dir.mkdirs()) {
                logger.info("Created Fabric install directory: {}", dir.toAbsolute())
            } else {
                logger.error("Failed to create Fabric install directory: {}", dir.toAbsolute())
            }
        }
    }

    /**
        * Download a Fabric loader jar into the shared loader cache.
        */
    override suspend fun download(version: String): Boolean {
        return try {
            val downloadUri = getDownloadUri(version)
            if(downloadUri == null) {
                logger.error("Download URI for version {} not found.", version)
                return false
            }
            val urlStr = downloadUri.toString()
            logger.info("Downloading Fabric Loader...")
            val data: ByteArray = downloadBytesChecked(urlStr)
            validateJarBytesOrThrow("fabric-loader-$version.jar", data)
            val file = dir.resolve("fabric-loader-$version.jar")
            file.writeBytesAtomic(data)
            logger.info("Downloaded Fabric Loader version {}", version)
            true
        } catch (e: Exception) {
            logger.error("Error downloading Fabric Loader {}: {}", version, e.message, e)
            false
        }
    }

    /**
        * Remove a cached Fabric loader jar if present.
        */
    override suspend fun uninstall(version: String): Boolean {
        return try {
            val file = dir.resolve("fabric-loader-$version.jar")
            if(file.exists()) {
                if(file.delete()) {
                    logger.info("Deleted Fabric Loader {}", version)
                    true
                } else {
                    logger.error("Failed to delete Fabric Loader {}", version)
                    false
                }
            } else {
                logger.warn("Fabric Loader {} not found for removal.", version)
                false
            }
        } catch (e: Exception) {
            logger.error("Error uninstalling Fabric Loader {}: {}", version, e.message, e)
            false
        }
    }

    /**
        * Fetch all published Fabric loader versions from the Fabric maven metadata.
        */
    override suspend fun getVersions(): List<String> {
        return try {
            val metadataUrl = repository.toString() + "net/fabricmc/fabric-loader/maven-metadata.xml"
            val xmlContent: String = client.get(metadataUrl).bodyAsText()
            val factory = DocumentBuilderFactory.newInstance()
            val builder = factory.newDocumentBuilder()
            val inputStream = xmlContent.byteInputStream()
            val doc = withContext(Dispatchers.IO) {
                builder.parse(inputStream)
            }
            doc.documentElement.normalize()
            val nodes = doc.getElementsByTagName("version")
            val versions = mutableListOf<String>()
            for (i in 0 until nodes.length) {
                versions.add(nodes.item(i).textContent)
            }
            logger.info("Retrieved {} versions from metadata", versions.size)
            versions
        } catch (e: Exception) {
            logger.error("Error fetching versions: {}", e.message, e)
            emptyList()
        }
    }

    /**
        * List loader versions compatible with a given Minecraft version using the Fabric meta API.
        */
    override suspend fun getCompatibleVersions(mcVersion: String): List<String> {
        return try {
            val url = "https://meta.fabricmc.net/v2/versions/loader/$mcVersion"

            val body: String = client.get(url).body()
            val compat: List<LoaderCompatibility> = json.decodeFromString(ListSerializer(LoaderCompatibility.serializer()), body)
            logger.info("Retrieved {} Fabric Loader versions for MC {}.", compat.size, mcVersion)

            if(compat.isEmpty()) return listOf("No available versions.")

            compat.map { it.loader.version }
        } catch (e: Exception) {
            logger.error("Error fetching Fabric Loader versions for MC $mcVersion: ${e.message}")
            emptyList()
        }
    }

    /**
        * Check whether a loader jar for [version] exists in the cache.
        */
    override fun isInstalled(version: String): Boolean {
        val file = File(INSTALL_DIR, "fabric-loader-$version.jar")
        val exists = file.exists()
        logger.debug("Checking if Fabric Loader {} is installed: {}", version, exists)
        return exists
    }

    /**
        * Return all cached loader versions detected in the installation directory.
        */
    override fun getInstalled(): List<String> {
        val files = VPath.get(INSTALL_DIR).listFiles { f ->
            f.fileName().startsWith("fabric-loader-") && f.fileName().endsWith(".jar")
        }
        val versions = files.map { it.fileName().removePrefix("fabric-loader-").removeSuffix(".jar") }
        logger.debug("Installed versions: {}", versions)
        return versions
    }

    /**
        * Return the numerically latest loader version available.
        */
    override suspend fun getLatest(): String? {
        val versions = getVersions()
        val latest = versions.maxByOrNull { parseVersion(it) }
        logger.info("Latest Fabric Loader version: {}", latest)
        return latest
    }

    /**
        * Build the expected download URI for a loader [version].
        */
    override suspend fun getDownloadUri(version: String): URI? {
        val urlStr = "${repository}net/fabricmc/fabric-loader/$version/fabric-loader-$version.jar"
        return try {
            urlStr.toURI()
        } catch (e: Exception) {
            logger.error("Error constructing download URI for version {}: {}", version, e.message, e)
            null
        }
    }

    /**
        * Update by uninstalling then re-downloading the given loader version.
        */
    override suspend fun update(version: String): Boolean {
        logger.info("Updating Fabric Loader {}...", version)
        return try {
            if(!uninstall(version)) {
                logger.warn("Uninstalling Fabric Loader {} failed.", version)
            }
            download(version)
        } catch (e: Exception) {
            logger.error("Error updating Fabric Loader {}: {}", version, e.message, e)
            false
        }
    }

    /**
        * Install Fabric into a project instance:
        * - fetch launcher meta,
        * - ensure required libraries (launcher + intermediary + loader),
        * - write version_patch.json for merging at launch.
        */
    override suspend fun installClient(version: String, mcVersion: String, targetDir: VPath): Boolean {
        logger.info("Fabric install start version={} mc={} target={}", version, mcVersion, targetDir)
        val metaUrl = "https://meta.fabricmc.net/v2/versions/loader/$mcVersion/$version"
        return try {
            val metaText = client.get(metaUrl).bodyAsText()
            val meta = json.parseToJsonElement(metaText).jsonObject
            val loaderMaven = meta["loader"]?.jsonObject?.get("maven")?.jsonPrimitive?.contentOrNull
            val intermediaryMaven = meta["intermediary"]?.jsonObject?.get("maven")?.jsonPrimitive?.contentOrNull
            val launcherMeta = meta["launcherMeta"]?.jsonObject
                ?: return false.also { logger.error("Fabric launcherMeta missing for {} {}", mcVersion, version) }

            val libsObj = launcherMeta["libraries"]?.jsonObject ?: JsonObject(emptyMap())
            val clientLibs = libsObj["client"]?.jsonArray ?: JsonArray(emptyList())
            val commonLibs = libsObj["common"]?.jsonArray ?: JsonArray(emptyList())
            val libs = JsonArray(clientLibs + commonLibs)
            if(libs.isEmpty()) {
                logger.warn("Fabric libraries list is empty for {} {}", mcVersion, version)
            }
            val extraLibs = listOfNotNull(
                loaderMaven?.let { mavenLib(it) },
                intermediaryMaven?.let { mavenLib(it) }
            )
            val libsWithExtras = JsonArray((libs + extraLibs).distinctBy {
                (it as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull ?: it.toString()
            })

            downloadLibraries(libsWithExtras, targetDir)

            val outDir = targetDir.resolve(".tr").resolve("loader").resolve(id)
            outDir.mkdirs()
            outDir.resolve("launcher-meta.json").writeBytesAtomic(metaText.toByteArray())
            val patch = buildJsonObject {
                launcherMeta["mainClass"]?.let { put("mainClass", it) }
                launcherMeta["arguments"]?.let { put("arguments", it) }
                put("libraries", libsWithExtras)
            }
            outDir.resolve("version_patch.json").writeBytesAtomic(json.encodeToString(patch).toByteArray())
            logger.info("Fabric install finished for {} {}", mcVersion, version)
            true
        } catch (e: Exception) {
            logger.error("Failed to install Fabric Loader {} for MC {}", version, mcVersion, e)
            false
        }
    }

    /**
        * Read the previously written launcher meta and emit a version patch for merging.
        */
    override suspend fun getVersionPatch(version: String, mcVersion: String, targetDir: VPath): JsonObject? {
        val metaFile = targetDir.resolve(".tr").resolve("loader").resolve(id).resolve("launcher-meta.json")
        val metaText = metaFile.readTextOrNull() ?: return null
        val meta = json.parseToJsonElement(metaText).jsonObject
        val launcherMeta = meta["launcherMeta"]?.jsonObject ?: return null

        val libsObj = launcherMeta["libraries"]?.jsonObject ?: JsonObject(emptyMap())
        val clientLibs = libsObj["client"]?.jsonArray ?: JsonArray(emptyList())
        val commonLibs = libsObj["common"]?.jsonArray ?: JsonArray(emptyList())
        val loaderMaven = meta["loader"]?.jsonObject?.get("maven")?.jsonPrimitive?.contentOrNull
        val libs = JsonArray(clientLibs + commonLibs + listOfNotNull(
            loaderMaven?.let { JsonObject(mapOf(
                "name" to JsonPrimitive(it),
                "url" to JsonPrimitive("https://maven.fabricmc.net/")
            )) }
        ))
        val intermediaryMaven = meta["intermediary"]?.jsonObject?.get("maven")?.jsonPrimitive?.contentOrNull
        val libsWithExtras = JsonArray(libs + listOfNotNull(
            intermediaryMaven?.let { JsonObject(mapOf(
                "name" to JsonPrimitive(it),
                "url" to JsonPrimitive("https://maven.fabricmc.net/")
            )) }
        ))

        return buildJsonObject {
            launcherMeta["mainClass"]?.let { put("mainClass", it) }
            launcherMeta["arguments"]?.let { put("arguments", it) }
            put("libraries", libsWithExtras)
        }
    }

    /**
     * Add the Fabric loader jar to the classpath when it is missing from merged libraries.
     */
    override fun prepareLaunchClasspath(context: LaunchContext, classpath: MutableList<String>) {
        val metaFile = context.projectDir.resolve(".tr").resolve("loader").resolve(id).resolve("launcher-meta.json")
        val metaText = metaFile.readTextOrNull() ?: return
        val meta = json.parseToJsonElement(metaText).jsonObject
        val loaderMaven = meta["loader"]?.jsonObject?.get("maven")?.jsonPrimitive?.contentOrNull
            ?: return
        val loaderPath = mavenPath(loaderMaven)
        val loaderJar = context.projectDir.resolve(".tr").resolve("libraries").resolve(loaderPath)
        if (!loaderJar.exists()) {
            logger.warn("Fabric loader jar missing at {}", loaderJar.toAbsolute())
            return
        }
        val abs = loaderJar.toAbsolute().toString()
        if (!classpath.contains(abs)) {
            classpath.add(abs)
            logger.info("Added Fabric loader jar to classpath: {}", abs)
        }
    }

    /**
        * Ensure all Fabric libraries in [libs] exist under the instance `.tr/libraries` directory.
        */
    private suspend fun downloadLibraries(libs: JsonArray, targetDir: VPath) = withContext(IODispatchers.BulkIO) {
        val baseDir = targetDir.resolve(".tr").resolve("libraries")
        val sharedLibsDir = fromTR(TConstants.Dirs.CACHE).resolve("libraries")
        val entries = libs.mapNotNull { it as? JsonObject }
        val total = entries.size
        val concurrency = chooseLibraryConcurrency(total)
        logger.info("Fabric libraries: {} to ensure, concurrency={}", total, concurrency)
        val semaphore = Semaphore(concurrency)
        val startedAt = System.currentTimeMillis()
        var completed = 0

        coroutineScope {
            entries.withIndex().map { (idx, obj) ->
                launch {
                    semaphore.withPermit {
                        try {
                            val artifact = obj["downloads"]?.jsonObject
                                ?.get("artifact")?.jsonObject
                            val artUrl = artifact?.get("url")?.jsonPrimitive?.contentOrNull
                            val artPath = artifact?.get("path")?.jsonPrimitive?.contentOrNull

                            if(artUrl != null && artPath != null) {
                                val dest = baseDir.resolve(artPath)
                                val cache = sharedLibsDir.resolve(artPath)
                                val expectedSize = artifact["size"]?.jsonPrimitive?.longOrNull
                                if (!isUsableLibraryArtifact(dest, expectedSize)) {
                                    if (!linkOrCopyFromCache(cache, dest) || !isUsableLibraryArtifact(dest, expectedSize)) {
                                        if (dest.exists()) dest.delete()
                                        if (cache.exists() && !isUsableLibraryArtifact(cache, expectedSize)) {
                                            cache.delete()
                                        }
                                    logger.debug("fabric lib [{}/{}] {}", idx + 1, total, artPath)
                                    dest.parent().mkdirs()
                                    val bytes = downloadBytesChecked(artUrl)
                                    if (expectedSize != null && expectedSize > 0L && bytes.size.toLong() != expectedSize) {
                                        throw IllegalStateException(
                                            "Fabric artifact size mismatch for $artPath: got ${bytes.size}, expected $expectedSize"
                                        )
                                    }
                                    validateJarBytesOrThrow(artPath, bytes)
                                    cache.parent().mkdirs()
                                    cache.writeBytesAtomic(bytes)
                                    if (!linkOrCopyFromCache(cache, dest)) {
                                        dest.writeBytesAtomic(bytes)
                                    }
                                    }
                                }
                                return@withPermit
                            }

                            val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@withPermit
                            val url = obj["url"]?.jsonPrimitive?.contentOrNull ?: "https://maven.fabricmc.net/"
                            val path = mavenPath(name)
                            val dest = baseDir.resolve(path)
                            val cache = sharedLibsDir.resolve(path)
                            if (!isUsableLibraryArtifact(dest, expectedSize = null)) {
                                if (!linkOrCopyFromCache(cache, dest) || !isUsableLibraryArtifact(dest, expectedSize = null)) {
                                    if (dest.exists()) dest.delete()
                                    if (cache.exists() && !isUsableLibraryArtifact(cache, expectedSize = null)) {
                                        cache.delete()
                                    }
                                    logger.debug("fabric maven lib [{}/{}] {}", idx + 1, total, path)
                                    dest.parent().mkdirs()
                                    val bytes = downloadBytesChecked(url.trimEnd('/') + "/" + path)
                                    validateJarBytesOrThrow(path, bytes)
                                    cache.parent().mkdirs()
                                    cache.writeBytesAtomic(bytes)
                                    if (!linkOrCopyFromCache(cache, dest)) {
                                        dest.writeBytesAtomic(bytes)
                                    }
                                }
                            }
                        } catch (t: Throwable) {
                            val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: "unknown"
                            logger.warn("Failed to download Fabric library {}", name, t)
                        } finally {
                            val done = synchronized(this@Fabric) { ++completed }
                            if (done % 40 == 0 || done == total) {
                                val elapsed = System.currentTimeMillis() - startedAt
                                logger.info("Fabric libraries progress {}/{} ({} ms)", done, total, elapsed)
                            }
                        }
                    }
                }
            }.joinAll()
        }

        logger.info("Fabric libraries complete in {} ms", System.currentTimeMillis() - startedAt)
    }

    private fun chooseLibraryConcurrency(total: Int): Int {
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(2)
        val target = when {
            total >= 1_000 -> 20
            total >= 500 -> 16
            else -> 12
        }
        return (cores * 3).coerceIn(LIBS_CONCURRENCY_MIN, minOf(target, LIBS_CONCURRENCY_MAX))
    }

    private suspend fun downloadBytesChecked(url: String, timeoutMs: Long = 45_000L): ByteArray = withTimeout(timeoutMs) {
        val response = client.get(url)
        if (!response.status.isSuccess()) {
            throw IllegalStateException("HTTP ${response.status.value} downloading '$url'")
        }
        response.bodyAsBytes()
    }

    private fun validateJarBytesOrThrow(path: String, bytes: ByteArray) {
        if (!path.endsWith(".jar", ignoreCase = true)) return
        if (!isValidJarBytes(bytes)) {
            throw IllegalStateException("Downloaded Fabric jar is invalid: $path")
        }
    }

    private fun isUsableLibraryArtifact(path: VPath, expectedSize: Long?): Boolean {
        if (!path.exists()) return false
        val size = path.sizeOrNull() ?: return false
        if (size <= 0L) return false
        if (expectedSize != null && expectedSize > 0L && size != expectedSize) return false
        if (!path.fileName().endsWith(".jar", ignoreCase = true)) return true
        return try {
            JarFile(path.toJFile()).use { true }
        } catch (_: Throwable) {
            false
        }
    }

    private fun isValidJarBytes(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        return try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
                zis.nextEntry != null
            }
        } catch (_: Throwable) {
            false
        }
    }

    /**
        * Convenience helper to build a library object for a maven coordinate.
        */
    private fun mavenLib(coordinate: String): JsonObject = JsonObject(mapOf(
        "name" to JsonPrimitive(coordinate),
        "url" to JsonPrimitive("https://maven.fabricmc.net/")
    ))

    /**
        * Convert a Maven coordinate (group:artifact:version[:classifier]) into a repository path.
        */
    private fun mavenPath(name: String): String {
        val parts = name.split(":")
        if(parts.size < 3) return name
        val group = parts[0].replace('.', '/')
        val artifact = parts[1]
        val version = parts[2]
        val classifier = parts.getOrNull(3)
        val file = if(classifier != null) {
            "$artifact-$version-$classifier.jar"
        } else {
            "$artifact-$version.jar"
        }
        return "$group/$artifact/$version/$file"
    }

    /**
        * Basic numeric sort key for loader versions (splits on '.').
        */
    private fun parseVersion(version: String): String {
        return version.split(".")
            .mapNotNull { it.toIntOrNull() }
            .joinToString(".")
    }
}
