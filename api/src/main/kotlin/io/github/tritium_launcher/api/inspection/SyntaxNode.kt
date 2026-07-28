/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.api.inspection

interface SyntaxNode {
    val type: String
    val startByte: UInt
    val endByte: UInt
    val parent: SyntaxNode?
    val children: List<SyntaxNode>
    val childCount: UInt
    val isError: Boolean
    val isMissing: Boolean
    val isNamed: Boolean
    fun child(index: UInt): SyntaxNode?
    fun text(): String?
}
