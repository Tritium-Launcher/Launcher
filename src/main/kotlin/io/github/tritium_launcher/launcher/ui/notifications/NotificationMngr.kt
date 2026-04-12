package io.github.tritium_launcher.launcher.ui.notifications

import io.github.tritium_launcher.launcher.core.project.ProjectBase
import io.github.tritium_launcher.launcher.extension.core.BuiltinRegistries
import io.github.tritium_launcher.launcher.fromTR
import io.github.tritium_launcher.launcher.io.VPath
import io.github.tritium_launcher.launcher.logger
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.awt.GraphicsEnvironment
import java.awt.Image
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.image.BufferedImage
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList
import javax.imageio.ImageIO

/**
 * General notification runtime with registry definitions and preferences.
 *
 * Notifications are posted by id from the registry and stored as project-scoped
 * history. Active notifications are those where [NotificationEntry.dismissed] is `false`.
 */
object NotificationMngr {
    private const val GLOBAL_SCOPE = "__global__"
    private const val GLOBAL_PREFS_FILE = "notification-preferences.json"
    private const val MAX_HISTORY_PER_SCOPE = 400

    private val logger = logger()
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val listeners = CopyOnWriteArrayList<(NotificationEvent) -> Unit>()
    private val lock = Any()

    private val entriesByScope = LinkedHashMap<String, MutableList<NotificationEntry>>()
    private val entryScopeById = HashMap<String, String>()

    private var globalPrefsLoaded = false
    private val globalDisabledIds = LinkedHashSet<String>()
    private val projectDisabledByScope = HashMap<String, MutableSet<String>>()

    private val globalPrefsPath: VPath = fromTR("settings", GLOBAL_PREFS_FILE)

    /**
     * Emits a notification from a registered definition.
     *
     * @return Created entry or `null` when the definition was not found or disabled.
     */
    fun post(request: NotificationRequest): NotificationEntry? {
        val requestedId = request.id.trim()
        if (requestedId.isEmpty()) {
            logger.warn("Cannot post notification with blank definition id")
            return null
        }
        val definition = BuiltinRegistries.Notification.get(requestedId)
        if (definition == null) {
            logger.warn("Cannot post notification '{}'; no definition registered", requestedId)
            return null
        }

        val header = request.header?.trim().takeUnless { it.isNullOrEmpty() } ?: definition.header
        val description = request.description ?: definition.description
        val icon = request.icon ?: definition.icon
        val links = (request.links ?: definition.links).toList()
        val widgetFactory = request.customWidgetFactory ?: definition.customWidgetFactory
        val sendToOs = request.sendToOs ?: definition.sendToOsByDefault

        val entry = NotificationEntry(
            instanceId = UUID.randomUUID().toString(),
            definitionId = definition.id,
            projectPath = request.project?.path?.toString(),
            projectName = request.project?.name,
            header = header,
            description = description,
            icon = icon,
            links = links,
            customWidgetFactory = widgetFactory,
            sendToOs = sendToOs,
            createdAtEpochMs = System.currentTimeMillis(),
            metadata = request.metadata.toMap(),
        )

        val accepted = synchronized(lock) {
            if (isDisabledLocked(definition.id, request.project)) {
                false
            } else {
                val scope = scopeOf(request.project)
                val bucket = entriesByScope.getOrPut(scope) { mutableListOf() }
                bucket += entry
                entryScopeById[entry.instanceId] = scope
                trimHistoryLocked(scope, bucket)
                true
            }
        }
        if (!accepted) return null

        emit(NotificationEvent.Posted(entry))
        if (entry.sendToOs) {
            OsNotificationDispatcher.show(entry)
        }
        return entry
    }

    /**
     * Overload for posting without constructing [NotificationRequest].
     */
    fun post(
        id: String,
        project: ProjectBase? = null,
        header: String? = null,
        description: String? = null,
        icon: io.qt.gui.QIcon? = null,
        links: List<NotificationLink>? = null,
        customWidgetFactory: NotificationWidgetFactory? = null,
        sendToOs: Boolean? = null,
        metadata: Map<String, String> = emptyMap()
    ): NotificationEntry? {
        return post(
            NotificationRequest(
                id = id,
                project = project,
                header = header,
                description = description,
                icon = icon,
                links = links,
                customWidgetFactory = customWidgetFactory,
                sendToOs = sendToOs,
                metadata = metadata
            )
        )
    }

