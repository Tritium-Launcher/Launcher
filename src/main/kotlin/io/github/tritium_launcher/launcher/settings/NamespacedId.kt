package io.github.tritium_launcher.launcher.settings

private val LOCAL_ID = Regex("^[a-z0-9_.-]+$")
private val FULL_ID = Regex("^[a-z0-9_.-]+:[a-z0-9_.-]+$")

/**
 * Represents an id scoped by the owning namespace (usually an extension id).
 */
data class NamespacedId(val namespace: String, val id: String) {
    init {
        require(LOCAL_ID.matches(namespace)) { "Invalid namespace '$namespace', expected ${LOCAL_ID.pattern}" }
        require(LOCAL_ID.matches(id)) { "Invalid id '$id', expected ${LOCAL_ID.pattern}" }
    }

    /**
     * Returns the canonical `namespace:id` form.
     */
    override fun toString(): String = "$namespace:$id"

    companion object {
        /**
         * Parses a raw `namespace:id` string into a [NamespacedId].
         *
         * @param raw Full id in `namespace:id` format.
         * @return Parsed [NamespacedId].
         * @throws IllegalArgumentException if [raw] does not match the expected format.
         * @see NamespacedId.toString
         */
        fun parse(raw: String): NamespacedId {
            require(FULL_ID.matches(raw)) { "Namespaced id must look like 'owner:id' (got '$raw')" }
            val (ns, id) = raw.split(":", limit = 2)
            return NamespacedId(ns, id)
        }
    }
}
