package io.github.tritium_launcher.launcher.registry

import java.util.concurrent.ConcurrentHashMap

/**
 * Global registry manager for creating and tracking named registries.
 */
object RegistryMngr {

    val registries = ConcurrentHashMap<RegistryKey, Registry<out Registrable>>()

    inline fun <reified T : Registrable> getOrCreateRegistry(name: String): Registry<T> {
        val key = RegistryKey(name, T::class)

        val stored: Registry<out Registrable> = registries.computeIfAbsent(key) {
            Registry(name, T::class) as Registry<out Registrable>
        }

        if(stored.elementClass != T::class) {
            throw IllegalStateException(
                "Registry '$name' exists with element type ${stored.elementClass}, requested ${T::class}",
            )
        }

        @Suppress("unchecked_cast")
        return stored as Registry<T>
    }

    inline fun <reified T: Registrable> getRegistryIfExists(name: String): Registry<T>? {
        val stored = registries[RegistryKey(name, T::class)] ?: return null

        if(stored.elementClass != T::class) return null

        @Suppress("unchecked_cast")
        return stored as Registry<T>
    }

    fun allRegistryKeys(): Set<RegistryKey> = registries.keys

    fun freezeAll() {
        registries.values.forEach { it.freeze() }
    }
}
