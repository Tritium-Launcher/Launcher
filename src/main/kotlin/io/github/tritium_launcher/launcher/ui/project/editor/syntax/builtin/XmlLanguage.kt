/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.ui.project.editor.syntax.builtin

import io.github.tritium_launcher.api.file.LSPDefinition
import io.github.tritium_launcher.api.file.LSPServerDefinition
import io.github.tritium_launcher.api.file.SyntaxLanguage
import io.github.tritium_launcher.api.file.SyntaxRule
import io.github.tritium_launcher.api.io.VPath
import io.github.tritium_launcher.launcher.matches

class XmlLanguage : SyntaxLanguage {
    override val id: String = "xml"
    override val displayName: String = "XML"

    override val rules: List<SyntaxRule> = listOf(
        // CDATA blocks — before everything else so nothing matches inside them
        SyntaxRule(Regex("<!\\[CDATA\\[.*?]]>", RegexOption.DOT_MATCHES_ALL), "String"),
        // Comments
        SyntaxRule(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "Comment"),
        // DOCTYPE declaration
        SyntaxRule(Regex("<!DOCTYPE[^>]*>"), "Keyword"),
        // Processing instructions
        SyntaxRule(Regex("<\\?.*?\\?>", RegexOption.DOT_MATCHES_ALL), "Keyword"),
        // Attribute values
        SyntaxRule(Regex("\"[^\"]*\"|'[^']*'"), "String"),
        // Attribute names
        SyntaxRule(Regex("\\b([\\w:.-]+)(?=\\s*=)"), "Attribute"),
        // Closing tags
        SyntaxRule(Regex("</[\\w:.-]+>"), "Tag"),
        // Opening/void tags — just the tag name portion
        SyntaxRule(Regex("<[\\w:.-]+"), "Tag"),
        // Punctuation: < > / = ?
        SyntaxRule(Regex("[<>/=?!]"), "Punctuation"),
        // Entity references
        SyntaxRule(Regex("&(?:#\\d+|#x[0-9a-fA-F]+|[\\w:.-]+);"), "Constant"),
    )

    override val lsp: LSPDefinition = LSPDefinition(
        servers = listOf(
            LSPServerDefinition(
                id = "lemminx",
                command = listOf("lemminx")
            )
        )
    )

    override fun matches(file: VPath): Boolean = file.extension().matches("xml", "svg", "svgs", "xhtml", "xsd")
}
