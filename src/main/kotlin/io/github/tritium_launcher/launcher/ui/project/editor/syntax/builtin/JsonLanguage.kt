package io.github.tritium_launcher.launcher.ui.project.editor.syntax.builtin

import io.github.tritium_launcher.launcher.io.VPath
import io.github.tritium_launcher.launcher.matches
import io.github.tritium_launcher.launcher.ui.project.editor.syntax.LSPDefinition
import io.github.tritium_launcher.launcher.ui.project.editor.syntax.LSPServerDefinition
import io.github.tritium_launcher.launcher.ui.project.editor.syntax.SyntaxLanguage
import io.github.tritium_launcher.launcher.ui.project.editor.syntax.SyntaxRule

class JsonLanguage : SyntaxLanguage {
    override val id: String = "json"
    override val displayName: String = "JSON"

    override val rules: List<SyntaxRule> = listOf(
        // Numbers: integer, decimal, scientific notation, all RFC 8259 forms
        SyntaxRule(
            Regex("-?(?:0|[1-9]\\d*)(?:\\.\\d+)?(?:[eE][+-]?\\d+)?"),
            "Number"
        ),
        // Literals
        SyntaxRule(
            Regex("\\b(?:true|false|null)\\b"),
            "Keyword"
        ),
        // Structural characters
        SyntaxRule(
            Regex("[{}\\[\\],:]"),
            "Punctuation"
        ),
        // String values — must come before Key so Key selections override at key positions
        SyntaxRule(Regex("\"(?:[^\"\\\\]|\\\\.)*\""), "String"),
        SyntaxRule(Regex("\"(?:[^\"\\\\]|\\\\.)*\"(?=\\s*:)"), "Key"),
    )

    override val lsp: LSPDefinition = LSPDefinition(
        servers = listOf(
            LSPServerDefinition(
                id = "vscode-json-languageserver",
                command = listOf("vscode-json-languageserver", "--stdio")
            )
        )
    )

    override fun matches(file: VPath): Boolean = file.extension().matches("json")
}
