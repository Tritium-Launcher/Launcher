/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.core.mod_config.formats

import io.github.tritium_launcher.api.modpack.*

class PropertiesConfigFormat : ConfigFormat {
    override val id: String = "properties"
    override val extensions: List<String> = listOf("properties", "cfg")

    override fun parse(text: String): ConfigNode {
        val map = linkedMapOf<String, ConfigNode>()

        for(line in text.lines()) {
            val trimmed = line.trim()

            when {
                trimmed.startsWith('#') -> {
                    val key = "__comment_${map.size}"
                    map[key] = ConfigComment(trimmed.removePrefix("#").trim())
                }
                trimmed.contains('=') -> {
                    val (k, v) = trimmed.split('=', limit = 2)
                    map[k.trim()] = parseScalar(v.trim())
                }
            }
        }

        return ConfigObj(map)
    }

    override fun serialize(node: ConfigNode): String {
        require(node is ConfigObj) { "Properties root must be an object" }
        return buildString {
            for((k, v) in node.entries) {
                when(v) {
                    is ConfigComment -> appendLine("# ${v.text}")
                    else -> appendLine("$k=${serializeScalar(v)}")
                }
            }
        }
    }

    private fun parseScalar(raw: String): ConfigNode = when {
        raw == "true" || raw == "false" -> ConfigBool(raw.toBoolean())
        raw.toIntOrNull() != null       -> ConfigInt(raw.toInt())
        raw.toFloatOrNull() != null     -> ConfigDouble(raw.toDouble())
        else                            -> ConfigString(raw)
    }

    private fun serializeScalar(node: ConfigNode): String = when (node) {
        is ConfigString -> node.value
        is ConfigInt    -> node.value.toString()
        is ConfigDouble -> node.value.toString()
        is ConfigBool   -> node.value.toString()
        else            -> ""
    }
}
