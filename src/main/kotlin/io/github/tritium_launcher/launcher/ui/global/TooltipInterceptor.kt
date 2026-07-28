/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.ui.global

import io.github.tritium_launcher.api.connect
import io.github.tritium_launcher.launcher.ui.widgets.TTooltip
import io.github.tritium_launcher.launcher.ui.widgets.TTooltipStyle
import io.qt.core.QEvent
import io.qt.core.QObject
import io.qt.core.QTimer
import io.qt.gui.QHelpEvent
import io.qt.widgets.QWidget

class TooltipInterceptor : QObject() {
    private val hideTimer = QTimer().apply {
        isSingleShot = true
        interval = 80
        timeout.connect { TTooltip.hide() }
    }

    override fun eventFilter(watched: QObject?, event: QEvent?): Boolean {
        if (event == null) return false

        when (event.type()) {
            QEvent.Type.ToolTip -> {
                val targetWidget = watched as? QWidget ?: return false

                val contextWidget = findTooltipContext(targetWidget) ?: return false
                val originalText = contextWidget.toolTip() ?: ""

                if (originalText.isNotBlank()) {
                    hideTimer.stop()
                    val he = event as QHelpEvent

                    val useDefaultStyle = contextWidget.property("use_default_tooltip") as? Boolean ?: false

                    if (useDefaultStyle) {
                        return false
                    }

                    val customStyle = contextWidget.property("tt_style") as? TTooltipStyle
                        ?: TTooltipStyle()

                    TTooltip.show(he.globalPos(), originalText, customStyle)

                    contextWidget.toolTip = ""

                    QTimer.singleShot(0) {
                        if (contextWidget.toolTip().isNullOrBlank()) {
                            contextWidget.toolTip = originalText
                        }
                    }

                    return true
                }
            }
            QEvent.Type.Leave,
            QEvent.Type.Hide -> {
                val targetWidget = watched as? QWidget
                if (targetWidget != null) {
                    hideTimer.start()
                }
            }
            else -> {}
        }
        return super.eventFilter(watched, event)
    }

    private fun findTooltipContext(widget: QWidget?): QWidget? {
        var current: QWidget? = widget
        while (current != null) {
            if (!current.toolTip().isNullOrBlank()) {
                return current
            }
            current = current.parentWidget()
        }
        return null
    }
}
