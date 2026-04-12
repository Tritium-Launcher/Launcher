package io.github.tritium_launcher.launcher.core.modloader

import io.github.tritium_launcher.launcher.TConstants
import io.github.tritium_launcher.launcher.fromTR
import io.github.tritium_launcher.launcher.io.IODispatchers
import io.github.tritium_launcher.launcher.io.VPath
import io.github.tritium_launcher.launcher.io.linkOrCopyFromCache
import io.github.tritium_launcher.launcher.logger
import io.github.tritium_launcher.launcher.platform.ClientIdentity
import io.github.tritium_launcher.launcher.platform.Java
import io.github.tritium_launcher.launcher.registry.Registrable
import io.github.tritium_launcher.launcher.toURI
import io.github.tritium_launcher.launcher.ui.theme.TIcons
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.utils.io.*
import io.ktor.utils.io.jvm.javaio.*
import io.qt.gui.QPixmap
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.*
import org.w3c.dom.Document
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.jar.*
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.absolutePathString

/**
 * NeoForge loader integration:
 * - Downloads installer jars and metadata from NeoForged maven,
 * - Runs install processors (installertools) to patch the Minecraft client,
 * - Materializes a merged version JSON for launching.
 */
class NeoForge : ModLoader(), Registrable {
    private companion object {
        const val LIBS_CONCURRENCY_MIN = 8
        const val LIBS_CONCURRENCY_MAX = 24
    }

    override val id: String = "neoforge"
    override val displayName: String = "NeoForge"
    override val repository: URI = "https://maven.neoforged.net/releases/net/neoforged/neoforge".toURI()
    override val oldestVersion: String = "1.20.1"
    override val icon: QPixmap
        get() = TIcons.NeoForge
    override val order: Int = 1

    val dir: VPath = fromTR(INSTALL_DIR, id)
    private val json = Json { ignoreUnknownKeys = true }

