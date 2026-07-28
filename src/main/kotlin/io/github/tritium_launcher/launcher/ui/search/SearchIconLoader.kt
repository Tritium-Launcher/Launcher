/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.ui.search

import io.github.tritium_launcher.api.core.project.ProjectBase
import io.github.tritium_launcher.api.io.VPath
import io.github.tritium_launcher.launcher.registrydb.RegistryDatabase
import io.github.tritium_launcher.launcher.registrydb.RegistryDbStatus
import io.github.tritium_launcher.launcher.ui.project.sidebar.AnimatedItemMngr
import io.github.tritium_launcher.launcher.ui.theme.TColors
import io.qt.core.Qt
import io.qt.gui.QPixmap
import io.qt.widgets.QLabel

object SearchIconLoader {

    fun subtextHeader(text: String): QLabel = QLabel(text).apply {
        styleSheet = "font-size: 10px; font-weight: 700; color: ${TColors.Subtext};" +
                " text-transform: uppercase; margin-top: 8px;"
    }

    fun loadItemIcon(
        project: ProjectBase,
        itemId: String,
        size: Int = 32,
        tintColor: Long? = null
    ): QPixmap? {
        val snapshotDir = snapshotDir(project) ?: return null
        val texPath = runCatching { RegistryDatabase.itemTexturePath(project, itemId) }.getOrNull()
        return loadItemIcon(project, itemId, texPath, snapshotDir, size, tintColor)
    }

    fun loadItemIcon(
        project: ProjectBase,
        itemId: String,
        texPath: String?,
        snapshotDir: VPath,
        size: Int,
        tintColor: Long? = null
    ): QPixmap? {
        val nsPath = itemId.split(":")
        if (nsPath.size != 2) {
            if (texPath != null) {
                val px = QPixmap(snapshotDir.resolve(texPath).toAbsolute().expandHome().toString())
                if (!px.isNull) {
                    val display = if (tintColor != null) applyTint(px, tintColor) else px
                    return display.scaled(size, size, Qt.AspectRatioMode.KeepAspectRatio, Qt.TransformationMode.FastTransformation)
                }
            }
            return null
        }
        val ns = nsPath[0]
        val path = nsPath[1]
        val candidates = buildList {
            add("icons/$ns/$path.png")
            add("icons/${ns}_${path.replace('/', '_')}.png")
            texPath?.let { add(it) }
            add("assets/textures/$ns/item/$path.png")
            add("assets/textures/$ns/block/$path.png")
            add("assets/$ns/textures/item/$path.png")
            add("assets/$ns/textures/block/$path.png")
        }
        for (candidate in candidates) {
            val file = snapshotDir.resolve(candidate)
            if (file.exists()) {
                val px = QPixmap(file.toAbsolute().expandHome().toString())
                if (!px.isNull) {
                    val display = if (tintColor != null) applyTint(px, tintColor) else px
                    return display.scaled(size, size, Qt.AspectRatioMode.KeepAspectRatio, Qt.TransformationMode.FastTransformation)
                }
            }
        }
        return null
    }

    fun loadSpritePixmap(
        project: ProjectBase,
        uiTexture: String
    ): QPixmap? {
        if (uiTexture.isBlank()) return null
        return if (uiTexture.startsWith("/")) {
            val path = VPath.parse(uiTexture)
            if (path.exists()) QPixmap(path.toAbsolute().expandHome().toString()) else null
        } else {
            val snapshotDir = snapshotDir(project) ?: return null
            val path = uiTextureToDumpPath(uiTexture)
            val file = snapshotDir.resolve(path)
            if (file.exists()) {
                QPixmap(file.toAbsolute().expandHome().toString())
            } else null
        }
    }

    fun snapshotDir(project: ProjectBase): VPath? {
        val status = RegistryDatabase.status(project)
        return if (status is RegistryDbStatus.Ready) {
            status.manifestPath.parent()
        } else null
    }

    fun uiTextureToDumpPath(uiTexture: String): String {
        val colonIdx = uiTexture.indexOf(':')
        if (colonIdx == -1) return "assets/textures/${uiTexture}.png"
        val namespace = uiTexture.substring(0, colonIdx)
        val path = uiTexture.substring(colonIdx + 1).removeSuffix(".png")
        return "assets/textures/$namespace/$path.png"
    }

    private fun applyTint(pixmap: QPixmap, tintColor: Long): QPixmap =
        AnimatedItemMngr.applyTint(pixmap, tintColor)
}
