/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.api.file

import io.github.tritium_launcher.api.platform.Platform

data class LSPDefinition(
    val servers: List<LSPServerDefinition>
)

data class LSPServerDefinition(
    val id: String,
    val command: List<String>,
    val installSpec: LSPInstallSpec? = null
)

data class LSPInstallSpec(
    val downloadUrls: Map<Platform, String>,
    val binaryPath: String
)