    /**
     * Marks a notification as dismissed.
     *
     * @return `true` when state changed.
     */
    fun dismiss(instanceId: String): Boolean {
        var changed = false
        var updatedEntry: NotificationEntry? = null

        synchronized(lock) {
            val scope = entryScopeById[instanceId] ?: return@synchronized
            val bucket = entriesByScope[scope] ?: return@synchronized
            val index = bucket.indexOfFirst { it.instanceId == instanceId }
            if (index < 0) return@synchronized
            val existing = bucket[index]
            if (existing.dismissed) return@synchronized
            val updated = existing.copy(dismissed = true)
            bucket[index] = updated
            changed = true
            updatedEntry = updated
        }

        if (changed && updatedEntry != null) {
            emit(NotificationEvent.Updated(updatedEntry))
        }
        return changed
    }

    /**
     * Permanently disables [definitionId] for all projects and dismisses active matching entries.
     *
     * @return `true` when the preference changed.
     */
    fun disableGlobally(definitionId: String): Boolean {
        val normalizedId = definitionId.trim()
        if (normalizedId.isEmpty()) return false
        var changed = false
        val dismissedEntries = synchronized(lock) {
            ensureGlobalPrefsLoadedLocked()
            changed = globalDisabledIds.add(normalizedId)
            if (changed) {
                persistGlobalPrefsLocked()
            }
            dismissMatchingLocked(normalizedId) { true }
        }
        dismissedEntries.forEach { emit(NotificationEvent.Updated(it)) }
        if (changed) emit(NotificationEvent.PreferencesChanged(normalizedId))
        return changed
    }

    /**
     * Permanently disables [definitionId] for [project] and dismisses active matching
     * entries in that project scope.
     *
     * @return `true` when the preference changed.
     */
    fun disableForProject(project: ProjectBase, definitionId: String): Boolean {
        val normalizedId = definitionId.trim()
        if (normalizedId.isEmpty()) return false
        val scope = scopeOf(project)
        var changed = false
        val dismissedEntries = synchronized(lock) {
            val set = projectDisabledSetLocked(project)
            changed = set.add(normalizedId)
            if (changed) {
                persistGlobalPrefsLocked()
            }
            dismissMatchingLocked(normalizedId) { it == scope }
        }
        dismissedEntries.forEach { emit(NotificationEvent.Updated(it)) }
        if (changed) emit(NotificationEvent.PreferencesChanged(normalizedId))
        return changed
    }

    /**
     * Re-enables [definitionId] globally.
     *
     * @return `true` when the preference changed.
     */
    fun enableGlobally(definitionId: String): Boolean {
        val normalizedId = definitionId.trim()
        if (normalizedId.isEmpty()) return false
        val changed = synchronized(lock) {
            ensureGlobalPrefsLoadedLocked()
            val removed = globalDisabledIds.remove(normalizedId)
            if (removed) {
                persistGlobalPrefsLocked()
            }
            removed
        }
        if (changed) emit(NotificationEvent.PreferencesChanged(normalizedId))
        return changed
    }

    /**
     * Re-enables [definitionId] for [project].
     *
     * @return `true` when the preference changed.
     */
    fun enableForProject(project: ProjectBase, definitionId: String): Boolean {
        val normalizedId = definitionId.trim()
        if (normalizedId.isEmpty()) return false
        val changed = synchronized(lock) {
            val set = projectDisabledSetLocked(project)
            val removed = set.remove(normalizedId)
            if (removed) {
                persistGlobalPrefsLocked()
            }
            removed
        }
        if (changed) emit(NotificationEvent.PreferencesChanged(normalizedId))
        return changed
    }

    /**
     * Returns whether [definitionId] is globally disabled.
     */
    fun isDisabledGlobally(definitionId: String): Boolean = synchronized(lock) {
        val normalizedId = definitionId.trim()
        if (normalizedId.isEmpty()) return@synchronized false
        ensureGlobalPrefsLoadedLocked()
        globalDisabledIds.contains(normalizedId)
    }

    /**
     * Returns whether [definitionId] is disabled for [project].
     */
    fun isDisabledForProject(project: ProjectBase, definitionId: String): Boolean = synchronized(lock) {
        val normalizedId = definitionId.trim()
        if (normalizedId.isEmpty()) return@synchronized false
        projectDisabledSetLocked(project).contains(normalizedId)
    }

