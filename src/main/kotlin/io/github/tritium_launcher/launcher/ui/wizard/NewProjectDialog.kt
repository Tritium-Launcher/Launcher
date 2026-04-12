package io.github.tritium_launcher.launcher.ui.wizard

import io.github.tritium_launcher.launcher.*
import io.github.tritium_launcher.launcher.core.project.ProjectGenerator
import io.github.tritium_launcher.launcher.core.project.ProjectMngr
import io.github.tritium_launcher.launcher.core.project.ProjectType
import io.github.tritium_launcher.launcher.coroutines.UIDispatcher
import io.github.tritium_launcher.launcher.extension.core.BuiltinRegistries
import io.github.tritium_launcher.launcher.io.VPath
import io.github.tritium_launcher.launcher.ui.dashboard.Dashboard
import io.github.tritium_launcher.launcher.ui.theme.TColors
import io.github.tritium_launcher.launcher.ui.theme.qt.setThemedStyle
import io.github.tritium_launcher.launcher.ui.widgets.TPushButton
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.hBoxLayout
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.label
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.vBoxLayout
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.widget
import io.qt.core.QSize
import io.qt.core.QTimer
import io.qt.core.Qt
import io.qt.core.Qt.ItemDataRole.UserRole
import io.qt.widgets.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import java.nio.file.Files

/**
 * The main window for creating new Projects.
 */
class NewProjectDialog internal constructor(parent: QWidget? = null): QDialog(parent) {
    private val list            = QListWidget()
    private val stacked         = QStackedWidget()
    private val createButton    = TPushButton()
    private val cancelButton    = TPushButton()
    private val statusLabel     = QLabel()

    private val creator = ProjectGenerator(UIDispatcher)
    private var currentJob: Job? = null
    private var runToken: Long = 0L
    private var isClosing = false
    private val variablesPerType = mutableMapOf<String, MutableMap<String, String>>()
    private val typeById = mutableMapOf<String, ProjectType>()
    private val unavailableTypeReasons = mutableMapOf<String, String>()
    private var cancelRequested = false
    private var closeAfterCancel = false
    private var pendingProjectRoot: VPath? = null
    private var pendingProjectRootExisted = false

    private val logger = logger()

    init {
        windowTitle = "New Project"
        minimumWidth = 800
        minimumHeight = 520
        objectName = "ProjectDialog"

        val mainLayout = hBoxLayout(this) {
            widgetSpacing = 0
            contentsMargins = 0.m
        }
        val leftPanel  = widget(this) {
            objectName = "leftPanel"
        }
        val leftLayout = vBoxLayout(leftPanel) {
            contentsMargins = 6.m
            widgetSpacing = 6
        }
        list.apply {
            selectionMode = QAbstractItemView.SelectionMode.SingleSelection
            minimumWidth  = 150
            sizePolicy = QSizePolicy(QSizePolicy.Policy.Preferred, QSizePolicy.Policy.Expanding)
            uniformItemSizes = false
            spacing = 0
            iconSize = qs(40, 40)
            frameShape = QFrame.Shape.NoFrame
            frameShadow = QFrame.Shadow.Plain
        }
        leftLayout.addWidget(list)

        val rightPanel  = widget {
            objectName = "rightPanel"
            autoFillBackground = true
            setAttribute(Qt.WidgetAttribute.WA_StyledBackground, true)
        }
        val rightLayout = vBoxLayout(rightPanel)

        rightLayout.addWidget(stacked, 1)

        statusLabel.text = ""
        rightLayout.addWidget(statusLabel)

        createButton.apply {
            text = "Create"
            minimumHeight = 36
        }
        cancelButton.apply {
            text = "Cancel"
            minimumHeight = 36
        }

        val btnRow = QWidget()
        val btnLayout = hBoxLayout(btnRow) {
            addStretch(1)
            addWidget(createButton)
            addWidget(cancelButton)
        }
        rightLayout.addWidget(btnRow)

        mainLayout.addWidget(leftPanel, 0)
        mainLayout.addWidget(rightPanel, 1)
        setLayout(mainLayout)

        setThemedStyle {
            selector("#ProjectDialog") {
                border(1, TColors.Surface0, "top")
                backgroundColor(TColors.Surface0)
            }

            selector("#leftPanel") {
                border(1, TColors.Surface0, "top")
                border(1, TColors.Surface0, "right")
                padding(0)
                backgroundColor(TColors.Surface0)
            }

            selector("#rightPanel") {
                border(1, TColors.Surface0, "top")
                backgroundColor(TColors.Surface0)
                padding(0)
            }

            selector("QListWidget") {
                border()
                background("transparent")
                padding(6)
            }

            selector("QListView::item") {
                border()
                showDecorationSelected()
                borderRadius(8)
                background("transparent")
                color(TColors.Text)
                padding(6, 8, 6, 10)
            }

            selector("QListView::item:selected") {
                border()
                backgroundColor(TColors.SelectedUI)
                borderRadius(8)
                color(TColors.SelectedText)
            }

        }

        setupTypes()
        connectSignals()
    }

