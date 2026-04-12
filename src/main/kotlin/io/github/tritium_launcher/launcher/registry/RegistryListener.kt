package io.github.tritium_launcher.launcher.registry

/**
 * Listener notified when a registry entry is registered.
 */
interface RegistryListener<T: Registrable> {
    fun onRegister(fullId: String, entry: T)
}
