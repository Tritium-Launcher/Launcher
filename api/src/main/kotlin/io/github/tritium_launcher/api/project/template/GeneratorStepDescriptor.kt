/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.api.project.template

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class GeneratorStepDescriptor(
    val id: String,
    val type: String,
    val meta: JsonObject = JsonObject(emptyMap()),
    val affects: List<String> = emptyList()
)
