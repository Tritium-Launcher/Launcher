package io.github.tritium_launcher.launcher.settings

import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigRenderOptions
import io.github.tritium_launcher.launcher.extension.Extension
import io.github.tritium_launcher.launcher.logger
import io.github.tritium_launcher.launcher.settings.SettingsMngr.addListener
import io.github.tritium_launcher.launcher.settings.SettingsMngr.applyPending
import io.github.tritium_launcher.launcher.settings.SettingsMngr.category
import io.github.tritium_launcher.launcher.settings.SettingsMngr.childrenOf
import io.github.tritium_launcher.launcher.settings.SettingsMngr.comment
import io.github.tritium_launcher.launcher.settings.SettingsMngr.currentValue
import io.github.tritium_launcher.launcher.settings.SettingsMngr.currentValueOrNull
import io.github.tritium_launcher.launcher.settings.SettingsMngr.findSetting
import io.github.tritium_launcher.launcher.settings.SettingsMngr.forNamespace
import io.github.tritium_launcher.launcher.settings.SettingsMngr.persistAll
import io.github.tritium_launcher.launcher.settings.SettingsMngr.persistNamespace
import io.github.tritium_launcher.launcher.settings.SettingsMngr.register
import io.github.tritium_launcher.launcher.settings.SettingsMngr.registerCategory
import io.github.tritium_launcher.launcher.settings.SettingsMngr.registerSetting
import io.github.tritium_launcher.launcher.settings.SettingsMngr.removeListener
import io.github.tritium_launcher.launcher.settings.SettingsMngr.suggestValue
import io.github.tritium_launcher.launcher.settings.SettingsMngr.text
import io.github.tritium_launcher.launcher.settings.SettingsMngr.toggle
import io.github.tritium_launcher.launcher.settings.SettingsMngr.updateValue
import io.github.tritium_launcher.launcher.settings.SettingsMngr.widget
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.hocon.Hocon
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Central entry point for registering settings, reading persisted values, and writing updates.
 *
 * Extensions register categories/settings via the builder helpers or by passing descriptors directly.
 * Values are stored per-namespace under ~/tritium/settings in HOCON format.
 */
@OptIn(ExperimentalSerializationApi::class)
object SettingsMngr {
    private object NullValueMarker
    private const val WRAPPED_KEY = "value"

    private val logger = logger()
    private val registry = SettingsRegistry()
    private val store = HoconSettingsStore()
    private val hocon = Hocon {
        useConfigNamingConvention = false
        encodeDefaults = true
    }
    private val renderOpts: ConfigRenderOptions = ConfigRenderOptions.defaults()
        .setJson(false)
        .setComments(false)
        .setOriginComments(false)

    private val loadedNamespaces = ConcurrentHashMap.newKeySet<String>()
    private val values = ConcurrentHashMap<NamespacedId, Any>()
    private val pendingRaw = ConcurrentHashMap<String, ConcurrentHashMap<String, String>>()
    private val loadLocks = ConcurrentHashMap<String, Any>()
    private val listeners = CopyOnWriteArrayList<SettingsListener>()

    /* Registration */

    /**
     * Registers a category for [namespace].
     *
     * @param namespace Namespace that owns the category.
     * @param descriptor Category metadata to register.
     * @return Registered category node.
     * @see SettingsRegistry.registerCategory
     * @see category
     */
    fun registerCategory(namespace: String, descriptor: SettingCategoryDescriptor): SettingsRegistry.CategoryNode =
        registry.registerCategory(namespace, descriptor)

    /**
     * Registers a category for the current extension namespace.
     *
     * @param descriptor Category metadata to register.
     * @return Registered category node.
     * @see registerCategory
     */
    context(ext: Extension)
    fun registerCategory(descriptor: SettingCategoryDescriptor): SettingsRegistry.CategoryNode =
        registerCategory(ext.namespace, descriptor)

