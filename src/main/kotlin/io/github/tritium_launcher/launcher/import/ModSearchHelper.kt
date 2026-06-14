package io.github.tritium_launcher.launcher.import

import io.github.tritium_launcher.launcher.core.source.ModBrowserContext
import io.github.tritium_launcher.launcher.core.source.ModSearchQuery
import io.github.tritium_launcher.launcher.core.source.ModSource
import io.github.tritium_launcher.launcher.extension.core.BuiltinRegistries
import io.github.tritium_launcher.launcher.logger
import kotlinx.coroutines.CancellationException

private val searchLog = logger("ModSearchHelper")

data class SourceMatch(
    val projectId: String,
    val iconUrl: String?,
    val isFileMatch: Boolean = true,
    val status: String = "Available"
)

private val loaderNameToId = mapOf(
    "Fabric" to "fabric",
    "NeoForge" to "neoforge",
    "Forge" to "forge",
    "Quilt" to "quilt"
)

fun normalize(str: String): String =
    str.lowercase().replace(Regex("[^a-z0-9]"), "")

fun buildSearchQueries(mod: ImportableMod): List<String> {
    // Skip mods that exactly match a registered ModSource name
    val sourceNames = BuiltinRegistries.ModSource.all().flatMap { listOf(it.id, it.displayName) }.map { it.lowercase() }.toSet()
    if (mod.modId.lowercase() in sourceNames || mod.displayName.lowercase() in sourceNames) {
        return emptyList()
    }

    val loaderIds = loaderNameToId.values.map { it.lowercase() }.toSet()
    val skipModId = mod.modId.lowercase() in loaderIds

    val result = mutableListOf<String>()
    if (!skipModId) result.add(mod.modId)
    if (mod.displayName != mod.modId || skipModId) result.add(mod.displayName)
    val fileName = mod.fileName.removeSuffix(".jar")
    if ((fileName != mod.modId || skipModId) && fileName != mod.displayName) result.add(fileName)

    val camel = Regex("([a-z])([A-Z])")
    val camelDigit = Regex("([a-zA-Z])([0-9])")
    for (s in (if (skipModId) listOf(mod.displayName, fileName) else listOf(mod.modId, mod.displayName, fileName)).distinct()) {
        val split = s.replace(camel, "$1 $2").replace(camelDigit, "$1 $2")
        if (split != s) result.add(split)
    }

    val stripped = mod.displayName.replace(Regex("\\s*\\(.*?\\)\\s*$"), "").trim()
    if (stripped != mod.displayName && stripped.isNotBlank()) result.add(stripped)

    val spacedModId = mod.modId.replace(Regex("[_\\-]"), " ")
    if (spacedModId != mod.modId) result.add(spacedModId)
    val spacedDisplayName = mod.displayName.replace(Regex("[_\\-]"), " ")
    if (spacedDisplayName != mod.displayName && spacedDisplayName != spacedModId && spacedDisplayName !in result) result.add(spacedDisplayName)

    val strippedVersion = mod.modId
        .replace(Regex("[-_][\\d.]+[-_].*$"), "")
        .replace(Regex("[-_][\\d.]+$"), "")
    if (strippedVersion != mod.modId && strippedVersion.isNotBlank()) {
        result.add(strippedVersion)
        val spacedVersion = strippedVersion.replace(Regex("[_\\-]"), " ")
        if (spacedVersion != strippedVersion) result.add(spacedVersion)
    }

    val fileNameCamel = fileName.replace(camel, "$1 $2").replace(camelDigit, "$1 $2")
    val strippedFileName = fileNameCamel
        .replace(Regex("(?<=\\S)[-_.]+\\w+[-_][\\d.]+.*$"), "").trim()
        .replace(Regex("(?<=\\S)[-_.]+[\\d.]+.*$"), "").trim()
    if (strippedFileName != fileName && strippedFileName != fileNameCamel && strippedFileName.isNotBlank()
        && strippedFileName != mod.modId && strippedFileName != mod.displayName) {
        result.add(strippedFileName)
    }

    val base = (if (strippedVersion != mod.modId) strippedVersion else mod.modId).lowercase()
    val loaded = mutableSetOf<String>()
    fun insertIfNew(s: String) { if (s.isNotBlank() && s !in loaded) { loaded.add(s); result.add(s) } }
    val knownParts = listOf("neoforge", "forge", "fabric", "quilt", "api", "core", "lib", "util", "mod", "config", "for")
    for (part in knownParts.sortedByDescending { it.length }) {
        var idx = base.indexOf(part)
        while (idx > 0) {
            val candidate = base.substring(0, idx) + " " + base.substring(idx)
            insertIfNew(candidate)
            val prefix = base.substring(0, idx)
            for (innerPart in knownParts.sortedByDescending { it.length }) {
                val innerIdx = prefix.indexOf(innerPart)
                if (innerIdx > 0) {
                    insertIfNew(prefix.substring(0, innerIdx) + " " + prefix.substring(innerIdx) + " " + base.substring(
                        idx
                    ))
                }
            }
            idx = base.indexOf(part, idx + 1)
        }
    }

    return result.distinct().filter { it.isNotBlank() }
}

