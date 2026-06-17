package io.github.tritium_launcher.launcher.core.mod_config.formats

import io.github.tritium_launcher.launcher.core.mod_config.*
import io.github.tritium_launcher.launcher.core.mod_config.formats.JsonConfigFormat.Variant.*
import kotlinx.serialization.json.*
import li.songe.json5.Json5

class JsonConfigFormat(private val variant: Variant): ConfigFormat {
    enum class Variant { J, JC, J5 }

    private data class Scope(
        val path: MutableList<String> = mutableListOf(),
        var pendingComments: MutableList<ConfigComment> = mutableListOf()
    )

    override val id: String = when(variant) {
        J  -> "json"
        JC -> "jsonc"
        J5 -> "json5"
    }

    override val extensions: List<String> = when(variant) {
        J  -> listOf("json")
        JC -> listOf("jsonc")
        J5 -> listOf("json5")
    }

    private val strictJson = Json {
        prettyPrint = true
        allowComments = true
    }

    private val jsonCJson  = Json {
        allowComments      = true
        allowTrailingComma = true
        prettyPrint        = true
    }

    override fun parse(text: String): ConfigNode {
        val elem = when (variant) {
            J  -> strictJson.parseToJsonElement(text)
            JC -> jsonCJson.parseToJsonElement(text)
            J5 -> Json5.parseToJson5Element(text)
        }
        val commentsByPath = when (variant) {
            J -> emptyMap()
            JC, J5 -> extractLeadingCommentsByPath(text)
        }
        return elementToNode(elem, emptyList(), commentsByPath)
    }

    private fun elementToNode(
        elem: JsonElement,
        path: List<String>,
        commentsByPath: Map<List<String>, List<ConfigComment>>
    ): ConfigNode = when (elem) {
        is JsonObject -> {
            val map = linkedMapOf<String, ConfigNode>()
            for((key, value) in elem) {
                commentsByPath[path + key]?.forEach { comment ->
                    map["__comment_${map.size}"] = comment
                }
                map[key] = elementToNode(value, path + key, commentsByPath)
            }
            ConfigObj(map)
        }
        is JsonArray -> ConfigArray(elem.mapIndexed { index, child ->
            elementToNode(child, path + index.toString(), commentsByPath)
        }.toMutableList())
        is JsonNull  -> ConfigNull()
        is JsonPrimitive -> when {
            elem.isString              -> ConfigString(elem.content)
            elem.booleanOrNull != null -> ConfigBool(elem.boolean)
            elem.intOrNull != null     -> ConfigInt(elem.int)
            elem.doubleOrNull != null  -> ConfigDouble(elem.double)
            else                       -> ConfigString(elem.content)
        }
    }

    override fun serialize(node: ConfigNode): String {
        return when (variant) {
            J  -> strictJson.encodeToString<JsonElement>(nodeToElement(node))
            JC, J5 -> renderWithComments(node)
        }
    }

    // TODO: Long term, a JsonC parser is needed to preserve comments
    private fun nodeToElement(node: ConfigNode): JsonElement = when (node) {
        is ConfigObj -> JsonObject(
            node.entries
                .filter { !it.key.startsWith("__comment_") }
                .mapValues { (_, v) -> nodeToElement(v) }
        )
        is ConfigArray   -> JsonArray(node.items.map { nodeToElement(it) })
        is ConfigString  -> JsonPrimitive(node.value)
        is ConfigInt     -> JsonPrimitive(node.value)
        is ConfigDouble  -> JsonPrimitive(node.value)
        is ConfigBool    -> JsonPrimitive(node.value)
        is ConfigNull    -> JsonNull
        is ConfigComment -> JsonNull
    }

    private fun extractLeadingCommentsByPath(text: String): Map<List<String>, List<ConfigComment>> {
        val commentsByPath = linkedMapOf<List<String>, MutableList<ConfigComment>>()
        val scopeStack = mutableListOf(Scope())

        for (rawLine in text.lines()) {
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty()) continue

            if (trimmed.startsWith("//")) {
                scopeStack.last().pendingComments += ConfigComment(trimmed.removePrefix("//").trim())
                continue
            }

            if (trimmed.startsWith("#")) {
                scopeStack.last().pendingComments += ConfigComment(trimmed.removePrefix("#").trim())
                continue
            }

            val current = scopeStack.last()
            val propertyMatch = PROPERTY_REGEX.find(trimmed)
            if (propertyMatch != null) {
                val key = propertyMatch.groupValues[1].takeIf { it.isNotBlank() }?.let(::unescapeJsonString)
                    ?: propertyMatch.groupValues[2]
                val path = current.path + key
                if (current.pendingComments.isNotEmpty()) {
                    commentsByPath.getOrPut(path) { mutableListOf() }.addAll(current.pendingComments)
                    current.pendingComments = mutableListOf()
                }

                val remainder = trimmed.substring(propertyMatch.range.last + 1).trimStart()
                if (remainder.startsWith("{")) {
                    scopeStack += Scope(current.path.toMutableList().apply { add(key) })
                }
            } else if (current.pendingComments.isNotEmpty()) {
                current.pendingComments = mutableListOf()
            }

            closeScopes(trimmed, scopeStack)
        }

