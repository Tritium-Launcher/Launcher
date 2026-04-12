package io.github.tritium_launcher.launcher.core.project

import io.github.tritium_launcher.launcher.TConstants
import io.github.tritium_launcher.launcher.core.project.templates.MigrationRegistry
import io.github.tritium_launcher.launcher.core.project.templates.ProjectFileLoader
import io.github.tritium_launcher.launcher.core.project.templates.TemplateDescriptor
import io.github.tritium_launcher.launcher.core.project.templates.TemplateRegistry
import io.github.tritium_launcher.launcher.extension.core.CoreSettingValues
import io.github.tritium_launcher.launcher.fromTR
import io.github.tritium_launcher.launcher.io.VPath
import io.github.tritium_launcher.launcher.logger
import io.github.tritium_launcher.launcher.ui.dashboard.Dashboard
import io.github.tritium_launcher.launcher.ui.project.ProjectWindows
import io.github.tritium_launcher.launcher.ui.theme.TIcons
import io.qt.widgets.QApplication
import io.qt.widgets.QMessageBox
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.prefs.Preferences

/**
 * Central manager for loading and tracking cataloged projects on disk.
 */
object ProjectMngr {
    /** Marker type id used for catalog entries whose project definition is missing. */
    const val INVALID_CATALOG_PROJECT_TYPE: String = "catalog.invalid"
    private const val PREFS_NODE = "/tritium/project-mngr"
    private const val PREF_ACTIVE_PROJECT = "active-project"
    private const val PROJECT_FILE_NAME = "trproj.json"

    private val logger = logger()
    @Volatile var generationActive: Boolean = false

    private val listeners = CopyOnWriteArrayList<ProjectMngrListener>()

    private val _projectsLock = Any()
    private val _projects = mutableListOf<ProjectBase>()
    val projects: List<ProjectBase>
        get() = synchronized(_projectsLock) { _projects.toList() }

    @Volatile
    var activeProject: ProjectBase? = null
        private set
    val projectsDir = fromTR(TConstants.Dirs.PROJECTS)
    private val catalogFile = fromTR(VPath.get("projects/catalog.json")).toAbsolute()

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val catalogLock = Any()
    private var catalogLoaded = false
    private val catalogEntries = linkedMapOf<String, String>()

    @Serializable
    private data class ProjectCatalogEntry(
        val path: String,
        val name: String = ""
    )

    @Serializable
    private data class ProjectCatalogPayload(
        val entries: List<ProjectCatalogEntry> = emptyList(),
        val projects: List<String> = emptyList()
    )

    /**
     * Register a listener for project events.
     */
    fun addListener(listener: ProjectMngrListener) { listeners.add(listener) }
    /**
     * Unregister a listener.
     */
    fun removeListener(listener: ProjectMngrListener) { listeners.remove(listener) }

    private fun notifyProjectCreated(project: ProjectBase) {
        listeners.forEach { it.onProjectCreated(project) }
    }
    private fun notifyProjectFailedToGenerate(project: ProjectBase, errorMsg: String, exception: Exception?) {
        listeners.forEach { it.onProjectFailedToGenerate(project, errorMsg, exception) }
    }
    private fun notifyProjectOpened(project: ProjectBase) {
        listeners.forEach { it.onProjectOpened(project) }
    }
    private fun notifyFinishedLoading() {
        val snapshot = synchronized(_projectsLock) { _projects.toList() }
        listeners.forEach { it.onProjectsFinishedLoading(snapshot) }
    }

