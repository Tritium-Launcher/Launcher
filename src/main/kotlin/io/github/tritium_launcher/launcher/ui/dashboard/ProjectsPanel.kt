package io.github.tritium_launcher.launcher.ui.dashboard

import io.github.tritium_launcher.launcher.*
import io.github.tritium_launcher.launcher.core.project.ProjectBase
import io.github.tritium_launcher.launcher.core.project.ProjectMngr
import io.github.tritium_launcher.launcher.core.project.ProjectMngrListener
import io.github.tritium_launcher.launcher.extension.core.BuiltinRegistries
import io.github.tritium_launcher.launcher.io.VPath
import io.github.tritium_launcher.launcher.registry.DeferredRegistryBuilder
import io.github.tritium_launcher.launcher.ui.dashboard.Dashboard.Companion.bgDashboardLogger
import io.github.tritium_launcher.launcher.ui.theme.TColors
import io.github.tritium_launcher.launcher.ui.theme.TIcons
import io.github.tritium_launcher.launcher.ui.theme.qt.setThemedStyle
import io.github.tritium_launcher.launcher.ui.widgets.TComboBox
import io.github.tritium_launcher.launcher.ui.widgets.TPushButton
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.*
import io.github.tritium_launcher.launcher.ui.wizard.NewProjectDialog
import io.qt.core.QMargins
import io.qt.core.QTimer
import io.qt.core.Qt
import io.qt.gui.QHideEvent
import io.qt.gui.QIcon
import io.qt.gui.QKeyEvent
import io.qt.gui.QShowEvent
import io.qt.widgets.*
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Persists view preferences for the projects panel. */
class ProjectsPanelPrefs(private val file: VPath) {
    @Serializable
    private data class Payload(val styleId: String? = null, val sortByStyle: Map<String, String> = emptyMap())

    private val json = Json { prettyPrint = true }
    private var styleId: String? = null
    private val sortByStyle = mutableMapOf<String, String>()

    init { load() }

    fun styleId(): String? = styleId
    fun sortFor(style: String): String? = sortByStyle[style]

    fun saveStyle(id: String?) {
        styleId = id
        persist()
    }

    fun saveSort(style: String, sortId: String) {
        sortByStyle[style] = sortId
        persist()
    }

    private fun load() {
        try {
            if (!file.exists()) return
            val text = file.readTextOrNull() ?: return
            val payload = json.decodeFromString<Payload>(text)
            styleId = payload.styleId
            sortByStyle.clear()
            sortByStyle.putAll(payload.sortByStyle)
        } catch (_: Throwable) {}
    }

    private fun persist() {
        try {
            val payload = Payload(styleId, sortByStyle.toMap())
            file.parent().mkdirs()
            file.writeBytesAtomic(json.encodeToString(payload).toByteArray())
        } catch (_: Throwable) {}
    }
}

/** Dashboard project list panel with pluggable styles. */
class ProjectsPanel internal constructor(): QWidget(), ProjectMngrListener {
    private var currentProjects: List<ProjectBase> = emptyList()
    private var searchFilter: String = ""

    private val styleButtons = QButtonGroup(this)
    private val styleButtonRow = hBoxLayout()
    private val sortCombo = TComboBox()
    private val styleStackHost = qWidget()
    private val styleStack = QStackedLayout().also { styleStackHost.setLayout(it) }
    private var styleControls: QWidget

    private val layoutStore = LayoutStore(fromTR(VPath.get("project_layouts/positions.json")).toAbsolute())
    private val groupStore = GroupStore(fromTR(VPath.get("project_layouts/groups.json")).toAbsolute())
    private val prefsStore = ProjectsPanelPrefs(fromTR(VPath.get("project_layouts/preferences.json")).toAbsolute())

    private val styleInstances = mutableMapOf<String, ProjectListStyle>()
    private val styleButtonById = mutableMapOf<String, QAbstractButton>()
    private var activeStyleId: String? = null

    private val searchDebounceTimer = QTimer().apply { isSingleShot = true; interval = 20 }
    private val refreshTimer = QTimer().apply { isSingleShot = true; interval = 750 }
    private var refreshPending = false
    private var refreshInFlight = false
    private var lastRefreshMs = 0L
    private var dvdHotkeyBuffer = ""

    private val styleRegistry = BuiltinRegistries.ProjectListStyle
    private var styleRegistryBuilder: DeferredRegistryBuilder<ProjectListStyleProvider>? = null

