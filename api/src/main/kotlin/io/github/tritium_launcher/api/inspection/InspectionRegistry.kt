/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.api.inspection

import io.github.tritium_launcher.api.BuiltinRegistries
import io.github.tritium_launcher.api.io.VPath

object InspectionRegistry {
    private val specs = mutableListOf<InspectionSpec>()
    private val byLanguage = mutableMapOf<String, MutableList<InspectionSpec>>()
    private var builtinsRegistered = false

    fun register(spec: InspectionSpec) {
        ensureBuiltins()
        specs.add(spec)
        byLanguage.getOrPut(spec.languageId) { mutableListOf() }.add(spec)
    }

    fun forLanguage(languageId: String): List<InspectionSpec> {
        ensureBuiltins()
        return byLanguage[languageId] ?: emptyList()
    }

    fun forFile(file: VPath): List<InspectionSpec> {
        ensureBuiltins()
        val matchingLanguages = BuiltinRegistries.SyntaxLanguage.all().filter { it.matches(file) }
        return matchingLanguages.flatMap { lang -> forLanguage(lang.id) }
    }

    fun grouped(): Map<String, Map<String, List<InspectionSpec>>> {
        ensureBuiltins()
        val result = linkedMapOf<String, LinkedHashMap<String, MutableList<InspectionSpec>>>()
        for (spec in specs.sortedBy { it.id }) {
            val langMap = result.getOrPut(spec.languageId) { linkedMapOf() }
            val catKey = spec.category.joinToString(" \u203A ")
            langMap.getOrPut(catKey) { mutableListOf() }.add(spec)
        }
        return result
    }

    fun all(): List<InspectionSpec> {
        ensureBuiltins()
        return specs.toList()
    }

    private fun ensureBuiltins() {
        if (builtinsRegistered) return
        builtinsRegistered = true
        for (langId in BuiltinRegistries.SyntaxLanguage.all().map { it.id }) {
            register(InspectionSpec(
                id = "syntax_error",
                title = "Syntax Error",
                description = "Marks Tree-sitter parse errors and missing nodes in the source.",
                languageId = langId,
                category = listOf("General"),
                defaultSeverity = Severity.ERROR,
                type = InspectionType.SyntaxError
            ))
        }
    }
}