    private fun loadProjectFromDir(dir: VPath): ProjectBase? {
        val trMeta = ProjectFiles.readTrProject(dir) ?: run {
            logger.warn("No trproj.json found in {}", dir)
            return null
        }

        val typeId = trMeta.type
        val name = trMeta.name.ifBlank { "Unknown" }
        val icon = trMeta.icon.ifBlank { TIcons.defaultProjectIcon }
        val schemaVersion = trMeta.schemaVersion
        val metaElem = trMeta.meta.jsonObjectOrEmpty()

        val descriptor = TemplateRegistry.get(typeId)
        if(descriptor is ProjectFileLoader) {
            return try {
                descriptor.loadFromProjectFile(trMeta, dir)
            } catch (e: Exception) {
                logger.error("Failed to load project via ProjectFileLoader for type=$typeId in $dir", e)
                ProjectBase(typeId, dir, name, icon, metaElem.jsonObjectOrEmpty())
            }
        }

        if(descriptor != null) {
            try {
                @Suppress("UNCHECKED_CAST")
                val serializer = descriptor.serializer as KSerializer<Any>
                val migratedMeta = if(schemaVersion < descriptor.currentSchema) {
                    try {
                        MigrationRegistry.migrate(typeId, schemaVersion, descriptor.currentSchema, metaElem)
                    } catch (e: Exception) {
                        logger.error("Migration failed for {} from schema {} to {}", typeId, schemaVersion, descriptor.currentSchema, e)
                        metaElem
                    }
                } else metaElem
                val typed = json.decodeFromJsonElement(serializer, migratedMeta)
                @Suppress("UNCHECKED_CAST")
                val typedDescriptor = descriptor as TemplateDescriptor<Any>
                return typedDescriptor.createProjectFromMeta(typed, descriptor.currentSchema, dir)
            } catch (e: Exception) {
                logger.error("Failed to decode meta for type=$typeId in $dir", e)
                return ProjectBase(typeId, dir, name, icon, metaElem.jsonObjectOrEmpty())
            }
        }

        logger.warn("Unknown project type: $typeId (directory $dir)")
        return ProjectBase(typeId, dir, name, icon, metaElem.jsonObjectOrEmpty())
    }

    /**
     * Load a project from a directory if a project definition exists.
     */
    fun loadProject(dir: VPath): ProjectBase? = loadProjectFromDir(dir)

    private fun JsonElement.jsonObjectOrEmpty(): JsonObject = (this as? JsonObject) ?: JsonObject(emptyMap())

    /** Converts a path into a stable absolute catalog key. */
    private fun normalizeCatalogPath(path: VPath): String {
        val abs = path.expandHome().toAbsolute()
        return try {
            val jPath = abs.toJPath()
            val canonical = if (Files.exists(jPath)) jPath.toRealPath() else jPath.toAbsolutePath().normalize()
            canonical.toString()
        } catch (_: Exception) {
            abs.toString()
        }
    }

    /** Returns `true` when the given project directory contains `trproj.json`. */
    private fun hasProjectDefinition(dir: VPath): Boolean {
        val file = dir.resolve(PROJECT_FILE_NAME)
        return file.exists() && file.isFile()
    }

    /** Builds a fallback display name for a catalog entry from its path. */
    private fun fallbackCatalogName(path: String): String {
        return try {
            val fileName = VPath.get(path).fileName()
            fileName.ifBlank { path }
        } catch (_: Exception) {
            path
        }
    }

    /** Reads catalog entries from disk into memory. */
    private fun ensureCatalogLoadedLocked() {
        check(Thread.holdsLock(catalogLock)) { "ensureCatalogLoadedLocked must be called while holding catalogLock" }
        if (catalogLoaded) return
        catalogEntries.clear()
        if (!catalogFile.exists()) {
            catalogLoaded = true
            return
        }

        val text = try {
            Files.readString(catalogFile.toJPath())
        } catch (e: IOException) {
            logger.warn("Failed reading project catalog from {}", catalogFile, e)
            return
        } catch (e: Exception) {
            logger.warn("Unexpected failure reading project catalog from {}", catalogFile, e)
            return
        }

        if (text.isBlank()) {
            catalogLoaded = true
            return
        }

        val payload = try {
            json.decodeFromString<ProjectCatalogPayload>(text)
        } catch (e: SerializationException) {
            logger.warn("Failed parsing project catalog from {}", catalogFile, e)
            return
        } catch (e: Exception) {
            logger.warn("Unexpected failure parsing project catalog from {}", catalogFile, e)
            return
        }

        val loadedEntries = payload.entries.ifEmpty {
            payload.projects.map { p -> ProjectCatalogEntry(path = p) }
        }

        loadedEntries.forEach { entry ->
            val normalized = try {
                normalizeCatalogPath(VPath.get(entry.path))
            } catch (e: Exception) {
                logger.warn("Skipping malformed catalog path '{}'", entry.path, e)
                return@forEach
            }

            val name = entry.name.trim().ifBlank { fallbackCatalogName(normalized) }
            catalogEntries[normalized] = name
        }

        catalogLoaded = true
    }

