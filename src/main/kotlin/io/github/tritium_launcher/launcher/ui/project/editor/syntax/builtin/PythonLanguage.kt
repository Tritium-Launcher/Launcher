/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.ui.project.editor.syntax.builtin

import io.github.tritium_launcher.api.file.*
import io.github.tritium_launcher.api.io.VPath
import io.github.tritium_launcher.api.platform.Platform
import io.github.tritium_launcher.launcher.matches

/**
 * Basic Python syntax definition with multiple LSP command options.
 *
 * The LSP manager will pick the first server it finds on PATH.
 */
class PythonLanguage : SyntaxLanguage {
    override val id: String = "python"
    override val displayName: String = "Python"

    override val rules: List<SyntaxRule> = listOf(
        SyntaxRule(Regex("#[^\n]*"), "Comment"),
        SyntaxRule(Regex("\"\"\".*?\"\"\"|'''.*?'''", RegexOption.DOT_MATCHES_ALL), "Comment"), // docstrings
        SyntaxRule(Regex("\\b\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?\\b"), "Number"),
        SyntaxRule(
            Regex(
                "f?b?\"\"\".*?\"\"\"|f?b?'''.*?'''|f?b?\"(?:[^\"\\\\]|\\\\.)*\"|f?b?'(?:[^'\\\\]|\\\\.)*'",
                RegexOption.DOT_MATCHES_ALL
            ), "String"
        ),
        SyntaxRule(Regex("[+\\-*/%=<>!&|^~@]+"), "Operator"),
    )

    override val lsp: LSPDefinition = LSPDefinition(
        servers = listOf(
            LSPServerDefinition(
                id = "basedpyright",
                command = listOf("basedpyright-langserver", "--stdio"),
                installSpec = LSPInstallSpec(
                    downloadUrls = mapOf(
                        Platform.Linux to "https://github.com/detachhead/basedpyright/releases/download/v1.1.350/basedpyright-linux-x64.tar.gz",
                        Platform.Windows to "https://github.com/detachhead/basedpyright/releases/download/v1.1.350/basedpyright-win-x64.zip"
                    ),
                    binaryPath = "bin/basedpyright-langserver"
                )
            ),
            LSPServerDefinition(
                id = "pyright",
                command = listOf("pyright-langserver", "--stdio"),
                installSpec = LSPInstallSpec(
                    downloadUrls = mapOf(
                        Platform.Linux to "https://github.com/microsoft/pyright/releases/download/1.1.350/pyright-linux-x64.tar.gz",
                        Platform.Windows to "https://github.com/microsoft/pyright/releases/download/1.1.350/pyright-win-x64.zip"
                    ),
                    binaryPath = "bin/pyright-langserver"
                )
            ),
            LSPServerDefinition(
                id = "pylsp",
                command = listOf("pylsp")
            )
        )
    )

    override fun matches(file: VPath): Boolean = file.extension().matches("py")
}
