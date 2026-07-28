/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.ui.dashboard

import io.github.tritium_launcher.api.extension.Extension
import io.github.tritium_launcher.api.extension.ExtensionStateMngr
import io.github.tritium_launcher.api.loadScaledPixmap
import io.github.tritium_launcher.api.qs
import io.github.tritium_launcher.launcher.extension.ExtensionLoader
import io.github.tritium_launcher.launcher.m
import io.github.tritium_launcher.launcher.ui.theme.TColors
import io.github.tritium_launcher.launcher.ui.theme.TIcons
import io.github.tritium_launcher.launcher.ui.theme.qt.icon
import io.github.tritium_launcher.launcher.ui.theme.qt.setThemedStyle
import io.github.tritium_launcher.launcher.ui.widgets.TToggleSwitch
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.hBoxLayout
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.label
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.vBoxLayout
import io.qt.gui.QPixmap
import io.qt.widgets.QFrame
import io.qt.widgets.QScrollArea
import io.qt.widgets.QSizePolicy
import io.qt.widgets.QWidget

private fun loadExtensionIconPixmap(ext: Extension, size: Int, dprWidget: QWidget? = null): QPixmap {
    val s = qs(size, size)

    val icon = ExtensionLoader.loadExtensionIcon(ext) ?: TIcons.Plugin.icon
    val src = icon.pixmap(qs(256,256)).takeIf { !it.isNull } ?: return QPixmap()

    return loadScaledPixmap(src.toImage(), s, dprWidget)
}

class ExtensionsPanel internal constructor() : QWidget() {
    private val mainLayout = vBoxLayout {
        contentsMargins = 12.m
        widgetSpacing = 12
    }

    init {
        objectName = "extensionsPanel"

        val title = label("Extensions") {
            objectName = "extensionsPanelTitle"
        }
        val desc = label("Manage installed extensions. Disabling an extension requires a restart to take effect.") {
            objectName = "extensionsPanelDesc"
            wordWrap = true
        }

        val list = ExtensionsManageList()

        mainLayout.addWidget(title)
        mainLayout.addWidget(desc)
        mainLayout.addSpacing(8)
        mainLayout.addWidget(list, 1)

        setLayout(mainLayout)

        setThemedStyle {
            selector("#extensionsPanel") { backgroundColor(TColors.Surface0) }
            selector("#extensionsPanelTitle") { fontSize(18); fontWeight(700) }
            selector("#extensionsPanelDesc") { fontSize(12); color(TColors.Subtext) }
        }
    }
}

/**
 * Reusable extension list widget used in both Dashboard and Settings.
 */
class ExtensionsManageList internal constructor() : QScrollArea() {
    private val state = ExtensionStateMngr.load().toMutableMap()

    init {
        objectName = "extensionsScroll"
        val scrollContent = QWidget()
        val scrollLayout = vBoxLayout(scrollContent) {
            contentsMargins = 0.m
            widgetSpacing = 4
        }

        setWidget(scrollContent)
        widgetResizable = true
        frameShape = Shape.NoFrame
        sizePolicy = QSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Expanding)

        val allExtensions = ExtensionLoader.allExtensions
        val sorted = allExtensions.sortedBy { it.displayName.lowercase() }

        for (ext in sorted) {
            scrollLayout.addWidget(ExtensionRow(ext))
        }

        if (sorted.isEmpty()) {
            val empty = QWidget()
            val emptyLayout = vBoxLayout(empty) {
                contentsMargins = 0.m
                widgetSpacing = 6
            }
            emptyLayout.addStretch(1)
            emptyLayout.addWidget(label("No extensions found.") { objectName = "extensionsEmpty" })
            emptyLayout.addStretch(1)
            scrollLayout.addWidget(empty, 1)
        } else {
            scrollLayout.addStretch(1)
        }

        setThemedStyle {
            selector("#extensionsRow") {
                backgroundColor(TColors.Surface0)
                border(1, TColors.Surface1)
                borderRadius(6)
            }
            selector("#extensionsRow:hover") { backgroundColor(TColors.Surface1) }
            selector("#extensionsRowName") { fontSize(13); fontWeight(600) }
            selector("#extensionsRowDesc") { fontSize(11); color(TColors.Subtext) }
            selector("#extensionsRowNamespace") { fontSize(10); color(TColors.Subtext) }
            selector("#extensionsRowBuiltin") {
                fontSize(10)
                color(TColors.Subtext)
                border(1, TColors.Surface1)
                borderRadius(4)
                padding(2, 8, 2, 8)
            }
            selector("#extensionsEmpty") { fontSize(12); color(TColors.Subtext); textAlign("center") }
        }
    }

    private inner class ExtensionRow(ext: Extension) : QFrame() {
        private val toggle = TToggleSwitch()

        init {
            objectName = "extensionsRow"
            setSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Fixed)

            val layout = hBoxLayout(this) {
                contentsMargins = 12.m
                widgetSpacing = 12
            }

            val iconLabel = label {
                pixmap = loadExtensionIconPixmap(ext, 48, this@ExtensionRow)
                minimumSize = qs(48, 48)
                maximumSize = qs(48, 48)
                sizePolicy = QSizePolicy(QSizePolicy.Policy.Fixed, QSizePolicy.Policy.Fixed)
            }
            layout.addWidget(iconLabel, 0)

            val infoWidget = QWidget()
            val infoLayout = vBoxLayout(infoWidget) {
                contentsMargins = 0.m
                widgetSpacing = 2
            }

            val nameLabel = label(ext.displayName) {
                objectName = "extensionsRowName"
            }

            val descText = ext.description ?: "No description available."
            val descLabel = label(descText) {
                objectName = "extensionsRowDesc"
                wordWrap = true
            }

            val nsLabel = label("${ext.namespace}  ·  ${if (ext.isBuiltin) "builtin" else if (ext.requiresRestart) "requires restart" else "restart not required"}") {
                objectName = "extensionsRowNamespace"
            }

            infoLayout.addWidget(nameLabel)
            infoLayout.addWidget(descLabel)
            infoLayout.addWidget(nsLabel)

            layout.addWidget(infoWidget, 1)

            if (!ext.isBuiltin) {
                val currentState = state.getOrDefault(ext.namespace, true)
                toggle.setChecked(currentState)
                toggle.toggled.connect({ checked: Boolean ->
                    state[ext.namespace] = checked
                    ExtensionStateMngr.setEnabled(ext.namespace, checked)
                })
                layout.addWidget(toggle, 0)
            } else {
                val builtinLabel = label("Builtin") {
                    objectName = "extensionsRowBuiltin"
                    sizePolicy = QSizePolicy(QSizePolicy.Policy.Fixed, QSizePolicy.Policy.Fixed)
                }
                layout.addWidget(builtinLabel, 0)
            }
        }
    }
}