    /**
     * Writes the in-memory catalog state to `projects/catalog.json`.
     *
     * @return `true` when write succeeded.
     */
    private fun writeCatalogLocked(): Boolean {
        check(Thread.holdsLock(catalogLock)) { "writeCatalogLocked must be called while holding catalogLock" }
        try {
            val payload = ProjectCatalogPayload(
                entries = catalogEntries.map { (path, name) ->
                    ProjectCatalogEntry(path = path, name = name)
                }
            )
            catalogFile.parent().mkdirs()
            catalogFile.writeBytesAtomic(json.encodeToString(payload).toByteArray())
            return true
        } catch (e: Exception) {
            logger.error("Failed writing project catalog to {}", catalogFile, e)
            return false
        }
    }

    /** Returns an immutable snapshot of current catalog entries. */
    private fun catalogSnapshot(): List<ProjectCatalogEntry> = synchronized(catalogLock) {
        ensureCatalogLoadedLocked()
        catalogEntries.map { (path, name) -> ProjectCatalogEntry(path, name) }
    }

    /**
     * Adds or updates a single catalog entry by path.
     *
     * @return `true` when the catalog changed.
     */
    private fun addCatalogPath(path: VPath, name: String? = null): Boolean = synchronized(catalogLock) {
        ensureCatalogLoadedLocked()
        val normalized = normalizeCatalogPath(path)
        val existing = catalogEntries[normalized]
        val resolvedName = when {
            !name.isNullOrBlank() -> name.trim()
            !existing.isNullOrBlank() -> existing
            else -> fallbackCatalogName(normalized)
        }
        val changed = existing == null || existing != resolvedName
        if (changed) {
            catalogEntries[normalized] = resolvedName
            if (!writeCatalogLocked()) {
                if (existing == null) {
                    catalogEntries.remove(normalized)
                } else {
                    catalogEntries[normalized] = existing
                }
                return@synchronized false
            }
        }
        changed
    }

    /** Applies name updates for existing catalog paths. */
    private fun updateCatalogNames(updates: Map<String, String>) = synchronized(catalogLock) {
        ensureCatalogLoadedLocked()
        if (updates.isEmpty()) return@synchronized
        var changed = false
        updates.forEach { (path, name) ->
            val trimmed = name.trim()
            if (trimmed.isBlank()) return@forEach
            if (!catalogEntries.containsKey(path)) return@forEach
            if (catalogEntries[path] != trimmed) {
                catalogEntries[path] = trimmed
                changed = true
            }
        }
        if (changed && !writeCatalogLocked()) {
            logger.warn("Catalog name updates were applied in memory but could not be persisted")
        }
    }

    /**
     * Removes catalog entries by absolute catalog path.
     *
     * @return number of removed entries.
     */
    private fun removeCatalogPaths(paths: Collection<String>): Int = synchronized(catalogLock) {
        ensureCatalogLoadedLocked()
        if (paths.isEmpty()) return 0
        var removedCount = 0
        for (p in paths) {
            if (catalogEntries.remove(p) != null) removedCount++
        }
        if (removedCount > 0 && !writeCatalogLocked()) {
            logger.warn("Catalog removals were applied in memory but could not be persisted")
        }
        removedCount
    }

    /**
     * Adds a project directory to the catalog.
     *
     * @param projectDir Project root directory.
     * @param projectName Optional display name override.
     * @return `true` when the catalog changed.
     */
    fun addProjectToCatalog(projectDir: VPath, projectName: String? = null): Boolean {
        val dir = projectDir.expandHome().toAbsolute().normalize()
        if (!hasProjectDefinition(dir)) {
            logger.warn("Skipping catalog add for {} (missing trproj.json)", dir)
            return false
        }
        val resolvedName = projectName?.trim().takeUnless { it.isNullOrBlank() }
            ?: ProjectFiles.readTrProject(dir)?.name?.trim().takeUnless { it.isNullOrBlank() }
            ?: dir.fileName().ifBlank { dir.toString() }
        return addCatalogPath(dir, resolvedName)
    }

