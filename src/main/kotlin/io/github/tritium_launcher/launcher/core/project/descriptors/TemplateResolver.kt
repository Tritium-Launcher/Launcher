/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.core.project.descriptors

import io.github.tritium_launcher.launcher.ui.project.sidebar.SlotEntry

object TemplateResolver {

    private val placeholderRegex = Regex("""\{\{(.+?)}}""")
    private val pipeFormatRegex = Regex("""^(.*?)\s*\|\s*(\w+)$""")

    fun resolve(
        template: String,
        fills: Map<String, SlotEntry>,
        options: Map<String, String>,
        format: String
    ): String {
        return placeholderRegex.replace(template) { match ->
            val raw = match.groupValues[1].trim()
            when {
                raw.startsWith("option:") -> resolveOption(raw.removePrefix("option:"), options)
                raw.contains("|") -> {
                    val (value, fmt) = parsePipe(raw)
                    resolvePlaceholder(value, fills, options, fmt)
                }
                else -> resolvePlaceholder(raw, fills, options, format)
            }
        }
    }

    private fun resolvePlaceholder(
        name: String,
        fills: Map<String, SlotEntry>,
        options: Map<String, String>,
        format: String
    ): String {
        val entry = fills[name] ?: return ""
        return formatSlotValue(entry, format)
    }

    private fun resolveOption(key: String, options: Map<String, String>): String {
        return options[key] ?: ""
    }

    private fun parsePipe(raw: String): Pair<String, String> {
        val idx = raw.lastIndexOf('|')
        if (idx == -1) return raw.trim() to ""
        val value = raw.substring(0, idx).trim()
        val fmt = raw.substring(idx + 1).trim()
        return value to fmt
    }

    private fun formatSlotValue(entry: SlotEntry, format: String): String {
        val isTag = entry.itemId.startsWith("#") || entry.isTag
        val cleanId = entry.itemId.removePrefix("#")
        return when (format) {
            "json" -> formatJson(cleanId, entry.quantity, isTag)
            "kubejs", "kubejs_custom" -> formatKubeJs(cleanId, entry.quantity, isTag)
            "id" -> "\"$cleanId\""
            else -> cleanId
        }
    }

    private fun formatJson(id: String, quantity: Int, isTag: Boolean): String {
        val key = if (isTag) "tag" else "item"
        return if (quantity > 1) {
            """{"$key": "$id", "count": $quantity}"""
        } else {
            """{"$key": "$id"}"""
        }
    }

    private fun formatKubeJs(id: String, quantity: Int, isTag: Boolean): String {
        val prefix = if (isTag) "#" else ""
        return if (quantity > 1) {
            if (isTag) "Ingredient.of('$prefix$id', $quantity)"
            else "Item.of('$prefix$id', $quantity)"
        } else {
            "'$prefix$id'"
        }
    }
}
