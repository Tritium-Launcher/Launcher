/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.ui.logging

import io.github.tritium_launcher.api.connect


object LogDialogMngr {
    private var dialog: LogDialog? = null

    fun openDialog() {
        val existing = dialog
        if (existing != null) {
            existing.openAndFocus()
            return
        }

        val created = LogDialog()
        created.destroyed.connect {
            if (dialog === created) dialog = null
        }
        dialog = created
        created.openAndFocus()
    }
}
