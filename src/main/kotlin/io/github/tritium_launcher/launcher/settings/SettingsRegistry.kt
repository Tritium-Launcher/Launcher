package io.github.tritium_launcher.launcher.settings

import io.github.tritium_launcher.launcher.extension.Extension
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * In-memory registry for categories and settings.
 *
 * This structure is owned by [SettingsMngr] and is responsible for enforcing
 * namespace ownership, parent-child category constraints, and uniqueness rules.
 *
 * @see SettingsMngr
 * @see SettingCategoryDescriptor
 * @see SettingDescriptor
 */
class SettingsRegistry {
    private val logger = LoggerFactory.getLogger("SettingsRegistry")

    /**
     * Registered settings category node.
     *
     * @property key Fully-qualified category id.
     * @property descriptor Category metadata.
     * @property path Absolute category path from root.
     * @property ownerNamespace Namespace that owns this category.
     */
    data class CategoryNode(
        val key: NamespacedId,
        val descriptor: SettingCategoryDescriptor,
        val path: CategoryPath,
        val ownerNamespace: String = key.namespace,
        private val childrenList: MutableList<CategoryNode> = mutableListOf(),
        private val settingsList: MutableList<SettingNode<*>> = mutableListOf()
    ) {
        /**
         * Immutable snapshot of child categories.
         */
        val children: List<CategoryNode> get() = childrenList.toList()

        /**
         * Immutable snapshot of settings registered directly under this category.
         */
        val settings: List<SettingNode<*>> get() = settingsList.toList()

        /**
         * Adds a child category to this node.
         *
         * @param node Child category node.
         */
        internal fun addChild(node: CategoryNode) = childrenList.add(node)

        /**
         * Adds a setting to this category.
         *
         * @param node Setting node to attach.
         */
        internal fun addSetting(node: SettingNode<*>) = settingsList.add(node)
    }

    private val rootCategories = mutableListOf<CategoryNode>()
    private val categoriesByPath = mutableMapOf<CategoryPath, CategoryNode>()
    private val settingsByOwner = ConcurrentHashMap<String, MutableMap<String, SettingNode<*>>>()
    private val nextSettingRegistrationIndex = AtomicLong(0)

    /**
     * Registers a category under [ownerNamespace].
     *
     * @param ownerNamespace Namespace that owns the category.
     * @param descriptor Category definition.
     * @return Registered [CategoryNode].
     * @throws IllegalStateException if a duplicate path is registered or foreign nesting is not allowed.
     * @throws IllegalArgumentException if the declared parent path is not registered.
     * @see SettingCategoryDescriptor
     * @see registerSetting
     */
    fun registerCategory(ownerNamespace: String, descriptor: SettingCategoryDescriptor): CategoryNode {
        val ownerNs = ownerNamespace.trim()
        val key = NamespacedId(ownerNs, descriptor.id)
        val path = descriptor.parent?.child(key) ?: CategoryPath.root(key)
        if (categoriesByPath.containsKey(path)) {
            throw IllegalStateException("Duplicate category path $path")
        }

        val parentNode = descriptor.parent?.let { categoriesByPath[it] }
        if (descriptor.parent != null && parentNode == null) {
            throw IllegalArgumentException("Parent category ${descriptor.parent} must be registered before children.")
        }
        if (descriptor.parent != null && parentNode != null) {
            val sameOwner = parentNode.ownerNamespace == ownerNs
            if (!sameOwner && !parentNode.descriptor.allowForeignSubcategories) {
                throw IllegalStateException("Category ${parentNode.path} does not allow foreign subcategories.")
            }
        }

        val node = CategoryNode(
            key = key,
            descriptor = descriptor,
            path = path,
        )

        if (parentNode == null) {
            rootCategories += node
        } else {
            parentNode.addChild(node)
        }

        categoriesByPath[path] = node
        logger.debug("Registered category {} under {}", key, descriptor.parent ?: "root")
        return node
    }

    /**
     * Registers a category for the current extension namespace.
     *
     * @param descriptor Category definition.
     * @return Registered [CategoryNode].
     * @see registerCategory
     */
    context(ext: Extension)
    fun registerCategory(descriptor: SettingCategoryDescriptor): CategoryNode =
        registerCategory(ext.namespace, descriptor)

