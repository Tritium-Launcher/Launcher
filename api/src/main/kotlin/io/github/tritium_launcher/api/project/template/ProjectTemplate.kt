/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.api.project.template

import kotlinx.serialization.Serializable

/**
 * Serializable project template used to describe generator steps and variables.
 */
@Serializable
data class ProjectTemplate(
    val id: String,
    val name: String,
    val description: String,
    val variables: List<TemplateVariable> = emptyList(),
    val genSteps: List<GeneratorStepDescriptor> = emptyList()
)