    init {
        objectName = "projectsPanel"
        focusPolicy = Qt.FocusPolicy.StrongFocus

        val mainLayout = vBoxLayout(this) {
            contentsMargins = QMargins(0, 10, 0, 10)
            widgetSpacing = 8
        }

        mainLayout.addLayout(buildHeaderControls())
        styleControls = buildStyleControls()
        mainLayout.addWidget(styleControls)
        mainLayout.addWidget(styleStackHost, 1)

        searchDebounceTimer.timeout.connect { applyFilter() }
        refreshTimer.timeout.connect {
            if (refreshInFlight) refreshPending = true else refresh()
        }

        ProjectMngr.addListener(this)
        scheduleRefresh()

        setThemedStyle {
            selector("#projectsPanel") { backgroundColor(TColors.Surface0) }
        }

        setupStyleRegistry()
    }

    /** Stops watching and releases style resources. */
    fun exit() {
        ProjectMngr.removeListener(this)
        styleInstances.values.forEach { it.dispose() }
    }

    /** Hides style controls each time the panel is shown. */
    override fun showEvent(event: QShowEvent?) {
        super.showEvent(event)
        styleControls.isVisible = false
    }

    /** Hides style controls when the panel is hidden. */
    override fun hideEvent(event: QHideEvent?) {
        super.hideEvent(event)
        styleControls.isVisible = false
    }

    /** Toggles style controls when Alt is pressed. */
    override fun keyPressEvent(event: QKeyEvent?) {
        val keyEvent = event ?: return super.keyPressEvent(event)
        handleDvdHotkey(keyEvent)
        if (keyEvent.key() == Qt.Key.Key_Alt.value()) {
            if (!keyEvent.isAutoRepeat) {
                styleControls.isVisible = !styleControls.isVisible
            }
            return
        }
        super.keyPressEvent(keyEvent)
    }

    /** Tracks the "dvd" key sequence to activate the style. */
    private fun handleDvdHotkey(event: QKeyEvent) {
        if (event.modifiers().testFlag(Qt.KeyboardModifier.ControlModifier) ||
            event.modifiers().testFlag(Qt.KeyboardModifier.MetaModifier) ||
            event.modifiers().testFlag(Qt.KeyboardModifier.AltModifier)) {
            if (event.key() != Qt.Key.Key_Alt.value()) dvdHotkeyBuffer = ""
            return
        }
        val text = event.text().lowercase()
        if (text.length != 1) { dvdHotkeyBuffer = ""; return }
        val keyChar = text[0]
        if (keyChar != 'd' && keyChar != 'v') { dvdHotkeyBuffer = ""; return }
        dvdHotkeyBuffer = (dvdHotkeyBuffer + keyChar).takeLast(3)
        if (dvdHotkeyBuffer == "dvd") activateDvdStyle()
    }

    /** Handles project creation events. */
    override fun onProjectCreated(project: ProjectBase) { scheduleRefresh() }

    /** Handles project deletion events. */
    override fun onProjectDeleted(project: ProjectBase) { scheduleRefresh() }

    /** Handles project updates. */
    override fun onProjectUpdated(project: ProjectBase) { scheduleRefresh() }

    /** Handles project load completion events. */
    override fun onProjectsFinishedLoading(projects: List<ProjectBase>) {}

    /** Handles project generation failures. */
    override fun onProjectFailedToGenerate(project: ProjectBase, errorMsg: String, exception: Exception?) {}

    /** Handles project opened events. */
    override fun onProjectOpened(project: ProjectBase) {}

    /**
     * Prompts for a `trproj.json` file and imports the owning project.
     */
    private fun showImportProjectDialog() {
        val startDir = try {
            ProjectMngr.projectsDir.toAbsolute().toString()
        } catch (_: Throwable) {
            fromTR().toString()
        }

        val chosen = QFileDialog.getOpenFileName(
            this,
            "Import Project",
            startDir,
            "Tritium Project (trproj.json);;JSON Files (*.json);;All Files (*)"
        )
        val selectedPath = chosen.result?.trim().orEmpty()
        if (selectedPath.isBlank()) return

        importProjectFromSelection(VPath.get(selectedPath))
    }

