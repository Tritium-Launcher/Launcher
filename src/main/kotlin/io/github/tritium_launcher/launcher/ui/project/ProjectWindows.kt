package io.github.tritium_launcher.launcher.ui.project

import io.github.tritium_launcher.launcher.connect
import io.github.tritium_launcher.launcher.core.project.ProjectBase
import io.github.tritium_launcher.launcher.logger
import io.github.tritium_launcher.launcher.ui.dashboard.Dashboard
import io.github.tritium_launcher.launcher.ui.helpers.runOnGuiThread
import io.qt.core.QThread
import io.qt.widgets.QApplication
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages project window instances and focuses existing windows when possible.
 */
object ProjectWindows {
    private val logger = logger()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val openWindows = ConcurrentHashMap<String, CompletableDeferred<ProjectViewWindow>>()

    /**
     * Controls whether project opening always creates a new window or prefers reuse.
     */
    enum class OpenMode {
        NEW_WINDOW,
        CURRENT_WINDOW
    }

    /**
     * Open (or focus) a project window for [project].
     *
     * @param closeDashboard When true, closes the dashboard after opening.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun openProject(
        project: ProjectBase,
        closeDashboard: Boolean = true,
        mode: OpenMode = OpenMode.NEW_WINDOW
    ) {
        if (mode == OpenMode.CURRENT_WINDOW) {
            openProjectInCurrentWindow(project, closeDashboard)
            return
        }
        openProjectInternal(project, closeDashboard)
    }

    /**
     * Opens a project while preferring to replace the active or otherwise reusable project window.
     */
    private fun openProjectInCurrentWindow(project: ProjectBase, closeDashboard: Boolean) {
        val replacement = preferredWindowForReuse()
        if (replacement == null) {
            openProjectInternal(project, closeDashboard)
            return
        }

        val targetCanonical = project.path.toString().trim()
        if (replacement.projectCanonicalPath() == targetCanonical) {
            openProjectInternal(project, closeDashboard)
            return
        }

        openProjectInternal(project, closeDashboard)
        val deferred = openWindows[targetCanonical] ?: return
        deferred.invokeOnCompletion {
            if (!deferred.isCompleted || deferred.isCancelled) return@invokeOnCompletion
            runOnGuiThread {
                try {
                    if (replacement.isVisible) {
                        replacement.close()
                    }
                } catch (t: Throwable) {
                    logger.debug("Failed closing replaced project window", t)
                }
            }
        }
    }

    /**
     * Opens, creates, or focuses the canonical window entry for [project].
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun openProjectInternal(project: ProjectBase, closeDashboard: Boolean) {
        val canonical = project.path.toString().trim()

        val newDeferred = CompletableDeferred<ProjectViewWindow>()
        val prevDeferred = openWindows.putIfAbsent(canonical, newDeferred)

        if (prevDeferred == null) {
            scope.launch {
                try {
                    val (state, files) = withContext(Dispatchers.IO) {
                        loadProjectInitialData(project)
                    }

                    createWindowAsync(project, newDeferred, closeDashboard, state, files)
                } catch (t: Throwable) {
                    logger.error("Failed to load project asynchronously for '{}'", project.name, t)
                    newDeferred.completeExceptionally(t)
                    openWindows.remove(canonical)
                }
            }
            return
        }

        // If an earlier creation is still pending, cancel it and start fresh to avoid deadlocks.
        if (!prevDeferred.isCompleted) {
            logger.warn("Previous project window creation still pending for '{}', restarting.", project.name)
            prevDeferred.cancel()
            openWindows[canonical] = newDeferred
            scope.launch {
                try {
                    val (state, files) = withContext(Dispatchers.IO) {
                        loadProjectInitialData(project)
                    }
                    createWindowAsync(project, newDeferred, closeDashboard, state, files)
                } catch (t: Throwable) {
                    logger.error("Failed to load project asynchronously for '{}'", project.name, t)
                    newDeferred.completeExceptionally(t)
                    openWindows.remove(canonical)
                }
            }
            return
        }

        if (prevDeferred.isCancelled) {
            openWindows[canonical] = newDeferred
            scope.launch {
                try {
                    val (state, files) = withContext(Dispatchers.IO) {
                        loadProjectInitialData(project)
                    }
                    createWindowAsync(project, newDeferred, closeDashboard, state, files)
                } catch (t: Throwable) {
                    logger.error("Failed to load project asynchronously for '{}'", project.name, t)
                    newDeferred.completeExceptionally(t)
                    openWindows.remove(canonical)
                }
            }
            return
        }

        // Otherwise re-show existing window.
        prevDeferred.invokeOnCompletion {
            if (prevDeferred.isCompleted) {
                try {
                    val w = prevDeferred.getCompleted()
                    runOnGuiThread {
                        try {
                            w.show()
                            w.raise()
                            w.activateWindow()
                            if (closeDashboard) {
                                Dashboard.I?.close()
                            }
                        } catch (t: Throwable) {
                            logger.debug(
                                "Failed to focus existing {} for '{}'",
                                ProjectViewWindow::class.qualifiedName,
                                project.name,
                                t
                            )
                        }
                    }
                } catch (t: Throwable) {
                    logger.debug("Deferred completed but failed to get window", t)
                }
            }
        }
        return
    }

    /**
     * Returns the best candidate window to reuse for current-window project opens.
     */
    private fun preferredWindowForReuse(): ProjectViewWindow? {
        val active = QApplication.activeWindow() as? ProjectViewWindow
        if (active != null && active.isVisible) return active
        return anyOpenWindow()
    }

