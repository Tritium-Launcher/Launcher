/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.ui.project.sidebar

import io.github.tritium_launcher.api.BuiltinRegistries
import io.github.tritium_launcher.api.core.project.ProjectBase
import io.github.tritium_launcher.api.docks.DockPanelProvider
import io.github.tritium_launcher.api.docks.DockWidget
import io.github.tritium_launcher.api.modpack.ModpackMeta
import io.github.tritium_launcher.launcher.core.project.Project
import io.github.tritium_launcher.launcher.m
import io.github.tritium_launcher.launcher.ui.theme.TColors
import io.github.tritium_launcher.launcher.ui.theme.TIcons
import io.github.tritium_launcher.launcher.ui.theme.qt.icon
import io.github.tritium_launcher.launcher.ui.theme.qt.setThemedStyle
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.gridLayout
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.label
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.qWidget
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.vBoxLayout
import io.qt.core.Qt
import io.qt.gui.QFont
import io.qt.gui.QIcon
import io.qt.gui.QPixmap
import io.qt.widgets.QSizePolicy
import io.qt.widgets.QWidget

/**
 * Side panel showing high-level modpack metadata.
 *
 * This is read-only for now. Future iterations may expose actions for changing project source,
 * Minecraft version, loader, and license.
 */
class ProjectModpackDockPanelProvider : DockPanelProvider {
    override val id: String = "modpack"
    override val displayName: String = "Modpack"
    override var icon: QIcon? = TIcons.ModConfig.icon
    override val order: Int = 5

    override val closeable: Boolean = false
    override val floatable: Boolean = false
    override val preferredArea: Qt.DockWidgetArea = Qt.DockWidgetArea.RightDockWidgetArea

    override fun create(project: ProjectBase): DockWidget {
        val dock = DockWidget(displayName, null)
        dock.setWidget(ModpackSummaryPanel(project))
        return dock
    }
}

private class ModpackSummaryPanel(
    private val project: ProjectBase
) : QWidget() {
    private val modSources = BuiltinRegistries.ModSource
    private val modLoaders = BuiltinRegistries.ModLoader
    private val licenses = BuiltinRegistries.License

    init {
        objectName = "modpackSidePanel"
        setThemedStyle {
            selector("#modpackSidePanel") {
                backgroundColor(TColors.Surface1)
                color(TColors.Text)
                border()
            }
            selector("#modpackSummaryTitle") {
                color(TColors.Text)
                fontSize(13)
            }
            selector("#modpackSummaryValue") {
                color(TColors.Text)
            }
            selector("#modpackSummaryKey") {
                color(TColors.Subtext)
            }
            selector("#modpackSummaryCard") {
                backgroundColor(TColors.Surface0)
                border(1, TColors.Surface2)
                padding(10)
            }
        }

        val outer = vBoxLayout(this) {
            contentsMargins = 12.m
            widgetSpacing = 12
        }

        val headerCard = qWidget { objectName = "modpackSummaryCard" }
        val headerLayout = vBoxLayout(headerCard) {
            contentsMargins = 12.m
            widgetSpacing = 8
        }

        val iconLabel = label {
            setAlignment(Qt.AlignmentFlag.AlignCenter)
            pixmap = projectIconPixmap()
        }
        val titleLabel = label(project.name) {
            objectName = "modpackSummaryTitle"
            setAlignment(Qt.AlignmentFlag.AlignCenter)
            wordWrap = true
            font = QFont(font).apply { setBold(true) }
        }

        headerLayout.addWidget(iconLabel)
        headerLayout.addWidget(titleLabel)
        outer.addWidget(headerCard)

        val detailsCard = qWidget { objectName = "modpackSummaryCard" }
        val detailsLayout = gridLayout(detailsCard) {
            contentsMargins = 12.m
            setHorizontalSpacing(10)
            setVerticalSpacing(8)
        }

        val meta = (project as? Project<*>)?.typedMeta as? ModpackMeta
        val rows = listOf(
            "Chosen Mod Source" to resolveModSource(meta?.source),
            "MC Version" to meta?.minecraftVersion.orUnknown(),
            "Mod Loader" to resolveModLoader(meta?.loader),
            "Mod Loader Version" to meta?.loaderVersion.orUnknown(),
            "License" to resolveLicense(meta?.license)
        )

        rows.forEachIndexed { index, (labelText, valueText) ->
            val key = label(labelText) {
                objectName = "modpackSummaryKey"
                setAlignment(Qt.AlignmentFlag.AlignTop)
                sizePolicy = QSizePolicy(QSizePolicy.Policy.Preferred, QSizePolicy.Policy.Preferred)
            }
            val value = label(valueText) {
                objectName = "modpackSummaryValue"
                wordWrap = true
                setAlignment(Qt.AlignmentFlag.AlignTop)
                sizePolicy = QSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Preferred)
            }
            detailsLayout.addWidget(key, index, 0)
            detailsLayout.addWidget(value, index, 1)
        }

        outer.addWidget(detailsCard)
        outer.addStretch(1)
    }

    private fun projectIconPixmap(): QPixmap {
        val pix = QPixmap(project.getIconPath())
        val base = if (pix.isNull) TIcons.Folder else pix
        return base.scaled(72, 72, Qt.AspectRatioMode.KeepAspectRatio, Qt.TransformationMode.SmoothTransformation)
    }

    private fun resolveModSource(id: String?): String =
        modSources.all().firstOrNull { it.id == id }?.displayName ?: id.orUnknown()

    private fun resolveModLoader(id: String?): String =
        modLoaders.all().firstOrNull { it.id == id }?.displayName ?: id.orUnknown()

    private fun resolveLicense(id: String?): String =
        licenses.all().firstOrNull { it.id == id }?.name ?: id.orUnknown()

    private fun String?.orUnknown(): String = this?.takeIf { it.isNotBlank() } ?: "Unknown"
}
