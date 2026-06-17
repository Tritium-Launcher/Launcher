package io.github.tritium_launcher.launcher.core.mod_config.formats

import io.github.tritium_launcher.launcher.core.mod_config.*
import net.mamoe.yamlkt.Yaml
import net.mamoe.yamlkt.YamlNullableDynamicSerializer
import kotlin.math.max

class YamlConfigFormat : ConfigFormat {
    override val id: String = "yaml"
    override val extensions: List<String> = listOf("yaml", "yml")

    private val yaml = Yaml {}

    override fun parse(text: String): ConfigNode {
        if (text.isBlank()) return ConfigObj()
        val commentsByPath = extractLeadingCommentsByPath(text)
        return anyToNode(
            yaml.decodeFromString(YamlNullableDynamicSerializer, normalizePlainColonScalars(text)),
            emptyList(),
            commentsByPath
        )
    }

    override fun serialize(node: ConfigNode): String =
        buildString { append(renderNode(node, 0)) }.trimEnd() + "\n"

    private fun anyToNode(
        value: Any?,
        path: List<String>,
        commentsByPath: Map<List<String>, List<ConfigComment>>
    ): ConfigNode = when (value) {
        null -> ConfigNull()
        is String -> ConfigString(value)
        is Int -> ConfigInt(value)
        is Long -> {
            if (value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) ConfigInt(value.toInt())
            else ConfigDouble(value.toDouble())
        }
        is Float -> ConfigDouble(value.toDouble())
        is Double -> ConfigDouble(value)
        is Boolean -> ConfigBool(value)
        is Map<*, *> -> ConfigObj(linkedMapOf<String, ConfigNode>().apply {
            value.forEach { (key, child) ->
                val keyText = key?.toString() ?: "null"
                commentsByPath[path + keyText].orEmpty().forEach { comment ->
                    put("__comment_${size}", comment)
                }
                put(keyText, anyToNode(child, path + keyText, commentsByPath))
            }
        })
        is List<*> -> ConfigArray(value.mapIndexedTo(mutableListOf()) { index, child ->
            anyToNode(child, path + index.toString(), commentsByPath)
        })
        else -> ConfigString(value.toString())
    }

    private fun normalizePlainColonScalars(text: String): String =
        text.lineSequence()
            .map(::normalizeLine)
            .joinToString("\n")

    private fun normalizeLine(line: String): String {
        val trimmedStart = line.trimStart()
        if (trimmedStart.isEmpty() || trimmedStart.startsWith("#")) return line

        val listPrefix = LIST_ITEM_PREFIX.find(trimmedStart)
        if (listPrefix != null) {
            val value = trimmedStart.substring(listPrefix.value.length)
            return line.take(line.length - trimmedStart.length) + listPrefix.value + normalizeScalar(value)
        }

        val keyMatch = KEY_VALUE_PREFIX.find(trimmedStart) ?: return line
        val value = trimmedStart.substring(keyMatch.value.length)
        return line.take(line.length - trimmedStart.length) + keyMatch.value + normalizeScalar(value)
    }

    private fun normalizeScalar(value: String): String {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return value
        if (!needsQuotes(trimmed)) return value

        val leading = value.takeWhile { it.isWhitespace() }
        val trailing = value.takeLastWhile { it.isWhitespace() }
        return leading + quote(trimmed) + trailing
    }

    private fun needsQuotes(value: String): Boolean {
        if (':' !in value) return false
        if (value.startsWith("\"") || value.startsWith("'")) return false
        if (value.startsWith("{") || value.startsWith("[") || value.startsWith("|") || value.startsWith(">")) return false
        if (value == "-" || value == "{}" || value == "[]") return false
        return value.none { it == '#' }
    }

