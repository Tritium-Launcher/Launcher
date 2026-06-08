package io.github.tritium_launcher.launcher.core.source

import io.github.tritium_launcher.launcher.core.source.curseforge.*
import io.github.tritium_launcher.launcher.logger
import io.github.tritium_launcher.launcher.platform.ClientIdentity
import io.github.tritium_launcher.launcher.registry.Registrable
import io.github.tritium_launcher.launcher.ui.theme.TIcons
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
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
    private val client = HttpClient(CIO) {
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
                        iconUrl = mod.logo?.url
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
        try {
            val allFiles = mutableListOf<CurseFileInfo>()
            var index = 0
            val pageSize = 50
            val maxPages = 4
            var pagesFetched = 0
            val loaderType = context.modLoaderId?.let { curseLoaderType(it) }

            while (pagesFetched < maxPages) {
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
                pagesFetched++

                val total = page.pagination.totalCount
                index += pageSize
                if (index >= total || page.data.isEmpty()) break
            }

            if (pagesFetched >= maxPages) {
                logger.warn("CurseForge versions hit page limit for projectId={}, total files may be truncated", projectId)
            }

            return allFiles
                .filter { it.isAvailable }
                .filter { file ->
                    (context.minecraftVersion == null || file.gameVersions.any { it == context.minecraftVersion }) &&
                    (context.modLoaderId == null || file.modLoaders == null || file.modLoaders.any { it.id.lowercase().startsWith(context.modLoaderId.lowercase()) })
                }
                .sortedByDescending { it.fileDate }
                .map { file ->
                    ModVersionOption(
                        id = file.id.toString(),
                        label = file.displayName.ifBlank { file.fileName },
                        gameVersions = file.gameVersions,
                        loaders = file.modLoaders?.map { it.id } ?: emptyList(),
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

    private fun curseLoaderType(id: String): Int? {
        return when (id.lowercase()) {
            "forge" -> 1
            "fabric" -> 4
            "quilt" -> 5
            "neoforge" -> 6
            else -> null
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
}
