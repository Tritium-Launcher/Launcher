/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

/**
 * UI threading helpers for Qt widgets.
 */
package io.github.tritium_launcher.api

import io.qt.core.QMetaObject
import io.qt.core.QThread
import io.qt.core.Qt
import io.qt.widgets.QApplication

val runUiLogger = logger("ui.runOnGuiThread")

/**
 * Runs [action] on the Qt GUI thread, queueing it when called from a background thread.
 *
 * If no QApplication is available or dispatch fails, runs the action synchronously and logs failures.
 */
inline fun runOnGuiThread(crossinline action: () -> Unit) {
    val app = QApplication.instance()
    if (app == null) {
        try {
            action()
        } catch (t: Throwable) {
            runUiLogger.warn("runOnGuiThread (no QApplication) action failed", t)
        }
        return
    }

    try {
        val appThread = app.thread()
        if (appThread != null && appThread == QThread.currentThread()) {
            action()
            return
        }
        val slot = QMetaObject.Slot0 {
            try {
                action()
            } catch (t: Throwable) {
                runUiLogger.warn("runOnGuiThread action failed on GUI thread", t)
            }
        }
        QMetaObject.invokeMethod(app, slot, Qt.ConnectionType.QueuedConnection)
    } catch (t: Throwable) {
        runUiLogger.warn("runOnGuiThread.invokeMethod failed — running action synchronously", t)
        try {
            action()
        } catch (t2: Throwable) {
            runUiLogger.warn("runOnGuiThread fallback action failed", t2)
        }
    }
}
