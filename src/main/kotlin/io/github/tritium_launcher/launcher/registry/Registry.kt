package io.github.tritium_launcher.launcher.registry

import io.github.tritium_launcher.launcher.extension.Extension
import io.github.tritium_launcher.launcher.registry.exceptions.DuplicateRegistrationException
import io.github.tritium_launcher.launcher.registry.exceptions.InvalidIdException
import io.github.tritium_launcher.launcher.registry.exceptions.RegistryFrozenException
import io.ktor.util.collections.*
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.reflect.KClass

private val LOCAL_ID = Regex("^[a-z0-9_.-]+$")
private val NAMESPACED_ID = Regex("^[a-z0-9_.-]+:[a-z0-9_.-]+$")

/**
 * Namespaced registry for extension-provided entries.
 *
 * Entries are keyed by a local [Registrable.id] and namespaced with the registering
 * extension id. Registries can be frozen to prevent further changes once startup
 * completes.
 */
@OptIn(ExperimentalAtomicApi::class)
class Registry<T: Registrable>(
    val name: String,
    val elementClass: KClass<T>
) {

    private val entries = ConcurrentMap<String, T>()
    private val listeners = CopyOnWriteArrayList<RegistryListener<T>>()
    private val frozen = AtomicBoolean(false)

    val isFrozen: Boolean get() = frozen.load()

    private fun validateLocalId(localId: String) {
        if (!LOCAL_ID.matches(localId)) throw InvalidIdException(
            "Local id must match ${LOCAL_ID.pattern} (lowercase, digits, ., -, _). Got '$localId'"
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
        listeners.forEach { it.onRegister(namespacedId, entry) }
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
            listeners.forEach { it.onRegister(namespacedId, entry) }
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
        listeners.forEach { it.onRegister(namespacedId, entry) }
    }

    fun get(id: String): T? = entries[id]
    fun require(id: String): T = get(id) ?: throw NoSuchElementException("No entry '$id' in registry '$name'")
    fun all(): Collection<T> = entries.values.toList()
    fun contains(id: String): Boolean = entries.containsKey(id)
    fun size(): Int = entries.size

    fun addListener(listener: RegistryListener<T>) {
        listeners += listener
    }

    fun removeListener(listener: RegistryListener<T>) {
        listeners -= listener
    }

    fun freeze() { frozen.store(true) }

    fun clear() {
        if(isFrozen) throw RegistryFrozenException("Registry '$name' is frozen")
        entries.clear()
    }

    fun toListString(): String = entries.entries.joinToString(", ")

    override fun toString(): String = "Registry<${elementClass.qualifiedName}>($name, size=${entries.size})"
}
