package io.github.tritium_launcher.launcher.core.source

import io.github.tritium_launcher.launcher.registry.Registrable
import io.github.tritium_launcher.launcher.ui.theme.TIcons
import io.qt.gui.QPixmap

class CurseForge : ModSource(), Registrable {
    override val id: String = "curseforge"
    override val displayName: String = "CurseForge"
    override val icon: QPixmap = TIcons.CurseForge
    override val webpage: String = "https://www.curseforge.com/"
    override val order: Int = 1

    override fun support(context: ModBrowserContext): ModSourceSupport = ModSourceSupport(
        available = false,
        message = "CurseForge browsing is not wired yet. The browser currently supports Modrinth-backed projects."
    )

    override suspend fun search(context: ModBrowserContext, query: ModSearchQuery): ModSearchPage {
        error("CurseForge browsing is not available yet")
    }

    override suspend fun details(context: ModBrowserContext, projectId: String): ModDetails {
        error("CurseForge browsing is not available yet")
    }

    override suspend fun versions(context: ModBrowserContext, projectId: String): List<ModVersionOption> {
        error("CurseForge browsing is not available yet")
    }

    override suspend fun resolveInstall(context: ModBrowserContext, projectId: String, versionId: String): ModInstallPlan {
        error("CurseForge browsing is not available yet")
    }
}
