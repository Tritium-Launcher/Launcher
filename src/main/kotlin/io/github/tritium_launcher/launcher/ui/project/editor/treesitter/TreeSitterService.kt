/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.ui.project.editor.treesitter

import io.github.treesitter.ktreesitter.Node
import io.github.treesitter.ktreesitter.Parser
import io.github.treesitter.ktreesitter.Tree
import io.github.tritium_launcher.api.logger
import io.github.tritium_launcher.launcher.ui.project.editor.treesitter.grammar.TreeSitterJavascript

object TreeSitterService {
    private val log = logger()
    private var jsLanguage: io.github.treesitter.ktreesitter.Language? = null
    private var jsParser: Parser? = null
    private var cachedText: String? = null
    private var cachedResult: TreeSitterParseResult? = null
    private val grammarLanguages = mutableMapOf<String, io.github.treesitter.ktreesitter.Language>()

    fun isAvailable(): Boolean = jsLanguage != null

    fun grammarFor(name: String): io.github.treesitter.ktreesitter.Language? = grammarLanguages[name]

    fun init() {
        loadJsLanguage()
        if (jsLanguage != null) {
            log.info("Tree-sitter JavaScript grammar loaded")
        } else {
            log.warn("Tree-sitter JavaScript grammar not available")
        }
    }

    fun parse(source: String): TreeSitterParseResult? {
        if (source == cachedText) return cachedResult
        val lang = jsLanguage ?: return null
        val parser = jsParser ?: Parser(lang).also { jsParser = it }
        return try {
            val tree = parser.parse(source)
            TreeSitterParseResult(tree).also {
                cachedText = source
                cachedResult = it
            }
        } catch (e: Throwable) {
            jsParser = null
            log.warn("Tree-sitter parse failed", e)
            null
        }
    }

    private fun loadJsLanguage() {
        val lang = try {
            TreeSitterJavascript.language()
        } catch (e: Throwable) {
            log.warn("Failed to load JS grammar", e)
            null
        }
        jsLanguage = lang
        if (lang != null) {
            grammarLanguages["javascript"] = lang
            grammarLanguages["kubescript"] = lang
        }
    }
}

class TreeSitterParseResult(
    val tree: Tree
) {
    val rootNode: Node get() = tree.rootNode

    fun findNodeAt(bytePos: Int): Node? = findDeepestContaining(rootNode, bytePos)

    fun collectDiagnostics(): List<ParseError> {
        val errors = mutableListOf<ParseError>()
        collectErrors(rootNode, errors)
        return errors
    }

    private fun findDeepestContaining(node: Node, bytePos: Int): Node? {
        val start = node.startByte.toInt()
        val end = node.endByte.toInt()
        if (bytePos !in start..<end) return null
        for (child in node.children) {
            val found = findDeepestContaining(child, bytePos)
            if (found != null) return found
        }
        return node
    }

    private fun collectErrors(node: Node, errors: MutableList<ParseError>) {
        if (node.isError) {
            errors.add(ParseError(ParseErrorType.ERROR, node.startByte.toInt(), node.endByte.toInt()))
        } else if (node.isMissing) {
            errors.add(ParseError(ParseErrorType.MISSING, node.startByte.toInt(), node.endByte.toInt()))
        }
        for (child in node.children) {
            collectErrors(child, errors)
        }
    }
}

data class ParseError(
    val type: ParseErrorType,
    val startByte: Int,
    val endByte: Int
)

enum class ParseErrorType { ERROR, MISSING }
