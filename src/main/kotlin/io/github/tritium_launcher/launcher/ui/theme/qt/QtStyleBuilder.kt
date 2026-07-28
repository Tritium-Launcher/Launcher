/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.ui.theme.qt

import io.github.tritium_launcher.api.connect
import io.github.tritium_launcher.launcher.ui.theme.TCol
import io.github.tritium_launcher.launcher.ui.theme.ThemeMngr
import io.qt.widgets.QWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@DslMarker
@Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE)
annotation class QtStyleMarker

/**
 * DSL for building Qt style sheets and applying them to widgets.
 *
 * Build rules with [StyleBuilder] and [QtStyleSheet], then apply via [qtStyle],
 * [QWidget.setStyle], or [QWidget.setThemedStyle].
 */
@Suppress("unused")
@QtStyleMarker
class StyleBuilder internal constructor(private val selector: String? = null) {
    private val props = LinkedHashMap<String, String>()
    private val children = mutableListOf<StyleBuilder>()

    fun backgroundColor(color: String) { props["background-color"] = color }
    @JvmName("backgroundColorTCol")
    fun backgroundColor(color: TCol) { props["background-color"] = color.value }
    fun background(value: String) { props["background"] = value }
    @JvmName("backgroundTCol")
    fun background(value: TCol) { props["background"] = value.value }
    fun color(color: String) { props["color"] = color }
    @JvmName("colorTCol")
    fun color(color: TCol) { props["color"] = color.value }
    fun selectionColor(color: String) { props["selection-color"] = color }
    @JvmName("selectionColorTCol")
    fun selectionColor(color: TCol) { props["selection-color"] = color.value }
    @JvmName("borderTCol")
    fun border(
        width: Int = 1,
        color: TCol = TCol.white,
        direction: String = "",
        style: String = "solid"
    ) {
        when(direction) {
            ""       -> props["border"] = "$width" + "px $style ${color.value}"
            "top"    -> props["border-top"] = "$width" + "px $style ${color.value}"
            "right"  -> props["border-right"] = "$width" + "px $style ${color.value}"
            "bottom" -> props["border-bottom"] = "$width" + "px $style ${color.value}"
            "left"   -> props["border-left"] = "$width" + "px $style ${color.value}"
        }
    }

    fun border(width: Int, color: String, direction: String = "", style: String = "solid") {
        when(direction) {
            ""       -> props["border"] = "$width" + "px $style $color"
            "top"    -> props["border-top"] = "$width" + "px $style $color"
            "right"  -> props["border-right"] = "$width" + "px $style $color"
            "bottom" -> props["border-bottom"] = "$width" + "px $style $color"
            "left"   -> props["border-left"] = "$width" + "px $style $color"
        }
    }

    /** Sets border to none */
    fun border() { props["border"] = "none" }

    /**
     * Applies borders on every side except [excludedSide].
     */
    @JvmName("borderExceptTCol")
    fun borderExcept(
        excludedSide: String,
        width: Int = 1,
        color: TCol = TCol.white,
        style: String = "solid"
    ) {
        val excluded = excludedSide.lowercase()
        border()
        if(excluded != "top") border(width, color, "top", style)
        if(excluded != "right") border(width, color, "right", style)
        if(excluded != "bottom") border(width, color, "bottom", style)
        if(excluded != "left") border(width, color, "left", style)
    }

    fun borderExcept(excludedSide: String, width: Int, color: String, style: String = "solid") {
        val excluded = excludedSide.lowercase()
        border()
        if(excluded != "top") border(width, color, "top", style)
        if(excluded != "right") border(width, color, "right", style)
        if(excluded != "bottom") border(width, color, "bottom", style)
        if(excluded != "left") border(width, color, "left", style)
    }

    fun borderRadius(radiusPx: Int, corner: Corner = Corner.All) {
        when(corner) {
            Corner.TLeft -> props["border-top-left-radius"] = "${radiusPx}px"
            Corner.TRight -> props["border-top-right-radius"] = "${radiusPx}px"
            Corner.BLeft -> props["border-bottom-left-radius"] = "${radiusPx}px"
            Corner.BRight -> props["border-bottom-right-radius"] = "${radiusPx}px"
            Corner.All -> props["border-radius"] = "${radiusPx}px"
        }
    }
    fun outlineColor(value: String) { props["outline-color"] = value }
    @JvmName("outlineColorTCol")
    fun outlineColor(value: TCol) { props["outline-color"] = value.value}
    fun padding(allPx: Int) { props["padding"] = "${allPx}px"}
    fun padding(top: Int = 0, right: Int = 0, bottom: Int = 0, left: Int = 0) { props["padding"] = "${top}px ${right}px ${bottom}px ${left}px" }
    fun margin(allPx: Int) { props["margin"] = "${allPx}px"}
    fun margin(top: Int = 0, right: Int = 0, bottom: Int = 0, left: Int = 0) { props["margin"] = "${top}px ${right}px ${bottom}px ${left}px"}
    fun spacing(length: Int) { props["spacing"] = "${length}px"}
    fun minHeight(px: Int) { props["min-height"] = "${px}px"}
    fun minWidth(px: Int) { props["min-width"] = "${px}px"}
    fun maxHeight(px: Int) { props["max-height"] = "${px}px"}
    fun maxWidth(px: Int) { props["max-width"] = "${px}px"}
    fun cursor(cursor: String) { props["cursor"] = cursor}
    fun opacity(value: Int) {
        if(value !in 0..255) props["opacity"] = value.toString()
        else props["opacity"] = 255.toString()
    }

