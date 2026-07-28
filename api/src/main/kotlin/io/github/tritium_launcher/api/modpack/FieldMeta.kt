/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.api.modpack

data class FieldMeta(
    val description: String = "",
    val default: String?    = null,
    val min: Double?        = null,
    val max: Double?        = null
)
