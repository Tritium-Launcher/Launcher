/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.ui.theme

import io.qt.core.QPoint
import io.qt.widgets.QToolTip
import io.qt.widgets.QWidget

/**
 * Mark a widget as invalid.
 *
 * Sets the Qt property `"invalid"` to [state], triggers a style repolish,
 * and optionally shows a tooltip with [msg] at the top-center of the widget.
 *
 * @param state  `true` applies the invalid styling, `false` removes it.
 * @param msg  Optional tooltip text shown when marking invalid.
 */
fun QWidget.setInvalid(state: Boolean, msg: String? = null) {
    this.setProperty("invalid", state)
    this.style()?.polish(this)
    this.update()

    if(state) {
        msg?.let {
            val pos = this.mapToGlobal(QPoint(this.width() / 2, 0))
            QToolTip.showText(pos, it, this)
        }
    }
}
