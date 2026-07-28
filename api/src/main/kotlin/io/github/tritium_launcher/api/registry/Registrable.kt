/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.api.registry

/**
 * Marker for registry entries that expose an [id].
 */
interface Registrable {
    val id: String
}
