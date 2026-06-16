package io.github.tritium_launcher.launcher.core.mod

import io.github.tritium_launcher.launcher.core.project.ModpackMeta
import io.github.tritium_launcher.launcher.core.project.Project
import io.github.tritium_launcher.launcher.core.project.ProjectBase
import io.github.tritium_launcher.launcher.core.source.ModBrowserContext
import io.github.tritium_launcher.launcher.core.source.ModSource
import io.github.tritium_launcher.launcher.core.source.ModVersionOption
import io.github.tritium_launcher.launcher.extension.core.BuiltinRegistries
import io.github.tritium_launcher.launcher.logger
import kotlinx.coroutines.*

object ModUpdateChecker {
    private val logger = logger()

    private fun resolveContext(project: ProjectBase): ModBrowserContext? {
        val meta = (project as? Project<*>)?.typedMeta as? ModpackMeta ?: return null
        return ModBrowserContext(
            project = project,
            minecraftVersion = meta.minecraftVersion,
            modLoaderId = meta.loader
        )
    }

    private fun resolveSource(context: ModBrowserContext): ModSource? {
        val sourceId = (context.project as? Project<*>)?.typedMeta as? ModpackMeta ?: return null
        return BuiltinRegistries.ModSource.all().find { it.id == sourceId.source }
    }

    suspend fun checkMod(
        project: ProjectBase,
        mod: InstalledMod
    ): ModVersionOption? {
        val context = resolveContext(project) ?: return null
        val source = resolveSource(context) ?: return null
        if (source.id != mod.source) return null
        if (mod.versionId.isBlank()) return null

        return try {
            val versions = source.versions(context, mod.projectId)
            val latest = versions.firstOrNull() ?: return null
            if (latest.id == mod.versionId) return null

            ModDatabase(project.projectDir).use { db ->
                if (db.isVersionSkipped(mod.projectId, latest.id)) return null
            }

            latest
        } catch (t: Throwable) {
            logger.warn("Failed to check update for '{}' from '{}'", mod.displayName, source.id, t)
            null
        }
    }

    suspend fun checkAll(
        project: ProjectBase,
        mods: List<InstalledMod>? = null
    ): Map<String, ModVersionOption> {
        val context = resolveContext(project) ?: return emptyMap()
        val source = resolveSource(context) ?: return emptyMap()

        val installedMods = mods ?: withContext(Dispatchers.IO) {
            ModDatabase(project.projectDir).use { db -> db.getBySource(source.id) }
        }

        val results = coroutineScope {
            installedMods
                .map { mod ->
                    async {
                        val update = checkMod(project, mod)
                        if (update != null) mod.projectId to update else null
                    }
                }
                .awaitAll()
                .filterNotNull()
                .toMap()
        }

        return results
    }
}
