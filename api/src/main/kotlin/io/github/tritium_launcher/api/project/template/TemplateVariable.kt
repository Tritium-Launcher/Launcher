/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.api.project.template

import kotlinx.serialization.Serializable

/**
 * Variable definition used by project templates.
 */
@Serializable
data class TemplateVariable(
    val id: String,
    val type: String = "string",
    val default: String? = null,
    val description: String? = null,
    val required: Boolean = false
)
