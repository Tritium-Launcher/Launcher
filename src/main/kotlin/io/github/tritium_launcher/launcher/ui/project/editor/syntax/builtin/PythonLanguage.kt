package io.github.tritium_launcher.launcher.ui.project.editor.syntax.builtin

import io.github.tritium_launcher.launcher.io.VPath
import io.github.tritium_launcher.launcher.matches
import io.github.tritium_launcher.launcher.ui.project.editor.syntax.SyntaxLanguage
import io.github.tritium_launcher.launcher.ui.project.editor.syntax.SyntaxRule

/**
 * Basic Python syntax definition with multiple LSP command options.
 *
 * The LSP manager will pick the first server it finds on PATH.
 */
class PythonLanguage : SyntaxLanguage {
    override val id: String = "python"
    override val displayName: String = "Python"

    override val rules: List<SyntaxRule> = listOf(
        // Keywords
        SyntaxRule(
            Regex("\\b(False|None|True|and|as|assert|async|await|break|case|class|continue|def|del|elif|else|except|finally|for|from|global|if|import|in|is|lambda|match|nonlocal|not|or|pass|raise|return|try|while|with|yield)\\b"),
            "Keyword"
        ),

        // Numbers
        SyntaxRule(
            Regex("(?<!\\w)(?:0[bB][01](?:_?[01])*|0[oO][0-7](?:_?[0-7])*|0[xX][0-9a-fA-F](?:_?[0-9a-fA-F])*|\\d(?:_?\\d)*(?:\\.\\d(?:_?\\d)*)?(?:[eE][+-]?\\d(?:_?\\d)*)?|\\.\\d(?:_?\\d)*(?:[eE][+-]?\\d(?:_?\\d)*)?)(?:[jJ])?(?!\\w)"),
            "Number"
        ),

        // Strings (single/double, triple, with prefixes)
        SyntaxRule(
            Regex("(?is)(?:r|u|f|fr|rf|b|br|rb|bu|ub)?(?:'''(?:.|\\R)*?'''|\"\"\"(?:.|\\R)*?\"\"\"|'(?:\\\\.|[^'\\\\])*'|\"(?:\\\\.|[^\"\\\\])*\")"),
            "String"
        ),

        // Comments (avoid matches inside simple quoted strings)
        SyntaxRule(Regex("#(?=(?:[^\"']|\"[^\"]*\"|'[^']*')*$).*"), "Comment")
    )

    override val lspCmds: List<List<String>> = listOf(
        listOf("pyright-langserver", "--stdio"),
        listOf("pylsp")
    )

    override fun matches(file: VPath): Boolean = file.extension().matches("py")
}