    private fun setupTypes() {
        val types = BuiltinRegistries.ProjectType
        logger.info("Registered Project Types: ${types.toListString()}")

        for(t in types.all().sortedWith(compareBy<ProjectType> { it.order }.thenBy { it.displayName })) {
            typeById[t.id] = t
            val item = QListWidgetItem(t.displayName)
            item.setToolTip(t.description)
            item.setData(UserRole, t.id)
            item.setIcon(t.icon.pixmap(32, 32))
            item.setSizeHint(QSize(0, 32))
            val vars = variablesPerType.getOrPut(t.id) { mutableMapOf() }
            val widget = try {
                t.createSetupWidget(null, vars)
            } catch (err: Throwable) {
                val reason = err.message?.trim().takeUnless { it.isNullOrBlank() } ?: err::class.simpleName ?: "Unknown error"
                unavailableTypeReasons[t.id] = reason
                item.setText("${t.displayName} (Unavailable)")
                item.setToolTip("${t.description}\n\nUnavailable: $reason")
                logger.error("Failed to initialize setup widget for project type {}", t.id, err)
                unavailableTypeWidget(t.displayName, reason)
            }
            list.addItem(item)
            stacked.addWidget(widget)
        }
        if(list.count > 0) {
            list.currentRow = 0
            stacked.currentIndex = 0
        }
    }

    private fun unavailableTypeWidget(displayName: String, reason: String): QWidget {
        val panel = QWidget()
        vBoxLayout(panel) {
            contentsMargins = 16.m
            widgetSpacing = 6
            addWidget(QLabel("$displayName is unavailable."))
            addWidget(label("Reason: $reason") { wordWrap = true })
            addStretch(1)
        }
        return panel
    }

    private fun connectSignals() {
        list.currentRowChanged.connect { row ->
            if (row >= 0) stacked.currentIndex = row
        }

        cancelButton.onClicked {
            if (currentJob?.isActive == true) {
                requestCancellation(closeWhenDone = true)
            } else {
                reject()
            }
        }

        createButton.onClicked {
            startCreate()
        }
    }

