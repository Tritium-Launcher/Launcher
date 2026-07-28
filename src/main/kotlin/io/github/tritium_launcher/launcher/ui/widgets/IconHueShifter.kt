/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.ui.widgets

import io.qt.gui.QColor
import io.qt.gui.QIcon
import io.qt.gui.QImage
import io.qt.gui.QPixmap
import java.util.concurrent.ConcurrentHashMap

object IconHueShifter {

    private val cache = ConcurrentHashMap<String, QPixmap>()

    fun modifyPixels(
        source: QPixmap,
        hueShift: Float? = null,
        saturationShift: Float? = null,
        lightnessShift: Float? = null
    ): QPixmap {
        if (hueShift == null && saturationShift == null && lightnessShift == null) return source

        val key = "${source.cacheKey()}|$hueShift|$saturationShift|$lightnessShift"
        cache[key]?.let { return it }

        val img = source.toImage() ?: return source
        val w = img.width()
        val h = img.height()
        val out = QImage(w, h, QImage.Format.Format_ARGB32)

        for (y in 0 until h) {
            for (x in 0 until w) {
                val argb = img.pixel(x, y)
                val a = (argb ushr 24) and 0xFF
                val r = ((argb ushr 16) and 0xFF) / 255f
                val g = ((argb ushr 8) and 0xFF) / 255f
                val b = (argb and 0xFF) / 255f

                val (h, s, l) = rgbToHsl(r, g, b)

                val shiftedH = (h + (hueShift ?: 0f) / 360f).let { if (it < 0f) it + 1f else if (it > 1f) it - 1f else it }
                val shiftedS = saturationShift?.let { (s + it).coerceIn(0f, 1f) } ?: s
                val shiftedL = lightnessShift?.let { (l + it).coerceIn(0f, 1f) } ?: l

                val (nr, ng, nb) = hslToRgb(shiftedH, shiftedS, shiftedL)

                val pixel = (a shl 24) or ((nr * 255f).toInt().coerceIn(0, 255) shl 16) or ((ng * 255f).toInt().coerceIn(0, 255) shl 8) or ((nb * 255f).toInt().coerceIn(0, 255))
                out.setPixel(x, y, pixel)
            }
        }

        val result = QPixmap.fromImage(out)
        cache[key] = result
        return result
    }

    fun modifyIcon(
        icon: QIcon,
        size: Int = 16,
        hueShift: Float? = null,
        saturationShift: Float? = null,
        lightnessShift: Float? = null
    ): QIcon {
        val pixmap = icon.pixmap(size, size)
        return QIcon(modifyPixels(pixmap, hueShift, saturationShift, lightnessShift))
    }

    private fun rgbToHsl(r: Float, g: Float, b: Float): Triple<Float, Float, Float> {
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val l = (max + min) / 2f

        if (max == min) return Triple(0f, 0f, l)

        val d = max - min
        val s = if (l <= 0.5f) d / (max + min) else d / (2f - max - min)

        val h = when (max) {
            r -> ((g - b) / d + if (g < b) 6f else 0f) / 6f
            g -> ((b - r) / d + 2f) / 6f
            else -> ((r - g) / d + 4f) / 6f
        }

        return Triple(h, s, l)
    }

    private fun hslToRgb(h: Float, s: Float, l: Float): Triple<Float, Float, Float> {
        if (s == 0f) return Triple(l, l, l)

        val q = if (l < 0.5f) l * (1f + s) else l + s - l * s
        val p = 2f * l - q

        fun hue2rgb(t: Float): Float {
            var tt = t
            if (tt < 0f) tt += 1f
            if (tt > 1f) tt -= 1f
            return when {
                tt < 1f / 6f -> p + (q - p) * 6f * tt
                tt < 1f / 2f -> q
                tt < 2f / 3f -> p + (q - p) * (2f / 3f - tt) * 6f
                else -> p
            }
        }

        return Triple(hue2rgb(h + 1f / 3f), hue2rgb(h), hue2rgb(h - 1f / 3f))
    }

    fun tintPixmap(source: QPixmap, color: QColor): QPixmap {
        val key = "tint:${source.cacheKey()}|${color.rgba()}"
        cache[key]?.let { return it }

        val img = source.toImage() ?: return source
        val w = img.width()
        val h = img.height()
        val out = QImage(w, h, QImage.Format.Format_ARGB32)
        val targetHue = color.hslHueF()
        val targetSat = color.hslSaturationF()

        for (y in 0 until h) {
            for (x in 0 until w) {
                val argb = img.pixel(x, y)
                val a = (argb ushr 24) and 0xFF
                val r = ((argb ushr 16) and 0xFF) / 255f
                val g = ((argb ushr 8) and 0xFF) / 255f
                val b = (argb and 0xFF) / 255f

                val (_, _, l) = rgbToHsl(r, g, b)
                val (nr, ng, nb) = hslToRgb(targetHue, targetSat, l)

                val pixel = (a shl 24) or ((nr * 255f).toInt().coerceIn(0, 255) shl 16) or ((ng * 255f).toInt().coerceIn(0, 255) shl 8) or ((nb * 255f).toInt().coerceIn(0, 255))
                out.setPixel(x, y, pixel)
            }
        }

        val result = QPixmap.fromImage(out)
        cache[key] = result
        return result
    }

    fun clearCache() = cache.clear()
}
