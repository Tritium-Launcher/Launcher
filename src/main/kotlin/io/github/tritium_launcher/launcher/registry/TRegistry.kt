package io.github.tritium_launcher.launcher.registry

/**
 * Generic registry for ID lookup
 */
interface TRegistry<T> {
    fun register(item: T): T?

    fun deregister(id: String): T?

    fun get(id: String): T?

    fun getIgnoreCase(id: String): T?

    fun find(predicate: (T) -> Boolean): T?

    fun list(): List<T>

    fun clear()
}