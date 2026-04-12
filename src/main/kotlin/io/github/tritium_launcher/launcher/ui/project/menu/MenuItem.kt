package io.github.tritium_launcher.launcher.ui.project.menu

import io.github.tritium_launcher.launcher.registry.Registrable
import io.qt.gui.QIcon
import io.qt.widgets.QWidget
import java.util.concurrent.atomic.AtomicReference

/**
 * Menu contribution model.
 *
 * Supports actions, menus, separators, and arbitrary widgets.
 * Extensions register instances via the `ui.menu` registry.
 */
enum class MenuItemKind { ACTION, MENU, SEPARATOR, WIDGET }

data class MenuItem(
    override val id: String,
    val title: String,
    val parentId: String? = null,
    val action: ((MenuActionContext) -> Unit)? = null,
    val order: Int = 0,
    val visible: Boolean = true,
    val enabled: Boolean = true,
    val shortcut: String? = null,
    val meta: Map<String, String> = emptyMap(),
    val kind: MenuItemKind = MenuItemKind.MENU,
    val widgetFactory: ((MenuActionContext) -> QWidget)? = null,
    val childrenProvider: ((MenuActionContext) -> List<MenuItem>)? = null,
    val tooltip: String? = null,
    val icon: QIcon? = null,
    val titleResolver: ((MenuActionContext) -> String)? = null,
    val visibleResolver: ((MenuActionContext) -> Boolean)? = null,
    val enabledResolver: ((MenuActionContext) -> Boolean)? = null,
    val iconResolver: ((MenuActionContext) -> QIcon?)? = null
): Registrable {
    private val enabledOverride = AtomicReference<Boolean?>(null)

    /**
     * Returns the resolved enabled state for this item.
     *
     * Resolution order:
     * 1. Runtime override set via [setEnabled], [enable], [disable]
     * 2. [enabledResolver] when [ctx] is provided
     * 3. Static [enabled] constructor value
     */
    fun isEnabled(ctx: MenuActionContext? = null): Boolean {
        enabledOverride.get()?.let { return it }
        if (ctx != null) {
            val resolved = runCatching { enabledResolver?.invoke(ctx) }.getOrNull()
            if (resolved != null) return resolved
        }
        return enabled
    }

    /**
     * Returns the resolved icon for this item.
     */
    fun resolveIcon(ctx: MenuActionContext? = null): QIcon? {
        if (ctx != null) {
            val resolved = runCatching { iconResolver?.invoke(ctx) }.getOrNull()
            if (resolved != null) return resolved
        }
        return icon
    }

    /**
     * Returns the resolved title for this item.
     */
    fun resolveTitle(ctx: MenuActionContext? = null): String {
        if (ctx != null) {
            val resolved = runCatching { titleResolver?.invoke(ctx) }.getOrNull()
            if (!resolved.isNullOrBlank()) return resolved
        }
        return title
    }

    /**
     * Returns the resolved visible state for this item.
     */
    fun isVisible(ctx: MenuActionContext? = null): Boolean {
        if (!visible) return false
        if (ctx != null) {
            val resolved = runCatching { visibleResolver?.invoke(ctx) }.getOrNull()
            if (resolved != null) return resolved
        }
        return true
    }

    /**
     * Sets a runtime enabled override for this item.
     */
    fun setEnabled(enabled: Boolean): MenuItem {
        enabledOverride.set(enabled)
        return this
    }

    /**
     * Enables this item via runtime override.
     */
    fun enable(): MenuItem = setEnabled(true)

    /**
     * Disables this item via runtime override.
     */
    fun disable(): MenuItem = setEnabled(false)

    /**
     * Clears any runtime enabled override and falls back to resolver/static state.
     */
    fun clearEnabledOverride(): MenuItem {
        enabledOverride.set(null)
        return this
    }
}
