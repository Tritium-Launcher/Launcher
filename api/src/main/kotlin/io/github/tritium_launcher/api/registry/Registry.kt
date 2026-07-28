/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.api.registry

import io.github.tritium_launcher.api.extension.Extension
import io.github.tritium_launcher.api.registry.exceptions.DuplicateRegistrationException
import io.github.tritium_launcher.api.registry.exceptions.InvalidIdException
import io.github.tritium_launcher.api.registry.exceptions.RegistryFrozenException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.reflect.KClass

private val LOCAL_ID = Regex("^[a-zA-Z0-9_.-]+$")
private val NAMESPACED_ID = Regex("^[a-zA-Z0-9_.-]+:[a-zA-Z0-9_.-]+$")

/**
 * Events emitted by [Registry].
 */
sealed class RegistryEvent<T: Registrable> {
    data class Registered<T: Registrable>(val fullId: String, val entry: T) : RegistryEvent<T>()
}

/**
 * Namespaced registry for extension-provided entries.
 *
 * Entries are keyed by a local [Registrable.id] and namespace with the registering
 * extension id. Registries can be frozen to prevent further changes once startup
 * completes.
 */
@OptIn(ExperimentalAtomicApi::class)
class Registry<T: Registrable>(
    val name: String,
    val elementClass: KClass<T>
) {

    private val entries = ConcurrentHashMap<String, T>()
    private val _events = MutableSharedFlow<RegistryEvent<T>>(replay = 0)
    val events: SharedFlow<RegistryEvent<T>> = _events.asSharedFlow()
    private val frozen = AtomicBoolean(false)
    private var cachedAll: List<T>? = null
    private val entryNamespaces = ConcurrentHashMap<String, String>()

    val isFrozen: Boolean get() = frozen.load()

    private fun validateLocalId(localId: String) {
        if (!LOCAL_ID.matches(localId)) throw InvalidIdException(
            "Local id must match ${LOCAL_ID.pattern} (letters, digits, ., -, _). Got '$localId'"
        )
    }

    private fun validateNamespacedId(fullId: String) {
        if (!NAMESPACED_ID.matches(fullId)) throw InvalidIdException(
            "Namespaced id must match ${NAMESPACED_ID.pattern} (owner:local). Got '$fullId'"
        )
    }

    /**
     * Register a single entry under the current extension context.
     */
    context(ext: Extension)
    fun register(entry: T) {
        if(isFrozen) throw RegistryFrozenException("Registry '$name' is frozen")
        validateLocalId(entry.id)
        val namespacedId = "${ext.namespace.trim()}:${entry.id}"
        validateNamespacedId(namespacedId)
        val prev = entries.putIfAbsent(entry.id, entry)
        if(prev != null) throw DuplicateRegistrationException("Duplicate id '${entry.id}' in registry '$name'")
        entryNamespaces[entry.id] = ext.namespace.trim()
        _events.tryEmit(RegistryEvent.Registered(namespacedId, entry))
    }

    /**
     * Register a batch of entries under the current extension context.
     */
    context(ext: Extension)
    fun register(items: List<T>) {
        if(isFrozen) throw RegistryFrozenException("Registry '$name' is frozen")
        for(entry in items) {
            validateLocalId(entry.id)
            val namespacedId = "${ext.namespace.trim()}:${entry.id}"
            validateNamespacedId(namespacedId)
            val prev = entries.putIfAbsent(entry.id, entry)
            if (prev != null) throw DuplicateRegistrationException("Duplicate id '${entry.id}' in registry '$name'")
            entryNamespaces[entry.id] = ext.namespace.trim()
            _events.tryEmit(RegistryEvent.Registered(namespacedId, entry))
        }
    }

    /**
     * Register or replace an entry using an explicit extension id.
     */
    fun registerOrReplace(extId: String, entry: T) {
        if (isFrozen) throw RegistryFrozenException("Registry '$name' is frozen")
        validateLocalId(entry.id)
        val namespacedId = "${extId.trim()}:${entry.id}"
        validateNamespacedId(namespacedId)
        entries[entry.id] = entry
        entryNamespaces[entry.id] = extId.trim()
        _events.tryEmit(RegistryEvent.Registered(namespacedId, entry))
    }

    fun get(id: String): T? = entries[id]
    fun require(id: String): T = get(id) ?: throw NoSuchElementException("No entry '$id' in registry '$name'")
    fun all(): Collection<T> = cachedAll ?: entries.values.toList()
    fun namespaceOf(id: String): String? = entryNamespaces[id]
    fun namespacedIdOf(id: String): String = namespaceOf(id)?.let { "$it:$id" } ?: id
    fun contains(id: String): Boolean = entries.containsKey(id)
    fun size(): Int = entries.size

    fun freeze() {
        frozen.store(true)
        cachedAll = entries.values.toList()
    }

    fun clear() {
        if(isFrozen) throw RegistryFrozenException("Registry '$name' is frozen")
        entries.clear()
    }

    fun toListString(): String = entries.entries.joinToString(", ")

    override fun toString(): String = "Registry<${elementClass.qualifiedName}>($name, size=${entries.size})"
}
