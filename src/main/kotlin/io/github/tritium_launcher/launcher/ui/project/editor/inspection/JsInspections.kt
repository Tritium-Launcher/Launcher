/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.ui.project.editor.inspection

import io.github.tritium_launcher.api.inspection.*

fun registerJsInspections() {
    InspectionRegistry.register(InspectionSpec(
        id = "assignment_in_condition",
        title = "Assignment in condition",
        description = "Assignment in a condition is likely a typo for '=='.",
        languageId = "javascript",
        category = listOf("Potential Bug"),
        defaultSeverity = Severity.WARNING,
        type = InspectionType.Query(
            sExpression = "(if_statement condition: (parenthesized_expression (assignment_expression)) @assign)",
            messageTemplate = "Assignment in condition; did you mean '=='?"
        )
    ))

    InspectionRegistry.register(InspectionSpec(
        id = "throw_literal",
        title = "Throw string literal",
        description = "Throwing a string literal loses the stack trace; throw an Error instead.",
        languageId = "javascript",
        category = listOf("Style"),
        defaultSeverity = Severity.WARNING,
        type = InspectionType.Query(
            sExpression = "(throw_statement (string) @value)",
            messageTemplate = "Throw an Error object instead of a string literal."
        ),
        fixes = listOf(
            InspectionFix("Wrap with 'new Error()'", 10, FixGenerator.CaptureTemplate("new Error({value})"))
        )
    ))

    InspectionRegistry.register(InspectionSpec(
        id = "comma_expression",
        title = "Comma expression",
        description = "Comma expression hides side effects and reduces readability.",
        languageId = "javascript",
        category = listOf("Code Style"),
        defaultSeverity = Severity.HINT,
        type = InspectionType.Query(
            sExpression = "(sequence_expression) @expr",
            messageTemplate = "Comma expression hides side effects."
        )
    ))

    InspectionRegistry.register(InspectionSpec(
        id = "division_by_zero",
        title = "Division by zero",
        description = "Division by zero results in Infinity or NaN.",
        languageId = "javascript",
        category = listOf("Potential Bug"),
        defaultSeverity = Severity.WARNING,
        type = InspectionType.Query(
            sExpression = """(binary_expression operator: "/" (number) @divisor (#eq? @divisor "0"))""",
            messageTemplate = "Division by zero."
        )
    ))

    InspectionRegistry.register(InspectionSpec(
        id = "void_expression",
        title = "'void' expression",
        description = "'void' expression evaluates an expression and returns undefined.",
        languageId = "javascript",
        category = listOf("Code Style"),
        defaultSeverity = Severity.HINT,
        type = InspectionType.Query(
            sExpression = """(unary_expression operator: "void") @expr""",
            messageTemplate = "'void' expression should be avoided."
        )
    ))

    InspectionRegistry.register(InspectionSpec(
        id = "suspicious_plus_equals",
        title = "Suspicious '=+' assignment",
        description = "'=+' looks like a mistyped '+=' and causes unexpected behavior.",
        languageId = "javascript",
        category = listOf("Potential Bug"),
        defaultSeverity = Severity.WARNING,
        type = InspectionType.Walk(
            check = { node, fullText, _ ->
                val problems = mutableListOf<Problem>()
                fun walk(n: SyntaxNode) {
                    if (n.type == "assignment_expression") {
                        val start = n.startByte.toInt()
                        val text = fullText.substring(start, n.endByte.toInt())
                        val idx = text.indexOf("=+")
                        if (idx >= 0) {
                            problems.add(Problem(
                                startByte = (start + idx).toUInt(),
                                endByte = (start + idx + 2).toUInt(),
                                message = "Suspicious '=+' assignment; did you mean '+='?",
                                severity = Severity.WARNING,
                                inspectionId = "suspicious_plus_equals",
                                availableFixes = listOf(
                                    InspectionFix("Replace '=+' with '+='", 10, FixGenerator.Dynamic { t, _, _ ->
                                        t.replaceFirst("=+", "+=")
                                    })
                                )
                            ))
                        }
                    }
                    for (child in n.children) {
                        walk(child)
                    }
                }
                walk(node)
                problems
            }
        )
    ))

    InspectionRegistry.register(InspectionSpec(
        id = "eval_call",
        title = "'eval' call",
        description = "'eval' executes arbitrary code and is a security risk.",
        languageId = "javascript",
        category = listOf("Security"),
        defaultSeverity = Severity.WARNING,
        type = InspectionType.Query(
            sExpression = "(call_expression function: (identifier) @fn (#eq? @fn \"eval\"))",
            messageTemplate = "'eval' call is a security risk."
        )
    ))

    InspectionRegistry.register(InspectionSpec(
        id = "result_of_assignment_used",
        title = "Result of assignment used",
        description = "Assignment expression's return value is used by surrounding code; verify intent.",
        languageId = "javascript",
        category = listOf("Potential Bug"),
        defaultSeverity = Severity.WARNING,
        type = InspectionType.Walk(
            check = { node, fullText, _ ->
                val problems = mutableListOf<Problem>()
                fun walk(n: SyntaxNode) {
                    if (n.type == "assignment_expression") {
                        val parent = n.parent ?: return
                        if (parent.type != "expression_statement" && parent.type != "assignment_expression") {
                            problems.add(Problem(
                                startByte = n.startByte,
                                endByte = n.endByte,
                                message = "Result of assignment used; did you mean '=='?",
                                severity = Severity.WARNING,
                                inspectionId = "result_of_assignment_used"
                            ))
                        }
                    }
                    for (child in n.children) walk(child)
                }
                walk(node)
                problems
            }
        )
    ))

    InspectionRegistry.register(InspectionSpec(
        id = "unreachable_code",
        title = "Unreachable code",
        description = "Code after return/throw/continue/break is never executed.",
        languageId = "javascript",
        category = listOf("Control Flow"),
        defaultSeverity = Severity.WARNING,
        type = InspectionType.Walk(
            check = { node, fullText, _ ->
                val problems = mutableListOf<Problem>()
                fun walk(n: SyntaxNode) {
                    if (n.type == "statement_block") {
                        val realStmts = n.children.filter { it.type != "{" && it.type != "}" }.toList()
                        for (i in realStmts.indices) {
                            val stmt = realStmts[i]
                            if (stmt.type in setOf("return_statement", "throw_statement")) {
                                for (j in i + 1 until realStmts.size) {
                                    val nextStmt = realStmts[j]
                                    problems.add(Problem(
                                        startByte = nextStmt.startByte,
                                        endByte = nextStmt.endByte,
                                        message = "Unreachable code.",
                                        severity = Severity.WARNING,
                                        inspectionId = "unreachable_code"
                                    ))
                                }
                            }
                        }
                    }
                    for (child in n.children) walk(child)
                }
                walk(node)
                problems
            }
        )
    ))

    InspectionRegistry.register(InspectionSpec(
        id = "constant_conditional_expression",
        title = "Constant conditional expression",
        description = "Condition is always true or always false.",
        languageId = "javascript",
        category = listOf("Control Flow"),
        defaultSeverity = Severity.WARNING,
        type = InspectionType.Walk(
            check = { node, fullText, _ ->
                val constTypes = setOf("true", "false", "number")
                val problems = mutableListOf<Problem>()
                fun isConstant(node: SyntaxNode): Boolean {
                    val children = node.children.toList()
                    return children.any { it.type in constTypes }
                }
                fun walk(n: SyntaxNode) {
                    when (n.type) {
                        "if_statement", "while_statement", "do_statement" -> {
                            val condChild = n.children.firstOrNull { it.type == "parenthesized_expression" }
                            if (condChild != null && isConstant(condChild)) {
                                problems.add(Problem(
                                    startByte = condChild.startByte,
                                    endByte = condChild.endByte,
                                    message = "Condition is constant.",
                                    severity = Severity.WARNING,
                                    inspectionId = "constant_conditional_expression"
                                ))
                            }
                        }
                        "conditional_expression" -> {
                            val firstChild = n.children.firstOrNull()
                            if (firstChild != null && firstChild.type in setOf("true", "false")) {
                                problems.add(Problem(
                                    startByte = firstChild.startByte,
                                    endByte = firstChild.endByte,
                                    message = "Condition is constant.",
                                    severity = Severity.WARNING,
                                    inspectionId = "constant_conditional_expression"
                                ))
                            }
                        }
                    }
                    for (child in n.children) walk(child)
                }
                walk(node)
                problems
            }
        )
    ))

    InspectionRegistry.register(InspectionSpec(
        id = "duplicate_declaration",
        title = "Duplicate declaration",
        description = "Variable or function declared more than once in the same scope.",
        languageId = "javascript",
        category = listOf("General"),
        defaultSeverity = Severity.WARNING,
        type = InspectionType.Walk(
            check = { node, fullText, _ ->
                val problems = mutableListOf<Problem>()
                fun walk(n: SyntaxNode) {
                    if (n.type == "variable_declaration") {
                        val seen = mutableSetOf<String>()
                        for (child in n.children) {
                            if (child.type == "variable_declarator") {
                                val nameNode = child.children.firstOrNull { it.type == "identifier" }
                                if (nameNode != null) {
                                    val name = fullText.substring(nameNode.startByte.toInt(), nameNode.endByte.toInt())
                                    if (!seen.add(name)) {
                                        problems.add(Problem(
                                            startByte = nameNode.startByte,
                                            endByte = nameNode.endByte,
                                            message = "Duplicate declaration '$name'.",
                                            severity = Severity.WARNING,
                                            inspectionId = "duplicate_declaration"
                                        ))
                                    }
                                }
                            }
                        }
                    }
                    for (child in n.children) walk(child)
                }
                walk(node)
                problems
            }
        )
    ))

    InspectionRegistry.register(InspectionSpec(
        id = "statement_with_empty_body",
        title = "Statement with empty body",
        description = "An if/while/for statement with an empty body is likely a bug.",
        languageId = "javascript",
        category = listOf("Potentially Confusing"),
        defaultSeverity = Severity.WARNING,
        type = InspectionType.Walk(
            check = { node, fullText, _ ->
                val problems = mutableListOf<Problem>()
                fun isBodyNode(n: SyntaxNode): Boolean =
                    n.type == "empty_statement" || (n.type == "expression_statement" && n.children.toList().isEmpty())
                fun walk(n: SyntaxNode) {
                    val children = n.children.toList()
                    when (n.type) {
                        "if_statement" -> {
                            if (children.size > 2 && isBodyNode(children[2])) {
                                problems.add(Problem(
                                    startByte = children[2].startByte,
                                    endByte = children[2].endByte,
                                    message = "Empty body in if statement.",
                                    severity = Severity.WARNING,
                                    inspectionId = "statement_with_empty_body"
                                ))
                            }
                        }
                        "while_statement" -> {
                            if (children.size > 2 && isBodyNode(children[2])) {
                                problems.add(Problem(
                                    startByte = children[2].startByte,
                                    endByte = children[2].endByte,
                                    message = "Empty body in while statement.",
                                    severity = Severity.WARNING,
                                    inspectionId = "statement_with_empty_body"
                                ))
                            }
                        }
                    }
                    for (child in children) walk(child)
                }
                walk(node)
                problems
            }
        )
    ))

    InspectionRegistry.register(InspectionSpec(
        id = "comparison_with_nan",
        title = "Comparison with NaN",
        description = "NaN compares unequal to everything, including itself; use isNaN() instead.",
        languageId = "javascript",
        category = listOf("Probable Bugs"),
        defaultSeverity = Severity.ERROR,
        type = InspectionType.Walk(
            check = { node, fullText, _ ->
                val problems = mutableListOf<Problem>()
                fun walk(n: SyntaxNode) {
                    if (n.type == "binary_expression") {
                        val children = n.children.toList()
                        val ids = children.filter { it.type == "identifier" }
                        if (ids.any { fullText.substring(it.startByte.toInt(), it.endByte.toInt()) == "NaN" }) {
                            problems.add(Problem(
                                startByte = n.startByte,
                                endByte = n.endByte,
                                message = "Comparison with NaN; use isNaN() instead.",
                                severity = Severity.ERROR,
                                inspectionId = "comparison_with_nan"
                            ))
                        }
                    }
                    for (child in n.children) walk(child)
                }
                walk(node)
                problems
            }
        )
    ))

    InspectionRegistry.register(InspectionSpec(
        id = "unnecessary_semicolon",
        title = "Unnecessary semicolon",
        description = "Empty statement created by an unnecessary semicolon.",
        languageId = "javascript",
        category = listOf("Code Style"),
        defaultSeverity = Severity.HINT,
        type = InspectionType.Walk(
            check = { node, fullText, _ ->
                val problems = mutableListOf<Problem>()
                fun walk(n: SyntaxNode) {
                    if (n.type == "empty_statement") {
                        problems.add(Problem(
                            startByte = n.startByte,
                            endByte = n.endByte,
                            message = "Unnecessary semicolon.",
                            severity = Severity.HINT,
                            inspectionId = "unnecessary_semicolon"
                        ))
                    }
                    for (child in n.children) walk(child)
                }
                walk(node)
                problems
            }
        )
    ))

    InspectionRegistry.register(InspectionSpec(
        id = "octal_integer",
        title = "Octal integer",
        description = "Octal integer literal with leading zero (e.g., 017).",
        languageId = "javascript",
        category = listOf("Validity Issues"),
        defaultSeverity = Severity.WARNING,
        type = InspectionType.Walk(
            check = { node, fullText, _ ->
                val problems = mutableListOf<Problem>()
                fun walk(n: SyntaxNode) {
                    if (n.type == "number") {
                        val text = fullText.substring(n.startByte.toInt(), n.endByte.toInt())
                        if (text.length > 1 && text[0] == '0' && text[1] in '0'..'7' && text.all { it in '0'..'7' }) {
                            problems.add(Problem(
                                startByte = n.startByte,
                                endByte = n.endByte,
                                message = "Octal integer literal '$text'; use '0o$text' instead.",
                                severity = Severity.WARNING,
                                inspectionId = "octal_integer"
                            ))
                        }
                    }
                    for (child in n.children) walk(child)
                }
                walk(node)
                problems
            }
        )
    ))

    InspectionRegistry.register(InspectionSpec(
        id = "reserved_word_as_name",
        title = "Reserved word used as name",
        description = "Using a JavaScript reserved word as an identifier can cause errors.",
        languageId = "javascript",
        category = listOf("Validity Issues"),
        defaultSeverity = Severity.ERROR,
        type = InspectionType.Walk(
            check = { node, fullText, _ ->
                val reserved = setOf(
                    "await", "break", "case", "catch", "class", "const", "continue",
                    "debugger", "default", "delete", "do", "else", "enum", "export",
                    "extends", "false", "finally", "for", "function", "if", "import",
                    "in", "instanceof", "let", "new", "null", "return", "super",
                    "switch", "this", "throw", "true", "try", "typeof", "var",
                    "void", "while", "with", "yield"
                )
                val problems = mutableListOf<Problem>()
                fun walk(n: SyntaxNode) {
                    if (n.type == "identifier") {
                        val name = fullText.substring(n.startByte.toInt(), n.endByte.toInt())
                        if (name in reserved) {
                            problems.add(Problem(
                                startByte = n.startByte,
                                endByte = n.endByte,
                                message = "'$name' is a reserved word.",
                                severity = Severity.ERROR,
                                inspectionId = "reserved_word_as_name"
                            ))
                        }
                    }
                    for (child in n.children) walk(child)
                }
                walk(node)
                problems
            }
        )
    ))

    InspectionRegistry.register(InspectionSpec(
        id = "typeof_non_standard",
        title = "'typeof' comparison with non-standard value",
        description = "typeof returns one of: undefined, object, boolean, number, string, function, symbol, bigint.",
        languageId = "javascript",
        category = listOf("Probable Bugs"),
        defaultSeverity = Severity.WARNING,
        type = InspectionType.Walk(
            check = { node, fullText, _ ->
                val validTypes = setOf(
                    "undefined", "object", "boolean", "number",
                    "string", "function", "symbol", "bigint"
                )
                val problems = mutableListOf<Problem>()
                fun walk(n: SyntaxNode) {
                    if (n.type == "binary_expression") {
                        val childList = n.children.toList()
                        if (childList.size >= 3) {
                            val left = childList[0]
                            val right = childList[2]
                            val typeofNode = if (left.type == "unary_expression") left else if (right.type == "unary_expression") right else null
                            val stringNode = if (left.type == "string") left else if (right.type == "string") right else null
                            if (typeofNode != null && stringNode != null) {
                                val text = fullText.substring(stringNode.startByte.toInt(), stringNode.endByte.toInt())
                                val value = text.removeSurrounding("\"").removeSurrounding("'")
                                if (value !in validTypes) {
                                    problems.add(Problem(
                                        startByte = stringNode.startByte,
                                        endByte = stringNode.endByte,
                                        message = "Non-standard typeof result '$value'.",
                                        severity = Severity.WARNING,
                                        inspectionId = "typeof_non_standard"
                                    ))
                                }
                            }
                        }
                    }
                    for (child in n.children) walk(child)
                }
                walk(node)
                problems
            }
        )
    ))

    InspectionRegistry.register(InspectionSpec(
        id = "assignment_to_function_parameter",
        title = "Assignment to function parameter",
        description = "Assigning to a function parameter mutates the argument variable, which can cause bugs.",
        languageId = "javascript",
        category = listOf("Potential Bug"),
        defaultSeverity = Severity.WARNING,
        type = InspectionType.Walk(
            check = { node, fullText, _ ->
                val problems = mutableListOf<Problem>()
                fun walk(n: SyntaxNode) {
                    if (n.type in setOf("function_declaration", "function_expression", "arrow_function")) {
                        val params = mutableSetOf<String>()
                        var bodyNode: SyntaxNode? = null
                        for (child in n.children) {
                            when (child.type) {
                                "formal_parameters" -> {
                                    for (p in child.children) {
                                        if (p.type == "identifier") {
                                            params.add(fullText.substring(p.startByte.toInt(), p.endByte.toInt()))
                                        }
                                    }
                                }
                                "statement_block" -> { bodyNode = child }
                            }
                        }
                        if (params.isNotEmpty() && bodyNode != null) {
                            fun checkAssign(bn: SyntaxNode) {
                                if (bn.type == "assignment_expression") {
                                    val left = bn.children.firstOrNull { it.type == "identifier" }
                                    if (left != null) {
                                        val name = fullText.substring(left.startByte.toInt(), left.endByte.toInt())
                                        if (name in params) {
                                            problems.add(Problem(
                                                startByte = bn.startByte,
                                                endByte = bn.endByte,
                                                message = "Assignment to function parameter '$name'.",
                                                severity = Severity.WARNING,
                                                inspectionId = "assignment_to_function_parameter"
                                            ))
                                        }
                                    }
                                }
                                if (bn.type !in setOf("function_declaration", "function_expression", "arrow_function")) {
                                    for (child in bn.children) checkAssign(child)
                                }
                            }
                            checkAssign(bodyNode)
                        }
                    }
                    for (child in n.children) walk(child)
                }
                walk(node)
                problems
            }
        )
    ))

    InspectionRegistry.register(InspectionSpec(
        id = "variable_assigned_to_self",
        title = "Variable assigned to itself",
        description = "Assignment of a variable to itself has no effect.",
        languageId = "javascript",
        category = listOf("Potential Bug"),
        defaultSeverity = Severity.WARNING,
        type = InspectionType.Walk(
            check = { node, fullText, _ ->
                val problems = mutableListOf<Problem>()
                fun walk(n: SyntaxNode) {
                    if (n.type == "assignment_expression") {
                        val children = n.children.toList()
                        val left = children.firstOrNull { it.type == "identifier" }
                        val right = children.firstOrNull { it.type == "identifier" && it != left }
                        if (left != null && right != null) {
                            val leftName = fullText.substring(left.startByte.toInt(), left.endByte.toInt())
                            val rightName = fullText.substring(right.startByte.toInt(), right.endByte.toInt())
                            if (leftName == rightName) {
                                problems.add(Problem(
                                    startByte = n.startByte,
                                    endByte = n.endByte,
                                    message = "Variable assigned to itself.",
                                    severity = Severity.WARNING,
                                    inspectionId = "variable_assigned_to_self"
                                ))
                            }
                        }
                    }
                    for (child in n.children) walk(child)
                }
                walk(node)
                problems
            }
        )
    ))

    InspectionRegistry.register(InspectionSpec(
        id = "statement_body_without_braces",
        title = "Statement body without braces",
        description = "Using braces for all statement bodies improves readability and prevents bugs.",
        languageId = "javascript",
        category = listOf("Code Style"),
        defaultSeverity = Severity.HINT,
        type = InspectionType.Walk(
            check = { node, fullText, _ ->
                val problems = mutableListOf<Problem>()
                fun walk(n: SyntaxNode) {
                    when (n.type) {
                        "if_statement", "while_statement" -> {
                            val body = n.children.toList().getOrNull(2)
                            if (body != null && body.type !in setOf("statement_block", "empty_statement")) {
                                problems.add(Problem(
                                    startByte = body.startByte,
                                    endByte = body.endByte,
                                    message = "Statement body should be enclosed in braces.",
                                    severity = Severity.HINT,
                                    inspectionId = "statement_body_without_braces"
                                ))
                            }
                        }
                        "do_statement" -> {
                            val body = n.children.toList().getOrNull(1)
                            if (body != null && body.type !in setOf("statement_block", "empty_statement")) {
                                problems.add(Problem(
                                    startByte = body.startByte,
                                    endByte = body.endByte,
                                    message = "Statement body should be enclosed in braces.",
                                    severity = Severity.HINT,
                                    inspectionId = "statement_body_without_braces"
                                ))
                            }
                        }
                        "for_statement" -> {
                            val children = n.children.toList()
                            val body = children.lastOrNull()
                            if (body != null && body.type !in setOf("statement_block", "empty_statement", ")", ";")) {
                                problems.add(Problem(
                                    startByte = body.startByte,
                                    endByte = body.endByte,
                                    message = "Statement body should be enclosed in braces.",
                                    severity = Severity.HINT,
                                    inspectionId = "statement_body_without_braces"
                                ))
                            }
                        }
                    }
                    for (child in n.children) walk(child)
                }
                walk(node)
                problems
            }
        )
    ))

    InspectionRegistry.register(InspectionSpec(
        id = "magic_number",
        title = "Magic number",
        description = "Numeric literal should be replaced with a named constant.",
        languageId = "javascript",
        category = listOf("Code Style"),
        defaultSeverity = Severity.HINT,
        type = InspectionType.Walk(
            check = { node, fullText, _ ->
                val problems = mutableListOf<Problem>()
                val commonNumbers = setOf("0", "1", "-1", "100", "1000")
                fun walk(n: SyntaxNode) {
                    if (n.type == "number" && n.parent != null) {
                        val text = fullText.substring(n.startByte.toInt(), n.endByte.toInt())
                        if (text !in commonNumbers && text.toDoubleOrNull() != null) {
                            val parent = n.parent!!
                            if (parent.type == "binary_expression" || parent.type == "call_expression") {
                                problems.add(Problem(
                                    startByte = n.startByte,
                                    endByte = n.endByte,
                                    message = "Magic number '$text'.",
                                    severity = Severity.HINT,
                                    inspectionId = "magic_number"
                                ))
                            }
                        }
                    }
                    for (child in n.children) walk(child)
                }
                walk(node)
                problems
            }
        )
    ))

    InspectionRegistry.register(InspectionSpec(
        id = "equality_operator_type_confusion",
        title = "Equality operator may cause type confusion",
        description = "Use '===' instead of '==' to avoid type coercion.",
        languageId = "javascript",
        category = listOf("Potential Bug"),
        defaultSeverity = Severity.WARNING,
        type = InspectionType.Walk(
            check = { node, fullText, _ ->
                val problems = mutableListOf<Problem>()
                fun walk(n: SyntaxNode) {
                    if (n.type == "binary_expression") {
                        for (child in n.children) {
                            if (child.type == "==" || child.type == "!=") {
                                problems.add(Problem(
                                    startByte = child.startByte,
                                    endByte = child.endByte,
                                    message = "Use '${child.type}=' instead of '${child.type}' to avoid type coercion.",
                                    severity = Severity.WARNING,
                                    inspectionId = "equality_operator_type_confusion"
                                ))
                            }
                        }
                    }
                    for (child in n.children) walk(child)
                }
                walk(node)
                problems
            }
        )
    ))

    InspectionRegistry.register(InspectionSpec(
        id = "negated_if_statement",
        title = "Negated 'if' statement",
        description = "Negated condition in an if/else can be swapped for clarity.",
        languageId = "javascript",
        category = listOf("Code Style"),
        defaultSeverity = Severity.HINT,
        type = InspectionType.Walk(
            check = { node, fullText, _ ->
                val problems = mutableListOf<Problem>()
                fun walk(n: SyntaxNode) {
                    if (n.type == "if_statement") {
                        val children = n.children.toList()
                        val cond = children.getOrNull(1)
                        if (cond != null) {
                            fun hasBang(n: SyntaxNode): Boolean {
                                if (n.type == "!") return true
                                return n.children.any { hasBang(it) }
                            }
                            if (hasBang(cond)) {
                                problems.add(Problem(
                                    startByte = cond.startByte,
                                    endByte = cond.endByte,
                                    message = "Negated if condition; consider swapping if/else branches.",
                                    severity = Severity.HINT,
                                    inspectionId = "negated_if_statement"
                                ))
                            }
                        }
                    }
                    for (child in n.children) walk(child)
                }
                walk(node)
                problems
            }
        )
    ))

    InspectionRegistry.register(InspectionSpec(
        id = "unnecessary_block_statement",
        title = "Unnecessary block statement",
        description = "A block inside another block can be removed.",
        languageId = "javascript",
        category = listOf("Code Style"),
        defaultSeverity = Severity.HINT,
        type = InspectionType.Walk(
            check = { node, fullText, _ ->
                val problems = mutableListOf<Problem>()
                fun walk(n: SyntaxNode) {
                    if (n.type == "statement_block" && n.parent?.type == "statement_block") {
                        val children = n.children.toList()
                        val realChildren = children.filter { it.type != "{" && it.type != "}" }
                        if (realChildren.isNotEmpty()) {
                            problems.add(Problem(
                                startByte = n.startByte,
                                endByte = n.endByte,
                                message = "Unnecessary block statement.",
                                severity = Severity.HINT,
                                inspectionId = "unnecessary_block_statement"
                            ))
                        }
                    }
                    for (child in n.children) walk(child)
                }
                walk(node)
                problems
            }
        )
    ))

    InspectionRegistry.register(InspectionSpec(
        id = "redundant_if_statement",
        title = "Redundant 'if' statement",
        description = "if (x) return true; else return false; can be simplified to return x;.",
        languageId = "javascript",
        category = listOf("Code Style"),
        defaultSeverity = Severity.HINT,
        type = InspectionType.Walk(
            check = { node, fullText, _ ->
                val problems = mutableListOf<Problem>()
                fun walk(n: SyntaxNode) {
                    if (n.type == "if_statement") {
                        val children = n.children.toList()
                        val elseClause = children.firstOrNull { it.type == "else_clause" }
                        if (elseClause != null) {
                            val conseq = children.getOrNull(2)
                            val alt = elseClause.children.toList().firstOrNull { it.type == "statement_block" }
                            if (conseq != null && alt != null) {
                                fun findBool(n: SyntaxNode): String? {
                                    if (n.type in setOf("true", "false")) return n.type
                                    for (child in n.children) {
                                        val result = findBool(child)
                                        if (result != null) return result
                                    }
                                    return null
                                }
                                fun boolReturn(node: SyntaxNode): String? {
                                    return when (node.type) {
                                        "statement_block" -> {
                                            val ret = node.children.toList().firstOrNull { it.type == "return_statement" }
                                            if (ret != null) findBool(ret) else null
                                        }
                                        "return_statement" -> findBool(node)
                                        else -> null
                                    }
                                }
                                val conseqVal = boolReturn(conseq)
                                val altVal = boolReturn(alt)
                                if (conseqVal != null && altVal != null && conseqVal != altVal) {
                                    problems.add(Problem(
                                        startByte = n.startByte,
                                        endByte = n.endByte,
                                        message = "Redundant if statement; can be simplified to 'return ${if (conseqVal == "true") "condition" else "!condition"}'.",
                                        severity = Severity.HINT,
                                        inspectionId = "redundant_if_statement"
                                    ))
                                }
                            }
                        }
                    }
                    for (child in n.children) walk(child)
                }
                walk(node)
                problems
            }
        )
    ))

    InspectionRegistry.register(InspectionSpec(
        id = "unnecessary_return_statement",
        title = "Unnecessary 'return' statement",
        description = "'return;' at the end of a function is redundant.",
        languageId = "javascript",
        category = listOf("Code Style"),
        defaultSeverity = Severity.HINT,
        type = InspectionType.Walk(
            check = { node, fullText, _ ->
                val problems = mutableListOf<Problem>()
                fun walk(n: SyntaxNode) {
                    if (n.type == "statement_block") {
                        val realStmts = n.children.filter { it.type != "{" && it.type != "}" }
                        val lastStmt = realStmts.lastOrNull()
                        if (lastStmt != null && lastStmt.type == "return_statement") {
                            val retChildren = lastStmt.children.toList()
                            if (retChildren.none { it.type != "return" && it.type != ";" }) {
                                var parent = n.parent
                                var inFunction = false
                                while (parent != null) {
                                    if (parent.type in setOf("function_declaration", "function_expression", "arrow_function")) {
                                        inFunction = true
                                        break
                                    }
                                    parent = parent.parent
                                }
                                if (inFunction) {
                                    problems.add(Problem(
                                        startByte = lastStmt.startByte,
                                        endByte = lastStmt.endByte,
                                        message = "Unnecessary 'return;' at end of function.",
                                        severity = Severity.HINT,
                                        inspectionId = "unnecessary_return_statement"
                                    ))
                                }
                            }
                        }
                    }
                    for (child in n.children) walk(child)
                }
                walk(node)
                problems
            }
        )
    ))

    InspectionRegistry.register(InspectionSpec(
        id = "confusing_floating_point_literal",
        title = "Confusing floating point literal",
        description = "Floating point literal without leading/trailing digit (e.g., .5) can be confused with other syntax.",
        languageId = "javascript",
        category = listOf("Code Style"),
        defaultSeverity = Severity.HINT,
        type = InspectionType.Walk(
            check = { node, fullText, _ ->
                val problems = mutableListOf<Problem>()
                fun walk(n: SyntaxNode) {
                    if (n.type == "number") {
                        val text = fullText.substring(n.startByte.toInt(), n.endByte.toInt())
                        if (text.startsWith(".") || text.endsWith(".")) {
                            problems.add(Problem(
                                startByte = n.startByte,
                                endByte = n.endByte,
                                message = if (text.startsWith("."))
                                    "Floating point literal '${text}' lacks a leading digit."
                                else
                                    "Floating point literal '${text}' lacks a trailing digit.",
                                severity = Severity.HINT,
                                inspectionId = "confusing_floating_point_literal"
                            ))
                        }
                    }
                    for (child in n.children) walk(child)
                }
                walk(node)
                problems
            }
        )
    ))

    InspectionRegistry.register(InspectionSpec(
        id = "nested_assignment",
        title = "Nested assignment",
        description = "Assignment expression used inside another expression can be confusing.",
        languageId = "javascript",
        category = listOf("Assignment Issues"),
        defaultSeverity = Severity.WARNING,
        type = InspectionType.Walk(
            check = { node, fullText, _ ->
                val problems = mutableListOf<Problem>()
                fun walk(n: SyntaxNode) {
                    if (n.type == "assignment_expression") {
                        val p = n.parent
                        val isNested = p?.type == "assignment_expression" ||
                            (p?.type == "parenthesized_expression" && p.parent?.type in setOf("assignment_expression", "variable_declarator"))
                        if (isNested) {
                            problems.add(Problem(
                                startByte = n.startByte,
                                endByte = n.endByte,
                                message = "Nested assignment expression.",
                                severity = Severity.WARNING,
                                inspectionId = "nested_assignment"
                            ))
                        }
                    }
                    for (child in n.children) walk(child)
                }
                walk(node)
                problems
            }
        )
    ))

    InspectionRegistry.register(InspectionSpec(
        id = "identical_branches_ternary",
        title = "Conditional expression with identical branches",
        description = "Both branches of a ternary are identical; the conditional is redundant.",
        languageId = "javascript",
        category = listOf("Control Flow"),
        defaultSeverity = Severity.WARNING,
        type = InspectionType.Walk(
            check = { node, fullText, _ ->
                val problems = mutableListOf<Problem>()
                fun walk(n: SyntaxNode) {
                    if (n.type == "ternary_expression") {
                        val children = n.children.toList()
                        val conseq = children.getOrNull(2)
                        val alt = children.getOrNull(4)
                        if (conseq != null && alt != null) {
                            val cText = fullText.substring(conseq.startByte.toInt(), conseq.endByte.toInt())
                            val aText = fullText.substring(alt.startByte.toInt(), alt.endByte.toInt())
                            if (cText == aText) {
                                problems.add(Problem(
                                    startByte = n.startByte,
                                    endByte = n.endByte,
                                    message = "Conditional expression with identical branches.",
                                    severity = Severity.WARNING,
                                    inspectionId = "identical_branches_ternary"
                                ))
                            }
                        }
                    }
                    for (child in n.children) walk(child)
                }
                walk(node)
                problems
            }
        )
    ))

    InspectionRegistry.register(InspectionSpec(
        id = "pointless_statement",
        title = "Pointless statement",
        description = "Expression statement has no side effects and can be removed.",
        languageId = "javascript",
        category = listOf("Control Flow"),
        defaultSeverity = Severity.WARNING,
        type = InspectionType.Walk(
            check = { node, fullText, _ ->
                val problems = mutableListOf<Problem>()
                val sideEffectTypes = setOf("call_expression", "assignment_expression", "update_expression", "await_expression", "yield_expression")
                fun walk(n: SyntaxNode) {
                    if (n.type == "expression_statement") {
                        val expr = n.children.firstOrNull { it.type != ";" }
                        if (expr != null && expr.type !in sideEffectTypes) {
                            problems.add(Problem(
                                startByte = n.startByte,
                                endByte = n.endByte,
                                message = "Pointless expression statement.",
                                severity = Severity.WARNING,
                                inspectionId = "pointless_statement"
                            ))
                        }
                    }
                    for (child in n.children) walk(child)
                }
                walk(node)
                problems
            }
        )
    ))

    InspectionRegistry.register(InspectionSpec(
        id = "constant_on_left_side",
        title = "Constant on left side of comparison",
        description = "Putting the constant on the left side of a comparison (Yoda condition) reduces readability.",
        languageId = "javascript",
        category = listOf("Code Style"),
        defaultSeverity = Severity.HINT,
        type = InspectionType.Walk(
            check = { node, fullText, _ ->
                val problems = mutableListOf<Problem>()
                val constTypes = setOf("number", "string", "true", "false", "null")
                fun walk(n: SyntaxNode) {
                    if (n.type == "binary_expression") {
                        val left = n.children.toList().getOrNull(0)
                        if (left != null && left.type in constTypes) {
                            problems.add(Problem(
                                startByte = left.startByte,
                                endByte = left.endByte,
                                message = "Constant '${fullText.substring(left.startByte.toInt(), left.endByte.toInt())}' on left side of comparison.",
                                severity = Severity.HINT,
                                inspectionId = "constant_on_left_side"
                            ))
                        }
                    }
                    for (child in n.children) walk(child)
                }
                walk(node)
                problems
            }
        )
    ))

    InspectionRegistry.register(InspectionSpec(
        id = "empty_catch_block",
        title = "Empty catch block",
        description = "An empty catch block silently swallows exceptions.",
        languageId = "javascript",
        category = listOf("Try Statement Issues"),
        defaultSeverity = Severity.WARNING,
        type = InspectionType.Walk(
            check = { node, fullText, _ ->
                val problems = mutableListOf<Problem>()
                fun walk(n: SyntaxNode) {
                    if (n.type == "catch_clause") {
                        val body = n.children.firstOrNull { it.type == "statement_block" }
                        if (body != null) {
                            val realStmts = body.children.filter { it.type != "{" && it.type != "}" }
                            if (realStmts.isEmpty()) {
                                problems.add(Problem(
                                    startByte = n.startByte,
                                    endByte = n.endByte,
                                    message = "Empty catch block.",
                                    severity = Severity.WARNING,
                                    inspectionId = "empty_catch_block"
                                ))
                            }
                        }
                    }
                    for (child in n.children) walk(child)
                }
                walk(node)
                problems
            }
        )
    ))

    InspectionRegistry.register(InspectionSpec(
        id = "empty_finally_block",
        title = "Empty finally block",
        description = "An empty finally block has no effect and can be removed.",
        languageId = "javascript",
        category = listOf("Try Statement Issues"),
        defaultSeverity = Severity.WARNING,
        type = InspectionType.Walk(
            check = { node, fullText, _ ->
                val problems = mutableListOf<Problem>()
                fun walk(n: SyntaxNode) {
                    if (n.type == "finally_clause") {
                        val body = n.children.firstOrNull { it.type == "statement_block" }
                        if (body != null) {
                            val realStmts = body.children.filter { it.type != "{" && it.type != "}" }
                            if (realStmts.isEmpty()) {
                                problems.add(Problem(
                                    startByte = n.startByte,
                                    endByte = n.endByte,
                                    message = "Empty finally block.",
                                    severity = Severity.WARNING,
                                    inspectionId = "empty_finally_block"
                                ))
                            }
                        }
                    }
                    for (child in n.children) walk(child)
                }
                walk(node)
                problems
            }
        )
    ))

    InspectionRegistry.register(InspectionSpec(
        id = "identical_branches_if",
        title = "'if' statement with identical branches",
        description = "Both branches of an if/else are the same; the conditional is redundant.",
        languageId = "javascript",
        category = listOf("Control Flow"),
        defaultSeverity = Severity.WARNING,
        type = InspectionType.Walk(
            check = { node, fullText, _ ->
                val problems = mutableListOf<Problem>()
                fun walk(n: SyntaxNode) {
                    if (n.type == "if_statement") {
                        val children = n.children.toList()
                        val elseClause = children.firstOrNull { it.type == "else_clause" }
                        if (elseClause != null) {
                            val conseq = children.getOrNull(2)
                            val alt = elseClause.children.firstOrNull { it.type != "else" }
                            if (conseq != null && alt != null) {
                                val cText = fullText.substring(conseq.startByte.toInt(), conseq.endByte.toInt())
                                val aText = fullText.substring(alt.startByte.toInt(), alt.endByte.toInt())
                                if (cText == aText) {
                                    problems.add(Problem(
                                        startByte = n.startByte,
                                        endByte = n.endByte,
                                        message = "'if' statement with identical branches.",
                                        severity = Severity.WARNING,
                                        inspectionId = "identical_branches_if"
                                    ))
                                }
                            }
                        }
                    }
                    for (child in n.children) walk(child)
                }
                walk(node)
                problems
            }
        )
    ))

    InspectionRegistry.register(InspectionSpec(
        id = "bitwise_operator_usage",
        title = "Bitwise operator usage",
        description = "Bitwise operators can indicate a typo or misunderstanding of JS numbers.",
        languageId = "javascript",
        category = listOf("Bitwise Operation Issues"),
        defaultSeverity = Severity.HINT,
        type = InspectionType.Walk(
            check = { node, fullText, _ ->
                val problems = mutableListOf<Problem>()
                val bitwiseOps = setOf("|", "&", "^", "~", "<<", ">>", ">>>")
                fun walk(n: SyntaxNode) {
                    if (n.type == "binary_expression" || n.type == "unary_expression") {
                        val op = n.children.firstOrNull { it.type in bitwiseOps }
                        if (op != null) {
                            problems.add(Problem(
                                startByte = op.startByte,
                                endByte = op.endByte,
                                message = "Bitwise operator '${op.type}'.",
                                severity = Severity.HINT,
                                inspectionId = "bitwise_operator_usage"
                            ))
                        }
                    }
                    for (child in n.children) walk(child)
                }
                walk(node)
                problems
            }
        )
    ))

    InspectionRegistry.register(InspectionSpec(
        id = "labeled_statement",
        title = "Labeled statement",
        description = "Labels are rarely needed and can usually be refactored.",
        languageId = "javascript",
        category = listOf("Potentially Undesirable"),
        defaultSeverity = Severity.HINT,
        type = InspectionType.Walk(
            check = { node, fullText, _ ->
                val problems = mutableListOf<Problem>()
                fun walk(n: SyntaxNode) {
                    if (n.type == "labeled_statement") {
                        problems.add(Problem(
                            startByte = n.startByte,
                            endByte = n.endByte,
                            message = "Labeled statement.",
                            severity = Severity.HINT,
                            inspectionId = "labeled_statement"
                        ))
                    }
                    for (child in n.children) walk(child)
                }
                walk(node)
                problems
            }
        )
    ))

    InspectionRegistry.register(InspectionSpec(
        id = "unnecessary_continue_statement",
        title = "Unnecessary 'continue' statement",
        description = "'continue' at the end of a loop body is redundant.",
        languageId = "javascript",
        category = listOf("Control Flow"),
        defaultSeverity = Severity.HINT,
        type = InspectionType.Walk(
            check = { node, fullText, _ ->
                val problems = mutableListOf<Problem>()
                fun walk(n: SyntaxNode) {
                    if (n.type == "continue_statement") {
                        val parentBlock = n.parent
                        if (parentBlock?.type == "statement_block") {
                            val loopParent = parentBlock.parent
                            if (loopParent?.type in setOf("for_statement", "while_statement", "do_statement")) {
                                val realStmts = parentBlock.children.filter { it.type != "{" && it.type != "}" }
                                if (realStmts.lastOrNull() == n) {
                                    val labelChild = n.children.firstOrNull { it.type == "statement_identifier" }
                                    if (labelChild == null) {
                                        problems.add(Problem(
                                            startByte = n.startByte,
                                            endByte = n.endByte,
                                            message = "Unnecessary 'continue' at end of loop.",
                                            severity = Severity.HINT,
                                            inspectionId = "unnecessary_continue_statement"
                                        ))
                                    }
                                }
                            }
                        }
                    }
                    for (child in n.children) walk(child)
                }
                walk(node)
                problems
            }
        )
    ))

    InspectionRegistry.register(InspectionSpec(
        id = "var_not_at_beginning",
        title = "'var' declared not at the beginning of a function",
        description = "Variables declared with 'var' are hoisted to the top of the function; declare them at the top.",
        languageId = "javascript",
        category = listOf("Code Style"),
        defaultSeverity = Severity.HINT,
        type = InspectionType.Walk(
            check = { node, fullText, _ ->
                val problems = mutableListOf<Problem>()
                fun walk(n: SyntaxNode) {
                    if (n.type == "variable_declaration" && n.children.any { it.type == "var" }) {
                        var parent = n.parent
                        while (parent != null) {
                            if (parent.type == "statement_block" && parent.parent?.type in setOf("function_declaration", "function_expression", "arrow_function")) {
                                val realStmts = parent.children.filter { it.type != "{" && it.type != "}" }
                                if (realStmts.firstOrNull() != n) {
                                    problems.add(Problem(
                                        startByte = n.startByte,
                                        endByte = n.endByte,
                                        message = "'var' declaration not at the top of the function.",
                                        severity = Severity.HINT,
                                        inspectionId = "var_not_at_beginning"
                                    ))
                                }
                                break
                            }
                            parent = parent.parent
                        }
                    }
                    for (child in n.children) walk(child)
                }
                walk(node)
                problems
            }
        )
    ))

    InspectionRegistry.register(InspectionSpec(
        id = "primitive_wrapper_object",
        title = "Primitive wrapper object used",
        description = "Using 'new Number()', 'new String()', etc. creates an object, not a primitive; use the literal form.",
        languageId = "javascript",
        category = listOf("General"),
        defaultSeverity = Severity.WARNING,
        type = InspectionType.Walk(
            check = { node, fullText, _ ->
                val problems = mutableListOf<Problem>()
                val wrappers = setOf("Number", "String", "Boolean", "Symbol", "BigInt")
                fun walk(n: SyntaxNode) {
                    if (n.type == "new_expression") {
                        val ctor = n.children.firstOrNull { it.type == "identifier" }
                        if (ctor != null) {
                            val name = fullText.substring(ctor.startByte.toInt(), ctor.endByte.toInt())
                            if (name in wrappers) {
                                problems.add(Problem(
                                    startByte = n.startByte,
                                    endByte = n.endByte,
                                    message = "Primitive wrapper object '$name'.",
                                    severity = Severity.WARNING,
                                    inspectionId = "primitive_wrapper_object"
                                ))
                            }
                        }
                    }
                    for (child in n.children) walk(child)
                }
                walk(node)
                problems
            }
        )
    ))

    InspectionRegistry.register(InspectionSpec(
        id = "anonymous_function",
        title = "Anonymous function",
        description = "Anonymous function expression should be named or replaced with an arrow function.",
        languageId = "javascript",
        category = listOf("Potentially Undesirable"),
        defaultSeverity = Severity.HINT,
        type = InspectionType.Walk(
            check = { node, fullText, _ ->
                val problems = mutableListOf<Problem>()
                fun walk(n: SyntaxNode) {
                    if (n.type == "function_expression") {
                        val secondChild = n.children.toList().getOrNull(1)
                        if (secondChild?.type != "identifier") {
                            problems.add(Problem(
                                startByte = n.startByte,
                                endByte = n.endByte,
                                message = "Anonymous function expression.",
                                severity = Severity.HINT,
                                inspectionId = "anonymous_function"
                            ))
                        }
                    }
                    for (child in n.children) walk(child)
                }
                walk(node)
                problems
            }
        )
    ))

    InspectionRegistry.register(InspectionSpec(
        id = "redundant_local_variable",
        title = "Redundant local variable",
        description = "Variable assigned an expression then immediately returned; return the expression directly.",
        languageId = "javascript",
        category = listOf("Data Flow"),
        defaultSeverity = Severity.HINT,
        type = InspectionType.Walk(
            check = { node, fullText, _ ->
                val problems = mutableListOf<Problem>()
                fun walk(n: SyntaxNode) {
                    if (n.type == "statement_block") {
                        val realStmts = n.children.filter { it.type != "{" && it.type != "}" }
                        for (i in 0 until realStmts.size - 1) {
                            val stmt = realStmts[i]
                            val next = realStmts[i + 1]
                            if (stmt.type == "variable_declaration" && next.type == "return_statement") {
                                val decl = stmt.children.firstOrNull { it.type == "variable_declarator" }
                                if (decl != null && decl.children.any { it.type == "=" }) {
                                    val idNode = decl.children.firstOrNull { it.type == "identifier" }
                                    val retId = next.children.firstOrNull { it.type == "identifier" }
                                    if (idNode != null && retId != null) {
                                        val declName = fullText.substring(idNode.startByte.toInt(), idNode.endByte.toInt())
                                        val retName = fullText.substring(retId.startByte.toInt(), retId.endByte.toInt())
                                        if (declName == retName) {
                                            problems.add(Problem(
                                                startByte = stmt.startByte,
                                                endByte = next.endByte,
                                                message = "Redundant local variable '$declName'; return the expression directly.",
                                                severity = Severity.HINT,
                                                inspectionId = "redundant_local_variable"
                                            ))
                                        }
                                    }
                                }
                            }
                        }
                    }
                    for (child in n.children) walk(child)
                }
                walk(node)
                problems
            }
        )
    ))

    InspectionRegistry.register(InspectionSpec(
        id = "break_with_label",
        title = "'break' statement with label",
        description = "Labeled break can usually be replaced with structured control flow.",
        languageId = "javascript",
        category = listOf("Potentially Undesirable"),
        defaultSeverity = Severity.HINT,
        type = InspectionType.Walk(
            check = { node, fullText, _ ->
                val problems = mutableListOf<Problem>()
                fun walk(n: SyntaxNode) {
                    if (n.type == "break_statement") {
                        val labelNode = n.children.firstOrNull { it.type == "statement_identifier" }
                        if (labelNode != null) {
                            problems.add(Problem(
                                startByte = n.startByte,
                                endByte = n.endByte,
                                message = "'break' with label.",
                                severity = Severity.HINT,
                                inspectionId = "break_with_label"
                            ))
                        }
                    }
                    for (child in n.children) walk(child)
                }
                walk(node)
                problems
            }
        )
    ))

    InspectionRegistry.register(InspectionSpec(
        id = "continue_with_label",
        title = "'continue' statement with label",
        description = "Labeled continue can usually be replaced with structured control flow.",
        languageId = "javascript",
        category = listOf("Potentially Undesirable"),
        defaultSeverity = Severity.HINT,
        type = InspectionType.Walk(
            check = { node, fullText, _ ->
                val problems = mutableListOf<Problem>()
                fun walk(n: SyntaxNode) {
                    if (n.type == "continue_statement") {
                        val labelNode = n.children.firstOrNull { it.type == "statement_identifier" }
                        if (labelNode != null) {
                            problems.add(Problem(
                                startByte = n.startByte,
                                endByte = n.endByte,
                                message = "'continue' with label.",
                                severity = Severity.HINT,
                                inspectionId = "continue_with_label"
                            ))
                        }
                    }
                    for (child in n.children) walk(child)
                }
                walk(node)
                problems
            }
        )
    ))

    InspectionRegistry.register(InspectionSpec(
        id = "empty_try_block",
        title = "Empty try block",
        description = "An empty try block has no effect.",
        languageId = "javascript",
        category = listOf("Try Statement Issues"),
        defaultSeverity = Severity.WARNING,
        type = InspectionType.Walk(
            check = { node, fullText, _ ->
                val problems = mutableListOf<Problem>()
                fun walk(n: SyntaxNode) {
                    if (n.type == "try_statement") {
                        val body = n.children.firstOrNull { it.type == "statement_block" }
                        if (body != null) {
                            val realStmts = body.children.filter { it.type != "{" && it.type != "}" }
                            if (realStmts.isEmpty()) {
                                problems.add(Problem(
                                    startByte = n.startByte,
                                    endByte = n.endByte,
                                    message = "Empty try block.",
                                    severity = Severity.WARNING,
                                    inspectionId = "empty_try_block"
                                ))
                            }
                        }
                    }
                    for (child in n.children) walk(child)
                }
                walk(node)
                problems
            }
        )
    ))

    InspectionRegistry.register(InspectionSpec(
        id = "chained_equality",
        title = "Chained equality",
        description = "Chained equality checks can behave unexpectedly; use explicit comparisons.",
        languageId = "javascript",
        category = listOf("Potential Bug"),
        defaultSeverity = Severity.WARNING,
        type = InspectionType.Walk(
            check = { node, fullText, _ ->
                val problems = mutableListOf<Problem>()
                val eqOps = setOf("==", "!=", "===", "!==")
                fun walk(n: SyntaxNode) {
                    if (n.type == "binary_expression") {
                        val children = n.children.toList()
                        val op = children.getOrNull(1)
                        if (op != null && op.type in eqOps) {
                            val left = children.getOrNull(0)
                            val right = children.getOrNull(2)
                            if (left?.type == "binary_expression" || right?.type == "binary_expression") {
                                problems.add(Problem(
                                    startByte = n.startByte,
                                    endByte = n.endByte,
                                    message = "Chained equality comparison.",
                                    severity = Severity.WARNING,
                                    inspectionId = "chained_equality"
                                ))
                            }
                        }
                    }
                    for (child in n.children) walk(child)
                }
                walk(node)
                problems
            }
        )
    ))

    InspectionRegistry.register(InspectionSpec(
        id = "unnecessary_parentheses",
        title = "Unnecessary parentheses",
        description = "Parentheses around an expression are unnecessary and can be removed.",
        languageId = "javascript",
        category = listOf("Code Style"),
        defaultSeverity = Severity.HINT,
        type = InspectionType.Walk(
            check = { node, fullText, _ ->
                val problems = mutableListOf<Problem>()
                fun walk(n: SyntaxNode) {
                    if (n.type == "parenthesized_expression") {
                        val parent = n.parent
                        if (parent?.type in setOf("expression_statement", "return_statement", "throw_statement")) {
                            problems.add(Problem(
                                startByte = n.startByte,
                                endByte = n.endByte,
                                message = "Unnecessary parentheses.",
                                severity = Severity.HINT,
                                inspectionId = "unnecessary_parentheses"
                            ))
                        }
                    }
                    for (child in n.children) walk(child)
                }
                walk(node)
                problems
            }
        )
    ))

    InspectionRegistry.register(InspectionSpec(
        id = "result_of_increment_used",
        title = "Result of increment or decrement used",
        description = "Using the return value of ++/-- can be confusing; split into separate statements.",
        languageId = "javascript",
        category = listOf("Potentially Confusing"),
        defaultSeverity = Severity.WARNING,
        type = InspectionType.Walk(
            check = { node, fullText, _ ->
                val problems = mutableListOf<Problem>()
                fun walk(n: SyntaxNode) {
                    if (n.type == "update_expression") {
                        val parent = n.parent
                        if (parent?.type != "expression_statement") {
                            problems.add(Problem(
                                startByte = n.startByte,
                                endByte = n.endByte,
                                message = "Result of increment/decrement used as value.",
                                severity = Severity.WARNING,
                                inspectionId = "result_of_increment_used"
                            ))
                        }
                    }
                    for (child in n.children) walk(child)
                }
                walk(node)
                problems
            }
        )
    ))

    InspectionRegistry.register(InspectionSpec(
        id = "redundant_arrow_braces",
        title = "Redundant braces around arrow function body",
        description = "Arrow function with a single return statement can omit braces and the 'return' keyword.",
        languageId = "javascript",
        category = listOf("Code Style"),
        defaultSeverity = Severity.HINT,
        type = InspectionType.Walk(
            check = { node, fullText, _ ->
                val problems = mutableListOf<Problem>()
                fun walk(n: SyntaxNode) {
                    if (n.type == "arrow_function") {
                        val body = n.children.toList().getOrNull(2)
                        if (body?.type == "statement_block") {
                            val realStmts = body.children.filter { it.type != "{" && it.type != "}" && it.type != ";" }
                            if (realStmts.size == 1 && realStmts[0].type == "return_statement") {
                                problems.add(Problem(
                                    startByte = body.startByte,
                                    endByte = body.endByte,
                                    message = "Redundant braces around arrow function body.",
                                    severity = Severity.HINT,
                                    inspectionId = "redundant_arrow_braces"
                                ))
                            }
                        }
                    }
                    for (child in n.children) walk(child)
                }
                walk(node)
                problems
            }
        )
    ))

    InspectionRegistry.register(InspectionSpec(
        id = "nested_ternary",
        title = "Nested conditional expression",
        description = "Ternary expressions should not be nested; extract into separate statements.",
        languageId = "javascript",
        category = listOf("Code Style"),
        defaultSeverity = Severity.HINT,
        type = InspectionType.Walk(
            check = { node, fullText, _ ->
                val problems = mutableListOf<Problem>()
                fun walk(n: SyntaxNode) {
                    if (n.type == "ternary_expression") {
                        val children = n.children.toList()
                        val conseq = children.getOrNull(2)
                        val alt = children.getOrNull(4)
                        fun hasNestedTernary(node: SyntaxNode): Boolean {
                            if (node.type == "ternary_expression") return true
                            return node.children.any { hasNestedTernary(it) }
                        }
                        if ((conseq != null && hasNestedTernary(conseq)) || (alt != null && hasNestedTernary(alt))) {
                            problems.add(Problem(
                                startByte = n.startByte,
                                endByte = n.endByte,
                                message = "Nested ternary expression.",
                                severity = Severity.HINT,
                                inspectionId = "nested_ternary"
                            ))
                        }
                    }
                    for (child in n.children) walk(child)
                }
                walk(node)
                problems
            }
        )
    ))

    InspectionRegistry.register(InspectionSpec(
        id = "nested_function",
        title = "Nested function",
        description = "Nested function declarations create closures on each invocation.",
        languageId = "javascript",
        category = listOf("Potentially Confusing"),
        defaultSeverity = Severity.HINT,
        type = InspectionType.Walk(
            check = { node, fullText, _ ->
                val problems = mutableListOf<Problem>()
                var depth = 0
                fun walk(n: SyntaxNode) {
                    if (n.type in setOf("function_declaration", "function_expression", "arrow_function")) {
                        depth++
                        if (depth > 1) {
                            problems.add(Problem(
                                startByte = n.startByte,
                                endByte = n.endByte,
                                message = "Nested function.",
                                severity = Severity.HINT,
                                inspectionId = "nested_function"
                            ))
                        }
                        for (child in n.children) walk(child)
                        depth--
                    } else {
                        for (child in n.children) walk(child)
                    }
                }
                walk(node)
                problems
            }
        )
    ))

    InspectionRegistry.register(InspectionSpec(
        id = "confusing_plus_minus",
        title = "Confusing sequence of '+' or '-'",
        description = "Adjacent unary '+' or '-' operators can be confused with increment/decrement.",
        languageId = "javascript",
        category = listOf("Potentially Confusing"),
        defaultSeverity = Severity.HINT,
        type = InspectionType.Walk(
            check = { node, fullText, _ ->
                val problems = mutableListOf<Problem>()
                fun walk(n: SyntaxNode) {
                    if (n.type == "binary_expression") {
                        val children = n.children.toList()
                        val op = children.getOrNull(1)
                        if (op != null && op.type in setOf("+", "-")) {
                            val left = children.getOrNull(0)
                            val right = children.getOrNull(2)
                            val hasUnary = (left?.type == "unary_expression" && left.children.any { it.type in setOf("+", "-") }) ||
                                (right?.type == "unary_expression" && right.children.any { it.type in setOf("+", "-") })
                            if (hasUnary) {
                                problems.add(Problem(
                                    startByte = n.startByte,
                                    endByte = n.endByte,
                                    message = "Confusing sequence of '${op.type}' operators.",
                                    severity = Severity.HINT,
                                    inspectionId = "confusing_plus_minus"
                                ))
                            }
                        }
                    }
                    for (child in n.children) walk(child)
                }
                walk(node)
                problems
            }
        )
    ))

    InspectionRegistry.register(InspectionSpec(
        id = "pointless_arithmetic",
        title = "Pointless arithmetic expression",
        description = "Arithmetic expression that does not change the value (e.g., x + 0, x * 1).",
        languageId = "javascript",
        category = listOf("Potentially Confusing"),
        defaultSeverity = Severity.HINT,
        type = InspectionType.Walk(
            check = { node, fullText, _ ->
                val problems = mutableListOf<Problem>()
                fun walk(n: SyntaxNode) {
                    if (n.type == "binary_expression") {
                        val children = n.children.toList()
                        val op = children.getOrNull(1)
                        val left = children.getOrNull(0)
                        val right = children.getOrNull(2)
                        if (op != null && left != null && right != null) {
                            val rightText = fullText.substring(right.startByte.toInt(), right.endByte.toInt())
                            val leftText = fullText.substring(left.startByte.toInt(), left.endByte.toInt())
                            val pointless = (op.type == "+" && (rightText == "0" || leftText == "0")) ||
                                (op.type == "-" && rightText == "0") ||
                                (op.type == "*" && (rightText == "1" || leftText == "1")) ||
                                (op.type == "/" && rightText == "1")
                            if (pointless) {
                                problems.add(Problem(
                                    startByte = n.startByte,
                                    endByte = n.endByte,
                                    message = "Pointless arithmetic expression.",
                                    severity = Severity.HINT,
                                    inspectionId = "pointless_arithmetic"
                                ))
                            }
                        }
                    }
                    for (child in n.children) walk(child)
                }
                walk(node)
                problems
            }
        )
    ))

    InspectionRegistry.register(InspectionSpec(
        id = "empty_catch_parameter",
        title = "Empty 'catch' parameter",
        description = "The catch block parameter is not used; consider omitting it.",
        languageId = "javascript",
        category = listOf("Try Statement Issues"),
        defaultSeverity = Severity.HINT,
        type = InspectionType.Walk(
            check = { node, fullText, _ ->
                val problems = mutableListOf<Problem>()
                fun walk(n: SyntaxNode) {
                    if (n.type == "catch_clause") {
                        val param = n.children.firstOrNull { it.type == "identifier" }
                        val body = n.children.firstOrNull { it.type == "statement_block" }
                        if (param != null && body != null) {
                            val paramName = fullText.substring(param.startByte.toInt(), param.endByte.toInt())
                            val bodyText = fullText.substring(body.startByte.toInt(), body.endByte.toInt())
                            if (!bodyText.contains(paramName)) {
                                problems.add(Problem(
                                    startByte = param.startByte,
                                    endByte = param.endByte,
                                    message = "Unused catch parameter '$paramName'.",
                                    severity = Severity.HINT,
                                    inspectionId = "empty_catch_parameter"
                                ))
                            }
                        }
                    }
                    for (child in n.children) walk(child)
                }
                walk(node)
                problems
            }
        )
    ))

    InspectionRegistry.register(InspectionSpec(
        id = "switch_no_default",
        title = "'switch' statement has no 'default' branch",
        description = "A switch statement should include a default branch to handle unexpected values.",
        languageId = "javascript",
        category = listOf("Switch Statement Issues"),
        defaultSeverity = Severity.WARNING,
        type = InspectionType.Walk(
            check = { node, fullText, _ ->
                val problems = mutableListOf<Problem>()
                fun walk(n: SyntaxNode) {
                    if (n.type == "switch_body" && n.parent?.type == "switch_statement") {
                        if (n.children.none { it.type == "switch_default" }) {
                            problems.add(Problem(
                                startByte = n.startByte,
                                endByte = n.endByte,
                                message = "'switch' statement has no 'default' branch.",
                                severity = Severity.WARNING,
                                inspectionId = "switch_no_default"
                            ))
                        }
                    }
                    for (child in n.children) walk(child)
                }
                walk(node)
                problems
            }
        )
    ))

    InspectionRegistry.register(InspectionSpec(
        id = "default_not_last_in_switch",
        title = "'default' not last case in 'switch'",
        description = "The default case should be the last case in a switch statement.",
        languageId = "javascript",
        category = listOf("Switch Statement Issues"),
        defaultSeverity = Severity.WARNING,
        type = InspectionType.Walk(
            check = { node, fullText, _ ->
                val problems = mutableListOf<Problem>()
                fun walk(n: SyntaxNode) {
                    if (n.type == "switch_body") {
                        val stmts = n.children.filter { it.type != "{" && it.type != "}" }
                        val defaultIdx = stmts.indexOfFirst { it.type == "switch_default" }
                        if (defaultIdx >= 0) {
                            for (i in defaultIdx + 1 until stmts.size) {
                                if (stmts[i].type == "switch_case") {
                                    problems.add(Problem(
                                        startByte = stmts[defaultIdx].startByte,
                                        endByte = stmts[defaultIdx].endByte,
                                        message = "'default' should be the last case in switch.",
                                        severity = Severity.WARNING,
                                        inspectionId = "default_not_last_in_switch"
                                    ))
                                    break
                                }
                            }
                        }
                    }
                    for (child in n.children) walk(child)
                }
                walk(node)
                problems
            }
        )
    ))

    InspectionRegistry.register(InspectionSpec(
        id = "return_in_finally",
        title = "'return' inside 'finally' block",
        description = "A return statement inside a finally block will override any exception or return from try/catch.",
        languageId = "javascript",
        category = listOf("Try Statement Issues"),
        defaultSeverity = Severity.WARNING,
        type = InspectionType.Walk(
            check = { node, fullText, _ ->
                val problems = mutableListOf<Problem>()
                fun walk(n: SyntaxNode) {
                    if (n.type == "return_statement") {
                        var parent = n.parent
                        var inFinally = false
                        while (parent != null) {
                            if (parent.type == "finally_clause") {
                                inFinally = true
                                break
                            }
                            parent = parent.parent
                        }
                        if (inFinally) {
                            problems.add(Problem(
                                startByte = n.startByte,
                                endByte = n.endByte,
                                message = "'return' inside 'finally' block.",
                                severity = Severity.WARNING,
                                inspectionId = "return_in_finally"
                            ))
                        }
                    }
                    for (child in n.children) walk(child)
                }
                walk(node)
                problems
            }
        )
    ))

    InspectionRegistry.register(InspectionSpec(
        id = "duplicate_condition_if",
        title = "Duplicate condition in 'if' statement",
        description = "A condition that appears in both an if and its else-if branch is likely a bug.",
        languageId = "javascript",
        category = listOf("Potential Bug"),
        defaultSeverity = Severity.WARNING,
        type = InspectionType.Walk(
            check = { node, fullText, _ ->
                val problems = mutableListOf<Problem>()
                fun walk(n: SyntaxNode) {
                    if (n.type == "if_statement") {
                        val children = n.children.toList()
                        val cond = children.getOrNull(1)
                        val elseClause = children.firstOrNull { it.type == "else_clause" }
                        if (cond != null && elseClause != null) {
                            val elseIf = elseClause.children.firstOrNull { it.type == "if_statement" }
                            if (elseIf != null) {
                                val elseCond = elseIf.children.toList().getOrNull(1)
                                if (elseCond != null) {
                                    val condText = fullText.substring(cond.startByte.toInt(), cond.endByte.toInt())
                                    val elseCondText = fullText.substring(elseCond.startByte.toInt(), elseCond.endByte.toInt())
                                    if (condText == elseCondText) {
                                        problems.add(Problem(
                                            startByte = elseCond.startByte,
                                            endByte = elseCond.endByte,
                                            message = "Duplicate condition in 'else if'.",
                                            severity = Severity.WARNING,
                                            inspectionId = "duplicate_condition_if"
                                        ))
                                    }
                                }
                            }
                        }
                    }
                    for (child in n.children) walk(child)
                }
                walk(node)
                problems
            }
        )
    ))

    InspectionRegistry.register(InspectionSpec(
        id = "for_replaceable_by_while",
        title = "'for' loop may be replaced by 'while' loop",
        description = "A 'for' loop with no initialization and no increment can be simplified to a 'while' loop.",
        languageId = "javascript",
        category = listOf("Control Flow"),
        defaultSeverity = Severity.HINT,
        type = InspectionType.Walk(
            check = { node, fullText, _ ->
                val problems = mutableListOf<Problem>()
                fun walk(n: SyntaxNode) {
                    if (n.type == "for_statement") {
                        val children = n.children.toList()
                        val init = children.getOrNull(2)
                        if (init?.type == "empty_statement") {
                            val cond = children.getOrNull(3)
                            if (cond != null && cond.type != "empty_statement" && cond.type != ")") {
                                val next1 = children.getOrNull(4)
                                val next2 = children.getOrNull(5)
                                val noIncrement = next1?.type == ")" || (next1?.type == ";" && next2?.type == ")")
                                if (noIncrement) {
                                    problems.add(Problem(
                                        startByte = n.startByte,
                                        endByte = n.endByte,
                                        message = "'for' loop may be replaced by 'while' loop.",
                                        severity = Severity.HINT,
                                        inspectionId = "for_replaceable_by_while"
                                    ))
                                }
                            }
                        }
                    }
                    for (child in n.children) walk(child)
                }
                walk(node)
                problems
            }
        )
    ))

    InspectionRegistry.register(InspectionSpec(
        id = "infinite_loop",
        title = "Infinite loop statement",
        description = "A 'for' loop with an empty condition runs indefinitely.",
        languageId = "javascript",
        category = listOf("Probable Bugs"),
        defaultSeverity = Severity.WARNING,
        type = InspectionType.Walk(
            check = { node, fullText, _ ->
                val problems = mutableListOf<Problem>()
                fun walk(n: SyntaxNode) {
                    if (n.type == "for_statement") {
                        val children = n.children.toList()
                        val init = children.getOrNull(2)
                        val cond = children.getOrNull(3)
                        if (init?.type == "empty_statement" && cond?.type == "empty_statement") {
                            problems.add(Problem(
                                startByte = n.startByte,
                                endByte = n.endByte,
                                message = "Infinite loop statement.",
                                severity = Severity.WARNING,
                                inspectionId = "infinite_loop"
                            ))
                        }
                    }
                    for (child in n.children) walk(child)
                }
                walk(node)
                problems
            }
        )
    ))

    InspectionRegistry.register(InspectionSpec(
        id = "object_property_shorthand",
        title = "Property can be replaced with shorthand",
        description = "Object property with the same key and value can use shorthand syntax.",
        languageId = "javascript",
        category = listOf("Code Style"),
        defaultSeverity = Severity.HINT,
        type = InspectionType.Walk(
            check = { node, fullText, _ ->
                val problems = mutableListOf<Problem>()
                fun walk(n: SyntaxNode) {
                    if (n.type == "pair") {
                        val key = n.children.firstOrNull { it.type == "property_identifier" }
                        val value = n.children.firstOrNull { it.type == "identifier" }
                        if (key != null && value != null) {
                            val keyText = fullText.substring(key.startByte.toInt(), key.endByte.toInt())
                            val valueText = fullText.substring(value.startByte.toInt(), value.endByte.toInt())
                            if (keyText == valueText) {
                                problems.add(Problem(
                                    startByte = n.startByte,
                                    endByte = n.endByte,
                                    message = "Object property '$keyText' can use shorthand syntax.",
                                    severity = Severity.HINT,
                                    inspectionId = "object_property_shorthand"
                                ))
                            }
                        }
                    }
                    for (child in n.children) walk(child)
                }
                walk(node)
                problems
            }
        )
    ))

    InspectionRegistry.register(InspectionSpec(
        id = "destructuring_same_key",
        title = "Destructuring properties with the same key",
        description = "Destructuring property with the same key and variable name can use shorthand syntax.",
        languageId = "javascript",
        category = listOf("Code Style"),
        defaultSeverity = Severity.HINT,
        type = InspectionType.Walk(
            check = { node, fullText, _ ->
                val problems = mutableListOf<Problem>()
                fun walk(n: SyntaxNode) {
                    if (n.type == "pair_pattern") {
                        val key = n.children.firstOrNull { it.type == "property_identifier" }
                        val value = n.children.firstOrNull { it.type == "identifier" }
                        if (key != null && value != null) {
                            val keyText = fullText.substring(key.startByte.toInt(), key.endByte.toInt())
                            val valueText = fullText.substring(value.startByte.toInt(), value.endByte.toInt())
                            if (keyText == valueText) {
                                problems.add(Problem(
                                    startByte = n.startByte,
                                    endByte = n.endByte,
                                    message = "Destructuring property '$keyText' can use shorthand syntax.",
                                    severity = Severity.HINT,
                                    inspectionId = "destructuring_same_key"
                                ))
                            }
                        }
                    }
                    for (child in n.children) walk(child)
                }
                walk(node)
                problems
            }
        )
    ))

    InspectionRegistry.register(InspectionSpec(
        id = "redundant_await",
        title = "Redundant 'await' expression",
        description = "'await' on a non-thenable value (like a literal) is redundant.",
        languageId = "javascript",
        category = listOf("Async Code and Promises"),
        defaultSeverity = Severity.HINT,
        type = InspectionType.Walk(
            check = { node, fullText, _ ->
                val problems = mutableListOf<Problem>()
                val literalTypes = setOf("number", "string", "true", "false", "null")
                fun walk(n: SyntaxNode) {
                    if (n.type == "await_expression") {
                        val expr = n.children.firstOrNull { it.type != "await" }
                        if (expr != null && expr.type in literalTypes) {
                            problems.add(Problem(
                                startByte = n.startByte,
                                endByte = n.endByte,
                                message = "Redundant 'await' expression.",
                                severity = Severity.HINT,
                                inspectionId = "redundant_await"
                            ))
                        }
                    }
                    for (child in n.children) walk(child)
                }
                walk(node)
                problems
            }
        )
    ))

    InspectionRegistry.register(InspectionSpec(
        id = "object_null_undefined",
        title = "Object is 'null' or 'undefined'",
        description = "Using '===' or '!==' to compare with 'null' or 'undefined' may indicate a control flow issue.",
        languageId = "javascript",
        category = listOf("Control Flow"),
        defaultSeverity = Severity.HINT,
        type = InspectionType.Walk(
            check = { node, fullText, _ ->
                val problems = mutableListOf<Problem>()
                val nullishTypes = setOf("null", "undefined")
                fun walk(n: SyntaxNode) {
                    if (n.type == "binary_expression") {
                        val children = n.children.toList()
                        val op = children.getOrNull(1)
                        if (op?.type in setOf("===", "!==")) {
                            val left = children.getOrNull(0)
                            val right = children.getOrNull(2)
                            if (left?.type in nullishTypes || right?.type in nullishTypes) {
                                problems.add(Problem(
                                    startByte = n.startByte,
                                    endByte = n.endByte,
                                    message = "Comparison to 'null' or 'undefined'.",
                                    severity = Severity.HINT,
                                    inspectionId = "object_null_undefined"
                                ))
                            }
                        }
                    }
                    for (child in n.children) walk(child)
                }
                walk(node)
                problems
            }
        )
    ))

    InspectionRegistry.register(InspectionSpec(
        id = "unnecessary_label",
        title = "Unnecessary label",
        description = "A label that is not referenced by any break or continue statement is unnecessary.",
        languageId = "javascript",
        category = listOf("Control Flow"),
        defaultSeverity = Severity.HINT,
        type = InspectionType.Walk(
            check = { node, fullText, _ ->
                val problems = mutableListOf<Problem>()
                fun walk(n: SyntaxNode) {
                    if (n.type == "labeled_statement") {
                        val labelNode = n.children.firstOrNull { it.type == "statement_identifier" } ?: return@walk
                        val labelName = fullText.substring(labelNode.startByte.toInt(), labelNode.endByte.toInt())
                        val body = n.children.toList().lastOrNull() ?: return@walk
                        var found = false
                        fun findRef(nn: SyntaxNode) {
                            if (nn.type in setOf("break_statement", "continue_statement")) {
                                val ref = nn.children.firstOrNull { it.type == "statement_identifier" }
                                if (ref != null) {
                                    val refName = fullText.substring(ref.startByte.toInt(), ref.endByte.toInt())
                                    if (refName == labelName) found = true
                                }
                            }
                            if (!found) {
                                for (child in nn.children) findRef(child)
                            }
                        }
                        findRef(body)
                        if (!found) {
                            problems.add(Problem(
                                startByte = n.startByte,
                                endByte = n.endByte,
                                message = "Unnecessary label '$labelName'.",
                                severity = Severity.HINT,
                                inspectionId = "unnecessary_label"
                            ))
                        }
                    }
                    for (child in n.children) walk(child)
                }
                walk(node)
                problems
            }
        )
    ))

    InspectionRegistry.register(InspectionSpec(
        id = "result_of_object_allocation_ignored",
        title = "Result of object allocation ignored",
        description = "Calling 'new' without using the result may indicate a bug or missing assignment.",
        languageId = "javascript",
        category = listOf("Probable Bugs"),
        defaultSeverity = Severity.WARNING,
        type = InspectionType.Walk(
            check = { node, fullText, _ ->
                val problems = mutableListOf<Problem>()
                fun walk(n: SyntaxNode) {
                    if (n.type == "new_expression") {
                        val parent = n.parent
                        if (parent?.type == "expression_statement") {
                            problems.add(Problem(
                                startByte = n.startByte,
                                endByte = n.endByte,
                                message = "Result of object allocation ignored.",
                                severity = Severity.WARNING,
                                inspectionId = "result_of_object_allocation_ignored"
                            ))
                        }
                    }
                    for (child in n.children) walk(child)
                }
                walk(node)
                problems
            }
        )
    ))

    InspectionRegistry.register(InspectionSpec(
        id = "break_continue_in_finally",
        title = "'break' or 'continue' inside 'finally' block",
        description = "A 'break' or 'continue' in a 'finally' block can override control flow from try/catch.",
        languageId = "javascript",
        category = listOf("Try Statement Issues"),
        defaultSeverity = Severity.WARNING,
        type = InspectionType.Walk(
            check = { node, fullText, _ ->
                val problems = mutableListOf<Problem>()
                fun walk(n: SyntaxNode) {
                    if (n.type in setOf("break_statement", "continue_statement")) {
                        var parent = n.parent
                        var inFinally = false
                        while (parent != null) {
                            if (parent.type == "finally_clause") {
                                inFinally = true
                                break
                            }
                            parent = parent.parent
                        }
                        if (inFinally) {
                            problems.add(Problem(
                                startByte = n.startByte,
                                endByte = n.endByte,
                                message = "'${n.type}' inside 'finally' block.",
                                severity = Severity.WARNING,
                                inspectionId = "break_continue_in_finally"
                            ))
                        }
                    }
                    for (child in n.children) walk(child)
                }
                walk(node)
                problems
            }
        )
    ))

    InspectionRegistry.register(InspectionSpec(
        id = "unneeded_trailing_comma_array",
        title = "Unneeded trailing comma in array literal",
        description = "Trailing commas in array literals can be omitted.",
        languageId = "javascript",
        category = listOf("Code Style"),
        defaultSeverity = Severity.HINT,
        type = InspectionType.Walk(
            check = { node, fullText, _ ->
                val problems = mutableListOf<Problem>()
                fun walk(n: SyntaxNode) {
                    if (n.type == "array") {
                        val children = n.children.toList()
                        val beforeEnd = children.getOrNull(children.size - 2)
                        if (beforeEnd?.type == ",") {
                            problems.add(Problem(
                                startByte = beforeEnd.startByte,
                                endByte = beforeEnd.endByte,
                                message = "Unneeded trailing comma in array literal.",
                                severity = Severity.HINT,
                                inspectionId = "unneeded_trailing_comma_array"
                            ))
                        }
                    }
                    for (child in n.children) walk(child)
                }
                walk(node)
                problems
            }
        )
    ))

    InspectionRegistry.register(InspectionSpec(
        id = "unneeded_trailing_comma_object",
        title = "Unneeded trailing comma in object literal",
        description = "Trailing commas in object literals can be omitted.",
        languageId = "javascript",
        category = listOf("Code Style"),
        defaultSeverity = Severity.HINT,
        type = InspectionType.Walk(
            check = { node, fullText, _ ->
                val problems = mutableListOf<Problem>()
                fun walk(n: SyntaxNode) {
                    if (n.type == "object") {
                        val children = n.children.toList()
                        val beforeEnd = children.getOrNull(children.size - 2)
                        if (beforeEnd?.type == ",") {
                            problems.add(Problem(
                                startByte = beforeEnd.startByte,
                                endByte = beforeEnd.endByte,
                                message = "Unneeded trailing comma in object literal.",
                                severity = Severity.HINT,
                                inspectionId = "unneeded_trailing_comma_object"
                            ))
                        }
                    }
                    for (child in n.children) walk(child)
                }
                walk(node)
                problems
            }
        )
    ))

    InspectionRegistry.register(InspectionSpec(
        id = "negated_conditional_expression",
        title = "Negated conditional expression",
        description = "A negated condition in a ternary can be swapped for clarity.",
        languageId = "javascript",
        category = listOf("Potentially Confusing"),
        defaultSeverity = Severity.HINT,
        type = InspectionType.Walk(
            check = { node, fullText, _ ->
                val problems = mutableListOf<Problem>()
                fun walk(n: SyntaxNode) {
                    if (n.type == "ternary_expression") {
                        val cond = n.children.toList().getOrNull(0)
                        if (cond?.type == "unary_expression" && cond.children.any { it.type == "!" }) {
                            problems.add(Problem(
                                startByte = n.startByte,
                                endByte = n.endByte,
                                message = "Negated conditional expression; consider swapping branches.",
                                severity = Severity.HINT,
                                inspectionId = "negated_conditional_expression"
                            ))
                        }
                    }
                    for (child in n.children) walk(child)
                }
                walk(node)
                problems
            }
        )
    ))

    InspectionRegistry.register(InspectionSpec(
        id = "conditional_expression",
        title = "Conditional expression",
        description = "Ternary operators can reduce readability; consider an if/else statement.",
        languageId = "javascript",
        category = listOf("Potentially Undesirable"),
        defaultSeverity = Severity.HINT,
        type = InspectionType.Walk(
            check = { node, fullText, _ ->
                val problems = mutableListOf<Problem>()
                fun walk(n: SyntaxNode) {
                    if (n.type == "ternary_expression") {
                        problems.add(Problem(
                            startByte = n.startByte,
                            endByte = n.endByte,
                            message = "Conditional expression.",
                            severity = Severity.HINT,
                            inspectionId = "conditional_expression"
                        ))
                    }
                    for (child in n.children) walk(child)
                }
                walk(node)
                problems
            }
        )
    ))

    InspectionRegistry.register(InspectionSpec(
        id = "break_statement",
        title = "'break' statement",
        description = "'break' statement may indicate unstructured control flow.",
        languageId = "javascript",
        category = listOf("Potentially Undesirable"),
        defaultSeverity = Severity.HINT,
        type = InspectionType.Walk(
            check = { node, fullText, _ ->
                val problems = mutableListOf<Problem>()
                fun walk(n: SyntaxNode) {
                    if (n.type == "break_statement") {
                        val hasLabel = n.children.any { it.type == "statement_identifier" }
                        if (!hasLabel) {
                            var p = n.parent
                            var inSwitch = false
                            while (p != null) {
                                if (p.type == "switch_body") {
                                    inSwitch = true
                                    break
                                }
                                p = p.parent
                            }
                            if (!inSwitch) {
                                problems.add(Problem(
                                    startByte = n.startByte,
                                    endByte = n.endByte,
                                    message = "'break' statement.",
                                    severity = Severity.HINT,
                                    inspectionId = "break_statement"
                                ))
                            }
                        }
                    }
                    for (child in n.children) walk(child)
                }
                walk(node)
                problems
            }
        )
    ))

    InspectionRegistry.register(InspectionSpec(
        id = "continue_statement",
        title = "'continue' statement",
        description = "'continue' statement may indicate unstructured control flow.",
        languageId = "javascript",
        category = listOf("Potentially Undesirable"),
        defaultSeverity = Severity.HINT,
        type = InspectionType.Walk(
            check = { node, fullText, _ ->
                val problems = mutableListOf<Problem>()
                fun walk(n: SyntaxNode) {
                    if (n.type == "continue_statement") {
                        val hasLabel = n.children.any { it.type == "statement_identifier" }
                        if (!hasLabel) {
                            problems.add(Problem(
                                startByte = n.startByte,
                                endByte = n.endByte,
                                message = "'continue' statement.",
                                severity = Severity.HINT,
                                inspectionId = "continue_statement"
                            ))
                        }
                    }
                    for (child in n.children) walk(child)
                }
                walk(node)
                problems
            }
        )
    ))

    InspectionRegistry.register(InspectionSpec(
        id = "nested_switch_statement",
        title = "Nested 'switch' statement",
        description = "Nested switch statements are hard to read and should be refactored.",
        languageId = "javascript",
        category = listOf("Switch Statement Issues"),
        defaultSeverity = Severity.HINT,
        type = InspectionType.Walk(
            check = { node, fullText, _ ->
                val problems = mutableListOf<Problem>()
                fun walk(n: SyntaxNode) {
                    if (n.type == "switch_statement") {
                        var p = n.parent
                        var nested = false
                        while (p != null) {
                            if (p.type == "switch_statement") {
                                nested = true
                                break
                            }
                            p = p.parent
                        }
                        if (nested) {
                            problems.add(Problem(
                                startByte = n.startByte,
                                endByte = n.endByte,
                                message = "Nested 'switch' statement.",
                                severity = Severity.HINT,
                                inspectionId = "nested_switch_statement"
                            ))
                        }
                    }
                    for (child in n.children) walk(child)
                }
                walk(node)
                problems
            }
        )
    ))

    InspectionRegistry.register(InspectionSpec(
        id = "duplicate_case_label",
        title = "Duplicate 'case' label",
        description = "A switch statement contains multiple cases with the same value.",
        languageId = "javascript",
        category = listOf("Switch Statement Issues"),
        defaultSeverity = Severity.WARNING,
        type = InspectionType.Walk(
            check = { node, fullText, _ ->
                val problems = mutableListOf<Problem>()
                fun walk(n: SyntaxNode) {
                    if (n.type == "switch_body") {
                        val cases = n.children.filter { it.type == "switch_case" }
                        val seen = mutableSetOf<String>()
                        for (caseNode in cases) {
                            val valueNode = caseNode.children.getOrNull(1)
                            if (valueNode != null) {
                                val text = fullText.substring(valueNode.startByte.toInt(), valueNode.endByte.toInt())
                                if (!seen.add(text)) {
                                    problems.add(Problem(
                                        startByte = valueNode.startByte,
                                        endByte = valueNode.endByte,
                                        message = "Duplicate case label '$text'.",
                                        severity = Severity.WARNING,
                                        inspectionId = "duplicate_case_label"
                                    ))
                                }
                            }
                        }
                    }
                    for (child in n.children) walk(child)
                }
                walk(node)
                problems
            }
        )
    ))

    InspectionRegistry.register(InspectionSpec(
        id = "loop_statement_doesnt_loop",
        title = "Loop statement that doesn't loop",
        description = "The loop condition is always false on first evaluation; the body never executes.",
        languageId = "javascript",
        category = listOf("Control Flow"),
        defaultSeverity = Severity.WARNING,
        type = InspectionType.Walk(
            check = { node, fullText, _ ->
                val problems = mutableListOf<Problem>()
                fun walk(n: SyntaxNode) {
                    if (n.type == "for_statement") {
                        val cond = n.children.toList().getOrNull(3)
                        if (cond != null && (cond.type == "false" || (cond.type == "number" &&
                                fullText.substring(cond.startByte.toInt(), cond.endByte.toInt()) == "0"))) {
                            problems.add(Problem(
                                startByte = n.startByte,
                                endByte = n.endByte,
                                message = "Loop statement that doesn't loop.",
                                severity = Severity.WARNING,
                                inspectionId = "loop_statement_doesnt_loop"
                            ))
                        }
                    }
                    for (child in n.children) walk(child)
                }
                walk(node)
                problems
            }
        )
    ))

    InspectionRegistry.register(InspectionSpec(
        id = "await_in_non_async_function",
        title = "'await' in non-async function",
        description = "'await' used outside an async function is a syntax error.",
        languageId = "javascript",
        category = listOf("Async Code and Promises"),
        defaultSeverity = Severity.ERROR,
        type = InspectionType.Walk(
            check = { node, fullText, _ ->
                val problems = mutableListOf<Problem>()
                fun walk(n: SyntaxNode) {
                    if (n.type == "await_expression") {
                        var p = n.parent
                        var asyncFunc = false
                        while (p != null) {
                            if (p.type in setOf("function_declaration", "function_expression", "arrow_function")) {
                                if (p.children.any { it.type == "async" }) {
                                    asyncFunc = true
                                }
                                break
                            }
                            p = p.parent
                        }
                        if (!asyncFunc) {
                            problems.add(Problem(
                                startByte = n.startByte,
                                endByte = n.endByte,
                                message = "'await' used outside async function.",
                                severity = Severity.ERROR,
                                inspectionId = "await_in_non_async_function"
                            ))
                        }
                    }
                    for (child in n.children) walk(child)
                }
                walk(node)
                problems
            }
        )
    ))

    InspectionRegistry.register(InspectionSpec(
        id = "redundant_conditional_expression",
        title = "Redundant conditional expression",
        description = "A ternary that resolves to true/false can be simplified.",
        languageId = "javascript",
        category = listOf("Control Flow"),
        defaultSeverity = Severity.HINT,
        type = InspectionType.Walk(
            check = { node, fullText, _ ->
                val problems = mutableListOf<Problem>()
                fun walk(n: SyntaxNode) {
                    if (n.type == "ternary_expression") {
                        val children = n.children.toList()
                        val conseq = children.getOrNull(2)
                        val alt = children.getOrNull(4)
                        if (conseq != null && alt != null &&
                            ((conseq.type == "true" && alt.type == "false") || (conseq.type == "false" && alt.type == "true"))) {
                            problems.add(Problem(
                                startByte = n.startByte,
                                endByte = n.endByte,
                                message = "Redundant conditional expression; can be simplified to '!!condition'.",
                                severity = Severity.HINT,
                                inspectionId = "redundant_conditional_expression"
                            ))
                        }
                    }
                    for (child in n.children) walk(child)
                }
                walk(node)
                problems
            }
        )
    ))

    InspectionRegistry.register(InspectionSpec(
        id = "unfiltered_for_in_loop",
        title = "Unfiltered 'for...in' loop",
        description = "'for...in' without hasOwnProperty filter iterates over inherited properties.",
        languageId = "javascript",
        category = listOf("General"),
        defaultSeverity = Severity.WARNING,
        type = InspectionType.Walk(
            check = { node, fullText, _ ->
                val problems = mutableListOf<Problem>()
                fun walk(n: SyntaxNode) {
                    if (n.type == "for_in_statement") {
                        val bodyText = fullText.substring(n.startByte.toInt(), n.endByte.toInt())
                        if (!bodyText.contains("hasOwnProperty")) {
                            problems.add(Problem(
                                startByte = n.startByte,
                                endByte = n.endByte,
                                message = "'for...in' loop without hasOwnProperty filter.",
                                severity = Severity.WARNING,
                                inspectionId = "unfiltered_for_in_loop"
                            ))
                        }
                    }
                    for (child in n.children) walk(child)
                }
                walk(node)
                problems
            }
        )
    ))

    InspectionRegistry.register(InspectionSpec(
        id = "fallthrough_in_switch",
        title = "Fallthrough in 'switch' statement",
        description = "A case without a break/return statement will fall through to the next case.",
        languageId = "javascript",
        category = listOf("Switch Statement Issues"),
        defaultSeverity = Severity.WARNING,
        type = InspectionType.Walk(
            check = { node, fullText, _ ->
                val problems = mutableListOf<Problem>()
                val terminalTypes = setOf("break_statement", "return_statement", "throw_statement", "continue_statement")
                fun walk(n: SyntaxNode) {
                    if (n.type == "switch_body") {
                        val cases = n.children.filter { it.type in setOf("switch_case", "switch_default") }
                        for (i in 0 until cases.size - 1) {
                            val caseNode = cases[i]
                            val bodyChildren = caseNode.children.toList()
                            val afterColon = bodyChildren.dropWhile { it.type != ":" }.drop(1)
                            val realStmts = afterColon.filter { it.type != "{" && it.type != "}" && it.type != ";" }
                            if (realStmts.isNotEmpty()) {
                                val lastStmt = realStmts.last()
                                if (lastStmt.type !in terminalTypes) {
                                    if (lastStmt.type != "statement_block" || lastStmt.children.any { it.type !in setOf("{", "}") }) {
                                        problems.add(Problem(
                                            startByte = caseNode.startByte,
                                            endByte = cases[i + 1].startByte,
                                            message = "Fallthrough in switch case.",
                                            severity = Severity.WARNING,
                                            inspectionId = "fallthrough_in_switch"
                                        ))
                                    }
                                }
                            }
                        }
                    }
                    for (child in n.children) walk(child)
                }
                walk(node)
                problems
            }
        )
    ))

    InspectionRegistry.register(InspectionSpec(
        id = "consecutive_commas_in_array",
        title = "Consecutive commas in array literal",
        description = "Sparse arrays with consecutive commas can be confusing.",
        languageId = "javascript",
        category = listOf("Probable Bugs"),
        defaultSeverity = Severity.WARNING,
        type = InspectionType.Walk(
            check = { node, fullText, _ ->
                val problems = mutableListOf<Problem>()
                fun walk(n: SyntaxNode) {
                    if (n.type == "array") {
                        val children = n.children.toList()
                        for (i in 0 until children.size - 1) {
                            if (children[i].type == "," && children[i + 1].type == ",") {
                                problems.add(Problem(
                                    startByte = children[i].startByte,
                                    endByte = children[i + 1].endByte,
                                    message = "Consecutive commas in array literal.",
                                    severity = Severity.WARNING,
                                    inspectionId = "consecutive_commas_in_array"
                                ))
                                break
                            }
                        }
                    }
                    for (child in n.children) walk(child)
                }
                walk(node)
                problems
            }
        )
    ))

    InspectionRegistry.register(InspectionSpec(
        id = "infinite_recursion",
        title = "Infinite recursion",
        description = "A function that calls itself directly may cause infinite recursion.",
        languageId = "javascript",
        category = listOf("Probable Bugs"),
        defaultSeverity = Severity.HINT,
        type = InspectionType.Walk(
            check = { node, fullText, _ ->
                val problems = mutableListOf<Problem>()
                fun walk(n: SyntaxNode) {
                    if (n.type in setOf("function_declaration", "function_expression")) {
                        val nameNode = n.children.firstOrNull { it.type == "identifier" }
                        val body = n.children.firstOrNull { it.type == "statement_block" }
                        if (nameNode != null && body != null) {
                            val funcName = fullText.substring(nameNode.startByte.toInt(), nameNode.endByte.toInt())
                            var callsSelf = false
                            fun findCall(nn: SyntaxNode) {
                                if (nn.type == "call_expression") {
                                    val target = nn.children.firstOrNull { it.type == "identifier" }
                                    if (target != null) {
                                        val targetText = fullText.substring(target.startByte.toInt(), target.endByte.toInt())
                                        if (targetText == funcName) {
                                            callsSelf = true
                                        }
                                    }
                                }
                                if (!callsSelf && nn.type !in setOf("function_declaration", "function_expression", "arrow_function")) {
                                    for (child in nn.children) findCall(child)
                                }
                            }
                            findCall(body)
                            if (callsSelf) {
                                problems.add(Problem(
                                    startByte = n.startByte,
                                    endByte = n.endByte,
                                    message = "Function '$funcName' calls itself directly.",
                                    severity = Severity.HINT,
                                    inspectionId = "infinite_recursion"
                                ))
                            }
                        }
                    }
                    for (child in n.children) walk(child)
                }
                walk(node)
                problems
            }
        )
    ))

    InspectionRegistry.register(InspectionSpec(
        id = "use_of_caller_property",
        title = "Use of 'caller' or 'callee' property",
        description = "'caller' and 'callee' are deprecated and forbidden in strict mode.",
        languageId = "javascript",
        category = listOf("Potentially Confusing"),
        defaultSeverity = Severity.WARNING,
        type = InspectionType.Walk(
            check = { node, fullText, _ ->
                val problems = mutableListOf<Problem>()
                fun walk(n: SyntaxNode) {
                    if (n.type == "member_expression") {
                        val prop = n.children.firstOrNull { it.type == "property_identifier" }
                        if (prop != null) {
                            val name = fullText.substring(prop.startByte.toInt(), prop.endByte.toInt())
                            if (name in setOf("caller", "callee", "arguments")) {
                                problems.add(Problem(
                                    startByte = n.startByte,
                                    endByte = n.endByte,
                                    message = "Use of '$name' property.",
                                    severity = Severity.WARNING,
                                    inspectionId = "use_of_caller_property"
                                ))
                            }
                        }
                    }
                    for (child in n.children) walk(child)
                }
                walk(node)
                problems
            }
        )
    ))

    InspectionRegistry.register(InspectionSpec(
        id = "assignment_to_for_loop_parameter",
        title = "Assignment to 'for' loop parameter",
        description = "Assigning to the loop variable inside the loop body can cause unexpected behavior.",
        languageId = "javascript",
        category = listOf("Assignment Issues"),
        defaultSeverity = Severity.WARNING,
        type = InspectionType.Walk(
            check = { node, fullText, _ ->
                val problems = mutableListOf<Problem>()
                fun walk(n: SyntaxNode) {
                    if (n.type == "for_statement") {
                        val children = n.children.toList()
                        val init = children.getOrNull(2)
                        if (init?.type == "variable_declaration") {
                            val decl = init.children.firstOrNull { it.type == "variable_declarator" }
                            if (decl != null) {
                                val loopVar = decl.children.firstOrNull { it.type == "identifier" }
                                if (loopVar != null) {
                                    val varName = fullText.substring(loopVar.startByte.toInt(), loopVar.endByte.toInt())
                                    val body = children.lastOrNull()
                                    if (body != null) {
                                        fun findAssign(nn: SyntaxNode) {
                                            if (nn.type == "assignment_expression") {
                                                val left = nn.children.firstOrNull { it.type == "identifier" }
                                                if (left != null) {
                                                    val leftText = fullText.substring(left.startByte.toInt(), left.endByte.toInt())
                                                    if (leftText == varName) {
                                                        problems.add(Problem(
                                                            startByte = nn.startByte,
                                                            endByte = nn.endByte,
                                                            message = "Assignment to 'for' loop parameter '$varName'.",
                                                            severity = Severity.WARNING,
                                                            inspectionId = "assignment_to_for_loop_parameter"
                                                        ))
                                                    }
                                                }
                                            }
                                            if (nn.type != "for_statement") {
                                                for (child in nn.children) findAssign(child)
                                            }
                                        }
                                        findAssign(body)
                                    }
                                }
                            }
                        }
                    }
                    for (child in n.children) walk(child)
                }
                walk(node)
                problems
            }
        )
    ))
}
