/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.ui.dashboard

import io.github.tritium_launcher.api.qs
import io.github.tritium_launcher.launcher.m
import io.github.tritium_launcher.launcher.ui.settings.SettingsLink
import io.github.tritium_launcher.launcher.ui.settings.SettingsView
import io.github.tritium_launcher.launcher.ui.theme.TColors
import io.github.tritium_launcher.launcher.ui.theme.qt.setThemedStyle
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.vBoxLayout
import io.qt.widgets.QDialog
import io.qt.widgets.QWidget

/**
 * Dedicated dashboard settings window host.
 */
class SettingsDialog(parent: QWidget? = null) : QDialog(parent) {
    private val view = SettingsView()

    init {
        objectName = "settingsDialog"
        setProperty("keymapFocusGroup", "settings")
        windowTitle = "Settings"
        modal = false
        resize(qs(1080, 760))
        minimumSize = qs(860, 620)

        val layout = vBoxLayout(this) {
            contentsMargins = 0.m
            widgetSpacing = 0
            addWidget(view)
        }

        setThemedStyle {
            selector("#settingsDialog") { backgroundColor(TColors.Surface0) }
        }

        view.reload()
    }

    /**
     * Opens the dialog and optionally focuses a settings [link].
     */
    fun open(link: SettingsLink? = null) {
        if (link != null) {
            view.openLink(link)
        }
        show()
        raise()
        activateWindow()
    }
}