    /**
     * Removes a project directory from the catalog.
     *
     * @param projectDir Project root directory to remove.
     * @return `true` when an entry was removed.
     */
    fun removeProjectFromCatalog(projectDir: VPath): Boolean {
        val normalized = normalizeCatalogPath(projectDir.expandHome().toAbsolute().normalize())
        val removed = removeCatalogPaths(listOf(normalized))
        if (removed <= 0) return false

        synchronized(_projectsLock) {
            _projects.removeAll { normalizeCatalogPath(it.projectDir) == normalized }
        }

        val currentActive = activeProject
        if (currentActive != null && normalizeCatalogPath(currentActive.projectDir) == normalized) {
            activeProject = null
        }
        return true
    }

    /** Creates a project model for an invalid catalog entry. */
    private fun invalidCatalogProject(path: VPath, catalogName: String): ProjectBase {
        val name = catalogName.ifBlank { path.fileName().ifBlank { path.toString() } }
        return ProjectBase(
            typeId = INVALID_CATALOG_PROJECT_TYPE,
            projectDir = path,
            name = name,
            icon = TIcons.defaultProjectIcon,
            rawMeta = JsonObject(emptyMap())
        )
    }

    /** Refreshes the in-memory project list using catalog entries. */
    private fun getProjectsFromCatalog(source: RefreshSource): List<ProjectBase> {
        if (source == RefreshSource.DASHBOARD && (generationActive || !isDashboardActive())) {
            logger.debug("Skipping project list refresh (source={}, generationActive={}, dashboardActive={})", source, generationActive, isDashboardActive())
            return synchronized(_projectsLock) { _projects.toList() }
        }

        val catalog = catalogSnapshot()
        val malformedEntries = mutableListOf<String>()
        val nameUpdates = linkedMapOf<String, String>()
        var loadedCount = 0
        var invalidCount = 0

        synchronized(_projectsLock) {
            _projects.clear()
            logger.info("Loading Projects from catalog {} (entries={})", catalogFile, catalog.size)
            for (entry in catalog) {
                val path = entry.path
                val dir = try {
                    VPath.get(path).toAbsolute()
                } catch (e: Exception) {
                    logger.warn("Invalid catalog project path '{}'", path, e)
                    malformedEntries.add(path)
                    continue
                }

                val catalogName = entry.name.ifBlank { fallbackCatalogName(path) }
                if (!hasProjectDefinition(dir)) {
                    _projects.add(invalidCatalogProject(dir, catalogName))
                    invalidCount++
                    continue
                }

                val proj = loadProjectFromDir(dir)
                if (proj != null) {
                    _projects.add(proj)
                    loadedCount++
                    logger.debug("Loaded project '{}' (type={})", proj.name, proj.typeId)
                    if (proj.name.isNotBlank() && proj.name != catalogName) {
                        nameUpdates[path] = proj.name
                    }
                } else {
                    _projects.add(invalidCatalogProject(dir, catalogName))
                    invalidCount++
                }
            }
        }

        if (malformedEntries.isNotEmpty()) {
            val removed = removeCatalogPaths(malformedEntries)
            if (removed > 0) {
                logger.info("Removed {} malformed project catalog entr{}", removed, if (removed == 1) "y" else "ies")
            }
        }
        if (nameUpdates.isNotEmpty()) {
            updateCatalogNames(nameUpdates)
        }

        logger.info(
            "Project catalog refresh complete: total={}, loaded={}, invalid={}, malformed={}",
            catalog.size,
            loadedCount,
            invalidCount,
            malformedEntries.size
        )
        notifyFinishedLoading()
        return synchronized(_projectsLock) { _projects.toList() }
    }

    /**
     * Find a project by display name.
     */
    fun getProject(name: String): ProjectBase? {
        return projects.find { it.name == name }
    }

    /**
     * Find a project by its root directory path.
     */
    fun getProject(path: VPath): ProjectBase? {
        return projects.find { it.projectDir.toAbsolute() == path.toAbsolute() }
    }

    /**
     * Open a project in the UI and mark it as active.
     */
    fun openProject(project: ProjectBase) {
        logger.info("Loading project {}", project.name)
        addProjectToCatalog(project.projectDir, project.name)
        val previousActive = activeProject
        val openMode = resolveOpenMode(project) ?: return
        val wasDifferent = previousActive !== project
        activeProject = project
        val closeDashboard = CoreSettingValues.closeDashboardOnProjectOpen() && wasDifferent

        try {
            ProjectWindows.openProject(
                project = project,
                closeDashboard = closeDashboard,
                mode = openMode
            )
        } catch (e: Exception) {
            logger.debug("Failed to open project", e)
        }

        notifyProjectOpened(project)
    }

