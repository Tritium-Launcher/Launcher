/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher

import io.github.tritium_launcher.api.connect
import io.qt.gui.QGuiApplication
import io.qt.gui.QScreen
import java.util.concurrent.CopyOnWriteArrayList

object DprMonitor {
    @Volatile
    var current: Double = 1.0
        private set

    private val listeners = CopyOnWriteArrayList<(Double) -> Unit>()

    fun init(app: QGuiApplication) {
        current = QGuiApplication.primaryScreen()?.devicePixelRatio ?: 1.0
        QGuiApplication.screens().forEach { screen -> if(screen != null) observeScreen(screen) }
        app.screenAdded.connect { screen -> if(screen != null) observeScreen(screen) }
    }

    private fun observeScreen(screen: QScreen) {
        screen.logicalDotsPerInchChanged.connect { _ ->
            val newDpr = QGuiApplication.primaryScreen()?.devicePixelRatio ?: 1.0
            if (newDpr != current) {
                current = newDpr
                listeners.toList().forEach { it(newDpr) }
            }
        }

        screen.physicalDotsPerInchChanged.connect { _ ->
            val newDpr = screen.devicePixelRatio
            if (newDpr != current) {
                current = newDpr
                listeners.toList().forEach { it(newDpr) }
            }
        }
    }

    fun onChange(listener: (Double) -> Unit) { listeners.add(listener) }
    fun removeListener(listener: (Double) -> Unit) { listeners.remove(listener) }
}
