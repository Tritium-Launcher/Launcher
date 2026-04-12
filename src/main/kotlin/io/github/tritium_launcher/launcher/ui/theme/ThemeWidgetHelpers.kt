package io.github.tritium_launcher.launcher.ui.theme

import io.github.tritium_launcher.launcher.TApp
import io.github.tritium_launcher.launcher.connect
import io.github.tritium_launcher.launcher.ui.theme.ColorPart.*
import io.qt.widgets.QWidget
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

private val autoObjNameCounter = AtomicInteger(0)

enum class ColorPart {
    Bg,
    Fg,
    Border,
    Icon,
    Placeholder,
    SelectionBg,
    SelectionFg,
    HoverBg,
    HoverFg,
    PressedBg,
    PressedFg;
}

fun QWidget.applyThemeStyle(colorKey: String, vararg parts: ColorPart) = applyThemeStyleTo(this, colorKey, *parts)

fun applyThemeStyleTo(widget: QWidget, colorKey: String, vararg parts: ColorPart): () -> Unit {
    val objName = ensureObjName(widget)
    val widgetMarkerStart = "/*theme-widget-style-start:$objName*/"
    val widgetMarkerEnd = "/*theme-widget-style-end:$objName*/"
    val globalMarkerStart = "/*theme-global-style-start:$objName*/"
    val globalMarkerEnd = "/*theme-global-style-end:$objName*/"

    val apply: () -> Unit = letUnit@{
        try {
            val hex = ThemeMngr.getColorHex(colorKey)
            if(hex == null) {
                removeInjectedFragment(widget, widgetMarkerStart, widgetMarkerEnd)
                removeGlobalFragment(globalMarkerStart, globalMarkerEnd)
                return@letUnit
            }

            val baseParts = parts.filter { p ->
                when(p) {
                    HoverBg, HoverFg, PressedBg, PressedFg -> false
                    else -> true
                }
            }.toTypedArray()

            if(baseParts.isNotEmpty()) {
                val inlineFragment = buildStyleFragment(null, hex, baseParts)
                injectFragment(widget, inlineFragment, widgetMarkerStart, widgetMarkerEnd)
            } else {
                removeInjectedFragment(widget, widgetMarkerStart, widgetMarkerEnd)
            }

            val hoverParts   = parts.filter { it == HoverBg || it == HoverFg }.toTypedArray()
            val pressedParts = parts.filter { it == PressedBg || it == PressedFg }.toTypedArray()

            val globalBuilder = StringBuilder()
            if(hoverParts.isNotEmpty()) {
                globalBuilder.append(buildStyleFragment("#$objName:hover", hex, hoverParts))
            }
            if(pressedParts.isNotEmpty()) {
                globalBuilder.append(buildStyleFragment("#$objName:pressed", hex, pressedParts))
            }

            if(globalBuilder.isNotEmpty()) {
                injectGlobalFragment(globalBuilder.toString(), globalMarkerStart, globalMarkerEnd)
            } else {
                removeGlobalFragment(globalMarkerStart, globalMarkerEnd)
            }

        } catch (e: Exception) {
            ThemeMngr.logger.warn("Failed to apply theme style for widget '{}': {}", widget, e.message)
        }
    }

    ThemeMngr.addListener(apply)
    apply()

    try {
        widget.destroyed.connect {
            try {
                ThemeMngr.removeListener(apply)
            } catch (e: Exception) {
                ThemeMngr.logger.warn("Failed to remove theme listener on destroy: {}", e.message)
            }
            removeInjectedFragment(widget, widgetMarkerStart, widgetMarkerEnd)
            removeGlobalFragment(globalMarkerStart, globalMarkerEnd)
        }
    } catch (_: Throwable) {}

    return {
        try {
            ThemeMngr.removeListener(apply)
        } catch (e: Exception) {
            ThemeMngr.logger.debug("Error removing theme listener: {}", e.message)
        }
        removeInjectedFragment(widget, widgetMarkerStart, widgetMarkerEnd)
        removeGlobalFragment(globalMarkerStart, globalMarkerEnd)
    }
}

private fun ensureObjName(widget: QWidget): String {
    val current = widget.objectName
    if(!current.isNullOrBlank()) return current
    val generated = "theme_auto_${abs(System.identityHashCode(widget))}_${autoObjNameCounter.incrementAndGet()}"
    widget.objectName = generated
    return generated
}

private fun buildStyleFragment(selector: String?, hex: String, parts: Array<out ColorPart>): String {
    val colorValue = hex.trim()
    val baseRules = mutableListOf<String>()

    for(p in parts) {
        baseRules += when(p) {
            Bg -> "background-color: $colorValue;"
            Fg -> "color: $colorValue;"
            Border -> "border: 1px solid $colorValue;"
            Icon -> "color: $colorValue;"
            Placeholder -> "placeholder-text-color: $colorValue;"
            SelectionBg -> "selection-background-color: $colorValue;"
            SelectionFg -> "selection-color: $colorValue;"
            HoverBg -> "background-color: $colorValue"
            HoverFg -> "color: $colorValue"
            PressedBg -> "background-color: $colorValue"
            PressedFg -> "color: $colorValue"
        }
    }

    val combined = baseRules.joinToString(" ")
    return if(selector.isNullOrEmpty()) combined else "$selector { $combined }"
}

private fun injectFragment(widget: QWidget, qssFragment: String, markerStart: String, markerEnd: String) {
    val existing = widget.styleSheet ?: ""
    val newFragment = "$markerStart $qssFragment $markerEnd"
    val updated = if (existing.contains(markerStart) && existing.contains(markerEnd)) {
        val start = existing.indexOf(markerStart)
        val end = existing.indexOf(markerEnd) + markerEnd.length
        existing.replaceRange(start, end, newFragment)
    } else {
        if (existing.isBlank()) newFragment else (existing.trimEnd() + "\n" + newFragment)
    }
    widget.styleSheet = updated
    try {
        widget.update()
    } catch (_: Throwable) {}
}

private fun removeInjectedFragment(widget: QWidget, markerStart: String, markerEnd: String) {
    val existing = widget.styleSheet ?: return
    if (!existing.contains(markerStart) || !existing.contains(markerEnd)) return
    val start = existing.indexOf(markerStart)
    val end = existing.indexOf(markerEnd) + markerEnd.length
    val cleaned = existing.removeRange(start, end).trim()
    widget.styleSheet = cleaned
    try {
        widget.update()
    } catch (_: Throwable) {}
}

private fun injectGlobalFragment(qssFragment: String, markerStart: String, markerEnd: String) {
    val existing = TApp.styleSheet ?: ""
    val newFragment = "$markerStart $qssFragment $markerEnd"
    val updated = if (existing.contains(markerStart) && existing.contains(markerEnd)) {
        val start = existing.indexOf(markerStart)
        val end = existing.indexOf(markerEnd) + markerEnd.length
        existing.replaceRange(start, end, newFragment)
    } else {
        if (existing.isBlank()) newFragment else (existing.trimEnd() + "\n" + newFragment)
    }
    TApp.styleSheet = updated
}

private fun removeGlobalFragment(markerStart: String, markerEnd: String) {
    val existing = TApp.styleSheet ?: return
    if (!existing.contains(markerStart) || !existing.contains(markerEnd)) return
    val start = existing.indexOf(markerStart)
    val end = existing.indexOf(markerEnd) + markerEnd.length
    val cleaned = existing.removeRange(start, end).trim()
    TApp.styleSheet = cleaned
}