package io.github.tritium_launcher.launcher.settings

/**
 * Base type for events emitted by [SettingsMngr].
 *
 * @property namespace Namespace associated with this event.
 * @see SettingsMngr.addListener
 * @see SettingsMngr.removeListener
 */
sealed interface SettingsEvent {
    val namespace: String
}

/**
 * Listener callback invoked for every [SettingsEvent].
 *
 * @see SettingsMngr.addListener
 */
typealias SettingsListener = (SettingsEvent) -> Unit

/**
 * Event fired when a setting's effective value changes through [SettingsMngr.updateValue].
 *
 * @property node Setting that changed.
 * @property oldValue Value before the update.
 * @property newValue Value after the update.
 * @see SettingsMngr.updateValue
 */
data class SettingValueChangedEvent<T>(
    val node: SettingNode<T>,
    val oldValue: T,
    val newValue: T
) : SettingsEvent {
    override val namespace: String = node.ownerNamespace
}

/**
 * Event fired when code suggests a value without applying it automatically.
 *
 * @property key Target setting key.
 * @property node Registered setting node when available, otherwise `null`.
 * @property currentValue Current value when [node] is available.
 * @property suggestedValue Proposed value.
 * @property reason Optional human-readable explanation.
 * @property source Optional source tag used by publishers.
 * @see SettingsMngr.suggestValue
 */
data class SettingValueSuggestedEvent<T>(
    val key: NamespacedId,
    val node: SettingNode<T>? = null,
    val currentValue: T? = null,
    val suggestedValue: T,
    val reason: String? = null,
    val source: String? = null
) : SettingsEvent {
    override val namespace: String = key.namespace
}