    private fun startCreate() {
        if (currentJob?.isActive == true) {
            logger.info("Create requested while generation is active; ignoring duplicate request")
            return
        }

        // Cancel any previous run to avoid overlapping UI state.
        runToken += 1
        currentJob?.cancel()
        val activeToken = runToken

        val row = list.currentRow
        if(row < 0) {
            statusLabel.text = "No project type selected."
            logger.warn("Create requested but no project type is selected")
            return
        }
        val item = list.item(row)
        val typeId = item?.data(UserRole) as? String ?: run {
            statusLabel.text = "Project type not found."
            logger.warn("Create requested but project type id missing")
            return
        }
        val type = typeById[typeId] ?: run {
            statusLabel.text = "Project type not registered."
            logger.warn("Create requested for unknown project type: {}", typeId)
            return
        }

        val vars = variablesPerType[typeId]?.toMap() ?: emptyMap()
        val unavailableReason = unavailableTypeReasons[typeId]
        if (unavailableReason != null) {
            statusLabel.text = "Project type is unavailable: $unavailableReason"
            logger.warn("Create requested for unavailable project type {}: {}", typeId, unavailableReason)
            return
        }

        cancelRequested = false
        closeAfterCancel = false
        pendingProjectRoot = inferProjectRoot(vars)
        pendingProjectRootExisted = pendingProjectRoot?.exists() == true
        logger.info("Starting create for type={} ({} input vars)", typeId, vars.size)

        statusLabel.text = "Creating Project..."
        createButton.isEnabled = false
        cancelButton.isEnabled = true
        ProjectMngr.generationActive = true

        currentJob = creator.createProjectAsync(type, vars, onProgress = progress@{ msg ->
            if (activeToken != runToken) {
                logger.debug("Ignoring stale project generation progress for runId={}", activeToken)
                return@progress
            }
            logger.info("Project generation progress: {}", msg)
            statusLabel.text = msg
        }, onComplete = complete@{ result ->
            if (activeToken != runToken) {
                logger.info("Ignoring stale project generation completion for runId={}", activeToken)
                return@complete
            }
            logger.info("Project generation onComplete invoked (success? {})", result.isSuccess)
            createButton.isEnabled = true
            cancelButton.isEnabled = true
            ProjectMngr.generationActive = false
            val jobWas = currentJob
            currentJob = null
            logger.info("Project generation completed (job active? {})", jobWas?.isActive)
            val completedSuccessfully = result.getOrNull()?.successful == true
            val wasCancelled = !completedSuccessfully && (cancelRequested || result.exceptionOrNull() is CancellationException)
            val shouldCloseAfterCancel = closeAfterCancel
            val cleanupRoot = pendingProjectRoot
            val cleanupRootExisted = pendingProjectRootExisted
            cancelRequested = false
            closeAfterCancel = false
            pendingProjectRoot = null
            pendingProjectRootExisted = false

            if (wasCancelled) {
                val cleaned = cleanupCancelledMalformedProject(cleanupRoot, cleanupRootExisted)
                statusLabel.text = if (cleaned) {
                    "Project creation cancelled. Removed incomplete project files."
                } else {
                    "Project creation cancelled."
                }
                logger.info(
                    "Project generation cancelled (cleanupRoot={}, removedMalformed={})",
                    cleanupRoot?.toString()?.redactUserPath(),
                    cleaned
                )
                if (shouldCloseAfterCancel) finishDialog(accepted = false)
                return@complete
            }

            result.fold(
                onSuccess = { res ->
                    if (!res.successful) {
                        statusLabel.text = "Project creation failed."
                        logger.warn("Project template reported failure: {}", res.logs.joinToString("\n"))
                        return@fold
                    }

                    statusLabel.text = "Project created: ${res.projectRoot}"
                    logger.info("Project template success: {}", res.projectRoot.redactUserPath())
                    val projectDir = VPath.get(res.projectRoot)
                    val project = try {
                        ProjectMngr.loadProject(projectDir)
                            ?: ProjectMngr.refreshProjects(ProjectMngr.RefreshSource.BACKGROUND)
                                .find { it.projectDir.toAbsolute() == projectDir.toAbsolute() }
                    } catch (t: Throwable) {
                        logger.warn("Failed to load newly created project from {}", projectDir.toString().redactUserPath(), t)
                        null
                    }

                    if(project == null) {
                        statusLabel.text = "Project created, but failed to load (trproj.json missing?)."
                        finishDialog(accepted = true)
                        return@fold
                    }

                    ProjectMngr.notifyCreatedExternal(project)

                    try {
                        statusLabel.text = "Opening project..."
                        logger.info("Opening project window for {}", project.name)
                        ProjectMngr.openProject(project)
                    } catch (t: Throwable) {
                        logger.warn("Failed to open project window for {}", project.name, t)
                    }

                    try {
                        Dashboard.I?.let { dash ->
                            try { dash.close() } catch (_: Throwable) {}
                        }
                    } catch (t: Throwable) {
                        logger.debug("Failed closing dashboard post-create", t)
                    }

                    finishDialog(accepted = true)
                },
                onFailure = { err ->
                    statusLabel.text = "Project creation failed: ${err.message}"
                    logger.error("Project creation failed", err)
                }
            )
        })

        // Watchdog to log if UI never receives completion.
        QTimer.singleShot(60_000) {
            if (activeToken == runToken && currentJob != null && currentJob?.isCompleted == false) {
                logger.warn("Project creation watchdog: job still running after 60s (active={})", currentJob?.isActive)
                statusLabel.text = "Still creating... (taking longer than expected; you can cancel)"
                createButton.isEnabled = false
                cancelButton.isEnabled = true
            }
        }
    }

