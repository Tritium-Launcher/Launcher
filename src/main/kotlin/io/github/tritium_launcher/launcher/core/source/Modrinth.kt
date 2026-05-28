package io.github.tritium_launcher.launcher.core.source

import io.github.tritium_launcher.launcher.core.source.modrinth.api.ModrinthCategories
import io.github.tritium_launcher.launcher.core.source.modrinth.api.ModrinthProject
import io.github.tritium_launcher.launcher.core.source.modrinth.api.ModrinthVersion
import io.github.tritium_launcher.launcher.core.source.modrinth.api.Search
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
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.qt.gui.QPixmap
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray

class Modrinth : ModSource(), Registrable {
    override val id: String = "modrinth"
    override val displayName: String = "Modrinth"
    override val icon: QPixmap = TIcons.Modrinth
    override val webpage: String = "https://modrinth.com/"
    override val order: Int = 2

    private val json = Json { ignoreUnknownKeys = true }
    private val logger = logger()
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 30_000
        }
        defaultRequest {
            url("https://api.modrinth.com/v2/")
            header("User-Agent", ClientIdentity.userAgent)
            header("X-Client-Info", ClientIdentity.clientInfoHeader)
            accept(ContentType.Application.Json)
        }
    }

    override suspend fun getCategories(context: ModBrowserContext): List<ModCategory> = ModrinthCategories.entries
        .filterNot { it in loaderCategories }
        .map { category ->
            ModCategory(
                id = category.name.lowercase().replace('_', '-'),
                displayName = category.name.lowercase()
                    .split('_')
                    .joinToString(" ") { token -> token.replaceFirstChar(Char::uppercase) }
            )
        }

    override suspend fun search(context: ModBrowserContext, query: ModSearchQuery): ModSearchPage {
        logger.info(
            "Modrinth search: mc={} loader={} text='{}' include={} exclude={} offset={} limit={}",
            context.minecraftVersion,
            context.modLoaderId,
            query.text,
            query.includedCategories,
            query.excludedCategories,
            query.offset,
            query.limit
        )
        val response = client.get("search") {
            parameter("query", query.text)
            parameter("facets", facetsFor(context, query.includedCategories))
            parameter("index", Search.Sorting.RELEVANCE.name.lowercase())
            parameter("offset", query.offset)
            parameter("limit", query.limit)
        }.body<Search.Ok>()

        return ModSearchPage(
            results = response.hits
                .filterNot(::isPluginLike)
                .filterNot { hit ->
                    val categoryIds = (hit.categories + hit.display_categories).map(::normalizeCategoryId).toSet()
                    query.excludedCategories.any { it in categoryIds }
                }
                .map { hit ->
                ModSearchResult(
                    id = hit.project_id,
                    title = hit.title,
                    summary = hit.description,
                    author = hit.author,
                    downloads = hit.downloads.toLong(),
                    categories = filterCategories(hit.display_categories.ifEmpty { hit.categories }),
                    versions = hit.versions,
                    iconUrl = hit.icon_url
                )
            },
            total = response.total_hits
        )
    }

    override suspend fun details(context: ModBrowserContext, projectId: String): ModDetails {
        logger.debug("Modrinth details: projectId={}", projectId)
        val project = client.get("project/$projectId").body<ModrinthProject>()
        return ModDetails(
            id = project.id,
            title = project.title,
            summary = project.summary,
            description = project.description,
            downloads = project.downloads.toLong(),
            categories = filterCategories(project.categories + project.additionalCategories),
            website = project.sourceUrl ?: project.issuesUrl ?: "$webpage/mod/${project.slug}",
            iconUrl = project.iconUrl
        )
    }

    override suspend fun versions(context: ModBrowserContext, projectId: String): List<ModVersionOption> {
        logger.debug("Modrinth versions: projectId={} mc={} loader={}", projectId, context.minecraftVersion, context.modLoaderId)
        return fetchVersions(context, projectId).map { version ->
            ModVersionOption(
                id = version.id,
                label = version.name.ifBlank { version.version_number },
                gameVersions = version.game_versions,
                loaders = version.loaders,
                featured = version.featured,
                downloads = version.downloads.toLong(),
                dependencies = version.dependencies.mapNotNull { dependency ->
                    dependency.project_id?.let { projectId ->
                        when (dependency.dependency_type) {
                            io.github.tritium_launcher.launcher.core.source.modrinth.api.DependencyType.REQUIRED ->
                                ModDependencyRef(projectId = projectId, required = true, incompatible = false)
                            io.github.tritium_launcher.launcher.core.source.modrinth.api.DependencyType.INCOMPATIBLE ->
                                ModDependencyRef(projectId = projectId, required = false, incompatible = true)
                            else -> null
                        }
                    }
                }.distinctBy { "${it.projectId}:${it.required}:${it.incompatible}" },
                releaseType = version.version_type,
            )
        }
    }

    override suspend fun resolveInstall(context: ModBrowserContext, projectId: String, versionId: String): ModInstallPlan {
        val version = fetchVersions(context, projectId).firstOrNull { it.id == versionId }
            ?: error("Selected version '$versionId' is no longer available")

        val file = version.files.firstOrNull { it.primary } ?: version.files.firstOrNull()
            ?: error("No downloadable file available for ${version.name}")

        logger.info("Modrinth install resolve: projectId={} versionId={} file={}", projectId, versionId, file.filename)
        return ModInstallPlan(
            projectId = projectId,
            versionId = version.id,
            versionLabel = version.name.ifBlank { version.version_number },
            fileName = file.filename,
            downloadUrl = file.url,
            releaseType = version.version_type,
            fileHash = file.hashes.sha1,
        )
    }

    private suspend fun fetchVersions(context: ModBrowserContext, projectId: String): List<ModrinthVersion> {
        val versions = client.get("project/$projectId/version") {
            context.minecraftVersion?.takeIf { it.isNotBlank() }?.let { version ->
                parameter("game_versions", json.encodeToString(ListSerializer(String.serializer()), listOf(version)))
            }
            context.modLoaderId?.takeIf { it.isNotBlank() }?.let { loader ->
                parameter("loaders", json.encodeToString(ListSerializer(String.serializer()), listOf(loader)))
            }
        }.body<List<ModrinthVersion>>()
        return versions
            .sortedWith(compareByDescending<ModrinthVersion> { it.featured }.thenByDescending { releaseRank(it.version_type) })
    }

    private fun facetsFor(context: ModBrowserContext, categories: Set<String>): String {
        val groups = mutableListOf<JsonArray>()
        groups += buildJsonArray { add(JsonPrimitive("project_type:mod")) }
        context.minecraftVersion?.takeIf { it.isNotBlank() }?.let { version ->
            groups += buildJsonArray { add(JsonPrimitive("versions:$version")) }
        }
        context.modLoaderId?.takeIf { it.isNotBlank() }?.let { loader ->
            groups += buildJsonArray { add(JsonPrimitive("categories:$loader")) }
        }
        if (categories.isNotEmpty()) {
            groups += buildJsonArray {
                categories.sorted().forEach { category ->
                    add(JsonPrimitive("categories:$category"))
                }
            }
        }
        return Json.encodeToString(ListSerializer(JsonArray.serializer()), groups)
    }

    private fun releaseRank(type: ReleaseType): Int = when (type) {
        ReleaseType.RELEASE -> 3
        ReleaseType.BETA -> 2
        ReleaseType.ALPHA -> 1
    }

    private fun filterCategories(values: List<String>): List<String> =
        values.filterNot { normalizeCategoryId(it) in excludedCategoryIds }

    private fun isPluginLike(hit: Search.Hit): Boolean {
        if (!hit.project_type.equals("mod", ignoreCase = true)) return true
        val allCategories = hit.categories + hit.display_categories
        return allCategories.any { normalizeCategoryId(it) in pluginCategoryIds }
    }

    private fun normalizeCategoryId(value: String): String = value
        .trim()
        .replace('_', '-')
        .lowercase()

    private companion object {
        val loaderCategories = setOf(
            ModrinthCategories.FABRIC,
            ModrinthCategories.NEOFORGE,
            ModrinthCategories.QUILT,
            ModrinthCategories.LITELOADER,
            ModrinthCategories.RISUGAMIS,
            ModrinthCategories.RIFT,
            ModrinthCategories.FORGE
        )
        val loaderCategoryIds = loaderCategories.map { it.name }.toSet()
        val pluginCategoryIds = setOf(
            "plugin", "plugins", "bukkit", "spigot", "paper", "purpur", "folia", "velocity", "waterfall",
            "bungeecord", "sponge", "proxy"
        )
        val excludedCategoryIds = loaderCategoryIds.map { it.lowercase().replace('_', '-') }.toSet() + pluginCategoryIds
    }
}
