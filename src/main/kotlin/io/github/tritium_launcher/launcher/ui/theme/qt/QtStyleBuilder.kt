package io.github.tritium_launcher.launcher.ui.theme.qt

import io.github.tritium_launcher.launcher.connect
import io.github.tritium_launcher.launcher.ui.theme.ThemeMngr
import io.qt.widgets.QWidget

@DslMarker
@Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE)
annotation class QtStyleMarker

/**
 * DSL for building Qt style sheets and applying them to widgets.
 *
 * Build rules with [StyleBuilder] and [QtStyleSheet], then apply via [qtStyle],
 * [QWidget.setStyle], or [QWidget.setThemedStyle].
 */
@QtStyleMarker
class StyleBuilder internal constructor(private val selector: String? = null) {
    private val props = LinkedHashMap<String, String>()
    private val children = mutableListOf<StyleBuilder>()

    fun backgroundColor(color: String) { props["background-color"] = color }
    fun background(value: String) { props["background"] = value }
    fun color(color: String) { props["color"] = color }
    fun selectionColor(color: String) { props["selection-color"] = color }
    fun border(
        width: Int = 1,
        color: String = "#ffffff",
        direction: String = "",
        style: String = "solid"
    ) {
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
    fun borderExcept(
        excludedSide: String,
        width: Int = 1,
        color: String = "#ffffff",
        style: String = "solid"
    ) {
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
    fun outlineColor(value: String) { props["outline-color"] = value}
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
    ThemeMngr.addListener(apply)
    apply()

    try {
        this.destroyed.connect { ThemeMngr.removeListener(apply) }
    } catch (_: Throwable) {}

    return {
        ThemeMngr.removeListener(apply)
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
 * Convenience helpers for CSS color strings.
 */
object QtColor {
    fun rgb(r: Int, g: Int, b: Int) = "rgb($r,$g,$b)"
    fun rgba(r: Int, g: Int, b: Int, a: Double) = "rgba($r,$g,$b,$a)"
    fun hex(hex: String) = if (hex.startsWith("#")) hex else "#$hex"
}

/**
 * Corner selector for [StyleBuilder.borderRadius].
 */
enum class Corner {
    TLeft, TRight, BLeft, BRight, All;
}
