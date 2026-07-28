/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.companion

import io.github.tritium_launcher.api.core.project.ProjectBase
import io.github.tritium_launcher.api.io.VPath
import io.github.tritium_launcher.api.logger
import io.github.tritium_launcher.api.modpack.ModpackMeta
import io.github.tritium_launcher.launcher.core.project.Project
import io.github.tritium_launcher.launcher.ui.theme.TIcons
import io.qt.widgets.QApplication
import io.qt.widgets.QMessageBox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant

object CompanionInfoDialog {
    private const val MARKER_FILE = ".companion-info-shown"
    private val logger = logger()

    suspend fun showIfNeeded(project: ProjectBase) {
        val meta = (project as? Project<*>)?.typedMeta as? ModpackMeta ?: return

        if (CompanionModProvider.jarExists(project.projectDir)) return
        if (markerExists(project)) return

        val shouldInstall = withContext(Dispatchers.Main) {
            val parent = QApplication.activeWindow()
            val box = QMessageBox(parent)
            box.windowTitle = "Tritium Companion Mod"
            box.text = "<b>Companion mod not found</b>"
            box.informativeText = buildString {
                appendLine("Consider adding Tritium's Companion Mod:")
                appendLine()
                appendLine("\u2022 WebSocket Connection \u2014 Provides utilities to reload, run commands and more from Tritium.")
                appendLine("\u2022 Registry Exporting \u2014 Can export all Registry Objects from the game for features such as the Item Browser.")
                appendLine("\u2022 KubeJS Integration \u2014 Server Script reloading, Type dumps and other helpers.")
                appendLine()
                append("This only appears once, you can always install at a later time")
            }
            box.iconPixmap = TIcons.Companion.scaled(64,64)
            val installButton = box.addButton("Install Companion Mod", QMessageBox.ButtonRole.AcceptRole)
            box.addButton("Skip", QMessageBox.ButtonRole.DestructiveRole)
            box.setDefaultButton(installButton)
            box.exec()
            box.clickedButton() == installButton
        }

        writeMarker(project)

        if (shouldInstall) {
            withContext(Dispatchers.IO) {
                CompanionModProvider.installIfNeeded(
                    projectRoot = project.projectDir,
                    mcVersion = meta.minecraftVersion,
                    loaderId = meta.loader
                )
            }
        }
    }

    private fun markerPath(project: ProjectBase): VPath =
        project.projectDir.resolve(".tr").resolve(MARKER_FILE)

    private fun markerExists(project: ProjectBase): Boolean =
        markerPath(project).exists()

    private fun writeMarker(project: ProjectBase) {
        try {
            markerPath(project).writeTextAtomic(
                Instant.now().toString()
            )
        } catch (t: Throwable) {
            logger.warn("Failed to write companion info marker", t)
        }
    }
}