    /**
     * Registers a setting in [category] for [namespace] and applies any pending value.
     *
     * @param namespace Namespace that owns the setting.
     * @param category Target category path.
     * @param descriptor Setting descriptor to register.
     * @return Registered setting node.
     * @see SettingsRegistry.registerSetting
     * @see updateValue
     */
    fun <T> registerSetting(namespace: String, category: CategoryPath, descriptor: SettingDescriptor<T>): SettingNode<T> {
        val node = registry.registerSetting(namespace, category, descriptor)
        ensureLoaded(node.ownerNamespace)
        applyPending(node)
        return node
    }

    /**
     * Registers a setting in [category] and immediately applies any previously loaded pending value.
     *
     * @param category Target category path.
     * @param descriptor Setting descriptor to register.
     * @return Registered setting node.
     * @see registerSetting
     */
    context(ext: Extension)
    fun <T> registerSetting(category: CategoryPath, descriptor: SettingDescriptor<T>): SettingNode<T> =
        registerSetting(ext.namespace, category, descriptor)

    /* Builder helpers */

    /**
     * Builds and registers a category using [CategoryBuilder] for [namespace].
     *
     * @param namespace Namespace that owns the category.
     * @param id Local category id.
     * @param block Builder mutation block.
     * @return Registered category node.
     * @see registerCategory
     * @see CategoryBuilder
     */
    fun category(namespace: String, id: String, block: CategoryBuilder.() -> Unit = {}): SettingsRegistry.CategoryNode =
        registerCategory(namespace, CategoryBuilder(id).apply(block).build())

    /**
     * Builds and registers a category using [CategoryBuilder].
     *
     * @param id Local category id.
     * @param block Builder mutation block.
     * @return Registered category node.
     * @see category
     */
    context(ext: Extension)
    fun category(id: String, block: CategoryBuilder.() -> Unit = {}): SettingsRegistry.CategoryNode =
        category(ext.namespace, id, block)

    /**
     * Builds and registers a toggle setting for [namespace].
     *
     * @param namespace Namespace that owns the setting.
     * @param category Target category path.
     * @param id Local setting id.
     * @param block Builder mutation block.
     * @return Registered setting node.
     * @see ToggleBuilder
     * @see registerSetting
     */
    fun toggle(namespace: String, category: CategoryPath, id: String, block: ToggleBuilder.() -> Unit = {}): SettingNode<Boolean> =
        registerSetting(namespace, category, ToggleBuilder(id).apply(block).build())

    /**
     * Builds and registers a toggle setting.
     *
     * @param category Target category path.
     * @param id Local setting id.
     * @param block Builder mutation block.
     * @return Registered setting node.
     * @see toggle
     */
    context(ext: Extension)
    fun toggle(category: CategoryPath, id: String, block: ToggleBuilder.() -> Unit = {}): SettingNode<Boolean> =
        toggle(ext.namespace, category, id, block)

    /**
     * Builds and registers a text setting for [namespace].
     *
     * @param namespace Namespace that owns the setting.
     * @param category Target category path.
     * @param id Local setting id.
     * @param block Builder mutation block.
     * @return Registered setting node.
     * @see TextBuilder
     * @see registerSetting
     */
    fun text(namespace: String, category: CategoryPath, id: String, block: TextBuilder.() -> Unit = {}): SettingNode<String> =
        registerSetting(namespace, category, TextBuilder(id).apply(block).build())

    /**
     * Builds and registers a text setting.
     *
     * @param category Target category path.
     * @param id Local setting id.
     * @param block Builder mutation block.
     * @return Registered setting node.
     * @see text
     */
    context(ext: Extension)
    fun text(category: CategoryPath, id: String, block: TextBuilder.() -> Unit = {}): SettingNode<String> =
        text(ext.namespace, category, id, block)

    /**
     * Builds and registers a comment-only setting entry for [namespace].
     *
     * @param namespace Namespace that owns the setting.
     * @param category Target category path.
     * @param id Local setting id.
     * @param block Builder mutation block.
     * @return Registered comment setting node.
     * @see CommentBuilder
     * @see CommentSettingDescriptor
     */
    fun comment(namespace: String, category: CategoryPath, id: String, block: CommentBuilder.() -> Unit = {}): SettingNode<Unit> =
        registerSetting(namespace, category, CommentBuilder(id).apply(block).build())

