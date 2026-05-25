package io.github.tritium_launcher.launcher.ui.project.editor.intelligence

data class CompletionItem(
    val label: String,
    val kind: CompletionItemKind = CompletionItemKind.Text,
    val detail: String? = null,
    val documentation: String? = null,
    val insertText: String? = null
)