    /**
     * Persist the current active project for next app launch.
     */
    fun saveActiveProject() {
        activeProject?.let { project ->
            persistActiveProject(project)
        }
    }

    /**
     * Persist the given project as active.
     */
    fun saveProjectAsActive(project: ProjectBase) {
        persistActiveProject(project)
    }

    /** Persists the active project path to the preferences store. */
    private fun persistActiveProject(project: ProjectBase) {
        val prefs = Preferences.userRoot().node(PREFS_NODE)
        prefs.put(PREF_ACTIVE_PROJECT, project.projectDir.toAbsolute().toString())
        logger.info("Set active project to {}", project.name)
    }

    /**
     * Load the previously active project if it still exists on disk.
     */
    fun loadActiveProject() {
        val prefs = Preferences.userRoot().node(PREFS_NODE)
        val activeProjectPath = prefs.get(PREF_ACTIVE_PROJECT, "")

        if(activeProjectPath.isNotEmpty()) {
            val projectDir = VPath.get(activeProjectPath)
            if(projectDir.exists() && projectDir.isDir()) {
                val project = loadProjectFromDir(projectDir)
                if(project != null) openProject(project)
                else logger.error("Active project definition exists but failed to load: $activeProjectPath")
            } else logger.warn("Saved active project directory no longer exists: $activeProjectPath")
        }
    }

    /**
     * Refresh the project list from disk.
     */
    fun refreshProjects(source: RefreshSource = RefreshSource.DASHBOARD): List<ProjectBase> {
        return getProjectsFromCatalog(source)
    }

    private fun resolveOpenMode(project: ProjectBase): ProjectWindows.OpenMode? {
        val existing = ProjectWindows.anyOpenWindow() ?: return ProjectWindows.OpenMode.NEW_WINDOW
        val targetCanonical = project.path.toString().trim()
        if (existing.projectCanonicalPath() == targetCanonical) {
            return ProjectWindows.OpenMode.NEW_WINDOW
        }

        return when (CoreSettingValues.projectOpenPromptMode()) {
            CoreSettingValues.ProjectOpenPromptMode.Always -> promptOpenMode(project)
            CoreSettingValues.ProjectOpenPromptMode.Never -> when (CoreSettingValues.projectOpenDefaultTarget()) {
                CoreSettingValues.ProjectOpenDefaultTarget.Current -> ProjectWindows.OpenMode.CURRENT_WINDOW
                CoreSettingValues.ProjectOpenDefaultTarget.New -> ProjectWindows.OpenMode.NEW_WINDOW
            }
        }
    }

    private fun promptOpenMode(project: ProjectBase): ProjectWindows.OpenMode? {
        val parent = QApplication.activeWindow() ?: Dashboard.I
        val box = QMessageBox(parent)
        box.icon = QMessageBox.Icon.Question
        box.windowTitle = "Open Project"
        box.text = "Open '${project.name}' in current window or a new window?"
        val currentButton = box.addButton("Current Window", QMessageBox.ButtonRole.AcceptRole)
        val newButton = box.addButton("New Window", QMessageBox.ButtonRole.ActionRole)
        box.addButton(QMessageBox.StandardButton.Cancel)
        box.exec()
        return when (box.clickedButton()) {
            currentButton -> ProjectWindows.OpenMode.CURRENT_WINDOW
            newButton -> ProjectWindows.OpenMode.NEW_WINDOW
            else -> null
        }
    }

    private fun isDashboardActive(): Boolean {
        val dash = Dashboard.I ?: return false
        return dash.isVisible
    }

    enum class RefreshSource {
        DASHBOARD,
        BACKGROUND
    }

    /**
     * Notify listeners that a project was created outside the manager.
     */
    fun notifyCreatedExternal(project: ProjectBase) {
        addProjectToCatalog(project.projectDir, project.name)
        synchronized(_projectsLock) {
            val existing = _projects.any { it.projectDir.toAbsolute() == project.projectDir.toAbsolute() }
            if(!existing) {
                _projects.add(project)
            } else {
                val idx = _projects.indexOfFirst { it.projectDir.toAbsolute() == project.projectDir.toAbsolute() }
                if(idx >= 0) _projects[idx] = project
            }
        }
        notifyProjectCreated(project)
    }
}