    /**
     * Registers a setting inside [category] for [ownerNamespace].
     *
     * @param ownerNamespace Namespace that owns the setting.
     * @param category Target category path.
     * @param descriptor Setting definition.
     * @return Registered [SettingNode].
     * @throws IllegalArgumentException if [category] is unknown.
     * @throws IllegalStateException if foreign settings are disallowed or id is duplicated.
     * @see SettingDescriptor
     * @see registerCategory
     */
    fun <T> registerSetting(ownerNamespace: String, category: CategoryPath, descriptor: SettingDescriptor<T>): SettingNode<T> {
        val ownerNs = ownerNamespace.trim()
        val categoryNode = categoriesByPath[category]
            ?: throw IllegalArgumentException("Category $category is not registered.")

        if (categoryNode.ownerNamespace != ownerNs && !categoryNode.descriptor.allowForeignSettings) {
            throw IllegalStateException("Category ${categoryNode.path} does not allow foreign settings.")
        }

        val perOwner = settingsByOwner.computeIfAbsent(ownerNs) { ConcurrentHashMap() }
        if (perOwner.containsKey(descriptor.id)) {
            throw IllegalStateException("Duplicate setting id '${descriptor.id}' for namespace '$ownerNs'")
        }

        val node = SettingNode(
            key = NamespacedId(ownerNs, descriptor.id),
            categoryPath = category,
            descriptor = descriptor,
            ownerNamespace = ownerNs,
            registrationIndex = nextSettingRegistrationIndex.getAndIncrement()
        )

        perOwner[descriptor.id] = node
        categoryNode.addSetting(node)
        logger.debug("Registered setting {} in category {}", node.key, category)
        return node
    }

    /**
     * Registers a setting inside [category] for the current extension namespace.
     *
     * @param category Target category path.
     * @param descriptor Setting definition.
     * @return Registered [SettingNode].
     * @see registerSetting
     */
    context(ext: Extension)
    fun <T> registerSetting(category: CategoryPath, descriptor: SettingDescriptor<T>): SettingNode<T> =
        registerSetting(ext.namespace, category, descriptor)

    /**
     * Finds a category by exact [path].
     *
     * @param path Absolute category path.
     * @return Matching [CategoryNode], or `null` when absent.
     */
    fun findCategory(path: CategoryPath): CategoryNode? = categoriesByPath[path]

    /**
     * Finds a setting by namespace and local id.
     *
     * @param ownerNamespace Namespace that owns the setting.
     * @param id Local setting id.
     * @return Matching [SettingNode], or `null`.
     */
    fun findSetting(ownerNamespace: String, id: String): SettingNode<*>? =
        settingsByOwner[ownerNamespace]?.get(id)

    /**
     * Returns root categories sorted for deterministic UI order.
     *
     * @return Sorted list of root [CategoryNode] entries.
     */
    fun root(): List<CategoryNode> = sortCategories(rootCategories)

    /**
     * Returns sorted child categories of [node].
     *
     * @param node Parent category node.
     * @return Sorted child categories.
     */
    fun childrenOf(node: CategoryNode): List<CategoryNode> = sortCategories(node.children)

    /**
     * Returns sorted settings directly under [node].
     *
     * @param node Category node.
     * @return Sorted settings.
     */
    fun settingsOf(node: CategoryNode): List<SettingNode<*>> = sortSettings(node.settings)

    /**
     * Returns all settings owned by [namespace], sorted for deterministic writes.
     *
     * @param namespace Owner namespace.
     * @return Sorted settings.
     */
    fun settingsForNamespace(namespace: String): List<SettingNode<*>> =
        settingsByOwner[namespace]?.values?.let { sortSettings(it) } ?: emptyList()

    /**
     * Returns every registered setting sorted for deterministic iteration.
     *
     * @return Sorted settings from all namespaces.
     */
    fun allSettings(): List<SettingNode<*>> = sortSettings(settingsByOwner.values.flatMap { it.values })

    /**
     * Sorts categories by namespace, title, then id for stable rendering order.
     */
    private fun sortCategories(categories: Collection<CategoryNode>): List<CategoryNode> =
        categories.sortedWith(compareBy<CategoryNode> { it.ownerNamespace }
            .thenBy { it.descriptor.title.lowercase() }
            .thenBy { it.key.id })

    /**
     * Sorts settings using explicit order first, then declaration order.
     *
     * Rules:
     * - `order >= 0`: Sorted ascending and shown before declaration-ordered settings.
     * - `order == -1`: Sorted by [SettingNode.registrationIndex].
     */
    private fun sortSettings(settings: Collection<SettingNode<*>>): List<SettingNode<*>> =
        settings.sortedWith(compareBy<SettingNode<*>> { it.descriptor.order < 0 }
            .thenBy { if (it.descriptor.order >= 0) it.descriptor.order else Int.MAX_VALUE }
            .thenBy { it.registrationIndex }
            .thenBy { it.ownerNamespace }
            .thenBy { it.key.id })
}
