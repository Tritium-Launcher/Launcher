package io.github.tritium_launcher.launcher.registry

import kotlin.reflect.KClass

/**
 * Key used to uniquely identify a registry by name and element type.
 */
data class RegistryKey(val name: String, val type: KClass<*>)