    /**
     * Builds and registers a comment-only setting entry.
     *
     * @param category Target category path.
     * @param id Local setting id.
     * @param block Builder mutation block.
     * @return Registered comment setting node.
     * @see comment
     */
    context(ext: Extension)
    fun comment(category: CategoryPath, id: String, block: CommentBuilder.() -> Unit = {}): SettingNode<Unit> =
        comment(ext.namespace, category, id, block)

    /**
     * Builds and registers a custom widget-backed setting for [namespace].
     *
     * @param namespace Namespace that owns the setting.
     * @param category Target category path.
     * @param id Local setting id.
     * @param block Builder mutation block.
     * @return Registered setting node.
     * @see WidgetBuilder
     * @see WidgetSettingDescriptor
     */
    fun <T> widget(namespace: String, category: CategoryPath, id: String, block: WidgetBuilder<T>.() -> Unit): SettingNode<T> =
        registerSetting(namespace, category, WidgetBuilder<T>(id).apply(block).build())

    /**
     * Builds and registers a custom widget-backed setting.
     *
     * @param category Target category path.
     * @param id Local setting id.
     * @param block Builder mutation block.
     * @return Registered setting node.
     * @see widget
     */
    context(ext: Extension)
    fun <T> widget(category: CategoryPath, id: String, block: WidgetBuilder<T>.() -> Unit): SettingNode<T> =
        widget(ext.namespace, category, id, block)

    /**
     * Creates a namespace-scoped settings registrar that can be passed to external files.
     *
     * @param namespace Namespace to bind registrations to.
     * @return Namespace-scoped registrar.
     * @see SettingsNamespaceScope
     */
    fun forNamespace(namespace: String): SettingsNamespaceScope = SettingsNamespaceScope(namespace.trim())

    /**
     * Registers a reusable [block] under [namespace].
     *
     * @param namespace Namespace to bind all registrations in [block].
     * @param block Registration block to execute.
     * @see SettingsRegistration
     * @see forNamespace
     */
    fun register(namespace: String, block: SettingsRegistration) {
        block(forNamespace(namespace))
    }

    /**
     * Creates a namespace-scoped settings registrar for the current extension.
     *
     * @return Namespace-scoped registrar bound to [Extension.namespace].
     * @see forNamespace
     */
    context(ext: Extension)
    fun scope(): SettingsNamespaceScope = forNamespace(ext.namespace)

    /**
     * Registers a reusable [block] for the current extension namespace.
     *
     * @param block Registration block to execute.
     * @see register
     */
    context(ext: Extension)
    fun register(block: SettingsRegistration) {
        register(ext.namespace, block)
    }

    /* Events */

    /**
     * Registers [listener] for settings events.
     *
     * Events are delivered synchronously on the calling thread.
     *
     * @param listener Event listener callback.
     * @see SettingsEvent
     * @see removeListener
     */
    fun addListener(listener: SettingsListener) {
        listeners += listener
    }

    /**
     * Removes a previously registered [listener].
     *
     * @param listener Listener to remove.
     * @see addListener
     */
    fun removeListener(listener: SettingsListener) {
        listeners -= listener
    }

    /**
     * Publishes a suggestion for [node] without mutating the setting.
     *
     * @param node Target setting node.
     * @param suggestedValue Value being suggested.
     * @param reason Optional human-readable reason for the suggestion.
     * @param source Optional source tag for diagnostics/analytics.
     * @see SettingValueSuggestedEvent
     * @see suggestValue
     */
    fun <T> suggestValue(
        node: SettingNode<T>,
        suggestedValue: T,
        reason: String? = null,
        source: String? = null
    ) {
        val current = currentValue(node).value
        emitEvent(
            SettingValueSuggestedEvent(
                key = node.key,
                node = node,
                currentValue = current,
                suggestedValue = suggestedValue,
                reason = reason,
                source = source
            )
        )
    }

