/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.core.source

import io.github.tritium_launcher.api.logger
import io.github.tritium_launcher.api.modpack.*
import io.github.tritium_launcher.api.platform.ClientIdentity
import io.github.tritium_launcher.api.registry.Registrable
import io.github.tritium_launcher.launcher.core.HttpClientProvider
import io.github.tritium_launcher.launcher.core.source.curseforge.*
import io.github.tritium_launcher.launcher.ui.theme.TIcons
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.HttpStatusCode.Companion.Forbidden
import io.ktor.http.HttpStatusCode.Companion.TooManyRequests
import io.ktor.http.HttpStatusCode.Companion.Unauthorized
import io.ktor.serialization.kotlinx.json.*
import io.qt.gui.QPixmap
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlin.time.Duration.Companion.milliseconds

class CurseForge : ModSource(), Registrable {
    override val id: String = "curseforge"
    override val displayName: String = "CurseForge"
    override val icon: QPixmap = TIcons.CurseForge
    override val webpage: String = "https://www.curseforge.com/"
    override val order: Int = 1
    override val descriptionFormat: DescriptionFormat = DescriptionFormat.HTML

    // If you fork or modify this launcher, you may NOT use this key.
    private val apiKey = "$2a$10\$P7GPNqahxcijWPXRG2HES.CCvjTAxfWEjQJ4WF42/CcVP8ksyIus."
    private val apiUrl = "https://api.curseforge.com/v1/"
    private var cachedCategories: List<ModCategory>? = null

    private val json = Json { ignoreUnknownKeys = true }
    private val client = HttpClientProvider.client {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 30_000
        }

