package io.github.tritium_launcher.launcher.ui.theme

import io.qt.gui.QColor

/**
 * Provides all colors provided by Themes.
 */
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
    val Green            get() = hex("Green")
    val Warning          get() = hex("Warning")
    val Error            get() = hex("Error")
    val ValidationBorder get() = hex("ValidationBorder")
    val Highlight        get() = hex("Highlight")

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
        val Default     get() = hex("Syntax.Default")
    }

    fun hex(key: String): String = ThemeMngr.getColorHex(key) ?: "#ffffff"
    fun qColor(key: String): QColor? = ThemeMngr.getQColor(key)
}
