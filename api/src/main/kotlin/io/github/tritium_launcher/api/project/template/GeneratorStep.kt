/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.api.project.template

interface GeneratorStep {
    val id: String
    val type: String

    suspend fun execute(ctx: GeneratorContext): StepExecutionResult

    suspend fun dryRun(ctx: GeneratorContext): StepExecutionResult = StepExecutionResult(
        stepId = id,
        stepType = type,
        success = true,
        message = "Dry-run: no-op"
    )
}