        defaultRequest {
            url(apiUrl)
            header("x-api-key", apiKey)
            header("User-Agent", ClientIdentity.userAgent)
            header("X-Client-Info", ClientIdentity.clientInfoHeader)
        }
    }

    private val logger = logger()

    override suspend fun getCategories(context: ModBrowserContext): List<ModCategory> {
        if (cachedCategories != null) return cachedCategories!!
        try {
            val response = retryOnThrottle {
                client.get("categories") {
                    parameter("gameId", 432)
                }
            }.let { response ->
                if (!response.status.isSuccess()) {
                    logger.error("CurseForge returned {}", response.status)
                }
                try {
                    response.body<CurseCategoryResponse>()
                } catch (e: Exception) {
                    val raw = response.bodyAsText()
                    logger.error("CurseForge categories deserialization failed. Raw response (first 2KB): {}", raw.take(2048))
                    throw e
                }
            }

            val result = response.data
                .filterNot { it.isClass }
                .filter { it.classId == 6 }
                .map { ModCategory(it.id.toString(), it.name, iconUrl = it.iconUrl) }
            cachedCategories = result
            return result
        } catch (e: ClientRequestException) {
            when(e.response.status) {
                Unauthorized, Forbidden ->
                    logger.error("CurseForge API Key Rejected ({}): {}", e.response.status, e.response.bodyAsText())
                TooManyRequests -> {
                    logger.warn("CurseForge rate limited")
                    throw e
                }
                else -> logger.error("CurseForge categories failed ({}): {}", e.response.status, e.response.bodyAsText())
            }
            throw e
        } catch (e: Exception) {
            logger.error("CurseForge categories deserialization failed: {}", e.message)
            throw e
        }
    }

    override suspend fun search(context: ModBrowserContext, query: ModSearchQuery): ModSearchPage {
        logger.info(
            "CurseForge search: mc={} loader={} text='{}' offset={} limit={}",
            context.minecraftVersion, context.modLoaderId,
            query.text, query.offset, query.limit
        )
        try {
            val response = retryOnThrottle {
                client.get("mods/search") {
                    parameter("gameId", 432)
                    parameter("classId", 6) // Mods only
                    parameter("sortField", 2) // Sort by Popularity
                    parameter("sortOrder", "desc")
                    parameter("searchFilter", query.text)
                    parameter("index", query.offset)
                    parameter("pageSize", query.limit)
                    context.minecraftVersion?.let { parameter("gameVersion", it) }
                    context.modLoaderId?.let { curseLoaderType(it) }?.let { parameter("modLoaderType", it) }
                    query.includedCategories.firstOrNull()?.let { parameter("classId", it) }
                }
            }.let { response ->
                if (!response.status.isSuccess()) {
                    logger.error("CurseForge returned {}", response.status)
                }
                try {
                    response.body<SearchResponse>()
                } catch (e: Exception) {
                    val raw = response.bodyAsText()
                    logger.error("CurseForge search deserialization failed. Raw response (first 2KB): {}", raw.take(2048))
                    throw e
                }
            }

            return ModSearchPage(
                results = response.data.map { mod ->
                    ModSearchResult(
                        id = mod.id.toString(),
                        title = mod.name,
                        summary = mod.summary,
                        author = mod.authors.firstOrNull()?.name,
                        downloads = mod.downloadCount,
                        categories = mod.categories.map { it.name },
                        versions = mod.latestFiles.flatMap { it.gameVersions },
                        iconUrl = mod.logo?.url,
                        slug = mod.slug,
                    )
                },
                total = response.pagination.totalCount
            )
        } catch (e: ClientRequestException) {
            when(e.response.status) {
                Unauthorized, Forbidden ->
                    logger.error("CurseForge API Key Rejected ({}): {}", e.response.status, e.response.bodyAsText())
                TooManyRequests -> {
                    logger.warn("CurseForge rate limited")
                    throw e
                }
                else -> logger.error("CurseForge search failed ({}): {}", e.response.status, e.response.bodyAsText())
            }
            throw e
        } catch (e: Exception) {
            logger.error("CurseForge search deserialization failed: {}", e.message)
            throw e
        }
    }

    override suspend fun details(context: ModBrowserContext, projectId: String): ModDetails {
        try {
            val response = retryOnThrottle {
                client.get("mods/$projectId")
            }.let { response ->
                if (!response.status.isSuccess()) {
                    logger.error("CurseForge returned {}", response.status)
                }
                try {
                    response.body<CurseModDetailResponse>()
                } catch (e: Exception) {
                    val raw = response.bodyAsText()
                    logger.error("CurseForge mod deserialization failed. Raw response (first 2KB): {}", raw.take(2048))
                    throw e
                }
            }

            val mod = response.data
            val description = runCatching {
                retryOnThrottle {
                    client.get("mods/$projectId/description")
                }.body<CurseDescriptionResponse>().data
            }.getOrNull() ?: mod.description ?: mod.summary

            return ModDetails(
                id = mod.id.toString(),
                title = mod.name,
                summary = mod.summary,
                description = description,
                author = mod.authors.firstOrNull()?.name,
                downloads = mod.downloadCount,
                categories = mod.categories.map { it.name },
                website = mod.links?.websiteUrl ?: "$webpage/mc-mods/${mod.name}",
                latestVersion = mod.latestFiles.firstOrNull()?.gameVersions?.lastOrNull(),
                iconUrl = mod.logo?.url
            )
        } catch (e: ClientRequestException) {
            when(e.response.status) {
                Unauthorized, Forbidden ->
                    logger.error("CurseForge API Key Rejected ({}): {}", e.response.status, e.response.bodyAsText())
                TooManyRequests -> {
                    logger.warn("CurseForge rate limited")
                    throw e
                }
                else -> logger.error("CurseForge mod detail failed ({}): {}", e.response.status, e.response.bodyAsText())
            }
            throw e
        } catch (e: Exception) {
            logger.error("CurseForge mod deserialization failed: {}", e.message)
            throw e
        }
    }

    override suspend fun versions(context: ModBrowserContext, projectId: String): List<ModVersionOption> {
        logger.debug("CurseForge versions: projectId={} mc={} loader={}", projectId, context.minecraftVersion, context.modLoaderId)
        if (projectId.isBlank() || projectId.toIntOrNull() == null) {
            logger.warn("CurseForge versions: non-numeric projectId '{}', returning empty versions", projectId)
            return emptyList()
        }
        try {
            val allFiles = mutableListOf<CurseFileInfo>()
            var index = 0
            val pageSize = 50
            val maxPages = 4
            val loaderType = context.modLoaderId?.let { curseLoaderType(it) }

            run pagination@{
                repeat(maxPages) {
                    val page = retryOnThrottle {
                        client.get("mods/$projectId/files") {
                            parameter("pageSize", pageSize)
                            parameter("index", index)
                            context.minecraftVersion?.let { parameter("gameVersion", it) }
                            loaderType?.let { parameter("modLoaderType", it) }
                        }
                    }.let { response ->
                        if (!response.status.isSuccess()) {
                            logger.error("CurseForge returned {}", response.status)
                        }
                        try {
                            response.body<CurseFilesResponse>()
                        } catch (e: Exception) {
                            val raw = response.bodyAsText()
                            logger.error("CurseForge files deserialization failed. Raw response (first 2KB): {}", raw.take(2048))
                            throw e
                        }
                    }

                    allFiles.addAll(page.data)

                    val total = page.pagination.totalCount
                    index += pageSize
                    if (index >= total || page.data.isEmpty()) return@pagination
                }
                logger.warn("CurseForge versions hit page limit for projectId={}, total files may be truncated", projectId)
            }

            return allFiles
                .filter { it.isAvailable }
                .filter { file ->
                    (context.minecraftVersion == null || file.gameVersions.any { it == context.minecraftVersion }) &&
                    (context.modLoaderId == null || file.modLoaders == null || file.modLoaders.any { it.id.lowercase().startsWith(
                        context.modLoaderId!!.lowercase()) })
                }
                .sortedByDescending { it.fileDate }
                .map { file ->
                    val loaders = file.modLoaders?.map { it.id }
                        ?: file.gameVersions.filter { gv ->
                            knownLoaderIdentifiers.any { gv.contains(it, ignoreCase = true) }
                        }
                    val sha1Hash = file.hashes?.firstOrNull { it.algo == 1 }?.value
                    ModVersionOption(
                        id = file.id.toString(),
                        label = file.displayName.ifBlank { file.fileName },
                        fileName = file.fileName,
                        fileHash = sha1Hash,
                        gameVersions = file.gameVersions,
                        loaders = loaders,
                        featured = false,
                        downloads = file.downloadCount,
                        releaseType = when (file.releaseType) {
                            1 -> ReleaseType.RELEASE
                            2 -> ReleaseType.BETA
                            3 -> ReleaseType.ALPHA
                            else -> null
                        }
                    )
                }
        } catch (e: ClientRequestException) {
            when (e.response.status) {
                Unauthorized, Forbidden ->
                    logger.error("CurseForge API Key Rejected ({}): {}", e.response.status, e.response.bodyAsText())
                TooManyRequests -> {
                    logger.warn("CurseForge rate limited")
                    throw e
                }
                else -> logger.error("CurseForge versions failed ({}): {}", e.response.status, e.response.bodyAsText())
            }
            throw e
        } catch (e: Exception) {
            logger.error("CurseForge versions failed: {}", e.message)
            throw e
        }
    }

    override suspend fun resolveProjectInfoByFingerprint(fingerprint: Long): HashProjectInfo? {
        return try {
            val response = retryOnThrottle {
                client.post("fingerprints/432") {
                    contentType(ContentType.Application.Json)
                    setBody(buildJsonObject { put("fingerprints", buildJsonArray { add(JsonPrimitive(fingerprint)) }) })
                }
            }.let { response ->
                if (!response.status.isSuccess()) {
                    logger.error("CurseForge fingerprint lookup returned {}", response.status)
                    return@let null
                }
                try {
                    response.body<CurseFingerprintResponse>()
                } catch (e: Exception) {
                    val raw = response.bodyAsText()
                    logger.error("CurseForge fingerprint deserialization failed. Raw response: {}", raw, e)
                    null
                }
            } ?: return null

            val match = (response.data.exactMatches + response.data.partialMatches).firstOrNull() ?: return null
            val modId = match.file.modId ?: return null
            HashProjectInfo(
                projectId = modId.toString(),
                projectTitle = match.file.displayName,
                versionId = match.file.id.toString()
            )
        } catch (e: ClientRequestException) {
            when (e.response.status) {
                Unauthorized, Forbidden ->
                    logger.error("CurseForge API Key Rejected ({}): {}", e.response.status, e.response.bodyAsText())
                TooManyRequests -> {
                    logger.warn("CurseForge rate limited")
                    throw e
                }
                else -> logger.error("CurseForge fingerprint lookup failed ({}): {}", e.response.status, e.response.bodyAsText())
            }
            null
        } catch (e: Exception) {
            logger.error("CurseForge fingerprint lookup failed: {}", e.message)
            null
        }
    }

    override suspend fun resolveProjectInfoByJarContents(bytes: ByteArray): HashProjectInfo? {
        val fingerprint = curseFingerprint(bytes)
        return resolveProjectInfoByFingerprint(fingerprint)
    }

    override fun computeFileFingerprint(bytes: ByteArray): Long = curseFingerprint(bytes)

    override suspend fun resolveProjectInfosByFingerprints(fingerprints: List<Long>): Map<Long, HashProjectInfo> {
        if (fingerprints.isEmpty()) {
            logger.debug("CurseForge batch fingerprint: no fingerprints to resolve")
            return emptyMap()
        }
        return try {
            val response = retryOnThrottle {
                client.post("fingerprints/432") {
                    contentType(ContentType.Application.Json)
                    setBody(buildJsonObject {
                        put("fingerprints", buildJsonArray {
                            fingerprints.forEach { add(JsonPrimitive(it)) }
                        })
                    })
                }
            }.let { httpResponse ->
                if (!httpResponse.status.isSuccess()) {
                    logger.error("CurseForge batch fingerprint lookup returned {}", httpResponse.status)
                    return@let null
                }
                try {
                    httpResponse.body<CurseFingerprintResponse>()
                } catch (e: Exception) {
                    logger.error("CurseForge batch fingerprint deserialization failed: {}", e.message)
                    return@let null
                }
            } ?: return emptyMap()

            val exact = response.data.exactMatches.size
            val partial = response.data.partialMatches.size
            logger.debug("CurseForge batch fingerprint: {} fingerprints, {} exact, {} partial", fingerprints.size, exact, partial)
            (response.data.exactMatches + response.data.partialMatches).mapNotNull { match ->
                val modId = match.file.modId ?: return@mapNotNull null
                val fp = match.file.fileFingerprint ?: match.id
                fp to HashProjectInfo(
                    projectId = modId.toString(),
                    projectTitle = match.file.displayName,
                    versionId = match.file.id.toString()
                )
            }.toMap().also { logger.debug("CurseForge batch fingerprint resolved {} mods", it.size) }
        } catch (e: Exception) {
            logger.error("CurseForge batch fingerprint lookup failed: {}", e.message)
            emptyMap()
        }
    }

    private fun curseLoaderType(id: String): Int? {
        return when (id.lowercase()) {
            "forge" -> 1
            "fabric" -> 4
            "quilt" -> 5
            "neoforge" -> 6
            else -> null
        }
    }

    /**
     * Resolves mod names and icon URLs for a list of CurseForge project IDs.
     *
     * @param projectIds CurseForge project IDs to look up.
     * @return Map of project ID to [CurseModBrief] with the mod's name and icon URL.
     */
    suspend fun batchModDetails(projectIds: List<Long>): Map<Long, CurseModBrief> {
        if (projectIds.isEmpty()) return emptyMap()
        return try {
            val response = retryOnThrottle {
                client.post("mods") {
                    contentType(ContentType.Application.Json)
                    setBody(buildJsonObject {
                        put("modIds", buildJsonArray {
                            projectIds.forEach { add(JsonPrimitive(it)) }
                        })
                    })
                }
            }.let { response ->
                if (!response.status.isSuccess()) {
                    logger.error("CurseForge batch mod lookup returned {}: {}", response.status, response.bodyAsText().take(500))
                    return@let null
                }
                response.body<CurseModListResponse>()
            } ?: return emptyMap()

            response.data.associate { mod ->
                mod.id.toLong() to CurseModBrief(mod.name, mod.logo?.url)
            }
        } catch (e: Exception) {
            logger.error("CurseForge batch mod lookup failed: {}", e.message)
            emptyMap()
        }
    }

    override suspend fun resolveInstall(
        context: ModBrowserContext,
        projectId: String,
        versionId: String
    ): ModInstallPlan {
        logger.info("CurseForge install resolve: projectId={} fileId={}", projectId, versionId)
        try {
            val file = retryOnThrottle {
                client.get("mods/$projectId/files/$versionId")
            }.let { response ->
                if (!response.status.isSuccess()) {
                    logger.error("CurseForge returned {}", response.status)
                }
                try {
                    response.body<CurseFileResponse>()
                } catch (e: Exception) {
                    val raw = response.bodyAsText()
                    logger.error("CurseForge file deserialization failed. Raw response (first 2KB): {}", raw.take(2048))
                    throw e
                }
            }.data

            val hash = file.hashes?.firstOrNull { it.algo == 1 }?.value

            return ModInstallPlan(
                projectId = projectId,
                versionId = file.id.toString(),
                versionLabel = file.displayName.ifBlank { file.fileName },
                fileName = file.fileName,
                downloadUrl = file.downloadUrl,
                releaseType = when (file.releaseType) {
                    1 -> ReleaseType.RELEASE
                    2 -> ReleaseType.BETA
                    3 -> ReleaseType.ALPHA
                    else -> null
                },
                fileHash = hash,
            )
        } catch (e: ClientRequestException) {
            when (e.response.status) {
                Unauthorized, Forbidden ->
                    logger.error("CurseForge API Key Rejected ({}): {}", e.response.status, e.response.bodyAsText())
                TooManyRequests -> {
                    logger.warn("CurseForge rate limited")
                    throw e
                }
                else -> logger.error("CurseForge install resolve failed ({}): {}", e.response.status, e.response.bodyAsText())
            }
            throw e
        } catch (e: Exception) {
            logger.error("CurseForge install resolve failed: {}", e.message)
            throw e
        }
    }

    private suspend fun <T> retryOnThrottle(block: suspend () -> T): T {
        repeat(3) { attempt ->
            try {
                return block()
            } catch (e: ClientRequestException) {
                if (e.response.status.value != 429 || attempt == 2) throw e
                val retryAfter = e.response.headers["Retry-After"]?.toLongOrNull()
                    ?: (10L * (1 shl attempt))
                delay((retryAfter * 1000).milliseconds)
            }
        }
        error("unreachable")
    }

    companion object {
        private val knownLoaderIdentifiers = setOf(
            "Forge", "NeoForge", "Fabric", "Quilt", "Fabric like", "Rift"
        )

        private const val MURMUR_MULTIPLEX = 1540483477

        fun curseFingerprint(bytes: ByteArray): Long {
            var normLen = 0
            for (b in bytes) {
                if (b !in whitespaceSet) normLen++
            }

            var h = 1 xor normLen
            var k = 0
            var offset = 0

            for (b in bytes) {
                if (b in whitespaceSet) continue
                k = k or ((b.toInt() and 0xFF) shl offset)
                offset += 8
                if (offset == 32) {
                    var k2 = k * MURMUR_MULTIPLEX
                    k2 = (k2 xor (k2 ushr 24)) * MURMUR_MULTIPLEX
                    h = h * MURMUR_MULTIPLEX xor k2
                    k = 0
                    offset = 0
                }
            }

            if (offset > 0) {
                h = (h xor k) * MURMUR_MULTIPLEX
            }

            h = (h xor (h ushr 13)) * MURMUR_MULTIPLEX
            h = h xor (h ushr 15)

            return h.toLong() and 0xFFFFFFFFL
        }

        private val whitespaceSet = setOf<Byte>(
            0x09, 0x0A, 0x0D, 0x20
        )
    }
}

data class CurseModBrief(
    val name: String,
    val iconUrl: String?
)
