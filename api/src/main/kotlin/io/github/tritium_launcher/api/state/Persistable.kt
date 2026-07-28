/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.api.state

import kotlinx.serialization.json.JsonObject

interface Persistable {
    val persistKey: String
    val flushPolicy: FlushPolicy get() = FlushPolicy.Periodic

    fun captureState(): JsonObject
    fun restoreState(state: JsonObject)

    fun markDirty() = UIStateMngr.markDirty(this)
}

enum class FlushPolicy { Immediate, Periodic, Shutdown }
