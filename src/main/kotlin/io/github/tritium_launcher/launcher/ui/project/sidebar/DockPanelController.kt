/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.ui.project.sidebar

import io.github.tritium_launcher.api.state.Persistable
import io.github.tritium_launcher.api.state.UIStateMngr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

abstract class DockPanelController : Persistable {
    protected val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    open fun start() {
        UIStateMngr.register(this)
    }

    open fun cleanup() {
        UIStateMngr.unregister(this)
        scope.cancel()
    }
}
