package io.github.tritium_launcher.launcher.core.source

import io.github.tritium_launcher.launcher.core.project.ProjectBase
import io.github.tritium_launcher.launcher.registry.Registrable
import io.qt.gui.QPixmap

/**
 * Mod Sources are web APIs that provide Mods and other content to users. Examples are [CurseForge] and [Modrinth].
 */
abstract class ModSource: Registrable {
    abstract override val id: String
    abstract val displayName: String
    abstract val icon: QPixmap
    abstract val webpage: String
    abstract val order: Int

    open fun support(context: ModBrowserContext): ModSourceSupport = ModSourceSupport()

    open suspend fun getCategories(context: ModBrowserContext): List<ModCategory> = emptyList()

    abstract suspend fun search(context: ModBrowserContext, query: ModSearchQuery): ModSearchPage

    abstract suspend fun details(context: ModBrowserContext, projectId: String): ModDetails

    abstract suspend fun versions(context: ModBrowserContext, projectId: String): List<ModVersionOption>

    abstract suspend fun resolveInstall(context: ModBrowserContext, projectId: String, versionId: String): ModInstallPlan

    override fun toString(): String = id
}

data class ModBrowserContext(
    val project: ProjectBase,
    val minecraftVersion: String?,
    val modLoaderId: String?
)

data class ModSourceSupport(
    val available: Boolean = true,
    val message: String? = null
)

data class ModCategory(
    val id: String,
    val displayName: String
)

data class ModSearchQuery(
    val text: String,
    val includedCategories: Set<String> = emptySet(),
    val excludedCategories: Set<String> = emptySet(),
    val offset: Int = 0,
    val limit: Int = 25
)

data class ModSearchResult(
    val id: String,
    val title: String,
    val summary: String,
    val author: String? = null,
    val downloads: Long? = null,
    val categories: List<String> = emptyList(),
    val versions: List<String> = emptyList(),
    val iconUrl: String? = null
)

data class ModSearchPage(
    val results: List<ModSearchResult>,
    val total: Int
)

data class ModDetails(
    val id: String,
    val title: String,
    val summary: String,
    val description: String,
    val author: String? = null,
    val downloads: Long? = null,
    val categories: List<String> = emptyList(),
    val website: String? = null,
    val latestVersion: String? = null,
    val iconUrl: String? = null
)

data class ModVersionOption(
    val id: String,
    val label: String,
    val gameVersions: List<String> = emptyList(),
    val loaders: List<String> = emptyList(),
    val featured: Boolean = false,
    val downloads: Long? = null,
    val dependencies: List<ModDependencyRef> = emptyList(),
    val releaseType: ReleaseType? = null,
)

data class ModDependencyRef(
    val projectId: String,
    val required: Boolean = true,
    val incompatible: Boolean = false
)

data class ModInstallPlan(
    val projectId: String,
    val versionId: String,
    val versionLabel: String,
    val fileName: String,
    val downloadUrl: String,
    val releaseType: ReleaseType? = null,
    val fileHash: String? = null,
)