    /**
     * Imports a project from a selected file path, expecting `trproj.json`.
     *
     * If the selected file's directory does not contain `trproj.json`, import is skipped.
     */
    private fun importProjectFromSelection(selectedFile: VPath) {
        val file = selectedFile.expandHome().toAbsolute().normalize()
        val projectDir = file.parent()
        val projectFile = if (file.fileName() == "trproj.json") file else projectDir.resolve("trproj.json")

        if (!projectFile.exists()) {
            // TODO: Add an import method for instances from other launchers
            Dashboard.logger.warn(
                "Import skipped for '{}' because trproj.json was not found in '{}'",
                selectedFile,
                projectDir
            )
            return
        }

        val project = try {
            ProjectMngr.loadProject(projectDir)
        } catch (t: Throwable) {
            Dashboard.logger.error("Failed to import project from {}", projectDir, t)
            null
        } ?: return

        ProjectMngr.notifyCreatedExternal(project)
        scheduleRefresh()
    }

    /** Schedules a refresh respecting debounce. */
    private fun scheduleRefresh() {
        if (ProjectMngr.generationActive) return
        val now = System.currentTimeMillis()
        if (refreshInFlight) { refreshPending = true; return }
        if (refreshTimer.isActive) return
        val elapsed = now - lastRefreshMs
        if (elapsed < 750) { refreshTimer.start(); return }
        refreshTimer.start(0)
    }

    /** Refreshes projects from disk and applies to the active style. */
    @OptIn(DelicateCoroutinesApi::class)
    private fun refresh() {
        lastRefreshMs = System.currentTimeMillis()
        refreshInFlight = true
        GlobalScope.launch(Dispatchers.IO) {
            bgDashboardLogger.info("Refreshing projects...")
            val projects = ProjectMngr.refreshProjects(ProjectMngr.RefreshSource.DASHBOARD)
            QTimer.singleShot(0) {
                currentProjects = projects
                refreshInFlight = false
                applyFilter(immediate = true)
                if (refreshPending) { refreshPending = false; scheduleRefresh() }
            }
        }
    }

    /** Applies search filter and pushes projects to the active style. */
    private fun applyFilter(immediate: Boolean = false) {
        val filtered = filteredProjects()
        val style = activeStyleId?.let { styleInstances[it] }
        val sort = currentSortOption()
        if (style != null && sort != null) {
            style.applyProjects(filtered, sort)
        }
        if (!immediate) searchDebounceTimer.stop()
    }

    /** Opens a project and closes the dashboard. */
    private fun openProject(project: ProjectBase) {
        if (project.typeId == ProjectMngr.INVALID_CATALOG_PROJECT_TYPE) return
        try { ProjectMngr.openProject(project) } catch (t: Throwable) { Dashboard.logger.error("Failed to open project: ${t.message}", t) }
        Dashboard.I?.let { try { it.close() } catch (_: Throwable) {} }
    }

    /** Builds the top toolbar; keeps existing buttons intact. */
    private fun buildHeaderControls(): QLayout {
        val layout = hBoxLayout { contentsMargins = QMargins(8, 0, 8, 0); widgetSpacing = 10 }

        val searchField = QLineEdit().apply {
            objectName = "searchField"
            placeholderText = "Search"
            maximumHeight = 40
            minimumHeight = 40
            sizePolicy = QSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Fixed)
            isVisible = false
            setThemedStyle {
                selector("QLineEdit#searchField") {
                    padding(left = 40)
                    border()
                    borderRadius(8)
                    backgroundColor(TColors.Button)
                }
            }
            textChanged.connect {
                searchFilter = text.lowercase()
                searchDebounceTimer.start()
            }
        }

        val searchToggle = TPushButton {
            objectName = "searchToggleBtn"
            isCheckable = true
            maximumHeight = 40
            minimumHeight = 40
            sizePolicy = QSizePolicy(QSizePolicy.Policy.Fixed, QSizePolicy.Policy.Fixed)
            icon = QIcon(TIcons.Search)
            iconSize = qs(24, 24)
        }

