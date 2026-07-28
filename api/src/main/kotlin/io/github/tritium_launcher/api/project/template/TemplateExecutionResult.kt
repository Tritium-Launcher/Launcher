/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.api.project.template

import java.time.Instant

/**
 * Result of executing a project template.
 */
data class TemplateExecutionResult(
    val templateId: String,
    val projectRoot: String,
    val startTime: Instant,
    var endTime: Instant? = null,
    val successful: Boolean,
    val snapshotPath: String?,
    val stepResults: List<StepExecutionResult>,
    val logs: List<String>
)