    private fun requestCancellation(closeWhenDone: Boolean) {
        if (currentJob?.isActive != true) {
            if (closeWhenDone) finishDialog(accepted = false)
            return
        }
        cancelRequested = true
        closeAfterCancel = closeAfterCancel || closeWhenDone
        statusLabel.text = "Cancelling project creation..."
        createButton.isEnabled = false
        cancelButton.isEnabled = false
        logger.info("Cancellation requested for active project generation")
        try {
            currentJob?.cancel(CancellationException("Cancelled by user"))
        } catch (t: Throwable) {
            logger.warn("Failed to request cancellation for active project generation", t)
        }
    }

    private fun inferProjectRoot(vars: Map<String, String>): VPath? {
        fun value(key: String): String? = vars[key]?.trim()?.takeIf { it.isNotEmpty() }

        val explicitRoot = value("projectRoot") ?: value("projectDir")
        if (explicitRoot != null) {
            return try {
                VPath.get(explicitRoot).expandHome().toAbsolute().normalize()
            } catch (t: Throwable) {
                logger.debug("Failed to infer project root from explicit path '{}'", explicitRoot, t)
                null
            }
        }

        val parent = value("projectPath") ?: value("packPath")
        val name = value("projectName") ?: value("packName")
        if (parent != null && name != null) {
            return try {
                VPath.get(parent).expandHome().resolve(name).toAbsolute().normalize()
            } catch (t: Throwable) {
                logger.debug("Failed to infer project root from parent/name '{}/{}'", parent, name, t)
                null
            }
        }

        return null
    }

    private fun cleanupCancelledMalformedProject(projectRoot: VPath?, rootExistedBeforeCreate: Boolean): Boolean {
        val root = projectRoot ?: return false
        if (rootExistedBeforeCreate) {
            logger.info("Skipping cancellation cleanup for {} because it existed before creation started", root)
            return false
        }

        val rootPath = try {
            root.expandHome().toAbsolute().normalize().toJPath()
        } catch (t: Throwable) {
            logger.warn("Failed to resolve project root for cancellation cleanup: {}", root, t)
            return false
        }

        return try {
            if (!Files.exists(rootPath) || !Files.isDirectory(rootPath)) return false
            if (Files.isSymbolicLink(rootPath)) {
                logger.warn("Skipping cancellation cleanup for symbolic-link directory {}", rootPath)
                return false
            }
            if (Files.exists(rootPath.resolve("trproj.json"))) {
                logger.info("Skipping cancellation cleanup for {} because trproj.json exists", rootPath)
                return false
            }
            Files.walk(rootPath).use { stream ->
                stream.sorted { a, b -> b.compareTo(a) }
                    .forEach { Files.deleteIfExists(it) }
            }
            true
        } catch (t: Throwable) {
            logger.warn("Failed deleting cancelled malformed project root {}", rootPath, t)
            false
        }
    }

    private fun finishDialog(accepted: Boolean) {
        if (isClosing) return
        isClosing = true
        logger.info("finishDialog invoked; closing dialog (accepted={})", accepted)
        runToken += 1
        ProjectMngr.generationActive = false
        cancelRequested = false
        closeAfterCancel = false
        pendingProjectRoot = null
        pendingProjectRootExisted = false
        try { creator.dispose() } catch (_: Throwable) {}
        try { currentJob?.cancel() } catch (_: Throwable) {}
        currentJob = null
        if (accepted) {
            try { super.accept() } catch (_: Throwable) {}
        } else {
            try { super.reject() } catch (_: Throwable) {}
        }
    }

    override fun reject() {
        if (currentJob?.isActive == true) {
            logger.info("Reject requested while generation is active; cancellation requested")
            requestCancellation(closeWhenDone = true)
            return
        }
        finishDialog(accepted = false)
    }

    override fun accept() {
        if (currentJob?.isActive == true) {
            logger.info("Accept requested while generation is active; request ignored")
            statusLabel.text = "Project creation is in progress."
            return
        }
        finishDialog(accepted = true)
    }
}
