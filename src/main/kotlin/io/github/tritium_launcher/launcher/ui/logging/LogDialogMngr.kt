package io.github.tritium_launcher.launcher.ui.logging

import io.github.tritium_launcher.launcher.connect

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
