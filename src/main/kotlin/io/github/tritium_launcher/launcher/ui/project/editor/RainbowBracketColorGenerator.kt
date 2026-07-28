/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.ui.project.editor

import io.github.tritium_launcher.api.TConstants
import io.github.tritium_launcher.api.io.VPath
import io.github.tritium_launcher.api.logger
import io.github.tritium_launcher.launcher.ui.theme.ThemeMngr
import kotlinx.serialization.json.*
import kotlin.math.abs
import kotlin.math.pow

object RainbowBracketColorGenerator {
    private val bracketDir: VPath by lazy {
        TConstants.TR_DIR.resolve(TConstants.Dirs.SETTINGS).resolve("rainbow_brackets").also { it.mkdirs() }
    }
    private val logger = logger()
    private val json = Json { prettyPrint = true }

    private const val CACHE_VERSION = 1

    private const val COLOR_COUNT = 8
    private const val MIN_CONTRAST = 3.5

    private const val GOLDEN_ANGLE = 137.508f

    private val hexColorRegex = Regex("^#[0-9A-Fa-f]{6}$")

    fun loadOrGenerate(themeId: String): List<String> {
        val bgHex = resolveBgHex(themeId)
        val file = fileFor(themeId)
        if (file.exists()) {
            loadFromFile(file, bgHex)?.let { return it }
        }
        return generateAndSave(themeId, bgHex, file)
    }

    private fun fileFor(themeId: String): VPath =
        bracketDir.resolve("$themeId.json")

    private fun loadFromFile(file: VPath, currentBgHex: String): List<String>? = try {
        val text = file.readTextOrNull() ?: return null
        val obj = json.parseToJsonElement(text).jsonObject

        if (obj["version"]?.jsonPrimitive?.int != CACHE_VERSION) return null
        if (obj["bg_hex"]?.jsonPrimitive?.content != currentBgHex) return null

        val storedCount = obj["count"]?.jsonPrimitive?.int ?: return null
        val colors = obj["colors"]?.jsonArray?.map { it.jsonPrimitive.content } ?: return null

        if (colors.size != storedCount) return null
        if (colors.any { !hexColorRegex.matches(it) }) return null

        colors
    } catch (_: Exception) { null }

    private fun generateAndSave(themeId: String, bgHex: String, file: VPath): List<String> {
        val colors = generateColors(bgHex, COLOR_COUNT)
        saveToFile(file, themeId, bgHex, colors)
        return colors
    }

    private fun resolveBgHex(themeId: String): String =
        listOf("LineEdit.Bg", "Surface1", "Surface0")
            .firstNotNullOfOrNull { ThemeMngr.getThemeColorHex(themeId, it) }
            ?: run {
                logger.error("No background color found for theme '$themeId'")
                "#242424"
            }

    internal fun generateColors(bgHex: String, count: Int = COLOR_COUNT): List<String> {
        val bgLum = relativeLuminance(bgHex)
        val isDark = bgLum < 0.4

        val baseSat: Float
        val targetLit: Float

        if (isDark) {
            val t = ((0.4 - bgLum) / 0.4).coerceIn(0.0, 1.0).toFloat()
            baseSat = 0.75f + t * 0.10f
            targetLit = 0.55f + t * 0.20f
        } else if (bgLum > 0.6) {
            val t = ((bgLum - 0.6) / 0.4).coerceIn(0.0, 1.0).toFloat()
            baseSat = 0.70f - t * 0.05f
            targetLit = 0.45f - t * 0.22f
        } else {
            baseSat = 0.75f
            targetLit = 0.50f
        }

        return (0 until count).map { i ->
            val hue = (i * GOLDEN_ANGLE) % 360f

            var lit = targetLit
            var bestLit = lit
            var bestContrast = 0.0

            var attempts = 0
            while (attempts < 20) {
                val hex = hslToHex(hue, baseSat, lit)
                val fgLum = relativeLuminance(hex)
                val contrast = contrastRatio(bgLum, fgLum)

                if (contrast > bestContrast) {
                    bestContrast = contrast
                    bestLit = lit
                }

                if (contrast >= MIN_CONTRAST) break

                lit = if (isDark) (lit + 0.03f).coerceAtMost(0.9f)
                else (lit - 0.03f).coerceAtLeast(0.1f)
                attempts++
            }
            hslToHex(hue, baseSat, bestLit)
        }
    }

    private fun saveToFile(file: VPath, themeId: String, bgHex: String, colors: List<String>) {
        val content = buildJsonObject {
            put("version", CACHE_VERSION)
            put("theme_id", themeId)
            put("bg_hex", bgHex)
            put("count", colors.size)
            put("colors", JsonArray(colors.map { JsonPrimitive(it) }))
        }
        file.writeBytes(json.encodeToString(JsonObject.serializer(), content).toByteArray())
    }

    private fun hslToHex(hue: Float, sat: Float, lit: Float): String {
        val c = (1f - abs(2f * lit - 1f)) * sat
        val x = c * (1f - abs(((hue / 60f) % 2f) - 1f))
        val m = lit - c / 2f
        val (r, g, b) = when {
            hue < 60f  -> Triple(c, x, 0f)
            hue < 120f -> Triple(x, c, 0f)
            hue < 180f -> Triple(0f, c, x)
            hue < 240f -> Triple(0f, x, c)
            hue < 300f -> Triple(x, 0f, c)
            else       -> Triple(c, 0f, x)
        }
        val ri = ((r + m) * 255).toInt().coerceIn(0, 255)
        val gi = ((g + m) * 255).toInt().coerceIn(0, 255)
        val bi = ((b + m) * 255).toInt().coerceIn(0, 255)
        return "#%02X%02X%02X".format(ri, gi, bi)
    }

    private fun parseHex(hex: String): Triple<Double, Double, Double> {
        val h = hex.removePrefix("#")
        if(h.length != 6 && h.length != 8) return Triple(0.5,0.5,0.5)
        return try {
            val r = h.substring(0, 2).toInt(16) / 255.0
            val g = h.substring(2, 4).toInt(16) / 255.0
            val b = h.substring(4, 6).toInt(16) / 255.0
            Triple(r, g, b)
        } catch (_: NumberFormatException) {
            Triple(0.5,0.5,0.5)
        }
    }

    private fun relativeLuminance(hex: String): Double {
        val (r, g, b) = parseHex(hex)
        fun linearize(c: Double) = if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        return 0.2126 * linearize(r) + 0.7152 * linearize(g) + 0.0722 * linearize(b)
    }

    private fun contrastRatio(l1: Double, l2: Double): Double {
        val lighter = maxOf(l1, l2)
        val darker = minOf(l1, l2)
        return (lighter + 0.05) / (darker + 0.05)
    }
}