    private fun quote(value: String): String {
        val escaped = buildString(value.length + 8) {
            value.forEach { ch ->
                when (ch) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(ch)
                }
            }
        }
        return "\"$escaped\""
    }

    private fun extractLeadingCommentsByPath(text: String): Map<List<String>, List<ConfigComment>> {
        val commentsByPath = linkedMapOf<List<String>, MutableList<ConfigComment>>()
        val pendingComments = mutableListOf<ConfigComment>()
        val stack = mutableListOf<Scope>()

        text.lines().forEach { rawLine ->
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty()) return@forEach

            if (trimmed.startsWith("#")) {
                pendingComments += ConfigComment(trimmed.removePrefix("#").trim())
                return@forEach
            }

            val indent = rawLine.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) 0 else it }
            while (stack.isNotEmpty() && indent <= stack.last().indent) {
                stack.removeAt(stack.lastIndex)
            }

            val currentPath = stack.lastOrNull()?.path ?: emptyList()
            val keyMatch = YAML_KEY.find(trimmed)
            if (keyMatch != null) {
                val key = keyMatch.groupValues[1].trim().removeSurrounding("\"").removeSurrounding("'")
                val path = currentPath + key
                if (pendingComments.isNotEmpty()) {
                    commentsByPath.getOrPut(path) { mutableListOf() }.addAll(pendingComments)
                    pendingComments.clear()
                }

                val remainder = trimmed.substring(keyMatch.value.length).trim()
                if (remainder.isEmpty()) {
                    stack += Scope(indent, path)
                }
                return@forEach
            }

            if (pendingComments.isNotEmpty()) {
                pendingComments.clear()
            }
        }

        return commentsByPath
    }

    private fun renderNode(node: ConfigNode, depth: Int): String = when (node) {
        is ConfigObj -> renderObject(node, depth)
        is ConfigArray -> renderArray(node, depth)
        is ConfigString -> renderScalar(node.value)
        is ConfigInt -> node.value.toString()
        is ConfigDouble -> formatDouble(node.value)
        is ConfigBool -> node.value.toString()
        is ConfigNull -> "null"
        is ConfigComment -> "${indent(depth)}# ${node.text}"
    }

    private fun renderObject(node: ConfigObj, depth: Int): String {
        val lines = mutableListOf<String>()
        node.entries.forEach { (key, value) ->
            if (key.startsWith("__comment_") && value is ConfigComment) {
                lines += "${indent(depth)}# ${value.text}"
                return@forEach
            }

            val prefix = "${indent(depth)}${renderKey(key)}:"
            when (value) {
                is ConfigObj -> {
                    val rendered = renderObject(value, depth + 1)
                    lines += if (value.entries.isEmpty()) "$prefix {}" else "$prefix\n$rendered"
                }
                is ConfigArray -> {
                    val rendered = renderArray(value, depth + 1)
                    lines += if (value.items.isEmpty()) "$prefix []" else "$prefix\n$rendered"
                }
                else -> lines += "$prefix ${renderNode(value, depth + 1).trimStart()}"
            }
        }
        return lines.joinToString("\n")
    }

    private fun renderArray(node: ConfigArray, depth: Int): String {
        val lines = mutableListOf<String>()
        node.items.forEach { item ->
            when (item) {
                is ConfigComment -> lines += "${indent(depth)}# ${item.text}"
                is ConfigObj -> {
                    if (item.entries.isEmpty()) {
                        lines += "${indent(depth)}- {}"
                    } else {
                        val rendered = renderObjectWithListPrefix(item, depth)
                        lines += rendered
                    }
                }
                is ConfigArray -> {
                    val rendered = renderArray(item, depth + 1)
                    lines += if (item.items.isEmpty()) "${indent(depth)}- []" else "${indent(depth)}-\n$rendered"
                }
                else -> lines += "${indent(depth)}- ${renderNode(item, depth + 1).trimStart()}"
            }
        }
        return lines.joinToString("\n")
    }

    private fun renderObjectWithListPrefix(node: ConfigObj, depth: Int): String {
        val rendered = renderObject(node, depth + 1).lines()
        if (rendered.isEmpty()) return "${indent(depth)}- {}"
        val firstMeaningful = rendered.indexOfFirst { it.isNotBlank() && !it.trimStart().startsWith("#") }
        if (firstMeaningful < 0) return "${indent(depth)}-\n${rendered.joinToString("\n")}"

        val lines = rendered.toMutableList()
        lines[firstMeaningful] = "${indent(depth)}- ${lines[firstMeaningful].trimStart()}"
        return lines.joinToString("\n")
    }

    private fun renderKey(key: String): String =
        if (BARE_KEY.matches(key)) key else quote(key)

    private fun renderScalar(value: String): String =
        if (needsQuotesWhenWriting(value)) quote(value) else value

    private fun needsQuotesWhenWriting(value: String): Boolean {
        if (value.isEmpty()) return true
        if (value == "null" || value == "true" || value == "false") return true
        if (value.toIntOrNull() != null || value.toDoubleOrNull() != null) return true
        if (value.first().isWhitespace() || value.last().isWhitespace()) return true
        return value.any { it == ':' || it == '#' || it == '"' || it == '\'' || it == '{' || it == '}' || it == '[' || it == ']' }
    }

    private fun formatDouble(value: Double): String {
        val text = value.toString()
        return if (text.contains('.') || text.contains('e', true)) text else "$text.0"
    }

    private fun indent(depth: Int): String = "  ".repeat(max(0, depth))

    private data class Scope(
        val indent: Int,
        val path: List<String>
    )

    private companion object {
        val LIST_ITEM_PREFIX = Regex("""^-\s+""")
        val KEY_VALUE_PREFIX = Regex("""^[^:#\[\]\{\},][^:]*:\s+""")
        val YAML_KEY = Regex("""^([^:#][^:]*)\s*:\s*""")
        val BARE_KEY = Regex("""[A-Za-z0-9_.-]+""")
    }
}
