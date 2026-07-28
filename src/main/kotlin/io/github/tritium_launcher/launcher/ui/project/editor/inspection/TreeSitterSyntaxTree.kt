/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.ui.project.editor.inspection

import io.github.tritium_launcher.api.inspection.SyntaxNode
import io.github.tritium_launcher.api.inspection.SyntaxTree

class TreeSitterSyntaxTree(
    private val tree: io.github.treesitter.ktreesitter.Tree
) : SyntaxTree {
    override val rootNode: SyntaxNode get() = TreeSitterSyntaxNode(tree.rootNode)
}
