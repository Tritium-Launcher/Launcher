/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.ui.theme

import io.qt.gui.QBrush
import io.qt.gui.QColor
import io.qt.gui.QPen

@Suppress("unused")
object TColors {

    object Editor {
        val Text get() = hex("Editor.Tab.Text")
    }
    val Button           get() = hex("Button")
    val Button0          get() = hex("Button0")
    val Button1          get() = hex("Button1")
    val Button2          get() = hex("Button2")
    val Button3          get() = hex("Button3")
    val ButtonDisabled0  get() = hex("ButtonDisabled0")
    val ButtonDisabled1  get() = hex("ButtonDisabled1")
    val Surface0         get() = hex("Surface0")
    val Surface1         get() = hex("Surface1")
    val Surface2         get() = hex("Surface2")
    val Text             get() = hex("Text")
    val SelectedText     get() = hex("SelectedText")
    val SelectedUI       get() = hex("SelectedUI")
    val Subtext          get() = hex("Subtext")
    val Accent           get() = hex("Accent")
    val Unsaved          get() = hex("Unsaved")
    val Green            get() = hex("Green")
    val Warning          get() = hex("Warning")
    val Error            get() = hex("Error")
    val ValidationBorder get() = hex("ValidationBorder")
    val Highlight        get() = hex("Highlight")
    val GameDataRoot     get() = hex("GameDataRoot")

    object Syntax {
        val Error       get() = hex("Syntax.Error")
        val Warning     get() = hex("Syntax.Warning")
        val Information get() = hex("Syntax.Info")

        val String      get() = hex("Syntax.String")
        val Key         get() = hex("Syntax.Key")
        val Keyword     get() = hex("Syntax.Keyword")
        val Number      get() = hex("Syntax.Number")
        val Punctuation get() = hex("Syntax.Punctuation")
        val Comment     get() = hex("Syntax.Comment")
        val Function    get() = hex("Syntax.Function")
        val Type        get() = hex("Syntax.Type")
        val Variable    get() = hex("Syntax.Variable")
        val Constant    get() = hex("Syntax.Constant")
        val Tag         get() = hex("Syntax.Tag")
        val Attribute   get() = hex("Syntax.Attribute")
        val Operator    get() = hex("Syntax.Operator")
        val Property    get() = hex("Syntax.Property")
        val Namespace   get() = hex("Syntax.Namespace")
        val Macro       get() = hex("Syntax.Macro")
        val Default     get() = hex("Syntax.Default")

        fun tokenColor(tokenType: String): QColor? = when (tokenType) {
            "String", "string"                              -> qColor("Syntax.String")
            "Key", "key"                                    -> qColor("Syntax.Key")
            "Keyword", "keyword", "modifier"                -> qColor("Syntax.Keyword")
            "Number", "number"                              -> qColor("Syntax.Number")
            "Punctuation", "punctuation", "operator"        -> qColor("Syntax.Punctuation")
            "Comment", "comment"                            -> qColor("Syntax.Comment")
            "Function", "function", "method"                -> qColor("Syntax.Function")
            "Type", "type", "class", "interface",
            "struct", "enum", "typeParameter"               -> qColor("Syntax.Type")
            "Variable", "variable", "parameter"             -> qColor("Syntax.Variable")
            "Constant", "constant", "enumMember"            -> qColor("Syntax.Constant")
            "Tag", "tag"                                    -> qColor("Syntax.Tag")
            "Attribute", "attribute"                        -> qColor("Syntax.Attribute")
            "Operator"                                      -> qColor("Syntax.Operator")
            "Property", "property"                          -> qColor("Syntax.Property")
            "Namespace", "namespace"                        -> qColor("Syntax.Namespace")
            "Macro", "macro"                                -> qColor("Syntax.Macro")
            else                                            -> qColor("Syntax.Default")
        }
    }

    object Log {
        val All       get() = hex("Log.All")
        val Info      get() = hex("Log.Info")
        val Warning   get() = hex("Log.Warning")
        val Err       get() = hex("Log.Err")
        val AllBg     get() = hex("Log.AllBg")
        val InfoBg    get() = hex("Log.InfoBg")
        val WarningBg get() = hex("Log.WarningBg")
        val ErrBg     get() = hex("Log.ErrBg")
    }

    /** Resolve a color key to a [TCol] from the active color theme. Falls back to white. */
    fun hex(key: String): TCol = ThemeMngr.getColorHex(key) ?: TCol.white

    /** Resolve a color key to a [QColor] from the active color theme. */
    fun qColor(key: String): QColor? = ThemeMngr.getQColor(key)
}

