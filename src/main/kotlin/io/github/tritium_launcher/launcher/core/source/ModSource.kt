package io.github.tritium_launcher.launcher.core.source

import io.github.tritium_launcher.launcher.core.project.ProjectBase
import io.github.tritium_launcher.launcher.registry.Registrable
import io.qt.gui.QPixmap

enum class DescriptionFormat { MARKDOWN, HTML }

/**
 * Mod Sources are web APIs that provide Mods and other content to users. Examples are [CurseForge] and [Modrinth].
 */
abstract class ModSource: Registrable {
    abstract override val id: String
    abstract val displayName: String
    abstract val icon: QPixmap
    abstract val webpage: String
    abstract val order: Int
    open val descriptionFormat: DescriptionFormat = DescriptionFormat.MARKDOWN

    open fun support(context: ModBrowserContext): ModSourceSupport = ModSourceSupport()

    open suspend fun getCategories(context: ModBrowserContext): List<ModCategory> = emptyList()

    abstract suspend fun search(context: ModBrowserContext, query: ModSearchQuery): ModSearchPage

    abstract suspend fun details(context: ModBrowserContext, projectId: String): ModDetails

    abstract suspend fun versions(context: ModBrowserContext, projectId: String): List<ModVersionOption>

    abstract suspend fun resolveInstall(context: ModBrowserContext, projectId: String, versionId: String): ModInstallPlan

    /**
     * Optionally resolve a project ID from a file hash.
     * Sources that support hash-based lookup should override this.
     */
    open suspend fun resolveProjectInfoByHash(hash: String): HashProjectInfo? = null

    open suspend fun resolveProjectInfoByFingerprint(fingerprint: Long): HashProjectInfo? = null

    /**
     * Compute a file fingerprint from the raw jar bytes.
     * Sources that use file fingerprint algorithms
     * should override this to compute their fingerprint.
     * Return `null` if not supported.
     */
    open fun computeFileFingerprint(bytes: ByteArray): Long? = null

    /**
     * Batch resolve project info for multiple fingerprints.
     * Sources that support batch fingerprint lookup should override this.
     * Returns a map of fingerprint -> project info; unmatched fingerprints are absent.
     */
    open suspend fun resolveProjectInfosByFingerprints(fingerprints: List<Long>): Map<Long, HashProjectInfo> = emptyMap()

    /**
     * Optionally resolve a project from the raw jar file contents.
     * Sources that use file fingerprint algorithms
     * should override this to compute their fingerprint.
     */
    open suspend fun resolveProjectInfoByJarContents(bytes: ByteArray): HashProjectInfo? = null

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
    val displayName: String,
    val iconUrl: String? = null
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
    val iconUrl: String? = null,
    val slug: String? = null,
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
    val fileName: String? = null,
    val fileHash: String? = null,
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
    val downloadUrl: String?,
    val releaseType: ReleaseType? = null,
    val fileHash: String? = null,
)

data class HashProjectInfo(
    val projectId: String,
    val projectTitle: String,
    val versionId: String? = null,
)

data class ResolvedFile(
    val fileName: String,
    val downloadUrl: String,
    val fileHash: String,
)

interface HashFallbackProvider {
    val priority: Int
    suspend fun resolveByHash(context: ModBrowserContext, hash: String): ResolvedFile?
}

data class ResolvedInstall(
    val plan: ModInstallPlan,
    val downloadUrl: String?,
    val fileName: String,
    val requiresManualDownload: Boolean,
)

suspend fun resolveInstallDownload(
    context: ModBrowserContext,
    source: ModSource,
    projectId: String,
    versionId: String,
    fallbacks: List<HashFallbackProvider> = emptyList(),
): ResolvedInstall {
    val plan = source.resolveInstall(context, projectId, versionId)
    if (plan.downloadUrl != null) {
        return ResolvedInstall(plan, plan.downloadUrl, plan.fileName, requiresManualDownload = false)
    }
    if (plan.fileHash != null) {
        for (fallback in fallbacks.sortedBy { it.priority }) {
            val resolved = runCatching { fallback.resolveByHash(context, plan.fileHash) }.getOrNull()
            if (resolved != null) {
                return ResolvedInstall(plan, resolved.downloadUrl, resolved.fileName, requiresManualDownload = false)
            }
        }
    }
    return ResolvedInstall(plan, null, plan.fileName, requiresManualDownload = true)
}
