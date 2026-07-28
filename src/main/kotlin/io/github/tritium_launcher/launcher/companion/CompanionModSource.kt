/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.companion

import io.github.tritium_launcher.api.modpack.*
import io.github.tritium_launcher.launcher.core.HttpClientProvider
import io.github.tritium_launcher.launcher.ui.theme.TIcons
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.qt.gui.QPixmap

class CompanionModSource : ModSource() {
    override val id = CompanionModProvider.COMPANION_SOURCE
    override val displayName = "Tritium Companion"
    override val icon: QPixmap = TIcons.Tritium
    override val webpage = "https://github.com/Tritium-Launcher/Tritium-Companion"
    override val order = 100
    override val descriptionFormat = DescriptionFormat.MARKDOWN

    private val httpClient = HttpClientProvider.client()

    override suspend fun search(context: ModBrowserContext, query: ModSearchQuery): ModSearchPage =
        ModSearchPage(emptyList(), 0)

    override suspend fun details(context: ModBrowserContext, projectId: String): ModDetails {
        val meta = CompanionModProvider.fetchProjectMeta()
        val description = if (meta?.descriptionUrl != null) {
            try {
                httpClient.get(meta.descriptionUrl).bodyAsText()
            } catch (_: Exception) {
                meta.description
            }
        } else {
            meta?.description ?: "No description available."
        }
        return ModDetails(
            id = projectId,
            title = meta?.title ?: "Tritium Companion Mod",
            summary = meta?.summary ?: "Provides helpful utilities for ModPack Developers",
            description = description,
            author = meta?.author,
            iconUrl = meta?.iconUrl
                ?: "https://raw.githubusercontent.com/Tritium-Launcher/Tritium-Companion/gh-pages/icon.png",
            website = meta?.website ?: webpage,
        )
    }

    override suspend fun versions(context: ModBrowserContext, projectId: String): List<ModVersionOption> {
        val mcVersion = context.minecraftVersion ?: return emptyList()
        val loaderId = context.modLoaderId ?: return emptyList()
        return CompanionModProvider.allVersions(mcVersion, loaderId).map { entry ->
            ModVersionOption(
                id = entry.modVersion,
                label = entry.displayName ?: entry.modVersion,
                fileHash = entry.jars[loaderId]?.sha256,
                releaseType = try {
                    entry.releaseType?.let { ReleaseType.valueOf(it.uppercase()) }
                } catch (_: IllegalArgumentException) {
                    null
                },
            )
        }
    }

    override suspend fun resolveInstall(
        context: ModBrowserContext,
        projectId: String,
        versionId: String
    ): ModInstallPlan {
        val mcVersion = context.minecraftVersion ?: error("No MC version in context")
        val loaderId = context.modLoaderId ?: error("No loader in context")
        val entry = CompanionModProvider.resolveEntry(mcVersion, loaderId, versionId)
            ?: error("Companion mod version $versionId not found for $mcVersion/$loaderId")
        val jar = entry.jars[loaderId] ?: error("No JAR for loader $loaderId")
        return ModInstallPlan(
            projectId = projectId,
            versionId = versionId,
            versionLabel = entry.displayName ?: versionId,
            fileName = jar.fileName,
            downloadUrl = jar.url,
            fileHash = jar.sha256,
        )
    }
}
