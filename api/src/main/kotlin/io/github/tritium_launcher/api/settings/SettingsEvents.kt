/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.api.settings

/**
 * Base type for events emitted by [io.github.tritium_launcher.launcher.settings.SettingsMngr].
 *
 * @property namespace Namespace associated with this event.
 */
sealed interface SettingsEvent {
    val namespace: String
}

/**
 * Listener callback invoked for every [SettingsEvent].
 */
typealias SettingsListener = (SettingsEvent) -> Unit

/**
 * Event fired when a setting's effective value changes through [io.github.tritium_launcher.launcher.settings.SettingsMngr.updateValue].
 *
 * @property node Setting that changed.
 * @property oldValue Value before the update.
 * @property newValue Value after the update.
 * @see io.github.tritium_launcher.launcher.settings.SettingsMngr.updateValue
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
 * @see io.github.tritium_launcher.launcher.settings.SettingsMngr.suggestValue
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