    fun fontSize(px: Int) { props["font-size"] = "${px}px"}
    fun fontWeight(value: Int) { props["font-weight"] = "$value"}
    fun textAlign(value: String) { props["text-align"] = value }

    fun showDecorationSelected(value: Boolean = true) { props["show-decoration-selected"] = if(value) "1" else "0" }

    fun any(name: String, value: String) { props[name] = value }
    @JvmName("anyTCol")
    fun any(name: String, value: TCol) { props[name] = value.value }

    fun descendant(selectorSuffix: String, block: StyleBuilder.() -> Unit) {
        val childSelector = when {
            selector == null -> selectorSuffix
            selectorSuffix.startsWith(":") -> "$selector$selectorSuffix"
            else -> "$selector $selectorSuffix"
        }
        val child = StyleBuilder(childSelector).apply(block)
        children.add(child)
    }

    fun pseudo(pseudoName: String, block: StyleBuilder.() -> Unit) {
        val childSelector = (selector ?: "") + pseudoName
        val child = StyleBuilder(childSelector).apply(block)
        children.add(child)
    }

    override fun toString(): String {
        val sb = StringBuilder()
        if(props.isNotEmpty()) {
            val sel = selector ?: "QWidget"
            sb.append(sel).append(" {")
            props.forEach { (k, v) ->
                sb.append(k).append(": ").append(v).append(';')
            }
            sb.append("}\n")
        }
        children.forEach { sb.append(it.toString()) }
        return sb.toString()
    }

    companion object {
        fun selector(sel: String, block: StyleBuilder.() -> Unit): StyleBuilder =
            StyleBuilder(sel).apply(block)
    }
}

/**
 * Container for style rules that can be applied to a [QWidget].
 */
@QtStyleMarker
class QtStyleSheet {
    private val blocks = mutableListOf<StyleBuilder>()

    fun selector(sel: String, block: StyleBuilder.() -> Unit) {
        blocks.add(StyleBuilder.selector(sel, block))
    }

    fun widget(block: StyleBuilder.() -> Unit) {
        blocks.add(StyleBuilder(null).apply(block))
    }

    fun toStyleSheet(): String = blocks.joinToString(separator = "\n") { it.toString() }

    fun applyTo(widget: QWidget) {
        widget.styleSheet = toStyleSheet()
        widget.update()
        widget.repaint()
    }
}

fun qtStyle(block: QtStyleSheet.() -> Unit): QtStyleSheet {
    return QtStyleSheet().apply(block)
}

/**
 * Apply a themed style sheet and keep it updated when the theme changes.
 *
 * Returns a cleanup function to remove the theme listener.
 */
fun QWidget.setThemedStyle(block: QtStyleSheet.() -> Unit): () -> Unit {
    val apply: () -> Unit = {
        try {
            qtStyle(block).applyTo(this)
        } catch (_: Throwable) {}
    }
    apply()

    val job = CoroutineScope(Dispatchers.Main).launch {
        ThemeMngr.currentColorThemeId.collect { apply() }
    }

    try {
        this.destroyed.connect { job.cancel() }
    } catch (_: Throwable) {}

    return {
        job.cancel()
    }
}

/**
 * Apply a one-off style sheet tied to this widget's objectName.
 */
fun QWidget.setStyle(block: StyleBuilder.() -> Unit) {

    val name = if(this.objectName.isNullOrBlank()) {
        val gen = "qt_${System.identityHashCode(this)}"
        this.objectName = gen
        gen
    } else this.objectName

    val style = qtStyle {
        selector(name) { block() }
    }

    this.styleSheet = style.toStyleSheet()
    this.update()
    this.repaint()
}

/**
 * Corner selector for [StyleBuilder.borderRadius].
 */
enum class Corner {
    TLeft, TRight, BLeft, BRight, All;
}
