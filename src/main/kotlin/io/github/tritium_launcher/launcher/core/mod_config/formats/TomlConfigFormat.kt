package io.github.tritium_launcher.launcher.core.mod_config.formats

import com.akuleshov7.ktoml.Toml
import com.akuleshov7.ktoml.tree.nodes.*
import com.akuleshov7.ktoml.tree.nodes.pairs.values.*
import io.github.tritium_launcher.launcher.core.mod_config.*

class TomlConfigFormat : ConfigFormat {
    override val id: String = "toml"
    override val extensions: List<String> = listOf("toml")

    override fun parse(text: String): ConfigNode {
        val file = Toml.tomlParser.parseString(text)
        return nodesToObject(file)
    }

    override fun serialize(node: ConfigNode): String {
        require(node is ConfigObj) { "TOML root must be an object" }
        return buildString {
            writeObject(node, emptyList(), isRoot = true)
        }.trimEnd() + "\n"
    }

    private fun nodesToObject(file: TomlFile): ConfigObj = nodesToObject(file.children)

    private fun nodesToObject(nodes: List<TomlNode>): ConfigObj {
        val map = linkedMapOf<String, ConfigNode>()
        nodes.forEach { node ->
            appendComments(map, node.comments)
            when (node) {
                is TomlKeyValuePrimitive -> map[node.name] = valueToConfig(node.value)
                is TomlKeyValueArray -> map[node.name] = valueToConfig(node.value)
                is TomlTable -> map[node.name] = tableToConfig(node)
                is TomlInlineTable -> map[node.name] = inlineTableToConfig(node)
                is TomlStubEmptyNode -> {}
                else -> {}
            }
        }
        return ConfigObj(map)
    }

    private fun tableToConfig(table: TomlTable): ConfigNode = when (table.type) {
        TableType.PRIMITIVE -> nodesToObject(table.children.filterNot { it is TomlStubEmptyNode })
        TableType.ARRAY -> ConfigArray(
            table.children
                .filterIsInstance<TomlArrayOfTablesElement>()
                .map { nodesToObject(it.children.filterNot { child -> child is TomlStubEmptyNode }) }
                .toMutableList()
        )
    }

    private fun inlineTableToConfig(table: TomlInlineTable): ConfigNode {
        val root = table.returnTable(TomlFile(), TomlFile())
        return tableToConfig(root)
    }

    private fun appendComments(map: LinkedHashMap<String, ConfigNode>, comments: List<String>) {
        comments.forEach { comment ->
            map["__comment_${map.size}"] = ConfigComment(comment)
        }
    }

    private fun valueToConfig(value: TomlValue): ConfigNode = when (value) {
        is TomlBasicString -> ConfigString(value.content as String)
        is TomlLiteralString -> ConfigString(value.content as String)
        is TomlBoolean -> ConfigBool(value.content as Boolean)
        is TomlDouble -> ConfigDouble(value.content as Double)
        is TomlLong -> {
            val longValue = value.content as Long
            if (longValue in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
                ConfigInt(longValue.toInt())
            } else {
                ConfigDouble(longValue.toDouble())
            }
        }
        is TomlUnsignedLong -> {
            val unsignedValue = value.content as ULong
            if (unsignedValue <= Int.MAX_VALUE.toULong()) {
                ConfigInt(unsignedValue.toInt())
            } else {
                ConfigDouble(unsignedValue.toDouble())
            }
        }
        is TomlArray -> @Suppress("unchecked_cast") ConfigArray((value.content as List<Any>).map { arrayValueToConfig(it) }.toMutableList())
        is TomlNull -> ConfigNull()
        is TomlDateTime -> ConfigString(value.content.toString())
    }

    private fun arrayValueToConfig(value: Any): ConfigNode = when (value) {
        is TomlValue -> valueToConfig(value)
        is TomlInlineTable -> inlineTableToConfig(value)
        else -> ConfigString(value.toString())
    }

