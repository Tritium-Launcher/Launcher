/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.api.inspection

enum class Severity {
    ERROR,
    WARNING,
    INFO,
    HINT,
    IGNORE
}

data class Problem(
    val startByte: UInt,
    val endByte: UInt,
    val message: String,
    val severity: Severity,
    val inspectionId: String,
    val matchedCaptures: Map<String, String> = emptyMap(),
    val availableFixes: List<InspectionFix> = emptyList()
)

data class InspectionFix(
    val label: String,
    val priority: Int,
    val generator: FixGenerator
)

sealed interface FixGenerator {
    data class Replace(val newText: String) : FixGenerator
    data class CaptureTemplate(val template: String) : FixGenerator
    data class Dynamic(
        val compute: (matchedText: String, captures: Map<String, String>, fileText: String) -> String
    ) : FixGenerator
}

typealias WalkCheckFn = (node: SyntaxNode, fullText: String, context: InspectionContext) -> List<Problem>

sealed interface InspectionType {
    data class Query(
        val sExpression: String,
        val messageTemplate: String
    ) : InspectionType

    data class Walk(val check: WalkCheckFn) : InspectionType

    data class ParameterizedStringCheck(
        val matchTypes: Set<String>,
        val messageTemplate: String,
        val check: (stringValue: String, context: InspectionContext) -> Boolean
    ) : InspectionType

    data object SyntaxError : InspectionType
}

data class InspectionSpec(
    val id: String,
    val title: String,
    val description: String,
    val languageId: String,
    val category: List<String>,
    val defaultSeverity: Severity,
    val type: InspectionType,
    val fixes: List<InspectionFix> = emptyList(),
    val condition: ((InspectionContext) -> Boolean)? = null
)
