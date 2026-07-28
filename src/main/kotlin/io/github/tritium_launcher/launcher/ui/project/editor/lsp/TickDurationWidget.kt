/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.ui.project.editor.lsp

import io.github.tritium_launcher.launcher.ui.theme.TColors
import io.qt.core.Qt
import io.qt.gui.QFont
import io.qt.widgets.QLabel
import io.qt.widgets.QVBoxLayout
import io.qt.widgets.QWidget

class TickDurationWidget(parent: QWidget? = null) : HoverContentWidget(parent) {
    private val layout = QVBoxLayout(this)
    private val headerLabel = QLabel()
    private val conversionLabel = QLabel()

    init {
        layout.setContentsMargins(10, 8, 10, 8)
        layout.setSpacing(4)

        headerLabel.font = QFont(headerLabel.font).apply { setPointSize(11); setBold(true) }
        headerLabel.setAlignment(Qt.AlignmentFlag.AlignCenter)

        conversionLabel.font = QFont(conversionLabel.font).apply { setPointSize(10) }
        conversionLabel.setAlignment(Qt.AlignmentFlag.AlignCenter)
        conversionLabel.styleSheet = "color: ${TColors.Subtext};"

        layout.addWidget(headerLabel)
        layout.addWidget(conversionLabel)

        styleSheet = "background: ${TColors.Surface0}; border: 1px solid ${TColors.Surface2}; border-radius: 4px;"
    }

    fun setTicks(ticks: Int) {
        val seconds = ticks / 20.0
        headerLabel.text = "$ticks ticks"

        val whole = seconds.toLong()
        val frac = seconds - whole
        val secondsStr = if (frac == 0.0) "$whole seconds" else "%.1f seconds".format(seconds)

        conversionLabel.text = if (ticks == 0) {
            "0 ticks = 0 seconds"
        } else {
            when {
                ticks % 20 == 0 -> "= $secondsStr"
                else -> "= $secondsStr (${ticks / 20} ticks/sec)"
            }
        }
        adjustSize()
    }

    override fun clear() {
        headerLabel.text = ""
        conversionLabel.text = ""
    }
}