    private fun StringBuilder.writeObject(node: ConfigObj, path: List<String>, isRoot: Boolean) {
        val bodyLines = mutableListOf<String>()
        val sections = mutableListOf<DeferredSection>()
        val pendingComments = mutableListOf<ConfigComment>()

        for ((key, value) in node.entries) {
            if (key.startsWith("__comment_") && value is ConfigComment) {
                pendingComments += value
                continue
            }

            if (value is ConfigObj || isArrayOfTables(value)) {
                sections += DeferredSection(key, value, pendingComments.toList())
                pendingComments.clear()
                continue
            }

            bodyLines += renderComments(pendingComments)
            pendingComments.clear()
            bodyLines += "${renderKey(key)} = ${renderValue(value)}"
        }

        bodyLines += renderComments(pendingComments)

        if (!isRoot) {
            appendLine("[${path.joinToString(".") { renderKey(it) }}]")
        }

        bodyLines.forEachIndexed { index, line ->
            appendLine(line)
            if (index == bodyLines.lastIndex && sections.isNotEmpty()) {
                appendLine()
            }
        }

        sections.forEachIndexed { index, section ->
            if (isNotEmpty() && !endsWith("\n\n")) {
                appendLine()
            }
            writeSection(section, path + section.key)
            if (index < sections.lastIndex && !endsWith("\n\n")) {
                appendLine()
            }
        }
    }

    private fun StringBuilder.writeSection(section: DeferredSection, path: List<String>) {
        renderComments(section.comments).forEach { appendLine(it) }
        when (val node = section.node) {
            is ConfigObj -> writeObject(node, path, isRoot = false)
            is ConfigArray -> {
                val objects = node.items.filterIsInstance<ConfigObj>()
                objects.forEachIndexed { index, obj ->
                    if (index > 0 || section.comments.isNotEmpty()) {
                        if (isNotEmpty() && !endsWith("\n\n")) {
                            appendLine()
                        }
                    }
                    appendLine("[[${path.joinToString(".") { renderKey(it) }}]]")
                    writeArrayTableBody(obj, path)
                }
            }
            else -> error("Unsupported TOML section node: ${node::class.simpleName}")
        }
    }

    private fun StringBuilder.writeArrayTableBody(node: ConfigObj, path: List<String>) {
        val bodyLines = mutableListOf<String>()
        val sections = mutableListOf<DeferredSection>()
        val pendingComments = mutableListOf<ConfigComment>()

        for ((key, value) in node.entries) {
            if (key.startsWith("__comment_") && value is ConfigComment) {
                pendingComments += value
                continue
            }

            if (value is ConfigObj || isArrayOfTables(value)) {
                sections += DeferredSection(key, value, pendingComments.toList())
                pendingComments.clear()
                continue
            }

            bodyLines += renderComments(pendingComments)
            pendingComments.clear()
            bodyLines += "${renderKey(key)} = ${renderValue(value)}"
        }

        bodyLines += renderComments(pendingComments)

        bodyLines.forEachIndexed { index, line ->
            appendLine(line)
            if (index == bodyLines.lastIndex && sections.isNotEmpty()) {
                appendLine()
            }
        }

        sections.forEachIndexed { index, section ->
            if (isNotEmpty() && !endsWith("\n\n")) {
                appendLine()
            }
            writeSection(section, path + section.key)
            if (index < sections.lastIndex && !endsWith("\n\n")) {
                appendLine()
            }
        }
    }

    private fun renderComments(comments: List<ConfigComment>): List<String> =
        comments.map { "# ${it.text}" }

    private fun isArrayOfTables(node: ConfigNode): Boolean =
        node is ConfigArray && node.items.isNotEmpty() && node.items.all { it is ConfigObj }

    private fun renderValue(node: ConfigNode): String = when (node) {
        is ConfigString -> quote(node.value)
        is ConfigInt -> node.value.toString()
        is ConfigDouble -> formatDouble(node.value)
        is ConfigBool -> node.value.toString()
        is ConfigNull -> "null"
        is ConfigArray -> "[${node.items.joinToString(", ") { renderValue(it) }}]"
        is ConfigObj -> error("ConfigObj must be rendered as a table section")
        is ConfigComment -> "# ${node.text}"
    }

    private fun renderKey(key: String): String =
        if (BARE_KEY.matches(key)) key else quote(key)

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

    private data class DeferredSection(
        val key: String,
        val node: ConfigNode,
        val comments: List<ConfigComment>
    )

    private companion object {
        val BARE_KEY = Regex("""[A-Za-z0-9_-]+""")
    }
}
