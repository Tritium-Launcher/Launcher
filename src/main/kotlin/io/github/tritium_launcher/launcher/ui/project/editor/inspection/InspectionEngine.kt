/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.ui.project.editor.inspection

import io.github.treesitter.ktreesitter.Query
import io.github.treesitter.ktreesitter.Tree
import io.github.tritium_launcher.api.BuiltinRegistries
import io.github.tritium_launcher.api.core.project.ProjectBase
import io.github.tritium_launcher.api.inspection.*
import io.github.tritium_launcher.api.io.VPath
import io.github.tritium_launcher.api.logger
import io.github.tritium_launcher.launcher.extension.core.CoreSettingKeys
import io.github.tritium_launcher.launcher.settings.SettingsMngr
import io.github.tritium_launcher.launcher.ui.project.editor.inspection.builtin.SyntaxErrorRule
import io.github.tritium_launcher.launcher.ui.project.editor.treesitter.TreeSitterService
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

object InspectionEngine {
    private val log = logger()

    suspend fun run(
        project: ProjectBase,
        file: VPath,
        text: String,
        tree: Tree
    ): List<Problem> {
        val matchingLanguages = BuiltinRegistries.SyntaxLanguage.all().filter { it.matches(file) }
        if (matchingLanguages.isEmpty()) return emptyList()

        val severityOverrides = runCatching {
            val raw = (SettingsMngr.currentValueOrNull(CoreSettingKeys.InspectionsConfig) as? String).orEmpty()
            if (raw.isBlank() || raw == "{}") emptyMap()
            else Json.decodeFromString(MapSerializer(String.serializer(), String.serializer()), raw)
        }.getOrDefault(emptyMap())

        val syntaxTree = TreeSitterSyntaxTree(tree)
        val context = InspectionDataProviders.build(project, file, syntaxTree, text)
        val allProblems = mutableListOf<Problem>()
        val seenProblems = mutableSetOf<String>()

        for (lang in matchingLanguages) {
            val specs = InspectionRegistry.forLanguage(lang.id)
            if (specs.isEmpty()) continue

            val grammar = TreeSitterService.grammarFor(lang.id)
            if (grammar == null) {
                log.warn("No grammar found for language '{}', skipping {} inspection(s)", lang.id, specs.size)
                continue
            }

            for (spec in specs) {
                if (spec.condition?.invoke(context) == false) continue
                try {
                    val problems = when (val type = spec.type) {
                        is InspectionType.Query -> runQuery(type, text, tree, grammar)
                        is InspectionType.Walk -> {
                            type.check(context.tree.rootNode, text, context)
                        }
                        is InspectionType.ParameterizedStringCheck -> runParameterizedCheck(
                            spec, type, text, tree, context, grammar
                        )
                        InspectionType.SyntaxError -> SyntaxErrorRule.run(context.tree.rootNode)
                    }

                    for (problem in problems) {
                        val key = "${problem.startByte}:${problem.endByte}:${problem.message}"
                        if (seenProblems.add(key)) {
                            val effectiveSeverity = severityOverrides[spec.id]?.let { sevStr ->
                                runCatching { Severity.valueOf(sevStr) }.getOrNull()
                            } ?: spec.defaultSeverity
                            if (effectiveSeverity == Severity.IGNORE) continue
                            allProblems.add(problem.copy(
                                severity = effectiveSeverity,
                                inspectionId = spec.id,
                                availableFixes = spec.fixes
                            ))
                        }
                    }
                } catch (t: Throwable) {
                    log.error("Inspection '{}' failed", spec.id, t)
                }
            }
        }

        return allProblems
    }

    private fun runQuery(
        type: InspectionType.Query,
        fullText: String,
        tree: Tree,
        grammar: io.github.treesitter.ktreesitter.Language
    ): List<Problem> {
        val query = QueryCache.getOrCompile(grammar, type.sExpression)
        val cursor = query(tree.rootNode)
        val problems = mutableListOf<Problem>()

        for (match in cursor.matches { true }) {
            val captures = mutableMapOf<String, String>()
            var minByte = UInt.MAX_VALUE
            var maxByte = 0u

            for (capture in match.captures) {
                val name = capture.name.removePrefix("_")
                val node = capture.node
                val text = fullText.substring(node.startByte.toInt(), node.endByte.toInt())
                captures[name] = text
                if (node.startByte < minByte) minByte = node.startByte
                if (node.endByte > maxByte) maxByte = node.endByte
            }

            if (captures.isEmpty()) continue

            val message = interpolate(type.messageTemplate, captures)
            problems.add(Problem(
                startByte = minByte,
                endByte = maxByte,
                message = message,
                severity = Severity.WARNING,
                inspectionId = "",
                matchedCaptures = captures
            ))
        }

        return problems
    }

    private fun runParameterizedCheck(
        spec: InspectionSpec,
        type: InspectionType.ParameterizedStringCheck,
        fullText: String,
        tree: Tree,
        context: InspectionContext,
        grammar: io.github.treesitter.ktreesitter.Language
    ): List<Problem> {
        val resolver = context.data["kubejs:param_type_resolver"] as? ParamTypeResolver
        if (resolver == null) return emptyList()

        val query = Query(grammar, """
            (call_expression
              function: (_) @callee
              arguments: (arguments (_) @arg))
        """.trimIndent())

        val cursor = query(tree.rootNode)
        val problems = mutableListOf<Problem>()

        for (match in cursor.matches { true }) {
            val calleeCaps = match["callee"]
            if (calleeCaps.isEmpty()) continue
            val calleeNode = calleeCaps.first()

            val argCaptures = match["arg"]
            val (receiver, method) = resolveCallee(calleeNode, fullText)

            for ((paramIndex, cap) in argCaptures.withIndex()) {
                if (cap.type != "string") continue
                val argText = fullText.substring(cap.startByte.toInt(), cap.endByte.toInt())
                val value = argText.removeSurrounding("\"").removeSurrounding("'")

                val expectedType = resolver.resolve(receiver, method, paramIndex)
                if (expectedType == null || expectedType !in type.matchTypes) continue

                if (type.check(value, context)) {
                    problems.add(Problem(
                        startByte = cap.startByte,
                        endByte = cap.endByte,
                        message = interpolate(type.messageTemplate, mapOf("value" to value)),
                        severity = spec.defaultSeverity,
                        inspectionId = spec.id
                    ))
                }
            }
        }

        return problems
    }

    private fun resolveCallee(calleeNode: io.github.treesitter.ktreesitter.Node, fullText: String): Pair<String?, String> {
        if (calleeNode.type == "identifier") {
            return null to (calleeNode.text()?.toString() ?: "")
        }
        if (calleeNode.type == "member_expression") {
            val objectNode = calleeNode.child(0u)
            val propNode = calleeNode.child(2u)
            val receiver = objectNode?.text()?.toString()
            val method = propNode?.text()?.toString() ?: ""
            return receiver to method
        }
        return null to (calleeNode.text()?.toString() ?: "")
    }

    private fun interpolate(template: String, captures: Map<String, String>): String {
        var result = template
        for ((key, value) in captures) {
            result = result.replace("{$key}", value)
        }
        return result
    }
}
