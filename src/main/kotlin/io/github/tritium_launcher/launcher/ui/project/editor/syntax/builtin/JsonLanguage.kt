package io.github.tritium_launcher.launcher.ui.project.editor.syntax.builtin

import io.github.tritium_launcher.launcher.io.VPath
import io.github.tritium_launcher.launcher.matches
import io.github.tritium_launcher.launcher.ui.project.editor.syntax.SyntaxLanguage
import io.github.tritium_launcher.launcher.ui.project.editor.syntax.SyntaxRule

class JsonLanguage : SyntaxLanguage {
    override val id: String = "json"
    override val displayName: String = "JSON"

    override val rules: List<SyntaxRule> = listOf(

        // Keywords
        SyntaxRule(Regex("\\b(true|false|null)\\b"), "Keyword"),

        // Numbers
        SyntaxRule(Regex("-?(?:0|[1-9]\\d*)(?:\\.\\d+)?(?:[eE][+-]?\\d+)?"), "Number"),

        // Punctuation
        SyntaxRule(Regex("[{}\\[\\],:]"), "Punctuation"),

        // String value
        SyntaxRule(
            Regex("\"(?:[^\"\\\\\\u0000-\\u001F]|\\\\[\"\\\\/bfnrt]|\\\\u[0-9a-fA-F]{4})*\""),
            "String"
        ),

        // Keys
        SyntaxRule(
            Regex("\"(?:[^\"\\\\\\u0000-\\u001F]|\\\\[\"\\\\/bfnrt]|\\\\u[0-9a-fA-F]{4})*\"(?=\\s*:)"),
            "Key"
        )
    )

    override fun matches(file: VPath): Boolean = file.extension().matches("json")
}
