/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.api.extension

import org.koin.core.module.Module

/**
 * Implement this to create an Extension loaded on startup.
 */
interface Extension {
    val namespace: String
    val modules: List<Module>

    val isBuiltin: Boolean get() = false
    val requiresRestart: Boolean get() = true
    val displayName: String get() = namespace
    val description: String? get() = null

    fun onRegister() {}

    fun onEnable() {}

    fun onDisable() {}
}