suspend fun findModOnSource(
    mod: ImportableMod,
    source: ModSource,
    context: ModBrowserContext
): SourceMatch? {
    // Strategy 0: File fingerprint lookup
    if (mod.fileFingerprint != null) {
        try {
            val fpInfo = source.resolveProjectInfoByFingerprint(mod.fileFingerprint!!)
            if (fpInfo != null) {
                return SourceMatch(fpInfo.projectId, iconUrl = null, isFileMatch = true, status = "Available")
            }
        } catch (_: Exception) { }
    }

    // Strategy 1: Hash lookup
    if (mod.sha1Hash != null) {
        try {
            val hashInfo = source.resolveProjectInfoByHash(mod.sha1Hash!!)
            if (hashInfo != null) {
                return SourceMatch(hashInfo.projectId, iconUrl = null, status = "Matched by file hash")
            }
        } catch (_: Exception) { }
    }

    // Strategy 2: Search by multiple query variations
    val queries = buildSearchQueries(mod)
    var bestNameMatch: SourceMatch? = null

    for (narrow in listOf(true, false)) {
        val searchContext = if (narrow) context else context.copy(minecraftVersion = null, modLoaderId = null)
        for (q in queries) {
            try {
                val page = source.search(searchContext, ModSearchQuery(text = q, limit = 30))
                if (page.results.isEmpty()) {
                    searchLog.warn("[{}] Search '{}': EMPTY", mod.modId, q)
                    continue
                }
                var matched = false
                for (r in page.results) {
                    val normTitle = normalize(r.title)
                    val normModId = normalize(mod.modId)
                    val normDisplayName = normalize(mod.displayName)
                    val normQ = normalize(q)
                    val normSlug = r.slug?.let { normalize(it) }
                    val isExact = r.id.equals(mod.modId, ignoreCase = true) ||
                        normSlug != null && (normSlug == normModId || normSlug == normDisplayName || normSlug == normQ) ||
                        normTitle == normModId || normTitle == normDisplayName || normTitle == normQ
                    val isContains = !isExact && (
                        normTitle.contains(normDisplayName) ||
                        normDisplayName.contains(normTitle) ||
                        normTitle.contains(normQ) ||
                        normQ.contains(normTitle)
                    )
                    if (isExact || isContains) {
                        matched = true
                        val verified = verifyVersionOnSource(source, searchContext, context, r.id, mod)
                        if (verified) {
                            return SourceMatch(r.id, r.iconUrl, isFileMatch = true, status = "Available")
                        }
                        if (bestNameMatch == null || isExact) {
                            bestNameMatch = SourceMatch(r.id, r.iconUrl, isFileMatch = false, status = "Available (name match)")
                        }
                    }
                }
                if (!matched) {
                    searchLog.warn("[{}] Search '{}': {} results, no candidate matched modId='{}' displayName='{}'. First 5: [{}]",
                        mod.modId, q, page.results.size, mod.modId, mod.displayName,
                        page.results.take(5).joinToString { it.title })
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                searchLog.warn("[{}] Search '{}' failed: {}", mod.modId, q, t.message)
            }
        }
    }

    return bestNameMatch
}

suspend fun verifyVersionOnSource(
    source: ModSource,
    fetchContext: ModBrowserContext,
    matchContext: ModBrowserContext,
    projectId: String,
    mod: ImportableMod
): Boolean {
    return try {
        val versions = source.versions(fetchContext, projectId)
        val mcVersion = matchContext.minecraftVersion
        val loaderId = matchContext.modLoaderId

        // Strategy 1: Match by MC version + loader
        if (mcVersion != null || loaderId != null) {
            val matched = versions.any { v ->
                val mcMatch = mcVersion == null || v.gameVersions.any { gv ->
                    gv == mcVersion || gv.startsWith("$mcVersion.")
                }
                val loaderMatch = loaderId == null ||
                    v.loaders.any { it.equals(loaderId, ignoreCase = true) } ||
                    (v.loaders.any { it.equals("forge", ignoreCase = true) } && loaderId.equals("neoforge", ignoreCase = true)) ||
                    (v.loaders.any { it.equals("fabric", ignoreCase = true) } && loaderId.equals("quilt", ignoreCase = true)) ||
                    (v.loaders.any { it.equals("quilt", ignoreCase = true) } && loaderId.equals("fabric", ignoreCase = true))
                mcMatch && loaderMatch
            }
            if (matched) return true
        }

        // Strategy 2: Match by SHA-1 hash
        if (mod.sha1Hash != null) {
            val byHash = versions.any { v -> v.fileHash?.equals(mod.sha1Hash, ignoreCase = true) == true }
            if (byHash) {
                searchLog.warn("verifyVersionOnSource: projectId={} matched by SHA-1 hash", projectId)
                return true
            }
        }

        // Strategy 3: Fallback — match by jar filename
        val jarName = mod.fileName.removeSuffix(".jar").lowercase()
        val byFilename = versions.any { v ->
            v.fileName.equals(mod.fileName, ignoreCase = true) ||
            v.fileName?.lowercase()?.contains(jarName) == true ||
            v.label.lowercase().contains(jarName)
        }
        if (byFilename) {
            searchLog.warn("verifyVersionOnSource: projectId={} matched by filename '{}'", projectId, mod.fileName)
            return true
        }

        searchLog.warn("verifyVersionOnSource: projectId={} mc={} loader={} versions={} no match. jar='{}'", projectId, mcVersion, loaderId, versions.size, mod.fileName)
        if (versions.isNotEmpty()) {
            searchLog.warn("  First 5 version details:")
            versions.take(5).forEachIndexed { i, v ->
                val mcOk = mcVersion == null || v.gameVersions.any { gv -> gv == mcVersion || gv.startsWith("$mcVersion.") }
                val loaderOk = loaderId == null || v.loaders.any { it.equals(loaderId, ignoreCase = true) } ||
                    (v.loaders.any { it.equals("forge", ignoreCase = true) } && loaderId.equals("neoforge", ignoreCase = true))
                val fnOk = v.fileName.equals(mod.fileName, ignoreCase = true) ||
                    v.fileName?.lowercase()?.contains(jarName) == true ||
                    v.label.lowercase().contains(jarName)
                val hashOk = mod.sha1Hash != null && v.fileHash?.equals(mod.sha1Hash, ignoreCase = true) == true
                searchLog.warn("    [{}] gv={} loaders={} label='{}' fileName='{}' hash='{}' mcOk={} loaderOk={} fnOk={} hashOk={}",
                    i, v.gameVersions, v.loaders, v.label, v.fileName, v.fileHash, mcOk, loaderOk, fnOk, hashOk)
            }
        }
        false
    } catch (e: Exception) {
        searchLog.warn("verifyVersionOnSource: projectId={} mc={} loader={} failed: {}", projectId, matchContext.minecraftVersion, matchContext.modLoaderId, e.message)
        false
    }
}