        val newProject = TPushButton {
            text = "New Project"
            objectName = "newProjectBtn"
            maximumHeight = 40
            sizePolicy = QSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Fixed)
            icon = QIcon(TIcons.NewProject)
            iconSize = qs(32, 32)
            onClicked { NewProjectDialog(activeWindow).isVisible = true }
        }

        val importProject = TPushButton {
            text = "Import Project"
            objectName = "importProjectBtn"
            maximumHeight = 40
            sizePolicy = QSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Fixed)
            icon = QIcon(TIcons.Import)
            iconSize = qs(32, 32)
            onClicked { showImportProjectDialog() }
        }

        val cloneFromGit = TPushButton {
            text = "Clone from Git"
            objectName = "cloneFromGitBtn"
            maximumHeight = 40
            sizePolicy = QSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Fixed)
            icon = QIcon(TIcons.Git)
            iconSize = qs(32, 32)
        }

        /** Updates the toolbar to show the search field. */
        fun setSearchExpanded(expanded: Boolean, clearText: Boolean = false) {
            searchField.isVisible = expanded
            newProject.isVisible = !expanded
            importProject.isVisible = !expanded
            cloneFromGit.isVisible = !expanded

            if (expanded) {
                layout.setStretch(0, 0); layout.setStretch(1, 1); layout.setStretch(2, 0); layout.setStretch(3, 0); layout.setStretch(4, 0)
                searchField.setFocus()
            } else {
                layout.setStretch(0, 0); layout.setStretch(1, 0); layout.setStretch(2, 2); layout.setStretch(3, 2); layout.setStretch(4, 2)
                if (clearText) searchField.clear()
            }
        }

        searchToggle.toggled.connect { checked ->
            searchToggle.isDown = checked
            setSearchExpanded(checked, clearText = !checked)
            if (!checked) { searchFilter = ""; searchDebounceTimer.start() }
        }

        layout.addWidget(searchToggle)
        layout.addWidget(searchField)
        layout.addWidget(newProject)
        layout.addWidget(importProject)
        layout.addWidget(cloneFromGit)
        layout.setStretch(0, 0)
        layout.setStretch(1, 0)
        layout.setStretch(2, 2)
        layout.setStretch(3, 2)
        layout.setStretch(4, 2)
        return layout
    }

    /** Builds view selection and sort controls. */
    private fun buildStyleControls(): QWidget {
        val container = qWidget { isVisible = false }
        val layout = hBoxLayout(container) { contentsMargins = 0.m; widgetSpacing = 8 }

        styleButtonRow.widgetSpacing = 6
        styleButtons.exclusive = true
        layout.addWidget(label("View:"))
        layout.addLayout(styleButtonRow)
        layout.addStretch(1)

        val sortRow = hBoxLayout { widgetSpacing = 6 }
        sortRow.addWidget(label("Sort:"))
        sortCombo.minimumWidth = 200
        sortCombo.currentIndexChanged.connect { idx ->
            if (idx < 0) return@connect
            val sort = currentSortOption() ?: return@connect
            activeStyleId?.let { prefsStore.saveSort(it, sort.id) }
            styleInstances[activeStyleId]?.applyProjects(filteredProjects(), sort)
        }
        sortRow.addWidget(sortCombo)
        layout.addLayout(sortRow)

        return container
    }

    /** Builds styles from the registered providers. */
    private fun buildStyleButtons(providers: List<ProjectListStyleProvider>) {
        if (styleInstances.isNotEmpty()) return

        val preferredStyle = prefsStore.styleId()
        var firstVisibleId: String? = null
        var firstStyleId: String? = null

        providers.sortedBy { it.id }.forEach { provider ->
            val id = provider.id
            val ctx = ProjectStyleContext(
                this,
                this::openProject,
                layoutStore,
                groupStore,
                this::requestRefresh,
                { styleControls.isVisible },
                this::hideInvalidProjectFromList
            )

            val style = provider.create(ctx)
            styleInstances[id] = style

            if (firstStyleId == null) firstStyleId = id
            if (!provider.hidden && firstVisibleId == null) firstVisibleId = id

            if (!provider.hidden) {
                val btn = toolButton().apply {
                    isCheckable = true
                    text = provider.title
                    icon = provider.icon ?: QIcon()
                    toolButtonStyle = Qt.ToolButtonStyle.ToolButtonTextUnderIcon
                    clicked.connect { switchStyle(id) }
                }
                styleButtonById[id] = btn
                styleButtons.addButton(btn)
                styleButtonRow.addWidget(btn)
            }

            val widget = style.widget()
            styleStack.addWidget(widget)
            val idx = styleStack.indexOf(widget)
            if (activeStyleId == null && preferredStyle == id) {
                activeStyleId = id
                styleButtonById[id]?.isChecked = true
                if (idx >= 0) styleStack.currentIndex = idx
            }
        }

        if (activeStyleId == null) {
            val target = preferredStyle?.takeIf { styleInstances.containsKey(it) }
                ?: firstVisibleId
                ?: firstStyleId
            target?.let { id ->
                activeStyleId = id
                styleButtonById[id]?.isChecked = true
                styleInstances[id]?.widget()?.let { widget ->
                    val idx = styleStack.indexOf(widget)
                    if (idx >= 0) styleStack.currentIndex = idx
                }
            }
        }

        refreshSortOptions()
    }

    /** Initializes the style registry listener. */
    private fun setupStyleRegistry() {
        if (styleRegistryBuilder != null) return
        styleRegistryBuilder = DeferredRegistryBuilder(styleRegistry) { providers ->
            buildStyleButtons(providers)
        }
    }

    /** Switches the active style and reapplies sorting. */
    private fun switchStyle(id: String) {
        if (activeStyleId == id) return
        val style = styleInstances[id] ?: return
        activeStyleId = id
        val widget = style.widget()
        val idx = styleStack.indexOf(widget)
        if (idx >= 0) styleStack.currentIndex = idx
        refreshSortOptions()
        val sort = currentSortOption()
        prefsStore.saveStyle(id)
        if (sort != null) {
            style.applyProjects(filteredProjects(), sort)
            prefsStore.saveSort(id, sort.id)
        }
    }

    /** Switches to the DVD bounce style when the secret hotkey is pressed. */
    private fun activateDvdStyle() {
        val targetId = "dvd"
        if (activeStyleId == targetId) {
            val sort = currentSortOption() ?: styleInstances[targetId]?.sortOptions?.firstOrNull()
            if (sort != null) styleInstances[targetId]?.applyProjects(filteredProjects(), sort)
            return
        }
        if (!styleInstances.containsKey(targetId)) return
        styleButtonById[targetId]?.isChecked = true
        switchStyle(targetId)
    }

    /** Refreshes the sort combo for the current style. */
    private fun refreshSortOptions() {
        val style = activeStyleId?.let { styleInstances[it] } ?: return
        val savedSort = activeStyleId?.let { prefsStore.sortFor(it) }
        sortCombo.blockSignals(true)
        sortCombo.clear()
        style.sortOptions.forEach { opt -> sortCombo.addItem(opt.label, opt.id) }
        val targetId = savedSort?.takeIf { id -> style.sortOptions.any { it.id == id } }
            ?: style.sortOptions.firstOrNull()?.id
        val idx = style.sortOptions.indexOfFirst { it.id == targetId }.coerceAtLeast(0)
        if (idx in 0 until sortCombo.count) sortCombo.currentIndex = idx
        sortCombo.blockSignals(false)
    }

    /** Returns projects filtered by the search query. */
    private fun filteredProjects(): List<ProjectBase> = currentProjects.filter { p ->
        searchFilter.isBlank() || p.name.contains(searchFilter, ignoreCase = true)
    }

    /** Resolves the selected sort option for the active style. */
    private fun currentSortOption(): ProjectSortOption? {
        val style = activeStyleId?.let { styleInstances[it] } ?: return null
        val id = sortCombo.currentData() as? String ?: style.sortOptions.firstOrNull()?.id
        return style.sortOptions.firstOrNull { it.id == id }
    }

    /** Updates search text from external callers. */
    fun onSearchTextChanged(text: String) {
        searchFilter = text.lowercase()
        searchDebounceTimer.start()
    }

    /** Forces a refresh of the active style layout. */
    private fun requestRefresh() {
        applyFilter(immediate = true)
    }

    /**
     * Removes an invalid catalog project entry after explicit user confirmation.
     *
     * This only edits `projects/catalog.json` and refreshes the dashboard list.
     */
    private fun hideInvalidProjectFromList(project: ProjectBase) {
        if (project.typeId != ProjectMngr.INVALID_CATALOG_PROJECT_TYPE) return
        if (!confirmHideInvalidProject(project)) return

        val removed = ProjectMngr.removeProjectFromCatalog(project.projectDir)
        if (!removed) {
            Dashboard.logger.warn("Failed to remove invalid project '{}' from catalog", project.name)
            return
        }
        scheduleRefresh()
    }

    private fun confirmHideInvalidProject(project: ProjectBase): Boolean {
        val box = QMessageBox(this)
        box.icon = QMessageBox.Icon.Question
        box.windowTitle = "Hide Invalid Project"
        box.text = "Hide '${project.name}' from the project list?"
        box.informativeText = "This will only hide the project from the list"
        val hideButton = box.addButton("Hide", QMessageBox.ButtonRole.AcceptRole)
        box.addButton(QMessageBox.StandardButton.Cancel)
        box.exec()
        return box.clickedButton() == hideButton
    }
}