    /**
     * Returns whether [definitionId] is disabled in the resolved scope.
     */
    fun isDisabled(definitionId: String, project: ProjectBase?): Boolean = synchronized(lock) {
        val normalizedId = definitionId.trim()
        if (normalizedId.isEmpty()) return@synchronized false
        isDisabledLocked(normalizedId, project)
    }

    /**
     * Finds a previously emitted notification entry by instance id.
     */
    fun findEntry(instanceId: String): NotificationEntry? = synchronized(lock) {
        val scope = entryScopeById[instanceId] ?: return@synchronized null
        entriesByScope[scope]?.firstOrNull { it.instanceId == instanceId }
    }

    /**
     * Returns project-scoped notification history.
     *
     * When [includeGlobal] is true, global notifications are merged into the result.
     * When [includeDisabled] is false, entries disabled for the resolved scope are omitted.
     */
    fun entriesForProject(
        project: ProjectBase?,
        includeDismissed: Boolean = true,
        includeGlobal: Boolean = true,
        includeDisabled: Boolean = true
    ): List<NotificationEntry> {
        val snapshot = synchronized(lock) {
            val result = mutableListOf<NotificationEntry>()
            if (project == null) {
                if (includeGlobal) {
                    result += entriesByScope[GLOBAL_SCOPE].orEmpty()
                }
            } else {
                if (includeGlobal) result += entriesByScope[GLOBAL_SCOPE].orEmpty()
                result += entriesByScope[scopeOf(project)].orEmpty()
            }
            val dismissalFiltered = if (includeDismissed) {
                result.toList()
            } else {
                result.filter { !it.dismissed }
            }
            if (includeDisabled) {
                dismissalFiltered
            } else {
                dismissalFiltered.filter { !isDisabledLocked(it.definitionId, project) }
            }
        }
        return snapshot.sortedByDescending { it.createdAtEpochMs }
    }

    /**
     * Subscribes to notification events.
     */
    fun addListener(listener: (NotificationEvent) -> Unit): () -> Unit {
        listeners += listener
        return { listeners -= listener }
    }

    /**
     * Clears all in-memory notification history.
     *
     * Active and dismissed entries are removed for every scope.
     *
     * @return Number of removed entries.
     */
    fun clearAll(): Int {
        val removed = synchronized(lock) {
            clearScopesLocked(entriesByScope.keys.toList())
        }
        if (removed > 0) emit(NotificationEvent.Cleared(removed))
        return removed
    }

    /**
     * Clears in-memory notification history for [project].
     *
     * @param includeGlobal Whether to also clear global-scope entries.
     * @return Number of removed entries.
     */
    fun clearForProject(project: ProjectBase, includeGlobal: Boolean = true): Int {
        val removed = synchronized(lock) {
            val scopes = buildList {
                add(scopeOf(project))
                if (includeGlobal) add(GLOBAL_SCOPE)
            }
            clearScopesLocked(scopes)
        }
        if (removed > 0) emit(NotificationEvent.Cleared(removed))
        return removed
    }

    private fun emit(event: NotificationEvent) {
        listeners.forEach { listener ->
            try {
                listener(event)
            } catch (t: Throwable) {
                logger.warn("Notification listener failed for {}", event.javaClass.simpleName, t)
            }
        }
    }

    private fun isDisabledLocked(definitionId: String, project: ProjectBase?): Boolean {
        ensureGlobalPrefsLoadedLocked()
        if (globalDisabledIds.contains(definitionId)) return true
        if (project == null) return false
        return projectDisabledSetLocked(project).contains(definitionId)
    }

    private fun scopeOf(project: ProjectBase?): String =
        project?.path?.toString()?.trim().orEmpty().ifBlank { GLOBAL_SCOPE }

    private fun dismissMatchingLocked(
        definitionId: String,
        scopeFilter: (String) -> Boolean
    ): List<NotificationEntry> {
        val dismissed = mutableListOf<NotificationEntry>()
        entriesByScope.forEach { (scope, bucket) ->
            if (!scopeFilter(scope)) return@forEach
            for (index in bucket.indices) {
                val current = bucket[index]
                if (current.definitionId != definitionId || current.dismissed) continue
                val updated = current.copy(dismissed = true)
                bucket[index] = updated
                dismissed += updated
            }
        }
        return dismissed
    }

