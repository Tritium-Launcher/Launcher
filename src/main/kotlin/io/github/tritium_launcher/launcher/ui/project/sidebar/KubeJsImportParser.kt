/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.ui.project.sidebar

import io.github.tritium_launcher.api.logger

object KubeJsImportParser {
    private val logger = logger()
    private val tagRegex = Regex("""\{\{(.+?)}}""")
    private val chainSegmentRegex = Regex("""\.(\w+)\s*\(((?:[^()]|\([^()]*\))*)\)""")

    internal data class ParsedImport(
        val fills: Map<String, String>,
        val options: Map<String, String>
    )

    internal fun parse(
        recipeType: RecipeTypeData,
        methodName: String,
        args: List<String>,
        chainText: String
    ): ParsedImport? {
        val templates = recipeType.templates?.formats ?: return null
        val skipArgs = recipeType.importSkipArgs
        val positionalOptions = recipeType.importOptions

        for ((formatId, variants) in templates) {
            val variant = resolveVariant(methodName, variants) ?: continue
            val tags = parseTemplateTags(variant, formatId)
            val nonOptionTags = tags.filter { !it.isOption }

            val fills = mutableMapOf<String, String>()
            var argIdx = skipArgs

            for (tag in nonOptionTags) {
                val arg = args.getOrNull(argIdx) ?: break
                argIdx++

                when (tag.processor) {
                    "result" -> {
                        logger.debug("result processor: arg='{}' format='{}' argIdx={}", arg, tag.format, argIdx - 1)
                        val extracted = extractValue(arg, tag.format)
                        logger.debug("result processor: extracted='{}'", extracted)
                        if (extracted == null) continue
                        fills["output"] = extracted
                    }
                    "fill" -> {
                        val extracted = extractValue(arg, tag.format) ?: continue
                        val slot = tag.slotIds.firstOrNull() ?: continue
                        fills[slot] = extracted
                    }
                    "grid" -> {
                        val rows = parseGridLines(arg)
                        val cols = tag.slotIds.find { it.startsWith("cols=") }?.substringAfter("=")?.toIntOrNull() ?: 3
                        val startSlot = tag.slotIds.firstOrNull() ?: continue
                        val gridSlotNums = expandSlotRange(startSlot, cols * cols) ?: continue
                        for ((rowIdx, row) in rows.withIndex()) {
                            for ((colIdx, ch) in row.withIndex()) {
                                if (colIdx >= cols) break
                                val idx = rowIdx * cols + colIdx
                                val num = gridSlotNums.getOrNull(idx) ?: break
                                fills["input_$num"] = ch.toString()
                            }
                        }
                    }
                    "list" -> {
                        val startSlot = tag.slotIds.firstOrNull() ?: continue
                        val items = parseListItems(arg)
                        val listSlotNums = expandSlotRange(startSlot, items.size) ?: continue
                        for ((i, item) in items.withIndex()) {
                            val num = listSlotNums.getOrNull(i) ?: break
                            fills["input_$num"] = item
                        }
                    }
                    "keyMap" -> {
                        val pairs = parseKeyMap(arg)
                        for ((key, item) in pairs) {
                            fills["key_$key"] = item
                        }
                    }
                    else -> {
                        val extracted = extractValue(arg, tag.format) ?: continue
                        if (tag.slotIds.isNotEmpty()) {
                            fills[tag.slotIds.first()] = extracted
                        }
                    }
                }
            }

            val keyMap = fills.filterKeys { it.startsWith("key_") }
                .mapKeys { it.key.removePrefix("key_") }
            logger.debug("fills before keyMap resolution: {} keyMap={}", fills, keyMap)
            if (keyMap.isNotEmpty()) {
                fills.filterKeys { it.startsWith("input_") }.entries.toList().forEach { (slotId, value) ->
                    if (value.length == 1) {
                        keyMap[value]?.let { resolved ->
                            logger.debug("resolved {}={} -> {} via keyMap", slotId, value, resolved)
                            fills[slotId] = resolved
                        }
                    }
                }
                keyMap.keys.forEach { fills.remove("key_$it") }
                logger.debug("fills after keyMap resolution: {}", fills)
            }

            if (fills.isEmpty() && nonOptionTags.isNotEmpty()) continue
            if (!fills.containsKey("output") && nonOptionTags.isNotEmpty()) continue

            logger.debug("option tags: count={}", tags.count { it.isOption })
            val options = mutableMapOf<String, String>()

            for (tag in tags.filter { it.isOption }) {
                val key = tag.optionKey ?: continue
                var value: String? = null

                if (tag.chainPattern != null) {
                    value = matchChainOption(chainText, tag.chainPattern)
                    logger.debug("option '{}' chainMatch: '{}' chainText='{}' pattern='{}'", key, value, chainText, tag.chainPattern)
                }
                if (value == null) {
                    val posOpt = positionalOptions.find { it.key == key }
                    val idx = posOpt?.positionalIndex
                    logger.debug("option '{}' positionalFallback: positionalOptions={} idx={} args.indices={}", key, positionalOptions.map { it.key to it.positionalIndex }, idx, args.indices)
                    if (idx != null && idx in args.indices) {
                        value = extractRawValue(args[idx])
                        logger.debug("option '{}' positionalValue='{}' rawArg='{}'", key, value, args[idx])
                    }
                }
                if (value != null) options[key] = value
            }

            logger.debug("options after tag loop: {}", options)
            for (opt in positionalOptions) {
                if (opt.key in options) continue
                val value = when {
                    opt.chainPattern != null -> matchChainOption(chainText, opt.chainPattern)
                    opt.positionalIndex != null && opt.positionalIndex in args.indices ->
                        extractRawValue(args[opt.positionalIndex])
                    else -> null
                }
                logger.debug("fallback opt '{}': chainPattern={} positionalIndex={} value={}", opt.key, opt.chainPattern, opt.positionalIndex, value)
                if (value != null) options[opt.key] = value
            }

            logger.debug("final options: {}", options)
            return ParsedImport(fills, options)
        }
        return null
    }