    /**
     * Publishes a suggestion for a raw setting [key], even if the setting is not registered yet.
     *
     * If the setting exists in the registry, [currentValue] and node are populated in the event.
     *
     * @param key Target setting key.
     * @param suggestedValue Value being suggested.
     * @param reason Optional human-readable reason for the suggestion.
     * @param source Optional source tag for diagnostics/analytics.
     * @see SettingValueSuggestedEvent
     * @see suggestValue
     */
    @Suppress("UNCHECKED_CAST")
    fun suggestValue(
        key: NamespacedId,
        suggestedValue: Any?,
        reason: String? = null,
        source: String? = null
    ) {
        val node = registry.findSetting(key.namespace, key.id) as? SettingNode<Any?>
        val current = node?.let { currentValue(it).value }
        emitEvent(
            SettingValueSuggestedEvent(
                key = key,
                node = node,
                currentValue = current,
                suggestedValue = suggestedValue,
                reason = reason,
                source = source
            )
        )
    }

    /* Queries */

    /**
     * Returns root categories in deterministic display order.
     *
     * @return Root categories.
     * @see childrenOf
     */
    fun roots(): List<SettingsRegistry.CategoryNode> = registry.root()

    /**
     * Returns sorted children under [node].
     *
     * @param node Parent category node.
     * @return Child categories.
     */
    fun childrenOf(node: SettingsRegistry.CategoryNode): List<SettingsRegistry.CategoryNode> =
        registry.childrenOf(node)

    /**
     * Returns sorted settings declared directly in [node].
     *
     * @param node Category node.
     * @return Settings under [node].
     */
    fun settingsOf(node: SettingsRegistry.CategoryNode): List<SettingNode<*>> = registry.settingsOf(node)

    /**
     * Returns all registered settings.
     *
     * @return Settings from all namespaces.
     */
    fun allSettings(): List<SettingNode<*>> = registry.allSettings()

    /**
     * Finds a category by exact [path].
     *
     * @param path Absolute category path.
     * @return Matching category node, or `null`.
     * @see SettingsRegistry.findCategory
     */
    fun findCategory(path: CategoryPath): SettingsRegistry.CategoryNode? = registry.findCategory(path)

    /**
     * Finds a setting by fully-qualified [key].
     *
     * @param key Fully-qualified setting key.
     * @return Matching setting node, or `null` when not registered.
     * @see currentValueOrNull
     */
    fun findSetting(key: NamespacedId): SettingNode<*>? = registry.findSetting(key.namespace, key.id)

    /**
     * Returns the current value for [key], or `null` if the setting is not registered.
     *
     * @param key Fully-qualified setting key.
     * @return Current setting value, or `null` when no matching setting exists.
     * @see findSetting
     * @see currentValue
     */
    @Suppress("UNCHECKED_CAST")
    fun currentValueOrNull(key: NamespacedId): Any? {
        val node = findSetting(key) as? SettingNode<Any?> ?: return null
        return currentValue(node).value
    }

    /**
     * Searches registered settings by query text.
     *
     * Matching is case-insensitive and token-based across namespace, ids, titles,
     * descriptions, and category titles.
     *
     * @param query Free-form query text.
     * @param limit Maximum number of results to return. Values <= 0 disable limiting.
     * @return Ranked matching settings.
     * @see SettingSearchHit
     */
    fun search(query: String, limit: Int = 100): List<SettingSearchHit> {
        val normalized = query.trim().lowercase()
        if (normalized.isEmpty()) return emptyList()
        val terms = normalized.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (terms.isEmpty()) return emptyList()

        val hits = registry.allSettings().mapNotNull { node ->
            val category = registry.findCategory(node.categoryPath)
            val fields = searchableFields(node, category)
            if (terms.any { term -> fields.values.none { value -> value.contains(term) } }) {
                return@mapNotNull null
            }

            SettingSearchHit(
                node = node,
                category = category,
                score = scoreMatch(node, category, normalized, terms, fields)
            )
        }.sortedWith(
            compareByDescending<SettingSearchHit> { it.score }
                .thenBy { it.node.descriptor.title.lowercase() }
                .thenBy { it.key.toString() }
        )

        return if (limit > 0) hits.take(limit) else hits
    }