    private fun trimHistoryLocked(scope: String, bucket: MutableList<NotificationEntry>) {
        while (bucket.size > MAX_HISTORY_PER_SCOPE) {
            val removed = bucket.removeAt(0)
            entryScopeById.remove(removed.instanceId)
        }
        if (bucket.isEmpty()) {
            entriesByScope.remove(scope)
        }
    }

    private fun clearScopesLocked(scopes: Collection<String>): Int {
        var removed = 0
        scopes.forEach { scope ->
            val bucket = entriesByScope.remove(scope) ?: return@forEach
            removed += bucket.size
            bucket.forEach { entry ->
                entryScopeById.remove(entry.instanceId)
            }
        }
        return removed
    }

    private fun ensureGlobalPrefsLoadedLocked() {
        if (globalPrefsLoaded) return
        globalPrefsLoaded = true
        if (!globalPrefsPath.exists()) return
        val raw = globalPrefsPath.readTextOrNull() ?: return
        val decoded = try {
            json.decodeFromString<GlobalNotificationPreferences>(raw)
        } catch (t: Throwable) {
            logger.warn("Failed to load notification preferences from {}", globalPrefsPath, t)
            return
        }

        globalDisabledIds.clear()
        globalDisabledIds += decoded.disabledIds
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        projectDisabledByScope.clear()
        decoded.disabledIdsByScope.forEach { (scopeRaw, idsRaw) ->
            val scope = scopeRaw.trim()
            if (scope.isEmpty()) return@forEach
            val normalized = idsRaw
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toMutableSet()
            if (normalized.isNotEmpty()) {
                projectDisabledByScope[scope] = normalized
            }
        }
    }

    private fun persistGlobalPrefsLocked() {
        try {
            globalPrefsPath.parent().mkdirs()
            val encoded = json.encodeToString(
                    GlobalNotificationPreferences.serializer(),
                    GlobalNotificationPreferences(
                        disabledIds = globalDisabledIds.toSet(),
                        disabledIdsByScope = projectDisabledByScope.mapValues { (_, ids) -> ids.toSet() }
                    )
                )
            globalPrefsPath.writeBytesAtomic(encoded.toByteArray())
        } catch (t: Throwable) {
            logger.warn("Failed to persist notification preferences to {}", globalPrefsPath, t)
        }
    }

    private fun projectDisabledSetLocked(project: ProjectBase): MutableSet<String> {
        val scope = scopeOf(project)
        return projectDisabledByScope.getOrPut(scope) { linkedSetOf() }
    }

    @Serializable
    private data class GlobalNotificationPreferences(
        val disabledIds: Set<String> = emptySet(),
        val disabledIdsByScope: Map<String, Set<String>> = emptyMap()
    )
}

//TODO: This kinda sucks
private object OsNotificationDispatcher {
    private val logger = logger()

    private val lock = Any()
    private var initialized = false
    private var trayIcon: TrayIcon? = null

    fun show(entry: NotificationEntry) {
        val tray = ensureTrayIcon() ?: return
        val title = entry.header.ifBlank { "Tritium" }
        val message = entry.description.ifBlank { entry.definitionId }
        try {
            tray.displayMessage(title, message, TrayIcon.MessageType.NONE)
        } catch (t: Throwable) {
            logger.debug("OS notification dispatch failed for {}", entry.definitionId, t)
        }
    }

    private fun ensureTrayIcon(): TrayIcon? = synchronized(lock) {
        if (initialized) return@synchronized trayIcon
        initialized = true

        if (GraphicsEnvironment.isHeadless() || !SystemTray.isSupported()) {
            return@synchronized null
        }

        return try {
            val tray = SystemTray.getSystemTray()
            val icon = TrayIcon(loadIconImage(), "Tritium").apply {
                isImageAutoSize = true
            }
            tray.add(icon)
            trayIcon = icon
            icon
        } catch (t: Throwable) {
            logger.debug("Failed to initialize OS tray notification icon", t)
            null
        }
    }

    private fun loadIconImage(): Image {
        return try {
            val resource = this::class.java.getResourceAsStream("/icons/tritium.png")
            resource?.use { stream ->
                val image = ImageIO.read(stream)
                if (image != null) {
                    return image
                }
            }
            BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
        } catch (_: Throwable) {
            BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
        }
    }
}
