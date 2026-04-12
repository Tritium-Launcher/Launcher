package io.github.tritium_launcher.launcher.settings

private val LOCAL_ID = Regex("^[a-z0-9_.-]+$")

/**
 * Hierarchical path to a settings category.
 *
 * The path is represented as ordered [segments], where each segment is a category id
 * scoped to the owning namespace.
 *
 * @property segments Ordered category segments from root to leaf.
 * @see SettingCategoryDescriptor
 * @see SettingsRegistry.CategoryNode
 */
data class CategoryPath(val segments: List<NamespacedId>) {
    init {
        require(segments.isNotEmpty()) { "CategoryPath must have at least one segment." }
    }

    /**
     * Returns the leaf category id.
     */
    val leaf: NamespacedId get() = segments.last()

    /**
     * Returns the namespace that owns the leaf category.
     */
    val ownerNamespace: String get() = leaf.namespace

    /**
     * Returns the parent path, or `null` if this path is already at root depth.
     *
     * @return Parent [CategoryPath] or `null`.
     */
    fun parent(): CategoryPath? = if (segments.size <= 1) null else CategoryPath(segments.dropLast(1))

    /**
     * Creates a child path by appending [id] to [segments].
     *
     * @param id Child category id to append.
     * @return New [CategoryPath] ending at [id].
     */
    fun child(id: NamespacedId): CategoryPath = CategoryPath(segments + id)

    /**
     * Returns a slash-delimited debug/render form of this path.
     */
    override fun toString(): String = segments.joinToString("/") { it.toString() }

    companion object {
        /**
         * Creates a root path with a single segment.
         *
         * @param id Root category id.
         * @return Root [CategoryPath].
         */
        fun root(id: NamespacedId): CategoryPath = CategoryPath(listOf(id))
    }
}

/**
 * Metadata used to register a settings category.
 *
 * @property id Local category id for the owner namespace.
 * @property title Display title shown in the settings UI.
 * @property description Optional description text shown in the settings UI.
 * @property allowForeignSettings Whether settings from other namespaces can be added to this category.
 * @property allowForeignSubcategories Whether subcategories from other namespaces can be nested under this category.
 * @property parent Optional parent category path; `null` means this is a root category.
 * When using [CategoryBuilder], prefer assigning `parent` as a category node directly.
 * @see SettingsRegistry.registerCategory
 * @see CategoryBuilder
 */
data class SettingCategoryDescriptor(
    val id: String,
    val title: String,
    val description: String? = null,
    val allowForeignSettings: Boolean = false,
    val allowForeignSubcategories: Boolean = false,
    val parent: CategoryPath? = null
) {
    init {
        require(LOCAL_ID.matches(id)) { "Category id must match ${LOCAL_ID.pattern} (got '$id')" }
    }
}
