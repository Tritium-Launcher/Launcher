package io.github.tritium_launcher.launcher.ui.project.editor.syntax

import io.github.tritium_launcher.launcher.platform.Platform

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
