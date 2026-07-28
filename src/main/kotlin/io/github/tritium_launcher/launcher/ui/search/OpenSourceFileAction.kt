/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.ui.search

import io.github.tritium_launcher.api.io.VPath
import io.github.tritium_launcher.api.search.SearchResult
import io.github.tritium_launcher.api.search.SearchResultAction
import io.github.tritium_launcher.launcher.ui.project.ProjectWindows
import io.qt.widgets.QTextEdit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class OpenSourceFileAction : SearchResultAction {
    override val id = "open_source_file"
    override val label = "Open Source File"
    override val icon = "file"
    override val handledKinds = setOf("file", "config", "recipe", "script_symbol")

    override suspend fun execute(result: SearchResult) {
        val vpath = VPath.get(result.path)
        if (!vpath.exists()) return
        withContext(Dispatchers.Main) {
            val window = ProjectWindows.anyOpenWindow() ?: return@withContext
            window.editorArea.openFile(vpath)
            if (result.sourceLine > 0) {
                val textEdit = window.editorArea.widget().findChildren(QTextEdit::class.java).firstOrNull { it.isVisible }
                if (textEdit != null) {
                    val doc = textEdit.document() ?: return@withContext
                    val block = doc.findBlockByLineNumber((result.sourceLine - 1).toInt())
                    if (block.isValid) {
                        val cursor = textEdit.textCursor()
                        cursor.setPosition(block.position())
                        textEdit.setTextCursor(cursor)
                        textEdit.ensureCursorVisible()
                    }
                }
            }
        }
    }
}