    /* Values */

    /**
     * Returns the current value for [node], resolving namespace data lazily if needed.
     *
     * @param node Setting node to query.
     * @return [SettingValue] containing value and source origin.
     * @see updateValue
     */
    @Suppress("UNCHECKED_CAST")
    fun <T> currentValue(node: SettingNode<T>): SettingValue<T> {
        ensureLoaded(node.ownerNamespace)
        val storedRaw = values[node.key]
        val (value, origin) = when (storedRaw) {
            null -> node.descriptor.defaultValue to SettingValueOrigin.Default
            NullValueMarker -> (null as T) to SettingValueOrigin.File
            else -> {
                val cast = storedRaw as? T
                if (cast == null) node.descriptor.defaultValue to SettingValueOrigin.Default else cast to SettingValueOrigin.File
            }
        }
        return SettingValue(node, value, origin)
    }

    /**
     * Validates and persists a new [value] for [node].
     *
     * @param node Setting node to update.
     * @param value New value to validate and store.
     * @return Validation result indicating acceptance or rejection.
     * @see currentValue
     * @see SettingDescriptor.validate
     */
    fun <T> updateValue(node: SettingNode<T>, value: T): SettingValidation {
        ensureLoaded(node.ownerNamespace)
        val oldValue = currentValue(node).value
        val validation = node.descriptor.validate(value)
        if (validation is SettingValidation.Invalid) return validation

        values[node.key] = value ?: NullValueMarker
        persistNamespace(node.ownerNamespace)
        if (oldValue != value) {
            emitEvent(
                SettingValueChangedEvent(
                    node = node,
                    oldValue = oldValue,
                    newValue = value
                )
            )
        }
        return SettingValidation.Valid
    }

    /**
     * Clears cached loaded values and pending raw entries.
     *
     * @param namespace Optional namespace to clear. When `null`, clears all namespaces.
     * @see currentValue
     */
    fun clearCached(namespace: String? = null) {
        if (namespace == null) {
            loadedNamespaces.clear()
            values.clear()
            pendingRaw.clear()
        } else {
            loadedNamespaces.remove(namespace)
            values.keys.filter { it.namespace == namespace }.forEach { values.remove(it) }
            pendingRaw.remove(namespace)
        }
    }

    /* Internal */

    /**
     * Ensures a namespace has been loaded once into in-memory state.
     *
     * @param namespace Namespace to ensure.
     */
    private fun ensureLoaded(namespace: String) {
        if (loadedNamespaces.contains(namespace)) return
        val lock = loadLocks.computeIfAbsent(namespace) { Any() }
        synchronized(lock) {
            if (loadedNamespaces.contains(namespace)) return
            loadNamespace(namespace)
            loadedNamespaces += namespace
        }
    }

    /**
     * Loads persisted entries for [namespace], applying known settings and buffering unknown ones.
     *
     * @param namespace Namespace to load.
     * @see applyPending
     */
    private fun loadNamespace(namespace: String) {
        val raw = store.read(namespace)
        if (raw.isEmpty()) return
        var pendingNs: ConcurrentHashMap<String, String>? = null
        raw.forEach { (localId, rendered) ->
            val node = registry.findSetting(namespace, localId)
            if (node == null) {
                val map = pendingNs ?: pendingRaw.computeIfAbsent(namespace) { ConcurrentHashMap() }.also { pendingNs = it }
                map[localId] = rendered
            } else {
                loadEntry(node, rendered)
            }
        }
        if (pendingNs != null && pendingNs!!.isEmpty()) {
            pendingRaw.remove(namespace)
        }
    }

