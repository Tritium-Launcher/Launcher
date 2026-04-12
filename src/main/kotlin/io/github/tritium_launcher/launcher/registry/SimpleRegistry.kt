package io.github.tritium_launcher.launcher.registry

import io.github.tritium_launcher.launcher.logger
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread safe registry implementation.
 */
open class SimpleRegistry<T : Any>(
    val idFn: (T) -> String,
    caseInsensitiveLookup: Boolean = true
): TRegistry<T> {

    // Primary storage: id -> item
    private val map = ConcurrentHashMap<String, T>()

    // Optional case-insensitive index: lowercasedId -> canonicalId
    private val ciIndex: ConcurrentHashMap<String, String>? = if (caseInsensitiveLookup) ConcurrentHashMap() else null

    private val logger = logger()

    override fun register(item: T): T? {
        val id = idFn(item)
        require(id.isNotBlank()) { "idFN produced blank id" }
        val prev = map.put(id, item)
        if (ciIndex != null) ciIndex[id.lowercase()] = id
        return prev
    }

    override fun deregister(id: String): T? {
        val removed = map.remove(id)
        ciIndex?.remove(id.lowercase())
        return removed
    }

    override fun get(id: String): T? = map[id]

    override fun getIgnoreCase(id: String): T? {
        if (ciIndex == null) return map[id]
        val key = ciIndex[id.lowercase()] ?: return null
        return map[key]
    }

    override fun find(predicate: (T) -> Boolean): T? = map.values.firstOrNull(predicate)

    override fun list(): List<T> = map.values.toList()

    override fun clear() {
        map.clear()
        ciIndex?.clear()
    }

    fun registerAll(items: Iterable<T>) {
        for (i in items) register(i)
    }

    fun <P> fromServiceLoader(providerClass: Class<P>, mapper: (P) -> T?) {
        val loader = ServiceLoader.load(providerClass)
        for (prov in loader) {
            try {
                val item = mapper(prov)
                if (item != null) register(item)
            } catch (t: Throwable) {
                logger.error("Registry: Provider $prov failed: ${t.message}")
            }
        }
    }

    fun registerFromSupplier(supplier: () -> Iterable<T>) {
        val items = try {
            supplier()
        } catch (_: Throwable) {
            emptyList()
        }
        registerAll(items)
    }

    fun getOrThrow(id: String, err: (() -> Exception)? = null): T =
        get(id) ?: getIgnoreCase(id) ?: throw (err?.invoke() ?: NoSuchElementException("No registry item for id='$id'"))

    fun getOrResolve(id: String, resolver: () -> T?): T? {
        val found = get(id) ?: getIgnoreCase(id)
        if(found != null) return found
        val created = resolver()
        if(created != null) register(created)
        return created
    }

    /**
     * Iterate over each registered item in unspecified order.
     */
    fun forEach(action: (T) -> Unit) {
        val snapshot = map.values.toList()
        for(v in snapshot) action(v)
    }

    fun filter(predicate: (T) -> Boolean): List<T> = map.values.filter(predicate)

    fun <R> map(transform: (T) -> R): List<R> = map.values.map(transform)

    fun any(predicate: (T) -> Boolean): Boolean = map.values.any(predicate)

    fun all(predicate: (T) -> Boolean): Boolean = map.values.all(predicate)

    fun none(predicate: (T) -> Boolean): Boolean = map.values.none(predicate)

    fun count(predicate: (T) -> Boolean): Int = map.values.count(predicate)

    fun size(): Int = map.size

    fun isEmpty(): Boolean = map.isEmpty()

    fun isNotEmpty(): Boolean = map.isNotEmpty()


}
