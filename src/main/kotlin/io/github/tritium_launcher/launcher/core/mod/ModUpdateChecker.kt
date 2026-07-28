/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.core.mod

import io.github.tritium_launcher.api.BuiltinRegistries
import io.github.tritium_launcher.api.core.project.ProjectBase
import io.github.tritium_launcher.api.logger
import io.github.tritium_launcher.api.modpack.ModBrowserContext
import io.github.tritium_launcher.api.modpack.ModSource
import io.github.tritium_launcher.api.modpack.ModVersionOption
import io.github.tritium_launcher.api.modpack.ModpackMeta
import io.github.tritium_launcher.launcher.companion.CompanionModProvider
import io.github.tritium_launcher.launcher.core.project.Project
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
        val context = resolveContext(project)
        val source = context?.let { resolveSource(it) }

        val installedMods = mods ?: withContext(Dispatchers.IO) {
            if (source != null) {
                ModDatabase(project.projectDir).use { db -> db.getBySource(source.id) }
            } else {
                emptyList()
            }
        }

        val results = coroutineScope {
            installedMods.map { mod ->
                async {
                    when {
                        mod.source == CompanionModProvider.COMPANION_SOURCE ->
                            CompanionModProvider.checkUpdate(mod)?.let { mod.projectId to it }
                        source != null && mod.source == source.id ->
                            checkMod(project, mod)?.let { mod.projectId to it }
                        else -> null
                    }
                }
            }.awaitAll().filterNotNull().toMap()
        }

        return results
    }
}
