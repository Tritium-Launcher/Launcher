package io.github.tritium_launcher.launcher.settings

/**
 * Link from a parent setting to a child setting with an enable predicate.
 *
 * @property node Child setting node.
 * @property enableWhen Predicate evaluated against the parent value to determine child enabled state in UI.
 * @see SettingNode.addChild
 * @see io.github.tritium_launcher.launcher.ui.settings.SettingsView
 */
data class SettingChildLink(
    val node: SettingNode<*>,
    val enableWhen: (Any?) -> Boolean = { true }
)

/**
 * Registered setting node, including descriptor and category ownership.
 *
 * @property key Fully-qualified setting id.
 * @property categoryPath Category containing this setting.
 * @property descriptor Definition metadata and serializer for this setting.
 * @property ownerNamespace Namespace that registered this setting.
 * @property registrationIndex Index assigned at registration for sorting.
 * @see SettingsRegistry.registerSetting
 * @see SettingsMngr.registerSetting
 */
data class SettingNode<T>(
    val key: NamespacedId,
    val categoryPath: CategoryPath,
    val descriptor: SettingDescriptor<T>,
    val ownerNamespace: String = key.namespace,
    val registrationIndex: Long = -1,
    private val childrenList: MutableList<SettingChildLink> = mutableListOf()
) {
    /**
     * Immutable snapshot of child links attached to this setting.
     */
    val children: List<SettingChildLink> get() = childrenList.toList()

    /**
     * Attaches [child] as a dependent setting controlled by this setting's value.
     *
     * @param child Child setting node.
     * @param enableWhen Predicate receiving the parent value. Return `true` to enable [child].
     * @see SettingChildLink
     */
    fun addChild(child: SettingNode<*>, enableWhen: (T) -> Boolean = { true }) {
        childrenList += SettingChildLink(child) { parentValue ->
            @Suppress("UNCHECKED_CAST")
            val typed = parentValue as? T ?: return@SettingChildLink false
            enableWhen(typed)
        }
    }
}

/**
 * Current resolved value for a setting.
 *
 * @property node Setting node this value belongs to.
 * @property value Current value.
 * @property origin Source of [value].
 * @see SettingsMngr.currentValue
 */
data class SettingValue<T>(
    val node: SettingNode<T>,
    val value: T,
    val origin: SettingValueOrigin
)

/**
 * Indicates where a [SettingValue] came from.
 */
enum class SettingValueOrigin { Default, File }
