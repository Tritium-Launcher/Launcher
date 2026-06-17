package io.github.tritium_launcher.launcher.ui.project.editor.treesitter

object JavaScriptNodeTypes {

    private val tokenMap = mapOf(
        "comment" to "Comment",
        "line_comment" to "Comment",
        "block_comment" to "Comment",
        "hash_bang_line" to "Comment",

        "string" to "String",
        "template_string" to "String",
        "template_literal" to "String",
        "string_fragment" to "String",
        "escape_sequence" to "String",

        "number" to "Number",
        "decimal" to "Number",
        "hex" to "Number",

        "function" to "Function",
        "function_declaration" to "Function",
        "arrow_function" to "Function",
        "method_definition" to "Function",
        "generator_function" to "Function",
        "function_name" to "Function",

        "property_identifier" to "Property",
        "property" to "Property",
        "shorthand_property_identifier" to "Property",
        "private_property_identifier" to "Property",

        "identifier" to "Variable",
        "variable_declaration" to "Variable",
        "variable_declarator" to "Variable",
        "required_parameter" to "Variable",
        "pattern" to "Variable",

        "module" to "Module",
        "import_statement" to "Module",
        "export_statement" to "Module",
        "import_clause" to "Module",
        "from_clause" to "Module",
        "namespace_import" to "Module",
        "named_imports" to "Module",
    )

    fun tokenName(treeSitterType: String): String? = tokenMap[treeSitterType]

    val keywordTypes = setOf(
        "async", "await", "break", "case", "catch", "class", "const", "continue",
        "debugger", "default", "delete", "do", "else", "export", "extends", "finally",
        "for", "function", "if", "import", "in", "instanceof", "let", "new", "of",
        "return", "static", "super", "switch", "this", "throw", "try", "typeof",
        "var", "void", "while", "with", "yield",
        "null", "undefined", "true", "false", "NaN", "Infinity"
    )

    val operatorTypes = setOf(
        "+", "-", "*", "/", "%", "=", "==", "===", "!=", "!==", ">", "<", ">=", "<=",
        "&&", "||", "!", "&", "|", "^", "~", "<<", ">>", ">>>", "??", "?.", "?",
        ":", ",", ";", ".", "(", ")", "{", "}", "[", "]", "=>", "...", "++", "--",
        "+=", "-=", "*=", "/=", "%=", "**", "**="
    )
}