    /**
     * Decodes and validates a single rendered setting entry before caching it.
     *
     * @param nodeAny Target setting node.
     * @param rendered HOCON-rendered value for the setting.
     */
    @Suppress("UNCHECKED_CAST")
    private fun loadEntry(nodeAny: SettingNode<*>, rendered: String) {
        val node = nodeAny as SettingNode<Any?>
        val descriptor = node.descriptor
        if (!descriptor.persistValue) return
        try {
            val decoded = decodeRendered(descriptor.serializer, rendered)
            val validation = descriptor.validate(decoded)
            if (validation is SettingValidation.Invalid) {
                logger.warn("Skipping invalid value for {}: {}", node.key, validation.reason)
                return
            }
            values[node.key] = decoded ?: NullValueMarker
        } catch (t: Throwable) {
            logger.warn("Failed to decode setting {} from disk, using default", node.key, t)
        }
    }

    /**
     * Persists all registered namespaces.
     *
     * @see persistNamespace
     */
    fun persistAll() {
        registry.allSettings().map { it.ownerNamespace }.distinct().forEach { persistNamespace(it) }
    }

    /**
     * Persists all settings for [namespace].
     *
     * @param namespace Namespace to persist.
     * @see persistAll
     */
    fun persistNamespace(namespace: String) {
        persistNamespaceInternal(namespace)
    }

    /**
     * Internal namespace persistence routine that also preserves unknown raw entries.
     *
     * @param namespace Namespace to persist.
     */
    @Suppress("UNCHECKED_CAST")
    private fun persistNamespaceInternal(namespace: String) {
        val nodes = registry.settingsForNamespace(namespace)
        val entries = nodes.mapNotNull { node ->
            val descriptor = node.descriptor
            if (!descriptor.persistValue) {
                if (descriptor is CommentSettingDescriptor) {
                    return@mapNotNull HoconSettingsStore.EncodedSetting(
                        key = descriptor.id,
                        renderedValue = "",
                        comments = descriptor.comments,
                        commentOnly = true
                    )
                }
                return@mapNotNull null
            }
            val storedRaw = values[node.key]
            val valueAny = when {
                storedRaw == null -> descriptor.defaultValue
                storedRaw === NullValueMarker -> null
                else -> storedRaw
            }
            val rendered = renderWithFallback(descriptor.serializer as KSerializer<Any?>, valueAny, descriptor.defaultValue, node.key)
                ?: return@mapNotNull null
            HoconSettingsStore.EncodedSetting(
                key = descriptor.id,
                renderedValue = rendered,
                comments = descriptor.comments
            )
        }.toMutableList()

        pendingRaw[namespace]?.forEach { (id, rendered) ->
            if (entries.none { it.key == id }) {
                entries += HoconSettingsStore.EncodedSetting(
                    key = id,
                    renderedValue = rendered
                )
            }
        }

        if (entries.isEmpty()) return
        store.write(namespace, entries)
    }

    /**
     * Applies a pending raw value for [node] if one was loaded before registration.
     *
     * @param node Newly registered setting node.
     */
    private fun applyPending(node: SettingNode<*>) {
        val pendingNs = pendingRaw[node.ownerNamespace] ?: return
        val rendered = pendingNs.remove(node.key.id) ?: return
        if (pendingNs.isEmpty()) {
            pendingRaw.remove(node.ownerNamespace)
        }
        loadEntry(node, rendered)
    }

    /**
     * Decodes a rendered HOCON value into a typed value using [serializer].
     *
     * @param serializer Serializer for the target type.
     * @param rendered Raw HOCON value string.
     * @return Decoded typed value.
     * @throws SerializationException when wrapped payload is missing.
     */
    private fun <T> decodeRendered(serializer: KSerializer<T>, rendered: String): T {
        val wrappedSerializer = MapSerializer(String.serializer(), serializer)
        val cfg = ConfigFactory.parseString("$WRAPPED_KEY = $rendered")
        val decoded = hocon.decodeFromConfig(wrappedSerializer, cfg)
        if (!decoded.containsKey(WRAPPED_KEY)) {
            throw SerializationException("Missing '$WRAPPED_KEY' for rendered value")
        }
        @Suppress("UNCHECKED_CAST")
        return decoded[WRAPPED_KEY] as T
    }