    private fun resolveVariant(methodName: String, variants: Map<String, String>): String? {
        if (methodName in variants) return variants[methodName]
        if ("_" in variants) return variants["_"]
        return null
    }

    private data class ResolvedTag(
        val slotIds: List<String>,
        val format: String,
        val processor: String?,
        val isOption: Boolean,
        val optionKey: String?,
        val chainPattern: String?
    )

    private fun parseTemplateTags(template: String, defaultFormat: String): List<ResolvedTag> {
        return tagRegex.findAll(template).map { match ->
            val raw = match.groupValues[1].trim()
            val pipeIdx = raw.lastIndexOf('|')
            val (expr, fmt) = if (pipeIdx != -1) {
                raw.substring(0, pipeIdx).trim() to raw.substring(pipeIdx + 1).trim()
            } else {
                raw to defaultFormat
            }
            val parts = expr.split(":")
            val processor = parts.firstOrNull()
            val tagArgs = parts.drop(1)

            val isOption = processor == "option"
            val optionKey = if (isOption) tagArgs.firstOrNull() else null
            val chainPattern = if (isOption && tagArgs.size > 1) {
                tagArgs.drop(1).joinToString(":")
            } else null

            ResolvedTag(
                slotIds = tagArgs,
                format = fmt,
                processor = processor,
                isOption = isOption,
                optionKey = optionKey,
                chainPattern = if (isOption) chainPattern else null
            )
        }.toList()
    }

    private fun extractValue(arg: String, format: String): String? {
        val trimmed = arg.trim()
        when (format) {
            "kubejs" -> {
                val kubejsMatch = Regex("""(?:Item\.of|Ingredient\.of)\s*\(\s*['"]([^'"]+)['"]""").find(trimmed)
                if (kubejsMatch != null) return kubejsMatch.groupValues[1]
                val strMatch = Regex("""['"]([^'"]+)['"]""").find(trimmed)
                if (strMatch != null) return strMatch.groupValues[1]
                val countPrefix = Regex("""(\d+)x\s+['"]?(\S+)['"]?""").find(trimmed)
                if (countPrefix != null) return countPrefix.groupValues[2]
                return null
            }
            "json" -> {
                val jsonMatch = Regex("""["']item["']\s*:\s*["']([^"']+)["']|["']id["']\s*:\s*["']([^"']+)["']""").find(trimmed)
                if (jsonMatch != null) return jsonMatch.groupValues.firstOrNull { it.isNotEmpty() }
                return null
            }
            "id" -> {
                val idMatch = Regex("""['"]([^'"]+)['"]""").find(trimmed)
                return idMatch?.groupValues?.get(1) ?: trimmed.removeSurrounding("\"").removeSurrounding("'")
            }
            "quote" -> {
                val quoteMatch = Regex("""['"]([^'"]+)['"]""").find(trimmed)
                return quoteMatch?.groupValues?.get(1) ?: trimmed.removeSurrounding("\"").removeSurrounding("'")
            }
            else -> {
                val fallback = Regex("""['"]([^'"]+)['"]""").find(trimmed)
                return fallback?.groupValues?.get(1) ?: trimmed
            }
        }
    }