@JvmInline
value class TCol(val value: String) {

    /**
     * Returns a copy with the HSL lightness adjusted by [factor].
     *
     * A positive [factor] moves the color toward white by that fraction
     * of the remaining distance (0f = unchanged, 1f = fully white).
     * A negative [factor] moves it toward black (0f = unchanged, -1f = fully black).
     *
     * Examples:
     *   `"#808080".brightness(0.25f)` — 25% lighter (toward white)
     *   `"#808080".brightness(-0.50f)` — 50% darker (toward black)
     */
    fun brightness(factor: Float): TCol {
        val c = parseHex()
        val hsl = rgbToHsl(c[0], c[1], c[2])
        val newL = when {
            factor >= 0f -> hsl[2] + (1f - hsl[2]) * factor
            else -> hsl[2] + hsl[2] * factor
        }.coerceIn(0f, 1f)
        val rgb = hslToRgb(hsl[0], hsl[1], newL)
        return TCol(rgbToHex(rgb[0], rgb[1], rgb[2]))
    }

    private fun parseHex(): IntArray {
        val hex = value.removePrefix("#")
        val len = hex.length
        val r: Int
        val g: Int
        val b: Int
        val a: Int
        when (len) {
            3 -> {
                r = hex.substring(0, 1).repeat(2).toInt(16)
                g = hex.substring(1, 2).repeat(2).toInt(16)
                b = hex.substring(2, 3).repeat(2).toInt(16)
                a = 255
            }
            6 -> {
                r = hex.substring(0, 2).toInt(16)
                g = hex.substring(2, 4).toInt(16)
                b = hex.substring(4, 6).toInt(16)
                a = 255
            }
            8 -> {
                r = hex.substring(0, 2).toInt(16)
                g = hex.substring(2, 4).toInt(16)
                b = hex.substring(4, 6).toInt(16)
                a = hex.substring(6, 8).toInt(16)
            }
            else -> throw IllegalArgumentException("Invalid hex color: $value")
        }
        return intArrayOf(r, g, b, a)
    }

    fun toQC(block: QColor.() -> Unit = {}): QColor = QColor(this.value).apply(block)
    fun toQB(block: QBrush.() -> Unit = {}): QBrush = QBrush(this.toQC()).apply(block)
    fun toQP(block: QPen.() -> Unit = {}): QPen     = QPen(this.toQC()).apply(block)
    override fun toString(): String = value

    companion object {
        /** Fully transparent placeholder. */
        val transparent = TCol("transparent")
        /** Opaque white (`#FFFFFF`). */
        val white = TCol("#FFFFFF")
    }
}

private fun rgbToHsl(r: Int, g: Int, b: Int): FloatArray {
    val rf = r / 255f
    val gf = g / 255f
    val bf = b / 255f
    val max = maxOf(rf, gf, bf)
    val min = minOf(rf, gf, bf)
    val delta = max - min
    val l = (max + min) / 2f
    if (delta == 0f) return floatArrayOf(0f, 0f, l)
    val s = if (l > 0.5f) delta / (2f - max - min) else delta / (max + min)
    val h = when (max) {
        rf -> ((gf - bf) / delta + (if (gf < bf) 6f else 0f)) / 6f
        gf -> ((bf - rf) / delta + 2f) / 6f
        else -> ((rf - gf) / delta + 4f) / 6f
    }
    return floatArrayOf(h, s, l)
}

private fun hslToRgb(h: Float, s: Float, l: Float): IntArray {
    if (s == 0f) {
        val v = (l * 255f).toInt().coerceIn(0, 255)
        return intArrayOf(v, v, v)
    }
    val q = if (l < 0.5f) l * (1f + s) else l + s - l * s
    val p = 2f * l - q
    fun hue2rgb(t: Float): Float {
        var ht = t
        if (ht < 0f) ht += 1f
        if (ht > 1f) ht -= 1f
        return when {
            ht < 1f / 6f -> p + (q - p) * 6f * ht
            ht < 1f / 2f -> q
            ht < 2f / 3f -> p + (q - p) * (2f / 3f - ht) * 6f
            else -> p
        }
    }
    val r = (hue2rgb(h + 1f / 3f) * 255f).toInt().coerceIn(0, 255)
    val g = (hue2rgb(h) * 255f).toInt().coerceIn(0, 255)
    val b = (hue2rgb(h - 1f / 3f) * 255f).toInt().coerceIn(0, 255)
    return intArrayOf(r, g, b)
}

private fun rgbToHex(r: Int, g: Int, b: Int): String {
    return "#%02X%02X%02X".format(r, g, b)
}