    /**
     * Returns any currently open project window if available.
     *
     * @return First completed project window, or `null`.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun anyOpenWindow(): ProjectViewWindow? {
        openWindows.values.forEach { deferred ->
            if (!deferred.isCompleted || deferred.isCancelled) return@forEach
            val window = try {
                deferred.getCompleted()
            } catch (_: Throwable) {
                return@forEach
            }
            if (window.isVisible) return window
        }
        return null
    }

    /**
     * Ensures [project] has a window and then invokes [action] on the GUI thread.
     *
     * @param project Target project.
     * @param closeDashboard Whether dashboard should close when a new window is created.
     * @param action Action receiving the resolved project window.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun withProjectWindow(
        project: ProjectBase,
        closeDashboard: Boolean = true,
        action: (ProjectViewWindow) -> Unit
    ) {
        openProject(project, closeDashboard)
        val canonical = project.path.toString().trim()
        val deferred = openWindows[canonical] ?: return
        deferred.invokeOnCompletion {
            if (!deferred.isCompleted || deferred.isCancelled) return@invokeOnCompletion
            val window = try {
                deferred.getCompleted()
            } catch (_: Throwable) {
                return@invokeOnCompletion
            }
            runOnGuiThread { action(window) }
        }
    }

    /**
     * Ensures project window creation happens on the Qt GUI thread.
     */
    private fun createWindowAsync(
        project: ProjectBase,
        deferred: CompletableDeferred<ProjectViewWindow>,
        closeDashboard: Boolean,
        initialState: ProjectUIState? = null,
        initialFiles: List<String>? = null
    ) {
        if (isGuiThread()) {
            createWindow(project, deferred, closeDashboard, initialState, initialFiles)
            return
        }

        runOnGuiThread { createWindow(project, deferred, closeDashboard, initialState, initialFiles) }
    }

    /**
     * Instantiates, shows, and registers a new project window.
     */
    private fun createWindow(
        project: ProjectBase,
        deferred: CompletableDeferred<ProjectViewWindow>,
        closeDashboard: Boolean,
        initialState: ProjectUIState? = null,
        initialFiles: List<String>? = null
    ) {
        try {
            val window = ProjectViewWindow(project, initialState, initialFiles)
            window.show()

            try {
                window.raise()
                window.activateWindow()
            } catch (t: Throwable) {
                logger.debug("Failed to raise / activate window for '{}'", project.name, t)
            }

            deferred.complete(window)

            val canonical = project.path.toString().trim()
            window.destroyed.connect { openWindows.remove(canonical) }
            if (closeDashboard) {
                Dashboard.I?.hide()
            }
        } catch (t: Throwable) {
            deferred.completeExceptionally(t)
            openWindows.remove(project.path.toString().trim())
            logger.error("Failed to create {} for '{}'", ProjectViewWindow::class.qualifiedName, project.name, t)
        }
    }

    private fun loadProjectInitialData(project: ProjectBase): Pair<ProjectUIState?, List<String>?> {
        return try {
            val dotTr = project.projectDir.resolve(".tr")
            val stateFile = dotTr.resolve("tritium-ui.json")
            if (!stateFile.exists()) {
                return null to null
            }
            val txt = stateFile.readTextOrNull() ?: return null to null
            val state = ProjectUIState.parseOrNull(txt) ?: return null to null
            state to state.openFiles
        } catch (t: Throwable) {
            logger.warn("Failed to load initial project UI state for '{}'", project.name, t)
            null to null
        }
    }

    /**
     * Returns whether the current thread is Qt's GUI thread.
     */
    private fun isGuiThread(): Boolean {
        val app = QApplication.instance() ?: return false
        return QThread.currentThread() == app.thread()
    }
}