    private fun extractRawValue(arg: String): String? {
        val trimmed = arg.trim()
        val strMatch = Regex("""['"]([^'"]+)['"]""").find(trimmed)
        return strMatch?.groupValues?.get(1) ?: trimmed
    }

    private fun parseGridLines(text: String): List<String> {
        val cleaned = text.trim().removeSurrounding("[", "]").trim()
        return splitSimpleArgs(cleaned).map { it.trim().removeSurrounding("\"").removeSurrounding("'") }
    }

    private fun parseListItems(text: String): List<String> {
        val cleaned = text.trim().removeSurrounding("[", "]").trim()
        return splitSimpleArgs(cleaned).mapNotNull { extractValue(it, "kubejs") }
    }

    private fun parseKeyMap(text: String): Map<String, String> {
        val cleaned = text.trim().removeSurrounding("{", "}").trim()
        if (cleaned.isEmpty()) return emptyMap()
        val result = mutableMapOf<String, String>()
        val pairs = splitSimpleArgs(cleaned)
        for (pair in pairs) {
            val colonIdx = pair.indexOf(':')
            if (colonIdx < 0) continue
            val key = pair.substring(0, colonIdx).trim().removeSurrounding("'").removeSurrounding("\"")
            val value = extractValue(pair.substring(colonIdx + 1), "kubejs") ?: continue
            result[key] = value
        }
        return result
    }

    private fun splitSimpleArgs(text: String): List<String> {
        val args = mutableListOf<String>()
        var depth = 0
        var inString = false
        var stringChar = ' '
        var start = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (inString) {
                if (c == stringChar && (i == 0 || text[i - 1] != '\\')) inString = false
            } else when (c) {
                '\'', '"' -> { inString = true; stringChar = c }
                '(', '[', '{' -> depth++
                ')', ']', '}' -> depth--
                ',' -> if (depth == 0) {
                    args.add(text.substring(start, i).trim())
                    start = i + 1
                }
            }
            i++
        }
        val last = text.substring(start).trim()
        if (last.isNotEmpty()) args.add(last)
        return args
    }

    private fun flattenGridLetters(rows: List<String>): List<String> {
        val result = mutableListOf<String>()
        for (row in rows) {
            for (ch in row) {
                result.add(ch.toString())
            }
        }
        return result
    }

    private fun expandSlotRange(spec: String, count: Int): List<Int>? {
        if (".." in spec) {
            val parts = spec.split("..")
            val start = parts[0].removePrefix("input_").toIntOrNull() ?: return null
            val end = parts[1].removePrefix("input_").toIntOrNull() ?: return null
            return (start..end).toList()
        }
        if (spec.startsWith("input_")) {
            val base = spec.removePrefix("input_").toIntOrNull() ?: return null
            return (base until base + count).toList()
        }
        return (0 until count).toList()
    }

    private fun matchChainOption(chainText: String, pattern: String): String? {
        val idx = pattern.indexOf("$0")
        if (idx < 0) return null
        val prefix = Regex.escape(pattern.substring(0, idx))
        val suffix = Regex.escape(pattern.substring(idx + 2))
        val regexStr = prefix + "(.+?)" + suffix
        val regex = try { Regex(regexStr) } catch (e: Exception) { return null }
        val match = regex.find(chainText) ?: return null
        return match.groupValues.getOrNull(1)
    }
}