     val client = HttpClient(CIO) {
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
                response.status == HttpStatusCode.TooManyRequests ||
                    response.status == HttpStatusCode.RequestTimeout
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
                logger.info("Created NeoForge install directory: {}", dir.toAbsolute())
            } else {
                logger.error("Failed to create NeoForge install directory: {}", dir.toAbsolute())
            }
        }
    }

    /**
     * Ensure the NeoForge shared install/cache directory exists.
     *
     * Cache invalidation can remove this directory while the process is still running.
     */
    private fun ensureInstallDir(): Boolean {
        if (dir.exists()) return true
        val created = runCatching { dir.mkdirs() }.getOrDefault(false)
        if (!created) {
            logger.error("Failed to recreate NeoForge install directory: {}", dir.toAbsolute())
        }
        return created
    }

    /**
     * Fetch all published NeoForge versions from the Maven metadata.
     */
    override suspend fun getVersions(): List<String> {
        val metaUrl = "$repository/maven-metadata.xml"
        val xml = try { readText(metaUrl) } catch (e: Exception) {
            logger.error("Failed to fetch maven-metadata.xml", e)
            return emptyList()
        }
        val doc = xmlToString(xml)
        val versions = mutableListOf<String>()
        val nodes = doc.getElementsByTagName("version")
        for(i in 0 until nodes.length) {
            val n = nodes.item(i)
            val v = n.textContent.trim()
            if(v.isNotEmpty()) versions.add(v)
        }
        logger.info("Retrieved {} NeoForge versions from metadata", versions.size)
        return versions
    }

    /**
     * Filter NeoForge versions compatible with a Minecraft version.
     */
    override suspend fun getCompatibleVersions(mcVersion: String): List<String> {
        val versions = getVersions()
        if(versions.isEmpty()) return versions

        val parts = mcVersion.split(".")
        if(parts.size < 3) {
            logger.warn("Invalid Minecraft version format: {}", mcVersion)
            return emptyList()
        }

        val neoPrefix = "${parts[1]}.${parts[2]}"

        val compatible = versions.filter { neoV ->
            neoV.startsWith("$neoPrefix.")
        }

        logger.info("Found {} NeoForge versions compatible with MC {}", compatible.size, mcVersion)

        return compatible
    }

    /**
     * Build the installer download URI for a NeoForge version.
     */
    override suspend fun getDownloadUri(version: String): URI =
        "$repository/$version/neoforge-$version-installer.jar".toURI()

    /**
     * Return the checksum URL for the installer jar.
     */
    fun getInstallerChecksumUri(version: String): URI =
        "$repository/$version/neoforge-$version-installer.jar.sha512".toURI()

    /**
     * Download and verify a NeoForge installer jar into the loader cache.
     */
    override suspend fun download(version: String): Boolean {
        return try {
            if (!ensureInstallDir()) return false
            val downloadUri = getDownloadUri(version)

            logger.info("Downloading NeoForge installer...")

            val url = downloadUri.toString()
            val filename = "neoforge-$version-installer.jar"
            val destFile = dir.resolve(filename)
            val shaCandidates = listOf(
                Pair("sha512", "SHA-512"),
                Pair("sha256", "SHA-256"),
                Pair("sha1", "SHA-1")
            )

            var checksum: Triple<String, String, String>? = null
            for ((ext, alg) in shaCandidates) {
                val cUrl = "${repository.toString().trimEnd('/')}/$version/$filename.$ext"
                try {
                    val text = readText(cUrl).trim()
                    if (text.isNotEmpty()) {
                        checksum = Triple(text, alg, ext)
                        break
                    }
                } catch (_: Exception) {
                }
            }

            if (checksum == null) {
                logger.error("No checksum available for NeoForge {}", version)
                return false
            }

            val (checksumText, checksumAlg, checksumExt) = checksum
            val checksumToken = checksumText.split(Regex("\\s+"))[0].lowercase()

            logger.info("Downloading NeoForge {}, verifying {}: {}", version, checksumAlg, url)
            val resp: HttpResponse = client.get(url)
            if (!resp.status.isSuccess()) {
                logger.error("Failed to download {} : HTTP {}", url, resp.status)
                return false
            }

            val md = MessageDigest.getInstance(checksumAlg)
            withContext(Dispatchers.IO) {
                val channel = resp.bodyAsChannel()
                FileOutputStream(destFile.toJFile()).use { fos ->
                    val buffer = ByteArray(8 * 1024)
                    while (!channel.isClosedForRead) {
                        val rc = channel.readAvailable(buffer, 0, buffer.size)
                        if (rc <= 0) break
                        md.update(buffer, 0, rc)
                        fos.write(buffer, 0, rc)
                    }
                    fos.flush()
                }
            }

            val computed = bytesToHex(md.digest()).lowercase()
            if (computed != checksumToken) {
                logger.error(
                    "Checksum mismatch for NeoForge {}: computed {} != expected {}",
                    version,
                    computed,
                    checksumToken
                )
                try {
                    destFile.delete()
                } catch (_: Exception) {
                }
                return false
            }

            val checksumFilename = "$filename.$checksumExt"
            val checksumFile = dir.resolve(checksumFilename)
            try {
                checksumFile.writeBytes(checksumText.toByteArray())
            } catch (e: Exception) {
                logger.warn("Failed to write checksum file {}", checksumFile, e)
            }

            logger.info("Downloaded and verified NeoForge {} -> {}", version, destFile.toAbsolute())
            true
        } catch (e: Exception) {
            logger.error("Error downloading NeoForge {}", version, e)
            false
        }
    }

    /**
     * Remove cached installer jars and checksum files for a version.
     */
    override suspend fun uninstall(version: String): Boolean {
        return try {
            if (!ensureInstallDir()) return false
            val filename = "neoforge-$version-installer.jar"
            val file = dir.resolve(filename)
            var ok = true
            if(file.exists()) {
                if(file.delete()) {
                    logger.info("Deleted NeoForge installer '{}'", version)
                } else {
                    logger.error("Failed to delete NeoForge installer '{}'", version)
                    ok = false
                }
            } else {
                logger.warn("NeoForge installer '{}' not found for removal", version)
                ok = false
            }

            val shaFiles = listOf("$filename.sha512", "$filename.sha256", "$filename.sha1")
            shaFiles.forEach {
                val f = dir.resolve(it)
                if(f.exists()) {
                    if(f.delete()) logger.debug("Removed checksum {}", it) else logger.warn("Failed to remove checksum {}", it)
                }
            }

            ok
        } catch (e: Exception) {
            logger.error("Error uninstalling NeoForge '{}'", version, e)
            false
        }
    }

    /**
     * Check whether a NeoForge installer jar exists in the cache.
     */
    override fun isInstalled(version: String): Boolean {
        val file = VPath.get(INSTALL_DIR, "$id/neoforge-$version-installer.jar")
        val exists = file.exists()
        logger.debug("Checking if NeoForge '{}' is installed: {}", version, exists)
        return exists
    }

    /**
     * Return all installed NeoForge versions detected in the cache directory.
     */
    override fun getInstalled(): List<String> {
        val root = VPath.get(INSTALL_DIR, id)
        val files = root.listFiles { f ->
            f.fileName().startsWith("neoforge-") && f.fileName().endsWith("-installer.jar")
        }
        val versions = files.map { it.fileName().removePrefix("neoforge-").removeSuffix("-installer.jar") }
        logger.debug("Installed NeoForge versions: {}", versions)
        return versions
    }

    override suspend fun getLatest(): String? {
        TODO("Not yet implemented")
    }

    /**
     * Update a NeoForge version by uninstalling and re-downloading.
     */
    override suspend fun update(version: String): Boolean {
        logger.info("Updating NeoForge '{}'...", version)
        return try {
            if(!uninstall(version)) {
                logger.warn("Uninstall step for NeoForge '{}' failed", version)
            }
            download(version)
        } catch (e: Exception) {
            logger.error("Error updating NeoForge '{}'", version, e)
            false
        }
    }

    /**
     * Install NeoForge into a project instance:
     * - Ensure installer jar is present
     * - Read install_profile and version.json from the installer
     * - Merge library lists and download them
     * - Run install processors to produce a patched client jar
     * - Write loader metadata + version_patch.json for launch
     */
    override suspend fun installClient(version: String, mcVersion: String, targetDir: VPath): Boolean {
        logger.info("NeoForge install start version={} mc={} target={}", version, mcVersion, targetDir)
        if (!ensureInstallDir()) return false
        val installer = dir.resolve("neoforge-$version-installer.jar")
        if(!installer.exists()) {
            if(!download(version)) return false
        }

        return try {
            val installProfile = readInstallProfile(installer) ?: run {
                logger.error("NeoForge install_profile.json not found for {}", version)
                return false
            }
            val versionJson = readVersionJson(installer) ?: run {
                logger.error("NeoForge version.json not found for {}", version)
                return false
            }

            val profileLibs = extractLibraries(installProfile)
            val versionLibs = extractLibrariesFromVersion(versionJson)
            val mergedLibs = mergeLibraries(profileLibs, versionLibs)
            val filtered = filterMcClientLibs(versionLibs)
            val runtimeLibs = appendUniversalIfMissing(version, filtered)
            val versionInfo = json.parseToJsonElement(versionJson).jsonObject
            val fmlMajor = getFmlLoaderMajor(versionInfo)
            if (isInstancePrepared(targetDir, mcVersion, version, runtimeLibs)) {
                logger.info("NeoForge instance already prepared; skipping install for {} {}", mcVersion, version)
                return true
            }
            logger.info("NeoForge runtime libraries to materialize: {} (contains universal? {})",
                runtimeLibs.size,
                runtimeLibs.any { (it as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull?.contains(":universal") == true }
            )
            downloadLibraries(mergedLibs, targetDir)
            sanitizeUniversalJar(targetDir, version)
            copyUniversalToMods(runtimeLibs, targetDir, fmlMajor)
            val patchLibs = removeUniversal(runtimeLibs, version)

            val outDir = targetDir.resolve(".tr").resolve("loader").resolve(id)
            outDir.mkdirs()
            outDir.resolve("install_profile.json").writeBytesAtomic(installProfile.toByteArray())
            outDir.resolve("version.json").writeBytesAtomic(versionJson.toByteArray())
            val patch = buildJsonObject {
                versionInfo["mainClass"]?.let { put("mainClass", it) }
                val args = versionInfo["arguments"] ?: versionInfo["minecraftArguments"]
                args?.let { put("arguments", it) }
                put("libraries", patchLibs)
            }
            outDir.resolve("version_patch.json").writeBytesAtomic(json.encodeToString(patch).toByteArray())

            if(!runInstallProcessors(installProfile, installer, targetDir, mcVersion, version)) {
                logger.warn("NeoForge install processors failed for {}", version)
                return false
            }

            ensurePatchedJar(targetDir, mcVersion, version)
            logger.info("Prepared NeoForge client {} into {}", version, targetDir.toAbsolute())
            true
        } catch (e: Exception) {
            logger.error("NeoForge client install failed for {}", version, e)
            false
        }
    }

    /**
     * Return a version patch JSON derived from the cached NeoForge version.json.
     */
    override suspend fun getVersionPatch(version: String, mcVersion: String, targetDir: VPath): JsonObject? {
        val versionFile = targetDir.resolve(".tr").resolve("loader").resolve(id).resolve("version.json")
        val versionText = versionFile.readTextOrNull() ?: return null
        val versionInfo = json.parseToJsonElement(versionText).jsonObject

        val libs = versionInfo["libraries"]?.jsonArray ?: JsonArray(emptyList())
        val mainClass = versionInfo["mainClass"]
        val arguments = versionInfo["arguments"] ?: versionInfo["minecraftArguments"]

        return buildJsonObject {
            mainClass?.let { put("mainClass", it) }
            arguments?.let { put("arguments", it) }
            put("libraries", libs)
        }
    }

    /**
     * Add NeoForge runtime jars to the classpath when required by newer FML versions.
     */
    override fun prepareLaunchClasspath(context: LaunchContext, classpath: MutableList<String>) {
        val toolingSkips = listOf(
            "net/neoforged/installertools/",
            "net/neoforged/AutoRenamingTool/",
            "net/neoforged/javadoctor/",
            "/jarsplitter/",
            "/binarypatcher/"
        )
        classpath.removeAll { entry -> toolingSkips.any { entry.contains(it) } }

        val fmlMajor = getFmlLoaderMajor(context.versionJson)
        if (fmlMajor == null || fmlMajor < 10) return
        val libsDir = context.projectDir.resolve(".tr").resolve("libraries")
        val universalPath = mavenPath("net.neoforged:neoforge:${context.loaderVersion}:universal")
        val universalJar = libsDir.resolve(universalPath)
        if (!universalJar.exists()) return
        val abs = universalJar.toAbsolute().toString()
        if (!classpath.contains(abs)) {
            classpath.add(abs)
            logger.info("Added NeoForge universal jar to classpath: {}", abs)
        }
    }

    /**
     * Adjust JVM arguments for the NeoForge module/classpath expectations.
     */
    override fun prepareLaunchJvmArgs(context: LaunchContext, classpath: List<String>, jvmArgs: MutableList<String>) {
        val libsDir = context.projectDir.resolve(".tr").resolve("libraries")
        val fmlMajor = getFmlLoaderMajor(context.versionJson)
        val earlyDisplayPath = findLibraryByPathAny(
            context.versionJson,
            listOf(
                "fancymodloader/earlydisplay",
                "fml/earlydisplay",
                "fml-earlydisplay",
                "earlydisplay"
            )
        )?.let { detected ->
            if (detected.contains('/')) detected else mavenPath(detected)
        }
        val earlyDisplay = earlyDisplayPath?.let { libsDir.resolve(it).toAbsolute().toString() }

        val pIdx = jvmArgs.indexOf("-p")
        var modulePathArg = ""
        if (pIdx >= 0 && pIdx + 1 < jvmArgs.size) {
            modulePathArg = jvmArgs[pIdx + 1]
        }

        // FML in some 1.20 versions is resolved differently.
        val shouldInjectEarlyDisplayIntoModulePath = fmlMajor == null || fmlMajor >= 4
        if (earlyDisplay != null && shouldInjectEarlyDisplayIntoModulePath) {
            if (pIdx >= 0 && pIdx + 1 < jvmArgs.size) {
                val current = jvmArgs[pIdx + 1]
                if (!current.contains(earlyDisplay)) {
                    jvmArgs[pIdx + 1] = current + File.pathSeparator + earlyDisplay
                    modulePathArg = jvmArgs[pIdx + 1]
                }
            } else {
                jvmArgs += listOf("-p", earlyDisplay)
                modulePathArg = earlyDisplay
            }
        }

        if (modulePathArg.isNotBlank()) {
            val cleaned = removeMainJarFromPaths(modulePathArg, context.mergedId)
            if (cleaned != modulePathArg) {
                if (pIdx >= 0 && pIdx + 1 < jvmArgs.size) {
                    jvmArgs[pIdx + 1] = cleaned
                } else {
                    jvmArgs += listOf("-p", cleaned)
                }
                modulePathArg = cleaned
            }
        }

        if (fmlMajor != null && fmlMajor < 4) {
            setOrReplaceProp(jvmArgs, "-Dfml.pluginLayerLibraries", "")
            setOrReplaceProp(jvmArgs, "-Dfml.gameLayerLibraries", "")
            logger.info(
                "NeoForge layer libraries prepared (fmlMajor={}, pluginCount=0, gameCount=0, mode=legacy-empty)",
                fmlMajor
            )
            return
        }

        val pluginLayerRaw = modulePathArg
        val gameLayerRaw = classpath.joinToString(File.pathSeparator)
        val pluginNames = toFmlLayerLibraryNameSet(removeMainJarFromPaths(pluginLayerRaw, context.mergedId))
        val gameNames = toFmlLayerLibraryNameSet(removeMainJarFromPaths(gameLayerRaw, context.mergedId))
            .filterNot { pluginNames.contains(it) }
            .toCollection(LinkedHashSet())
        val pluginLayer = pluginNames.joinToString(",")
        val gameLayer = gameNames.joinToString(",")
        setOrReplaceProp(jvmArgs, "-Dfml.pluginLayerLibraries", pluginLayer)
        setOrReplaceProp(jvmArgs, "-Dfml.gameLayerLibraries", gameLayer)
        logger.info(
            "NeoForge layer libraries prepared (fmlMajor={}, pluginCount={}, gameCount={}, earlydisplayInPlugin={}, earlydisplayInGame={})",
            fmlMajor,
            pluginNames.size,
            gameNames.size,
            pluginNames.any { it.contains("earlydisplay", ignoreCase = true) },
            gameNames.any { it.contains("earlydisplay", ignoreCase = true) }
        )
        logger.debug("NeoForge layer plugin list: {}", pluginLayer)
        logger.debug("NeoForge layer game list: {}", gameLayer)
    }

    /**
     * NeoForge handles Minecraft client artifacts internally, so keep module paths intact.
     */
    override fun shouldStripMinecraftClientArtifacts(context: LaunchContext): Boolean = false

    /**
     * Read install_profile.json from the installer jar.
     */
    private fun readInstallProfile(installer: VPath): String? {
        ZipFile(installer.toJFile()).use { zip ->
            val entry = zip.getEntry("install_profile.json") ?: return null
            zip.getInputStream(entry).use { input ->
                return input.readBytes().toString(Charsets.UTF_8)
            }
        }
    }

    /**
     * Read version.json from the installer jar.
     */
    private fun readVersionJson(installer: VPath): String? {
        ZipFile(installer.toJFile()).use { zip ->
            val entry = zip.getEntry("version.json") ?: return null
            zip.getInputStream(entry).use { input ->
                return input.readBytes().toString(Charsets.UTF_8)
            }
        }
    }

    /**
     * Extract the library list from install_profile.json.
     */
    private fun extractLibraries(profileJson: String): JsonArray {
        val root = json.parseToJsonElement(profileJson).jsonObject
        val versionInfo = root["versionInfo"]?.jsonObject
        val libs = versionInfo?.get("libraries")?.jsonArray ?: root["libraries"]?.jsonArray
        return libs ?: JsonArray(emptyList())
    }

    /**
     * Extract the library list from version.json.
     */
    private fun extractLibrariesFromVersion(versionJson: String): JsonArray {
        val root = json.parseToJsonElement(versionJson).jsonObject
        return root["libraries"]?.jsonArray ?: JsonArray(emptyList())
    }

    /**
     * Check whether an instance already has the NeoForge runtime in place.
     */
    private fun isInstancePrepared(targetDir: VPath, mcVersion: String, loaderVersion: String, runtimeLibs: JsonArray): Boolean {
        val loaderDir = targetDir.resolve(".tr").resolve("loader").resolve(id)
        val profileFile = loaderDir.resolve("install_profile.json")
        val versionFile = loaderDir.resolve("version.json")
        val patchFile = loaderDir.resolve("version_patch.json")
        if (!profileFile.exists() || !versionFile.exists() || !patchFile.exists()) return false

        val mergedId = "$mcVersion-$id-$loaderVersion"
        val mergedJar = targetDir.resolve(".tr").resolve("versions").resolve(mergedId).resolve("$mergedId.jar")
        if (!mergedJar.exists()) return false

        val libsDir = targetDir.resolve(".tr").resolve("libraries")
        for (el in runtimeLibs) {
            val obj = el as? JsonObject ?: continue
            val path = obj["downloads"]?.jsonObject
                ?.get("artifact")?.jsonObject
                ?.get("path")?.jsonPrimitive?.contentOrNull
                ?: obj["name"]?.jsonPrimitive?.contentOrNull?.let { mavenPath(it) }
            if (path.isNullOrBlank()) continue
            if (!libsDir.resolve(path).exists()) return false
        }
        return true
    }

    /**
    * Ensure the NeoForge universal jar is present, some loaders expect the universal artifact to be
    * available for mod discovery even if the installer version.json omits it.
    */
    private fun appendUniversalIfMissing(loaderVersion: String, libs: JsonArray): JsonArray {
        val hasUniversal = libs.any {
            val obj = it as? JsonObject
            val name = obj?.get("name")?.jsonPrimitive?.contentOrNull
            name == "net.neoforged:neoforge:$loaderVersion:universal"
        }
        if (hasUniversal) return libs

        val path = mavenPath("net.neoforged:neoforge:$loaderVersion:universal")
        val universal = buildJsonObject {
            put("name", "net.neoforged:neoforge:$loaderVersion:universal")
            put("downloads", buildJsonObject {
                put("artifact", buildJsonObject {
                    put("path", path)
                    put("url", "${repository.toString().trimEnd('/')}/$loaderVersion/neoforge-$loaderVersion-universal.jar")
                })
            })
        }
        val combined = libs.toMutableList()
        combined.add(universal)
        return JsonArray(combined)
    }

    /**
     * Copy the universal jar into the mods folder for legacy FML discovery.
     */
    private fun copyUniversalToMods(libs: JsonArray, targetDir: VPath, fmlMajor: Int?) {
        if (fmlMajor != null && fmlMajor >= 10) return
        val libsDir = targetDir.resolve(".tr").resolve("libraries")
        val modsDir = targetDir.resolve("mods")
        val universalCoord = libs.firstOrNull {
            val obj = it as? JsonObject
            val name = obj?.get("name")?.jsonPrimitive?.contentOrNull
            name?.contains(":neoforge:") == true && name.endsWith(":universal")
        } as? JsonObject ?: return
        val path = universalCoord["downloads"]?.jsonObject
            ?.get("artifact")?.jsonObject
            ?.get("path")?.jsonPrimitive?.contentOrNull
            ?: mavenPath(universalCoord["name"]?.jsonPrimitive?.contentOrNull ?: return)
        val src = libsDir.resolve(path)
        if (!src.exists()) return
        modsDir.mkdirs()
        val dest = modsDir.resolve(src.fileName())
        if (dest.exists()) return
        try {
            dest.writeBytes(src.bytesOrNothing())
            logger.info("Copied NeoForge universal to mods folder for discovery: {}", dest.toAbsolute())
        } catch (t: Throwable) {
            logger.warn("Failed to copy NeoForge universal jar into mods folder", t)
        }
    }

    /**
     * Merge library arrays by unique maven coordinate.
     */
    private fun mergeLibraries(a: JsonArray, b: JsonArray): JsonArray {
        if (a.isEmpty()) return b
        if (b.isEmpty()) return a
        val combined = (a + b).distinctBy { lib ->
            val obj = lib as? JsonObject
            obj?.get("name")?.jsonPrimitive?.contentOrNull ?: lib.toString()
        }
        return JsonArray(combined)
    }

    /**
     * Remove the universal jar coordinate from a library list.
     */
    private fun removeUniversal(libs: JsonArray, loaderVersion: String): JsonArray {
        return JsonArray(
            libs.filterNot { el ->
                val obj = el as? JsonObject ?: return@filterNot false
                val name = obj["name"]?.jsonPrimitive?.contentOrNull
                name == "net.neoforged:neoforge:$loaderVersion:universal"
            }
        )
    }

    /**
     * Strip module metadata from the universal jar to avoid JPMS conflicts.
     */
    private fun sanitizeUniversalJar(targetDir: VPath, loaderVersion: String) {
        val libsDir = targetDir.resolve(".tr").resolve("libraries")
        val path = libsDir.resolve("net/neoforged/neoforge/$loaderVersion/neoforge-$loaderVersion-universal.jar")
        if (!path.exists()) return
        val sanitized = path.parent().resolve(path.fileName() + ".nomod")
        try {
            JarFile(path.toJFile()).use { jar ->
                val manifest = jar.manifest ?: Manifest()
                manifest.mainAttributes.remove(Attributes.Name("Automatic-Module-Name"))
                JarOutputStream(sanitized.toJFile().outputStream(), manifest).use { jos ->
                    val entries = jar.entries()
                    val seen = HashSet<String>()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        if (entry.name == "module-info.class") continue
                        if (entry.name.equals("META-INF/MANIFEST.MF", ignoreCase = true)) continue
                        if (!seen.add(entry.name)) continue
                        val newEntry = JarEntry(entry.name)
                        newEntry.time = entry.time
                        jos.putNextEntry(newEntry)
                        jar.getInputStream(entry).use { it.copyTo(jos) }
                        jos.closeEntry()
                    }
                }
            }
            Files.move(sanitized.toJPath(), path.toJPath(), StandardCopyOption.REPLACE_EXISTING)
            logger.info("Sanitized NeoForge universal jar to remove module hints: {}", path.toAbsolute())
        } catch (t: Throwable) {
            logger.warn("Failed to sanitize NeoForge universal jar {}", path.toAbsolute(), t)
            try { sanitized.delete() } catch (_: Throwable) {}
        }
    }

    /**
     * Filter out Minecraft client artifacts so the merged jar can be used for launch.
     */
    private fun filterMcClientLibs(libs: JsonArray): JsonArray {
        val kept = libs.filter { el ->
            val obj = el as? JsonObject ?: return@filter true
            val name = obj["name"]?.jsonPrimitive?.contentOrNull
            val path = obj["downloads"]?.jsonObject
                ?.get("artifact")?.jsonObject
                ?.get("path")?.jsonPrimitive?.contentOrNull
            if (name != null && name.startsWith("net.minecraft:client")) return@filter false
            if (path != null && path.contains("net/minecraft/client/")) return@filter false
            true
        }
        return JsonArray(kept)
    }

    /**
     * Execute installer processors (installertools) defined in install_profile.json.
     * Processors patch the base Minecraft jar and download auxiliary data like mappings.
     */
    private suspend fun runInstallProcessors(profileJson: String, installer: VPath, targetDir: VPath, mcVersion: String, loaderVersion: String): Boolean {
        val root = json.parseToJsonElement(profileJson).jsonObject
        val processors = root["processors"]?.jsonArray ?: JsonArray(emptyList())
        if (processors.isEmpty()) return true

        val data = root["data"]?.jsonObject ?: JsonObject(emptyMap())
        val baseJar = targetDir.resolve(".tr").resolve("versions").resolve(mcVersion).resolve("$mcVersion.jar")
        if(!baseJar.exists()) {
            logger.warn("Base minecraft jar missing for processors: {}", baseJar.toAbsolute())
            return false
        }

        val binPatch = extractInstallerData(installer, "data/client.lzma", targetDir)

        for (procEl in processors) {
            val proc = procEl as? JsonObject ?: continue
            val sides = proc["sides"]?.jsonArray?.mapNotNull { it.jsonPrimitiveOrNull()?.contentOrNull }
            if (sides != null && !sides.contains("client")) continue

            val jarCoord = proc["jar"]?.jsonPrimitiveOrNull()?.contentOrNull
            val toolJar = if (!jarCoord.isNullOrBlank()) {
                ensureMavenArtifact(jarCoord, targetDir)
            } else {
                ensureMavenArtifact("net.neoforged.installertools:installertools:4.0.6:fatjar", targetDir)
            }
            if (toolJar == null || !toolJar.exists()) {
                logger.warn("Installertools jar missing; cannot run processors")
                return false
            }

            val classpath = proc["classpath"]?.jsonArray
                ?.mapNotNull { it.jsonPrimitiveOrNull()?.contentOrNull }
                .orEmpty()
            val cpJars = mutableListOf<VPath>()
            for (cp in classpath) {
                val jar = ensureMavenArtifact(cp, targetDir)
                if (jar != null) cpJars.add(jar)
            }

            val args = proc["args"]?.jsonArray ?: continue
            val expanded = buildList {
                for (argEl in args) {
                    val raw = argEl.jsonPrimitiveOrNull()?.contentOrNull ?: continue
                    add(
                        resolveProcessorArg(
                            raw,
                            data,
                            "client",
                            targetDir,
                            installer,
                            baseJar,
                            binPatch
                        )
                    )
                }
            }

            val taskName = findArgValue(expanded, "--task")
            val outputPath = findArgValue(expanded, "--output")?.let { VPath.get(it) }
            if (outputPath != null) {
                val restored = restoreProcessorOutputFromCache(taskName, outputPath, mcVersion, loaderVersion)
                if (restored) {
                    logger.info("Processor {} skipped (restored cached output {})", taskName ?: "unknown", outputPath.toAbsolute())
                    continue
                }
                if (outputPath.exists() && (outputPath.sizeOrNull() ?: 0L) > 0L) {
                    logger.info("Processor {} skipped (output exists {})", taskName ?: "unknown", outputPath.toAbsolute())
                    continue
                }
            }

            val mainClass = readJarMainClass(toolJar)
            if (!runInstallerTool(toolJar, mainClass, cpJars, expanded, targetDir)) {
                logger.warn("Processor failed for NeoForge {}", loaderVersion)
                return false
            }

            if (outputPath != null && taskName != null) {
                cacheProcessorOutput(taskName, outputPath, mcVersion, loaderVersion)
            }
        }

        return true
    }

    /**
     * Resolve a processor argument by expanding tokens and downloading bracketed artifacts.
     */
    private suspend fun resolveProcessorArg(
        raw: String,
        data: JsonObject,
        side: String,
        root: VPath,
        installer: VPath,
        baseJar: VPath,
        binPatch: VPath?
    ): String {
        val expanded = expandProcessorTokens(raw, data, side, root, installer, baseJar, binPatch)
        val bracketed = resolveBracketedMaven(expanded, root)
        return bracketed?.toAbsolute()?.toString() ?: expanded
    }

    /**
     * Resolve a keyed value from the install_profile `data` block, honoring side overrides
     * and downloading bracketed maven artifacts when needed.
     */
    private suspend fun resolveDataValue(data: JsonObject, key: String, side: String, targetDir: VPath): String? = withContext(Dispatchers.IO) {
        val el = data[key] ?: return@withContext null
        val obj = el as? JsonObject
        val sideEl = obj?.get(side)
        val raw = sideEl?.jsonPrimitiveOrNull()?.contentOrNull
            ?: el.jsonPrimitiveOrNull()?.contentOrNull
            ?: return@withContext null

        val resolved = resolveBracketedMaven(raw, targetDir)
        if (resolved != null) {
            resolved.parent().mkdirs()
            return@withContext resolved.toAbsolute().toString()
        }
        return@withContext raw
    }

    /**
     * Extract a data entry from the installer jar into the instance loader data directory.
     */
    private fun extractInstallerData(installer: VPath, entryName: String, targetDir: VPath): VPath? {
        return try {
            val outDir = targetDir.resolve(".tr").resolve("loader").resolve(id).resolve("data")
            outDir.mkdirs()
            val out = outDir.resolve(entryName.substringAfterLast('/'))
            ZipFile(installer.toJFile()).use { zip ->
                val entry = zip.getEntry(entryName) ?: return null
                zip.getInputStream(entry).use { input ->
                    out.writeBytes(input.readBytes())
                }
            }
            out
        } catch (t: Throwable) {
            logger.warn("Failed to extract {} from installer", entryName, t)
            null
        }
    }

    /**
     * Expand processor argument tokens:
     * - Builtin placeholders ({ROOT}, {MINECRAFT_JAR}, etc.)
     * - Data-driven keys from the install_profile `data` map
     * - Leaves unknown tokens untouched for downstream tools
     */
    private suspend fun expandProcessorTokens(
        raw: String,
        data: JsonObject,
        side: String,
        root: VPath,
        installer: VPath,
        baseJar: VPath,
        binPatch: VPath?
    ): String {
        val builtins = mapOf(
            "ROOT" to root.toAbsolute().toString(),
            "SIDE" to side,
            "INSTALLER" to installer.toAbsolute().toString(),
            "MINECRAFT_JAR" to baseJar.toAbsolute().toString(),
            "BINPATCH" to (binPatch?.toAbsolute()?.toString() ?: "{BINPATCH}"),
            "MOJMAPS" to (resolveDataValue(data, "MOJMAPS", side, root) ?: "{MOJMAPS}"),
            "PATCHED" to (resolveDataValue(data, "PATCHED", side, root) ?: "{PATCHED}"),
            "MCP_VERSION" to (resolveDataValue(data, "MCP_VERSION", side, root) ?: "{MCP_VERSION}")
        )
        var out = raw
        for ((key, value) in builtins) {
            out = out.replace("{$key}", value)
        }
        val tokenRegex = Regex("\\{([A-Za-z0-9_]+)}")
        val sb = StringBuilder(out.length)
        var lastIndex = 0
        for (match in tokenRegex.findAll(out)) {
            val range = match.range
            if (range.first > lastIndex) {
                sb.append(out, lastIndex, range.first)
            }
            val key = match.groupValues[1]
            val replacement = resolveDataValue(data, key, side, root) ?: match.value
            sb.append(replacement)
            lastIndex = range.last + 1
        }
        if (lastIndex < out.length) {
            sb.append(out, lastIndex, out.length)
        }
        out = sb.toString()
        return out
    }

    /**
     * Run an installertools processor jar with the provided arguments.
     */
    private fun runInstallerTool(
        toolJar: VPath,
        mainClass: String?,
        classpath: List<VPath>,
        args: List<String>,
        targetDir: VPath
    ): Boolean {
        val javaExec = Java.locateJavaExecutablesWithVersion().firstOrNull { it.first.startsWith("21") }?.second
            ?: "java"
        val cmd = mutableListOf<String>()
        cmd += javaExec
        if (classpath.isNotEmpty() && !mainClass.isNullOrBlank()) {
            val cpEntries = buildList {
                add(toolJar.toAbsolute().toString())
                addAll(classpath.map { it.toAbsolute().toString() })
            }
            cmd += listOf("-cp", cpEntries.joinToString(File.pathSeparator), mainClass)
        } else {
            cmd += listOf("-jar", toolJar.toAbsolute().toString())
        }
        cmd += args
        return try {
            val proc = ProcessBuilder(cmd)
                .directory(targetDir.toJFile())
                .redirectErrorStream(true)
                .start()
            val output = proc.inputStream.bufferedReader().readText()
            val exit = proc.waitFor()
            if (exit != 0) {
                logger.warn("Installertools failed (exit {}): {}", exit, output.take(2000))
                false
            } else {
                logger.info("Installertools OK: {}", args.joinToString(" "))
                true
            }
        } catch (t: Throwable) {
            logger.warn("Failed to run installertools", t)
            false
        }
    }

    /**
     * Read the Main-Class from a jar manifest.
     */
    private fun readJarMainClass(jar: VPath): String? {
        return try {
            JarFile(jar.toJFile()).use { it.manifest?.mainAttributes?.getValue(Attributes.Name.MAIN_CLASS) }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Extract the value for a named argument (e.g. --task, --output) from a processor arg list.
     */
    private fun findArgValue(args: List<String>, key: String): String? {
        val idx = args.indexOf(key)
        if (idx < 0 || idx + 1 >= args.size) return null
        return args[idx + 1]
    }

    /**
     * Try to restore a processor output from the shared NeoForge cache.
     */
    private fun restoreProcessorOutputFromCache(task: String?, output: VPath, mcVersion: String, loaderVersion: String): Boolean {
        val cache = cachePathForTask(task, output, mcVersion, loaderVersion) ?: return false
        if (!cache.exists()) return false
        if (output.exists() && (output.sizeOrNull() ?: 0L) > 0L) return false
        return try {
            output.parent().mkdirs()
            output.writeBytes(cache.bytesOrNothing())
            true
        } catch (t: Throwable) {
            logger.warn("Failed to restore cached {} output {}", task ?: "processor", output.toAbsolute(), t)
            false
        }
    }

    /**
     * Cache processor outputs for reuse in future instances.
     */
    private fun cacheProcessorOutput(task: String, output: VPath, mcVersion: String, loaderVersion: String) {
        val cache = cachePathForTask(task, output, mcVersion, loaderVersion) ?: return
        if (!output.exists() || (output.sizeOrNull() ?: 0L) == 0L) return
        try {
            cache.parent().mkdirs()
            cache.writeBytes(output.bytesOrNothing())
            logger.debug("Cached processor output {} -> {}", output.toAbsolute(), cache.toAbsolute())
        } catch (t: Throwable) {
            logger.warn("Failed to cache processor output {}", output.toAbsolute(), t)
        }
    }

    /**
     * Resolve the cache path for a known processor output.
     */
    private fun cachePathForTask(task: String?, output: VPath, mcVersion: String, loaderVersion: String): VPath? {
        val cacheRoot = dir.resolve("cache")
        return when (task) {
            "DOWNLOAD_MOJMAPS" -> cacheRoot.resolve("mojmaps").resolve(mcVersion).resolve(output.fileName())
            "PROCESS_MINECRAFT_JAR" -> cacheRoot.resolve("patched").resolve(mcVersion).resolve(loaderVersion).resolve(output.fileName())
            else -> null
        }
    }

    /**
     * Copy the patched jar produced by install processors into the merged version folder,
     * inserting manifest attributes NeoForge expects (e.g., Minecraft-Dists).
     */
    private fun ensurePatchedJar(targetDir: VPath, mcVersion: String, loaderVersion: String) {
        val patched = targetDir.resolve(".tr").resolve("libraries")
            .resolve("net/neoforged/minecraft-client-patched/$loaderVersion/minecraft-client-patched-$loaderVersion.jar")
        val neoforgeClient = targetDir.resolve(".tr").resolve("libraries")
            .resolve("net/neoforged/neoforge/$loaderVersion/neoforge-$loaderVersion-client.jar")
        val clientSrg = findClientSrgJar(targetDir, mcVersion)
        val sourceJar = when {
            neoforgeClient.exists() -> neoforgeClient
            patched.exists() -> patched
            clientSrg != null -> clientSrg
            else -> null
        }
        if (sourceJar == null) {
            logger.warn(
                "Patched Minecraft jar missing; checked {} and {}",
                patched.toAbsolute(),
                neoforgeClient.toAbsolute()
            )
            return
        }

        val extraJar = findExtraJar(targetDir, mcVersion)
        val overlays = buildList {
            clientSrg?.let { if (it.exists()) add(it) }
            extraJar?.let { if (it.exists()) add(it) }
        }
        val mergedSource = if (overlays.isNotEmpty()) mergeJarWithOverlays(sourceJar, overlays) else sourceJar

        val mergedId = "$mcVersion-$id-$loaderVersion"
        val outDir = targetDir.resolve(".tr").resolve("versions").resolve(mergedId)
        outDir.mkdirs()
        val outJar = outDir.resolve("$mergedId.jar")
        if (writePatchedJarWithManifest(mergedSource, outJar)) {
            logger.info("Wrote patched jar to {}", outJar.toAbsolute())
        } else {
            outJar.writeBytes(mergedSource.bytesOrNothing())
            logger.warn("Failed to update patched manifest; copied jar directly to {}", outJar.toAbsolute())
        }
        cleanupClientArtifacts(targetDir, mcVersion)
        logJarSelection(mergedSource, outJar)
    }

    /**
     * Locate a client SRG jar under the instance libraries.
     */
    private fun findClientSrgJar(targetDir: VPath, mcVersion: String): VPath? {
        val clientRoot = targetDir.resolve(".tr").resolve("libraries").resolve("net/minecraft/client")
        if (!clientRoot.exists()) return null
        val candidateDirs = clientRoot.listFilesSortedBy(
            selector = { it.fileName() },
            filter = { it.isDir() && it.fileName().startsWith(mcVersion) }
        )
        for (dir in candidateDirs) {
            val files = dir.listFiles { it.fileName().contains("-srg.jar") }
            val match = files.firstOrNull()
            if (match != null) return match
        }
        return null
    }

    /**
     * Locate the -extra client jar under the instance libraries.
     */
    private fun findExtraJar(targetDir: VPath, mcVersion: String): VPath? {
        val clientRoot = targetDir.resolve(".tr").resolve("libraries").resolve("net/minecraft/client")
        if (!clientRoot.exists()) return null
        val candidateDirs = clientRoot.listFiles { it.isDir() && it.fileName().startsWith(mcVersion) }
        for (dir in candidateDirs) {
            val files = dir.listFiles { path ->
                val name = path.fileName()
                name.endsWith("-extra.jar")
            }
            val match = files.firstOrNull()
            if (match != null) return match
        }
        return null
    }

    /**
     * Remove leftover cache artifacts after patching.
     */
    private fun cleanupClientArtifacts(targetDir: VPath, mcVersion: String) {
        val clientRoot = targetDir.resolve(".tr").resolve("libraries").resolve("net/minecraft/client")
        if (!clientRoot.exists()) return
        val dirs = clientRoot.listFiles { it.isDir() && it.fileName().startsWith(mcVersion) }
        for (dir in dirs) {
            val files = dir.listFiles { path ->
                val name = path.fileName()
                name.endsWith(".cache")
            }
            files.forEach { f ->
                try {
                    if (f.delete()) {
                        logger.debug("Removed intermediate MC artifact {}", f.toAbsolute())
                    } else {
                        logger.warn("Failed to remove intermediate MC artifact {}", f.toAbsolute())
                    }
                } catch (t: Throwable) {
                    logger.warn("Error removing intermediate MC artifact {}", f.toAbsolute(), t)
                }
            }
        }
    }

    /**
     * Merge a base jar with overlay jars, skipping duplicate entries.
     */
    private fun mergeJarWithOverlays(base: VPath, overlays: List<VPath>): VPath {
        val merged = base.parent().resolve(base.fileName() + ".merged")
        return try {
            val seen = HashSet<String>()
            JarOutputStream(merged.toJFile().outputStream()).use { jos ->
                JarFile(base.toJFile()).use { jar ->
                    val entries = jar.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        if (!seen.add(entry.name)) continue
                        if (entry.name.equals("META-INF/MANIFEST.MF", ignoreCase = true)) continue
                        val newEntry = JarEntry(entry.name)
                        newEntry.time = entry.time
                        jos.putNextEntry(newEntry)
                        jar.getInputStream(entry).use { it.copyTo(jos) }
                        jos.closeEntry()
                    }
                }
                for (overlay in overlays) {
                    JarFile(overlay.toJFile()).use { jar ->
                        val entries = jar.entries()
                        while (entries.hasMoreElements()) {
                            val entry = entries.nextElement()
                            if (!seen.add(entry.name)) continue
                            if (entry.name.equals("META-INF/MANIFEST.MF", ignoreCase = true)) continue
                            val newEntry = JarEntry(entry.name)
                            newEntry.time = entry.time
                            jos.putNextEntry(newEntry)
                            jar.getInputStream(entry).use { it.copyTo(jos) }
                            jos.closeEntry()
                        }
                    }
                }
            }
            merged
        } catch (t: Throwable) {
            logger.warn("Failed to merge overlay jars {}; using base {}", overlays.joinToString { it.toAbsoluteString() }, base.toAbsolute(), t)
            base
        }
    }

    /**
     * Log jar selection details for diagnostics.
     */
    private fun logJarSelection(sourceJar: VPath, outJar: VPath) {
        try {
            logger.info("NeoForge merged jar source: {} -> {}", sourceJar.toAbsolute(), outJar.toAbsolute())
            JarFile(outJar.toJFile()).use { jar ->
                val manifest = jar.manifest
                val amn = manifest?.mainAttributes?.getValue("Automatic-Module-Name")
                val dists = manifest?.mainAttributes?.getValue("Minecraft-Dists")
                logger.info("Merged jar manifest: Automatic-Module-Name='{}', Minecraft-Dists='{}'", amn, dists)
                val hasModuleInfo = jar.getEntry("module-info.class") != null
                logger.info("Merged jar contains module-info.class? {}", hasModuleInfo)
            }
        } catch (t: Throwable) {
            logger.warn("Failed to inspect merged jar {}", outJar.toAbsolute(), t)
        }
    }

    /**
     * Rewrite the patched jar while injecting manifest defaults (Manifest-Version + Minecraft-Dists).
     */
    private fun writePatchedJarWithManifest(source: VPath, dest: VPath): Boolean {
        return try {
            val tmp = dest.parent().resolve(dest.fileName() + ".tmp")
            JarFile(source.toJFile()).use { jar ->
                val manifest = jar.manifest ?: Manifest()
                val attrs = manifest.mainAttributes
                if (attrs.getValue(Attributes.Name.MANIFEST_VERSION) == null) {
                    attrs[Attributes.Name.MANIFEST_VERSION] = "1.0"
                }
                // Expose the merged jar as the 'minecraft' automatic module for JPMS-based launch.
                attrs.putValue("Automatic-Module-Name", "minecraft")
                if (attrs.getValue("Minecraft-Dists").isNullOrBlank()) {
                    attrs.putValue("Minecraft-Dists", "client")
                }

                JarOutputStream(tmp.toJFile().outputStream(), manifest).use { jos ->
                    val entries = jar.entries()
                    val seen = HashSet<String>()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        if (entry.name == "module-info.class") continue
                        if (entry.name.equals("META-INF/MANIFEST.MF", ignoreCase = true)) continue
                        if (!seen.add(entry.name)) continue
                        val newEntry = JarEntry(entry.name)
                        newEntry.time = entry.time
                        jos.putNextEntry(newEntry)
                        jar.getInputStream(entry).use { it.copyTo(jos) }
                        jos.closeEntry()
                    }
                }
            }
            dest.parent().mkdirs()
            Files.move(tmp.toJPath(), dest.toJPath(), StandardCopyOption.REPLACE_EXISTING)
            true
        } catch (t: Throwable) {
            logger.warn("Failed to write patched jar manifest for {}", dest.toAbsolute(), t)
            false
        }
    }

    /**
     * Resolve a Maven artifact path under the instance libraries.
     */
    private fun resolveMavenArtifact(coord: String, targetDir: VPath): VPath {
        val libsDir = targetDir.resolve(".tr").resolve("libraries")
        val path = mavenPath(coord)
        return libsDir.resolve(path)
    }

    /**
     * Return the JsonPrimitive if the element is a primitive, otherwise null.
     */
    private fun JsonElement.jsonPrimitiveOrNull(): JsonPrimitive? = this as? JsonPrimitive

    /**
     * Read the major FML loader version from a NeoForge version.json object.
     */
    private fun getFmlLoaderMajor(versionJson: JsonObject): Int? {
        return try {
            val libs = versionJson["libraries"]?.jsonArray ?: return null
            for (el in libs) {
                val obj = el as? JsonObject ?: continue
                val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: continue
                if (name.startsWith("net.neoforged.fancymodloader:loader:")) {
                    val version = name.substringAfterLast(':')
                    return parseMajorVersion(version)
                }
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Parse the leading major version number from a version string.
     */
    private fun parseMajorVersion(version: String): Int? {
        val digits = version.takeWhile { it.isDigit() }
        if (digits.isBlank()) return null
        return digits.toIntOrNull()
    }

    /**
     * Remove the merged game jar from module path-like lists to avoid duplicate module errors.
     */
    private fun removeMainJarFromPaths(paths: String, mergedId: String?): String {
        if (mergedId.isNullOrBlank()) return paths
        val filtered = paths.split(File.pathSeparator)
            .filterNot { it.contains("${File.separator}versions${File.separator}$mergedId${File.separator}$mergedId.jar") }
        return filtered.joinToString(File.pathSeparator)
    }

    /**
     * Convert a path-separated list into the comma-separated jar-name list FML expects.
     */
    private fun toFmlLayerLibraryNameSet(paths: String): LinkedHashSet<String> {
        val names = LinkedHashSet<String>()
        if (paths.isBlank()) return names

        for (entry in paths.split(File.pathSeparator)) {
            val trimmed = entry.trim()
            if (trimmed.isBlank()) continue
            val normalized = trimmed.replace('\\', '/')
            val fileName = normalized.substringAfterLast('/')
            if (fileName.isBlank()) continue
            names += fileName
        }
        return names
    }

    /**
     * Replace or append a JVM system property argument.
     */
    private fun setOrReplaceProp(args: MutableList<String>, key: String, value: String) {
        val idx = args.indexOfFirst { it.startsWith("$key=") }
        if (idx >= 0) {
            args[idx] = "$key=$value"
        } else {
            args.add("$key=$value")
        }
    }

    /**
     * Locate a library path (or name) whose artifact path contains any of the provided substrings.
     * Returns the download path when available, otherwise falls back to the library name.
     */
    private fun findLibraryByPathAny(versionObj: JsonObject, substrings: List<String>): String? {
        val libs = versionObj["libraries"]?.jsonArray ?: return null
        for (el in libs) {
            val obj = el as? JsonObject ?: continue
            val path = obj["downloads"]?.jsonObject
                ?.get("artifact")?.jsonObject
                ?.get("path")?.jsonPrimitive?.contentOrNull
            val name = obj["name"]?.jsonPrimitive?.contentOrNull
            val candidates = listOfNotNull(path, name)
            if (candidates.any { cand -> substrings.any { cand.contains(it) } }) {
                return path ?: name
            }
        }
        return null
    }

    /**
     * Resolve a bracketed maven coordinate (e.g. `[group:artifact:version@classifier]`)
     * to a path under the instance libraries directory, downloading it if missing.
     */
    private suspend fun resolveBracketedMaven(raw: String, targetDir: VPath): VPath? = withContext(Dispatchers.IO) {
        if (!raw.startsWith("[") || !raw.endsWith("]")) return@withContext null
        val inner = raw.substring(1, raw.length - 1)
        val parts = inner.split("@")
        val coord = parts[0]
        val ext = parts.getOrNull(1)
        val libsDir = targetDir.resolve(".tr").resolve("libraries")
        val path = mavenPathWithExt(coord, ext)
        val out = libsDir.resolve(path)
        if (!out.exists()) {
            try {
                out.parent().mkdirs()
                val bytes = downloadFromRepos(coord, path)
                if (bytes != null) {
                    validateJarBytesOrThrow(path, bytes)
                    out.writeBytes(bytes)
                    logger.info("Downloaded maven artifact {} -> {}", coord, out.toAbsolute())
                }
            } catch (t: Throwable) {
                logger.warn("Failed to download maven artifact {}", coord, t)
            }
        }
        return@withContext out
    }

    /**
     * Ensure a Maven artifact exists under the instance libraries, downloading it if needed.
     */
    private suspend fun ensureMavenArtifact(coord: String, targetDir: VPath): VPath? = withContext(Dispatchers.IO) {
        val coordNormalized = coord.substringBefore("@")
        val libsDir = targetDir.resolve(".tr").resolve("libraries")
        val path = mavenPath(coordNormalized)
        val out = libsDir.resolve(path)
        if (out.exists()) {
            if (isValidZipFile(out)) return@withContext out
            try { out.delete() } catch (_: Throwable) {}
            logger.warn("Artifact {} was corrupt on disk; re-downloading", coord)
        }
        return@withContext try {
            out.parent().mkdirs()
            val bytes = downloadFromRepos(coordNormalized, path)
            if (bytes != null) {
                validateJarBytesOrThrow(path, bytes)
                out.writeBytes(bytes)
                logger.info("Downloaded maven artifact {} -> {}", coord, out.toAbsolute())
                out
            } else {
                logger.warn("No repository yielded artifact {}", coord)
                null
            }
        } catch (t: Throwable) {
            logger.warn("Failed to download maven artifact {}", coord, t)
            null
        }
    }

    /**
     * Convert a Maven coordinate to a repository path with optional extension override.
     */
    private fun mavenPathWithExt(name: String, ext: String?): String {
        val parts = name.split(":")
        if(parts.size < 3) return name
        val group = parts[0].replace('.', '/')
        val artifact = parts[1]
        val version = parts[2]
        val classifier = parts.getOrNull(3)
        val fileExt = ext ?: "jar"
        val file = if(classifier != null) {
            "$artifact-$version-$classifier.$fileExt"
        } else {
            "$artifact-$version.$fileExt"
        }
        return "$group/$artifact/$version/$file"
    }

    /**
     * Return candidate Maven repositories for a coordinate.
     */
    private fun mavenReposFor(coord: String): List<String> {
        val group = coord.substringBefore(':')
        return when {
            group.startsWith("net.neoforged") -> listOf("https://maven.neoforged.net/releases")
            group.startsWith("net.fabricmc") -> listOf("https://maven.fabricmc.net")
            group.startsWith("net.md-5") -> listOf(
                "https://repo.papermc.io/repository/maven-public",
                "https://maven.neoforged.net/releases"
            )
            else -> listOf(
                "https://libraries.minecraft.net",
                "https://repo.maven.apache.org/maven2"
            )
        }
    }

    /**
     * Ensure every library in the installer + version.json set is present under the instance `.tr/libraries`.
     */
    private suspend fun downloadLibraries(libs: JsonArray, targetDir: VPath) = withContext(IODispatchers.BulkIO) {
        val baseDir = targetDir.resolve(".tr").resolve("libraries")
        val cacheRoot = fromTR(TConstants.Dirs.CACHE).resolve("libraries")
        val entries = libs.mapNotNull { it as? JsonObject }
        val total = entries.size
        val concurrency = chooseLibraryConcurrency(total)
        logger.info("NeoForge libraries: {} to ensure, concurrency={}", total, concurrency)
        val semaphore = Semaphore(concurrency)
        val startedAt = System.currentTimeMillis()
        var completed = 0

        coroutineScope {
            entries.withIndex().map { (idx, obj) ->
                launch {
                    val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@launch
                    val artifact = obj["downloads"]?.jsonObject
                        ?.get("artifact")?.jsonObject
                    val path = artifact?.get("path")?.jsonPrimitive?.contentOrNull
                        ?: mavenPathFromName(name)
                    val explicitUrl = artifact?.get("url")?.jsonPrimitive?.contentOrNull
                    val expectedSize = artifact?.get("size")?.jsonPrimitive?.longOrNull
                    val baseUrl = obj["url"]?.jsonPrimitive?.contentOrNull ?: "https://maven.neoforged.net/releases/"
                    val url = explicitUrl ?: (baseUrl.trimEnd('/') + "/" + path)
                    val dest = baseDir.resolve(path)
                    val cacheFile = cacheRoot.resolve(path)
                    semaphore.withPermit {
                        try {
                            if (linkOrCopyFromCache(cacheFile, dest) && isUsableLibraryArtifact(dest, expectedSize)) {
                                return@withPermit
                            }
                            if (dest.exists() && !isUsableLibraryArtifact(dest, expectedSize)) {
                                dest.delete()
                            }
                            if (cacheFile.exists() && !isUsableLibraryArtifact(cacheFile, expectedSize)) {
                                cacheFile.delete()
                            }
                            val bytes = downloadBytesChecked(url)
                            if (expectedSize != null && expectedSize > 0L && bytes.size.toLong() != expectedSize) {
                                throw IllegalStateException(
                                    "Size mismatch for $name ($path): got ${bytes.size}, expected $expectedSize"
                                )
                            }
                            validateJarBytesOrThrow(path, bytes)
                            cacheFile.parent().mkdirs()
                            cacheFile.writeBytesAtomic(bytes)
                            dest.parent().mkdirs()
                            if (!linkOrCopyFromCache(cacheFile, dest)) {
                                dest.writeBytesAtomic(bytes)
                            }
                            logger.debug("neoforge lib [{}/{}] {} -> {}", idx + 1, total, name, dest.toAbsolute())
                        } catch (t: Throwable) {
                            logger.warn("Failed to download library {} from {}", name, url, t)
                        } finally {
                            val done = synchronized(this@NeoForge) { ++completed }
                            if (done % 40 == 0 || done == total) {
                                val elapsed = System.currentTimeMillis() - startedAt
                                logger.info("NeoForge libraries progress {}/{} ({} ms)", done, total, elapsed)
                            }
                        }
                    }
                }
            }.joinAll()
        }

        logger.info("NeoForge libraries complete in {} ms", System.currentTimeMillis() - startedAt)
    }

    private fun chooseLibraryConcurrency(total: Int): Int {
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(2)
        val target = when {
            total >= 1_000 -> 24
            total >= 500 -> 20
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
            throw IllegalStateException("Downloaded NeoForge jar is invalid: $path")
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
     * Convert a Maven coordinate into a repository path.
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
     * Convert a Maven coordinate with optional extension override into a repository path.
     */
    private fun mavenPathFromName(name: String): String {
        val split = name.split("@", limit = 2)
        if (split.size == 2) {
            return mavenPathWithExt(split[0], split[1])
        }
        return mavenPath(name)
    }

    /**
     * Download an artifact from known repositories, returning bytes on success.
     */
    private suspend fun downloadFromRepos(coord: String, path: String): ByteArray? {
        for (repo in mavenReposFor(coord)) {
            try {
                val url = repo.trimEnd('/') + "/" + path
                val resp = client.get(url)
                if (!resp.status.isSuccess()) {
                    logger.debug("Repo {} returned HTTP {} for {}", repo, resp.status, coord)
                    continue
                }

                val tmp = withContext(IODispatchers.FileIO) {
                    runInterruptible { Files.createTempFile("tritium-repo-download-", ".zip") }
                }
                try {
                    val channel: ByteReadChannel = resp.bodyAsChannel()
                    tmp.toFile().outputStream().use { out ->
                        channel.copyTo(out)
                    }

                    if (!isValidZipFile(VPath.get(tmp.absolutePathString()))) {
                        logger.debug("Repo {} returned non-archive content for {}", repo, coord)
                        continue
                    }

                    val bytes = withContext(IODispatchers.FileIO) {
                        runInterruptible { Files.readAllBytes(tmp) }
                    }
                    withContext(IODispatchers.FileIO) {
                        runInterruptible { Files.deleteIfExists(tmp) }
                    }
                    return bytes
                } finally {
                    withContext(IODispatchers.FileIO) {
                        runInterruptible { Files.deleteIfExists(tmp) }
                    }
                }
            } catch (t: Throwable) {
                logger.debug("Repo {} failed for {}: {}", repo, coord, t.toString())
            }
        }
        return null
    }

    /**
     * Check the on-disk file header for a zip/jar signature.
     */
    private fun isValidZipFile(path: VPath): Boolean =
        try {
            ZipFile(path.toJFile()).use { zip ->
                val hasAny = zip.entries().hasMoreElements()
                return hasAny
            }
        } catch (_: Exception) {
            false
        }

    /**
     * Parse XML into a DOM document for metadata inspection.
     */
    private fun xmlToString(xml: String): Document {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        val builder = factory.newDocumentBuilder()
        return builder.parse(xml.byteInputStream())
    }

    /**
     * Fetch a URL as text, raising on non-success responses.
     */
    private suspend fun readText(url: String): String {
        val resp = client.get(url)
        if(!resp.status.isSuccess()) {
            logger.error("HTTP error {} reading text from '{}'", resp.status, url)
            throw Exception("HTTP ${resp.status} reading text from '$url'")
        }
        return resp.bodyAsText()
    }

    /**
     * Convert bytes to lowercase hex string.
     */
    private fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        bytes.forEach {
            sb.append(String.format("%02x", it))
        }
        return sb.toString()
    }
}
