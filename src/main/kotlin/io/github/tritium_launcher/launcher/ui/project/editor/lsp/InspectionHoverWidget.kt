/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.ui.project.editor.lsp

import io.github.tritium_launcher.api.connect
import io.github.tritium_launcher.api.inspection.InspectionFix
import io.github.tritium_launcher.api.inspection.Problem
import io.github.tritium_launcher.api.inspection.Severity
import io.github.tritium_launcher.launcher.ui.theme.TColors
import io.qt.core.Qt
import io.qt.gui.QFont
import io.qt.widgets.QLabel
import io.qt.widgets.QPushButton
import io.qt.widgets.QVBoxLayout
import io.qt.widgets.QWidget

class InspectionHoverWidget(parent: QWidget? = null) : HoverContentWidget(parent) {
    private val layout = QVBoxLayout(this)
    private val headerLabel = QLabel()
    private val messageLabel = QLabel()
    private val sourceLabel = QLabel()
    private val fixesContainer = QWidget()
    private val fixesLayout = QVBoxLayout(fixesContainer)

    var onApplyFix: ((InspectionFix, Problem) -> Unit)? = null
    private var currentProblem: Problem? = null

    init {
        layout.setContentsMargins(10, 8, 10, 8)
        layout.setSpacing(4)

        headerLabel.font = QFont(headerLabel.font).apply { setPointSize(10); setBold(true) }

        messageLabel.wordWrap = true
        messageLabel.font = QFont(messageLabel.font).apply { setPointSize(10) }

        sourceLabel.styleSheet = "color: ${TColors.Subtext}; font-size: 9px; font-family: monospace;"
        sourceLabel.wordWrap = true
        sourceLabel.setTextInteractionFlags(Qt.TextInteractionFlag.TextSelectableByMouse)

        fixesLayout.setContentsMargins(0, 2, 0, 0)
        fixesLayout.setSpacing(2)

        layout.addWidget(headerLabel)
        layout.addWidget(messageLabel)
        layout.addWidget(sourceLabel)
        layout.addWidget(fixesContainer)

        setStyleSheet("background: ${TColors.Surface0}; border: 1px solid ${TColors.Surface2}; border-radius: 4px;")
        setMaximumWidth(400)
    }

    fun showProblem(problem: Problem, sourceText: String? = null) {
        currentProblem = problem
        val (headerText, headerColor) = when (problem.severity) {
            Severity.ERROR -> "Syntax Error" to TColors.Syntax.Error
            Severity.WARNING -> "Warning" to TColors.Syntax.Warning
            Severity.INFO -> "Info" to TColors.Syntax.Information
            Severity.HINT -> "Hint" to TColors.Syntax.Default
            Severity.IGNORE -> return
        }
        headerLabel.text = headerText
        headerLabel.styleSheet = "color: $headerColor;"
        messageLabel.text = problem.message
        messageLabel.styleSheet = "color: ${TColors.Text};"
        sourceLabel.text = sourceText?.let { "at: $it" } ?: ""
        sourceLabel.isVisible = sourceText != null

        rebuildFixButtons(problem)
        adjustSize()
    }

    fun showError(message: String, sourceText: String? = null) {
        currentProblem = null
        headerLabel.text = "Syntax Error"
        headerLabel.styleSheet = "color: ${TColors.Syntax.Error};"
        messageLabel.text = message
        messageLabel.styleSheet = "color: ${TColors.Text};"
        sourceLabel.text = sourceText?.let { "at: $it" } ?: ""
        sourceLabel.isVisible = sourceText != null
        clearFixButtons()
        adjustSize()
    }

    fun showWarning(message: String, sourceText: String? = null) {
        currentProblem = null
        headerLabel.text = "Warning"
        headerLabel.styleSheet = "color: ${TColors.Syntax.Warning};"
        messageLabel.text = message
        messageLabel.styleSheet = "color: ${TColors.Text};"
        sourceLabel.text = sourceText?.let { "at: $it" } ?: ""
        sourceLabel.isVisible = sourceText != null
        clearFixButtons()
        adjustSize()
    }

    private fun rebuildFixButtons(problem: Problem) {
        clearFixButtons()
        if (problem.availableFixes.isEmpty()) {
            fixesContainer.isVisible = false
            return
        }
        fixesContainer.isVisible = true
        val sorted = problem.availableFixes.sortedByDescending { it.priority }
        for (fix in sorted) {
            val btn = QPushButton("\uD83D\uDD27 ${fix.label}")
            btn.styleSheet = """
                QPushButton {
                    background: ${TColors.Surface1};
                    border: 1px solid ${TColors.Surface2};
                    border-radius: 3px;
                    padding: 4px 8px;
                    text-align: left;
                    font-size: 10px;
                }
                QPushButton:hover {
                    background: ${TColors.Surface2};
                }
            """.trimIndent()
            btn.clicked.connect {
                onApplyFix?.invoke(fix, problem)
            }
            fixesLayout.addWidget(btn)
        }
    }

    private fun clearFixButtons() {
        while (fixesLayout.count() > 0) {
            val item = fixesLayout.takeAt(0)
            item?.widget()?.disposeLater()
        }
        fixesContainer.isVisible = false
    }

    override fun clear() {
        currentProblem = null
        headerLabel.text = ""
        messageLabel.text = ""
        sourceLabel.text = ""
        clearFixButtons()
    }
}
