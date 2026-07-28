/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.ui.project.editor.inspection

import io.github.tritium_launcher.api.inspection.SyntaxNode

class TreeSitterSyntaxNode(
    private val node: io.github.treesitter.ktreesitter.Node
) : SyntaxNode {
    override val type: String get() = node.type
    override val startByte: UInt get() = node.startByte
    override val endByte: UInt get() = node.endByte
    override val parent: SyntaxNode? get() = node.parent?.let { TreeSitterSyntaxNode(it) }
    override val children: List<SyntaxNode> get() = node.children.map { TreeSitterSyntaxNode(it) }
    override val childCount: UInt get() = node.childCount
    override val isError: Boolean get() = node.isError
    override val isMissing: Boolean get() = node.isMissing
    override val isNamed: Boolean get() = node.isNamed

    override fun child(index: UInt): SyntaxNode? = node.child(index)?.let { TreeSitterSyntaxNode(it) }
    override fun text(): String? = node.text()?.toString()
}