        return commentsByPath
    }

    private fun closeScopes(line: String, scopeStack: MutableList<Scope>) {
        if (scopeStack.isEmpty()) return

        val objectClosings = countStructural(line, '}')
        repeat(objectClosings) {
            if (scopeStack.size > 1) {
                scopeStack.removeAt(scopeStack.lastIndex)
            }
        }
    }

    private fun bracketDelta(text: String, open: Char, close: Char): Int =
        countStructural(text, open) - countStructural(text, close)

    private fun countStructural(text: String, target: Char): Int {
        var inString = false
        var escaped = false
        var count = 0

        text.forEach { ch ->
            when {
                escaped -> escaped = false
                ch == '\\' && inString -> escaped = true
                ch == '"' -> inString = !inString
                !inString && ch == target -> count++
            }
        }
        return count
    }

    private fun unescapeJsonString(value: String): String =
        buildString(value.length) {
            var i = 0
            while (i < value.length) {
                val ch = value[i]
                if (ch == '\\' && i + 1 < value.length) {
                    val next = value[i + 1]
                    append(
                        when (next) {
                            '\\', '/', '"' -> next
                            'b' -> '\b'
                            'f' -> '\u000C'
                            'n' -> '\n'
                            'r' -> '\r'
                            't' -> '\t'
                            'u' -> {
                                val hex = value.substring(i + 2, (i + 6).coerceAtMost(value.length))
                                if (hex.length == 4) {
                                    i += 4
                                    hex.toIntOrNull(16)?.toChar() ?: next
                                } else next
                            }
                            else -> next
                        }
                    )
                    i += 2
                    continue
                }
                append(ch)
                i++
            }
        }

    private fun renderWithComments(node: ConfigNode): String =
        buildString { append(renderNode(node, 0)) }.trimEnd() + "\n"

    private fun renderNode(node: ConfigNode, depth: Int): String = when (node) {
        is ConfigObj -> renderObject(node, depth)
        is ConfigArray -> renderArray(node, depth)
        is ConfigString -> quote(node.value)
        is ConfigInt -> node.value.toString()
        is ConfigDouble -> formatDouble(node.value)
        is ConfigBool -> node.value.toString()
        is ConfigNull -> "null"
        is ConfigComment -> "${indent(depth)}// ${node.text}"
    }

    private fun renderObject(node: ConfigObj, depth: Int): String {
        val indent = indent(depth)
        val childIndent = indent(depth + 1)
        val rendered = mutableListOf<String>()
        val entries = node.entries.entries.toList()

        for ((index, entry) in entries.withIndex()) {
            val key = entry.key
            val value = entry.value
            if (key.startsWith("__comment_")) {
                if (value is ConfigComment) rendered += "$childIndent// ${value.text}"
                continue
            }

            val renderedValue = renderNode(value, depth + 1)
            val suffix = if (index == entries.lastIndex || noMoreProperties(entries, index + 1)) "" else ","
            val formattedValue = if (value is ConfigObj || value is ConfigArray) {
                renderedValue.prependIndent(childIndent).removePrefix(childIndent)
            } else {
                renderedValue
            }
            rendered += "$childIndent${renderKey(key)}: $formattedValue$suffix"
        }

        return if (rendered.isEmpty()) {
            "{}"
        } else {
            "{\n${rendered.joinToString("\n")}\n$indent}"
        }
    }

    private fun renderArray(node: ConfigArray, depth: Int): String {
        if (node.items.isEmpty()) return "[]"
        val indent = indent(depth)
        val childIndent = indent(depth + 1)
        val rendered = node.items.mapIndexed { index, item ->
            val suffix = if (index == node.items.lastIndex) "" else ","
            val value = renderNode(item, depth + 1)
            "$childIndent$value$suffix"
        }
        return "[\n${rendered.joinToString("\n")}\n$indent]"
    }

    private fun noMoreProperties(entries: List<Map.Entry<String, ConfigNode>>, start: Int): Boolean =
        entries.drop(start).none { !it.key.startsWith("__comment_") }

    private fun renderKey(key: String): String =
        if (IDENTIFIER_REGEX.matches(key)) key else quote(key)

    private fun quote(value: String): String {
        val escaped = buildString(value.length + 8) {
            value.forEach { ch ->
                when (ch) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\b' -> append("\\b")
                    '\u000C' -> append("\\f")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(ch)
                }
            }
        }
        return "\"$escaped\""
    }

    private fun formatDouble(value: Double): String {
        val text = value.toString()
        return if (text.contains('.') || text.contains('e', true)) text else "$text.0"
    }

    private fun indent(depth: Int): String = "    ".repeat(depth)

    private companion object {
        val PROPERTY_REGEX = Regex("""^(?:"((?:\\.|[^"\\])*)"|([A-Za-z_\$][A-Za-z0-9_\-\$]*))\s*:""")
        val IDENTIFIER_REGEX = Regex("""[A-Za-z_$][A-Za-z0-9_\-$]*""")
    }
}
