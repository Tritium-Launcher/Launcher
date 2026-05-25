package io.github.tritium_launcher.launcher.settings

import kotlin.properties.ReadOnlyProperty

/**
 * Creates a read-only property delegate for a setting.
 *
 * @param key The namespaced id of the setting.
 * @param defaultValue The value to return if the setting is not found or has an incompatible type.
 * @param mapper Optional function to map the raw setting value to the target type [T].
 */
fun <T> setting(
    key: NamespacedId,
    defaultValue: T,
    mapper: (Any?) -> T? = { @Suppress("UNCHECKED_CAST") (it as? T) }
): ReadOnlyProperty<Any?, T> = ReadOnlyProperty { _, _ ->
    mapper(SettingsMngr.currentValueOrNull(key)) ?: defaultValue
}

/**
 * Creates a read-only property delegate for an optional text setting.
 */
fun optionalTextSetting(key: NamespacedId): ReadOnlyProperty<Any?, String?> = ReadOnlyProperty { _, _ ->
    val raw = (SettingsMngr.currentValueOrNull(key) as? String)?.trim().orEmpty()
    raw.takeIf { it.isNotBlank() }
}

/**
 * Creates a read-only property delegate for an enum setting.
 */
fun <T : Enum<T>> enumSetting(
    key: NamespacedId,
    fallback: T,
    mapping: Map<String, T>
): ReadOnlyProperty<Any?, T> = ReadOnlyProperty { _, _ ->
    val raw = (SettingsMngr.currentValueOrNull(key) as? String)?.trim()?.lowercase() ?: return@ReadOnlyProperty fallback
    mapping[raw] ?: fallback
}
