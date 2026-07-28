/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.api.project.template

/**
 * Result of executing a generator step.
 */
data class StepExecutionResult(
    val stepId: String,
    val stepType: String,
    val success: Boolean,
    val message: String? = null,
    val createdFiles: List<String> = emptyList(),
    val modifiedFiles: List<String> = emptyList()
)