    /**
     * Renders [value] to a HOCON value string using [serializer].
     *
     * @param serializer Serializer for the value type.
     * @param value Value to render.
     * @return Rendered HOCON value.
     */
    private fun <T> renderValue(serializer: KSerializer<T>, value: T): String {
        val wrappedSerializer = MapSerializer(String.serializer(), serializer)
        val config = hocon.encodeToConfig(wrappedSerializer, mapOf(WRAPPED_KEY to value))
        return config.getValue(WRAPPED_KEY).render(renderOpts)
    }

    /**
     * Renders [value], falling back to [defaultValue] if encoding fails.
     *
     * @param serializer Serializer for value type.
     * @param value Primary value to render.
     * @param defaultValue Fallback value when rendering [value] fails.
     * @param key Setting key used for diagnostics.
     * @return Rendered value, or `null` if both primary and fallback encoding fail.
     */
    private fun <T> renderWithFallback(
        serializer: KSerializer<T>,
        value: T,
        defaultValue: T,
        key: NamespacedId
    ): String? {
        return try {
            renderValue(serializer, value)
        } catch (t: Throwable) {
            logger.warn("Failed to encode setting {} (falling back to default)", key, t)
            try {
                renderValue(serializer, defaultValue)
            } catch (t2: Throwable) {
                logger.warn("Failed to encode default for setting {}, skipping", key, t2)
                null
            }
        }
    }

    /**
     * Builds lowercase searchable fields for [node].
     */
    private fun searchableFields(
        node: SettingNode<*>,
        category: SettingsRegistry.CategoryNode?
    ): Map<String, String> = linkedMapOf(
        "key" to node.key.toString().lowercase(),
        "namespace" to node.ownerNamespace.lowercase(),
        "id" to node.key.id.lowercase(),
        "title" to node.descriptor.title.lowercase(),
        "description" to node.descriptor.description.orEmpty().lowercase(),
        "category" to category?.descriptor?.title.orEmpty().lowercase(),
    )

    /**
     * Scores a search match for [node] using field-specific weights.
     */
    private fun scoreMatch(
        node: SettingNode<*>,
        category: SettingsRegistry.CategoryNode?,
        normalized: String,
        terms: List<String>,
        fields: Map<String, String>
    ): Int {
        var score = 0
        val key = fields["key"].orEmpty()
        val id = fields["id"].orEmpty()
        val title = fields["title"].orEmpty()
        val categoryTitle = fields["category"].orEmpty()
        val description = fields["description"].orEmpty()
        val namespace = fields["namespace"].orEmpty()

        if (key == normalized) score += 220
        if (id == normalized) score += 200
        if (title == normalized) score += 180
        if (title.startsWith(normalized)) score += 120
        if (id.startsWith(normalized)) score += 100
        if (key.contains(normalized)) score += 90
        if (title.contains(normalized)) score += 80
        if (categoryTitle.contains(normalized)) score += 55
        if (description.contains(normalized)) score += 35
        if (namespace.contains(normalized)) score += 25

        terms.forEach { term ->
            if (title.contains(term)) score += 30
            if (id.contains(term)) score += 24
            if (categoryTitle.contains(term)) score += 18
            if (description.contains(term)) score += 10
            if (namespace.contains(term)) score += 8
        }

        if (category == null) score -= 15
        if (node.descriptor is CommentSettingDescriptor) score -= 8
        return score
    }

    /**
     * Dispatches [event] to all registered listeners.
     *
     * @param event Event payload.
     * @see addListener
     */
    private fun emitEvent(event: SettingsEvent) {
        listeners.forEach { listener ->
            try {
                listener(event)
            } catch (t: Throwable) {
                logger.warn("Settings listener failed for {}", event.javaClass.simpleName, t)
            }
        }
    }
}

/**
 * Builder for [SettingCategoryDescriptor].
 *
 * @param id Local category id.
 * @see SettingsMngr.category
 */
@SettingsDsl
class CategoryBuilder(private val id: String) {
    var title: String = id
    var description: String? = null
    var allowForeignSettings: Boolean = false
    var allowForeignSubcategories: Boolean = false
    var parent: SettingsRegistry.CategoryNode? = null
    private var parentPathOverride: CategoryPath? = null

