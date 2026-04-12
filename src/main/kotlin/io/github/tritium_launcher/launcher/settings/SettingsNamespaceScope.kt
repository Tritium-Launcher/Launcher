package io.github.tritium_launcher.launcher.settings

/**
 * Reusable settings registration block bound to a [SettingsNamespaceScope] receiver.
 *
 * Define these blocks in any file, then register them with [SettingsMngr.register].
 *
 * @see SettingsMngr.register
 * @see settingsDefinition
 */
typealias SettingsRegistration = SettingsNamespaceScope.() -> Unit

/**
 * Declares a reusable settings registration block.
 *
 * @param block Registration lambda.
 * @return The same [SettingsRegistration] block for reuse.
 * @see SettingsRegistration
 */
fun settingsDefinition(block: SettingsRegistration): SettingsRegistration = block

/**
 * Namespace-bound registrar facade for defining settings outside [io.github.tritium_launcher.launcher.extension.Extension] context.
 *
 * @property namespace Namespace all registrations in this scope belong to.
 * @see SettingsMngr.forNamespace
 */
class SettingsNamespaceScope internal constructor(
    val namespace: String
) {
    /**
     * Executes an additional [registration] block within this namespace scope.
     *
     * @param registration Reusable registration lambda.
     */
    fun include(registration: SettingsRegistration) {
        registration(this)
    }

    /**
     * Registers a category descriptor in [namespace].
     *
     * @param descriptor Category descriptor.
     * @return Registered category node.
     */
    fun registerCategory(descriptor: SettingCategoryDescriptor): SettingsRegistry.CategoryNode =
        SettingsMngr.registerCategory(namespace, descriptor)

    /**
     * Registers a setting descriptor in [namespace].
     *
     * @param category Target category path.
     * @param descriptor Setting descriptor.
     * @return Registered setting node.
     */
    fun <T> registerSetting(category: CategoryPath, descriptor: SettingDescriptor<T>): SettingNode<T> =
        SettingsMngr.registerSetting(namespace, category, descriptor)

    /**
     * Builds and registers a category.
     *
     * @param id Local category id.
     * @param block Category builder mutation block.
     * @return Registered category node.
     */
    fun category(id: String, block: CategoryBuilder.() -> Unit = {}): SettingsRegistry.CategoryNode =
        SettingsMngr.category(namespace, id, block)

    /**
     * Builds and registers a toggle setting.
     *
     * @param category Target category path.
     * @param id Local setting id.
     * @param block Toggle builder mutation block.
     * @return Registered setting node.
     */
    fun toggle(category: CategoryPath, id: String, block: ToggleBuilder.() -> Unit = {}): SettingNode<Boolean> =
        SettingsMngr.toggle(namespace, category, id, block)

    /**
     * Builds and registers a text setting.
     *
     * @param category Target category path.
     * @param id Local setting id.
     * @param block Text builder mutation block.
     * @return Registered setting node.
     */
    fun text(category: CategoryPath, id: String, block: TextBuilder.() -> Unit = {}): SettingNode<String> =
        SettingsMngr.text(namespace, category, id, block)

    /**
     * Builds and registers a comment-only setting entry.
     *
     * @param category Target category path.
     * @param id Local setting id.
     * @param block Comment builder mutation block.
     * @return Registered setting node.
     */
    fun comment(category: CategoryPath, id: String, block: CommentBuilder.() -> Unit = {}): SettingNode<Unit> =
        SettingsMngr.comment(namespace, category, id, block)

    /**
     * Builds and registers a widget-backed setting.
     *
     * @param category Target category path.
     * @param id Local setting id.
     * @param block Widget builder mutation block.
     * @return Registered setting node.
     */
    fun <T> widget(category: CategoryPath, id: String, block: WidgetBuilder<T>.() -> Unit): SettingNode<T> =
        SettingsMngr.widget(namespace, category, id, block)
}
