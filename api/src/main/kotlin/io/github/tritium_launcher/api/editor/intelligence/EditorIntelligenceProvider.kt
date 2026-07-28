/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.api.editor.intelligence

import io.github.tritium_launcher.api.core.project.ProjectBase

interface EditorIntelligence {
    fun getContextualCompletions(project: ProjectBase, fullText: String, cursorPos: Int): List<CompletionItem>
    fun getCompletions(project: ProjectBase, line: String, column: Int): List<CompletionItem>
    fun findItemSlotAt(project: ProjectBase, fullText: String, charPos: Int): ItemSlotInfo?
    fun getSignatureHelp(project: ProjectBase, fullText: String, cursorPos: Int): String?
    fun getHover(project: ProjectBase, symbol: String): HoverContent?
    fun findTickDurationAt(project: ProjectBase, fullText: String, cursorPos: Int): Int?
    fun findAllItemSlots(project: ProjectBase, fullText: String): List<ItemSlotInfo>
    fun invalidateConnection()
}

object EditorIntelligenceProvider {
    var instance: EditorIntelligence? = null
}