    /**
     * Sets the parent category using a raw path.
     *
     * This keeps descriptor-based and migration code paths possible while the main builder
     * can use [parent] directly.
     *
     * @param path Parent category path.
     */
    fun parent(path: CategoryPath) {
        parentPathOverride = path
    }

    /**
     * Builds an immutable [SettingCategoryDescriptor] from the current builder state.
     *
     * @return Built category descriptor.
     */
    fun build(): SettingCategoryDescriptor = SettingCategoryDescriptor(
        id = id,
        title = title,
        description = description,
        allowForeignSettings = allowForeignSettings,
        allowForeignSubcategories = allowForeignSubcategories,
        parent = parent?.path ?: parentPathOverride
    )
}

/**
 * Builder for [ToggleSettingDescriptor].
 *
 * @param id Local setting id.
 * @see SettingsMngr.toggle
 */
@SettingsDsl
class ToggleBuilder(private val id: String) {
    var title: String = id
    var description: String? = null
    var defaultValue: Boolean = false
    var comments: List<String> = emptyList()
    var order: Int = -1

    /**
     * Builds an immutable [ToggleSettingDescriptor].
     *
     * @return Built toggle descriptor.
     */
    fun build(): ToggleSettingDescriptor =
        ToggleSettingDescriptor(id, title, description, defaultValue, comments, order)
}

/**
 * Builder for [TextSettingDescriptor].
 *
 * @param id Local setting id.
 * @see SettingsMngr.text
 */
@SettingsDsl
class TextBuilder(private val id: String) {
    var title: String = id
    var description: String? = null
    var defaultValue: String = ""
    var placeholder: String? = null
    var comments: List<String> = emptyList()
    var order: Int = -1
    private val disallowPatterns = mutableListOf<Regex>()

    /**
     * Adds a regex pattern that values must not match.
     *
     * @param pattern Regular expression pattern to disallow.
     */
    fun disallow(pattern: String) {
        disallowPatterns += Regex(pattern)
    }

    /**
     * Builds an immutable [TextSettingDescriptor].
     *
     * @return Built text descriptor.
     */
    fun build(): TextSettingDescriptor =
        TextSettingDescriptor(
            id = id,
            title = title,
            description = description,
            defaultValue = defaultValue,
            disallowed = disallowPatterns.toList(),
            placeholder = placeholder,
            comments = comments,
            order = order
        )
}

/**
 * Builder for [CommentSettingDescriptor].
 *
 * @param id Local setting id.
 * @see SettingsMngr.comment
 */
@SettingsDsl
class CommentBuilder(private val id: String) {
    var text: String = id
    var order: Int = -1

    /**
     * Builds an immutable [CommentSettingDescriptor].
     *
     * @return Built comment descriptor.
     */
    fun build(): CommentSettingDescriptor = CommentSettingDescriptor(id, text, order)
}

/**
 * Builder for [WidgetSettingDescriptor].
 *
 * @param id Local setting id.
 * @see SettingsMngr.widget
 */
@SettingsDsl
class WidgetBuilder<T>(private val id: String) {
    var title: String = id
    var description: String? = null
    var defaultValue: T? = null
    var serializer: KSerializer<T>? = null
    var comments: List<String> = emptyList()
    var order: Int = -1
    lateinit var widgetFactory: SettingWidgetFactory<T>

    /**
     * Builds an immutable [WidgetSettingDescriptor].
     *
     * @return Built widget descriptor.
     * @throws IllegalStateException when [defaultValue] or [widgetFactory] is not configured.
     */
    fun build(): WidgetSettingDescriptor<T> {
        val default = defaultValue ?: throw IllegalStateException("defaultValue is required for widget setting '$id'")
        val factory = if (this::widgetFactory.isInitialized) widgetFactory else throw IllegalStateException("widgetFactory is required for widget setting '$id'")
        return WidgetSettingDescriptor(
            id = id,
            title = title,
            description = description,
            defaultValue = default,
            serializer = serializer,
            widgetFactory = factory,
            comments = comments,
            order = order
        )
    }
}
