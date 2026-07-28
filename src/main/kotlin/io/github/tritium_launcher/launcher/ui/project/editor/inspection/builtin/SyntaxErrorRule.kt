/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.ui.project.editor.inspection.builtin

import io.github.tritium_launcher.api.inspection.Problem
import io.github.tritium_launcher.api.inspection.Severity
import io.github.tritium_launcher.api.inspection.SyntaxNode

object SyntaxErrorRule {
    fun run(root: SyntaxNode): List<Problem> {
        val problems = mutableListOf<Problem>()
        walk(root, problems)
        return problems
    }

    private fun walk(node: SyntaxNode, problems: MutableList<Problem>) {
        if (node.isError || node.isMissing) {
            val rawText = node.text()?.take(80) ?: ""
            val parentType = node.parent?.type ?: ""
            val msg = if (node.isMissing) {
                describeMissing(node, parentType)
            } else {
                describeError(node, parentType, rawText)
            }
            problems.add(Problem(
                startByte = node.startByte,
                endByte = node.endByte,
                message = msg,
                severity = Severity.ERROR,
                inspectionId = "syntax_error"
            ))
        }
        for (child in node.children) {
            walk(child, problems)
        }
    }

    private fun describeMissing(node: SyntaxNode, parentType: String): String {
        val type = node.type
        val desc = when (type) {
            ";" -> "semicolon"
            "," -> "comma"
            "." -> "dot"
            ":" -> "colon"
            "?" -> "question mark"
            ")" -> "closing parenthesis ')'"
            "]" -> "closing bracket ']'"
            "}" -> "closing brace '}'"
            "else" -> "'else' clause"
            "catch" -> "'catch' clause"
            "finally" -> "'finally' clause"
            "=>" -> "arrow '=>'"
            "expression" -> "an expression"
            "statement" -> "a statement"
            "declaration" -> "a declaration"
            "pattern" -> "a destructuring pattern"
            "parameter" -> "a function parameter"
            "name" -> "a name"
            "value" -> "a value"
            "key" -> "a key"
            "body" -> "a body"
            "consequence" -> "a consequence"
            "alternate" -> "an alternate"
            "operator" -> "an operator"
            "argument" -> "an argument"
            "template_literal" -> "a template literal"
            "string" -> "a string"
            else -> type.replace('_', ' ').removePrefix("_")
        }
        val ctx = when (parentType) {
            "binary_expression" -> " after operator"
            "unary_expression" -> " after operator"
            "call_expression" -> " in function call"
            "member_expression" -> " after '.'"
            "return_statement" -> " in return"
            "variable_declarator" -> " in variable declaration"
            "assignment_expression" -> " after '='"
            "if_statement" -> " in if condition"
            "for_statement" -> " in for statement"
            "while_statement" -> " in while condition"
            "arrow_function" -> " in arrow function"
            "object" -> " in object literal"
            "array" -> " in array literal"
            else -> ""
        }
        return "Missing $desc$ctx"
    }

    private fun describeError(node: SyntaxNode, parentType: String, rawText: String): String {
        val msg = when {
            parentType == "string" || parentType == "template_string" ->
                "Unterminated string literal"
            parentType == "call_expression" ->
                "Invalid arguments in function call"
            rawText.contains("await") && rawText.contains("async").not() ->
                "'await' used outside of async function"
            rawText.any { it in "}]" } -> {
                val match = mapOf('}' to '{', ']' to '[', ')' to '(')
                val expected = match[rawText.first { it in "}]" }] ?: "opening bracket"
                "Unmatched closing bracket \u2014 expected '$expected'"
            }
            else -> {
                inferFromParent(node) ?: "Unexpected syntax"
            }
        }
        return if (rawText.isNotBlank() && rawText.length < 60) {
            "$msg near \"$rawText\""
        } else msg
    }

    private fun inferFromParent(node: SyntaxNode): String? {
        val p = node.parent ?: return null
        return when (p.type) {
            "binary_expression" -> "Expected expression after operator"
            "unary_expression" -> "Expected expression after operator"
            "return_statement" -> "Expected expression after 'return'"
            "if_statement" -> "Expected condition in 'if' statement"
            "for_statement" -> "Expected expression in 'for' statement"
            "while_statement" -> "Expected condition in 'while' statement"
            "variable_declarator" -> "Expected initializer in variable declaration"
            "assignment_expression" -> "Expected expression after '='"
            "member_expression" -> "Expected property name after '.'"
            "call_expression" -> "Expected arguments in function call"
            "export_statement" -> "Expected declaration after 'export'"
            "throw_statement" -> "Expected expression after 'throw'"
            "switch_case" -> "Expected ':' after case value"
            "switch_default" -> "Expected ':' after default"
            "labeled_statement" -> "Expected statement after label"
            "pair" -> "Expected value after ':'"
            else -> null
        }
    }
}
