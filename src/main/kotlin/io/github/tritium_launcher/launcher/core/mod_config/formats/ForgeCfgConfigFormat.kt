/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.core.mod_config.formats

import io.github.tritium_launcher.api.modpack.*

class ForgeCfgConfigFormat: ConfigFormat {
    override val id: String = "forge_cfg"
    override val extensions: List<String> = listOf("forge_cfg")

    override fun parse(text: String): ConfigNode {
        val lines = text.lines()
        val (root, _) = parseBlock(lines, 0)
        return root
    }

    private fun parseBlock(lines: List<String>, startIndex: Int): Pair<ConfigObj, Int> {
        val obj = ConfigObj()
        var i = startIndex

        while(i < lines.size) {
            val raw  = lines[i]
            val line = raw.trim()

            when {
                line.isEmpty() -> i++

                line.startsWith('#') -> {
                    val key = "__comment_${obj.entries.size}"
                    obj.entries[key] = ConfigComment(line.removePrefix("#").trim())
                    i++
                }

                line == "}" -> return Pair(obj, i + 1)

                line.endsWith("{") -> {
                    val catName = line.dropLast(1).trim().trim('"')
                    val (child, next) = parseBlock(lines, i + 1)
                    obj.entries[catName] = child
                    i = next
                }

                else -> {
                    val (name, node, next) = parseProperty(lines, i)
                    if(name != null && node != null) obj.entries[name] = node
                    i = next
                }
            }
        }

        return obj to i
    }

    private fun parseProperty(lines: List<String>, index: Int): Triple<String?, ConfigNode?, Int> {
        val line = lines[index].trim()

        val effective = line.substringBefore('#').trim()

        val typeKey = effective.firstOrNull()
            ?: return Triple(null, null, index + 1)

        val nameStart = effective.indexOf('"')
        val nameEnd   = effective.indexOf('"', nameStart + 1)
        if(nameStart < 0 || nameEnd < 0) return Triple(null, null, index + 1)
        val name = effective.substring(nameStart + 1, nameEnd)

        val rest = effective.substring(nameEnd + 1).trim()

        return when {
            rest.startsWith('=') -> {
                val raw = rest.removePrefix("=")
                val node = parseScalar(typeKey, raw)
                Triple(name, node, index + 1)
            }

            rest == "<" -> {
                val items = mutableListOf<ConfigNode>()
                var i = index + 1
                while(i < lines.size) {
                    val item = lines[i].trim().substringBefore('#').trim()
                    if(item == ">") { i++; break }
                    if(item.isNotEmpty()) items.add(parseScalar(typeKey, item))
                    i++
                }
                Triple(name, ConfigArray(items), i)
            }

            else -> Triple(null, null, index + 1)
        }
    }

    private fun parseScalar(typeKey: Char, raw: String): ConfigNode = when (typeKey.uppercaseChar()) {
        'S'  -> ConfigString(raw)
        'I'  -> ConfigInt(raw.trim().toIntOrNull() ?: 0)
        'B'  -> ConfigBool(raw.trim() == "true")
        'D'  -> ConfigDouble(raw.trim().toDoubleOrNull() ?: 0.0)
        else -> ConfigString(raw)
    }

    override fun serialize(node: ConfigNode): String {
        require(node is ConfigObj) { "Forge CFG root must be an object" }
        return buildString { serializeObj(node, this, "") }
    }

    fun serializeObj(obj: ConfigObj, sb: StringBuilder, indent: String) {
        for((key, value) in obj.entries) {
            when {
                key.startsWith("__comment_") -> {
                    val c = value as ConfigComment
                    sb.appendLine("$indent# ${c.text}")
                }
                value is ConfigObj -> {
                    sb.appendLine("$indent\"$key\" {")
                    serializeObj(value, sb, "$indent\t")
                    sb.appendLine("$indent}")
                }
                value is ConfigArray -> {
                    val typeKey = inferTypeKey(value.items.firstOrNull())
                    sb.appendLine("$indent$typeKey:\"$key\" <")
                    for (item in value.items) {
                        sb.appendLine("$indent\t${serializeScalar(item)}")
                    }
                    sb.appendLine("$indent>")
                }
                else -> {
                    val typeKey = inferTypeKey(value)
                    sb.appendLine("$indent$typeKey:\"$key\"=${serializeScalar(value)}")
                }
            }
        }
    }

    private fun inferTypeKey(node: ConfigNode?): Char = when (node) {
        is ConfigString -> 'S'
        is ConfigInt -> 'I'
        is ConfigBool -> 'B'
        is ConfigDouble -> 'D'
        else            -> 'S'
    }

    private fun serializeScalar(node: ConfigNode): String = when (node) {
        is ConfigString -> node.value
        is ConfigInt -> node.value.toString()
        is ConfigBool -> node.value.toString()
        is ConfigDouble -> node.value.toString()
        else            -> ""
    }
}
