package io.github.tritium_launcher.launcher.ui.project

import io.github.tritium_launcher.launcher.connect
import io.github.tritium_launcher.launcher.core.project.ProjectBase
import io.github.tritium_launcher.launcher.logger
import io.github.tritium_launcher.launcher.ui.dashboard.Dashboard
import io.github.tritium_launcher.launcher.ui.helpers.runOnGuiThread
import io.qt.core.QThread
import io.qt.widgets.QApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages project window instances and focuses existing windows when possible.
 */
object ProjectWindows {
    private val logger = logger()

    private val openWindows = ConcurrentHashMap<String, CompletableDeferred<ProjectViewWindow>>()

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

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun openProjectInternal(project: ProjectBase, closeDashboard: Boolean) {
        val canonical = project.path.toString().trim()

        val newDeferred = CompletableDeferred<ProjectViewWindow>()
        val prevDeferred = openWindows.putIfAbsent(canonical, newDeferred)

        if(prevDeferred == null) {
            createWindowAsync(project, newDeferred, closeDashboard)
            return
        }

        // If an earlier creation is still pending, cancel it and start fresh to avoid deadlocks.
        if(!prevDeferred.isCompleted) {
            logger.warn("Previous project window creation still pending for '{}', restarting.", project.name)
            prevDeferred.cancel()
            openWindows[canonical] = newDeferred
            createWindowAsync(project, newDeferred, closeDashboard)
            return
        }

        if(prevDeferred.isCancelled) {
            openWindows[canonical] = newDeferred
            createWindowAsync(project, newDeferred, closeDashboard)
            return
        }

        // Otherwise re-show existing window.
        prevDeferred.invokeOnCompletion {
            if(prevDeferred.isCompleted) {
                try {
                    val w = prevDeferred.getCompleted()
                    runOnGuiThread {
                        try {
                            w.show()
                            w.raise()
                            w.activateWindow()
                            if(closeDashboard) {
                                Dashboard.I?.close()
                            }
                        } catch (t: Throwable) {
                            logger.debug("Failed to focus existing {} for '{}'", ProjectViewWindow::class.qualifiedName, project.name, t)
                        }
                    }
                } catch (t: Throwable) {
                    logger.debug("Deferred completed but failed to get window", t)
                }
            }
        }
        return
    }

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

    private fun createWindowAsync(
        project: ProjectBase,
        deferred: CompletableDeferred<ProjectViewWindow>,
        closeDashboard: Boolean
    ) {
        if (isGuiThread()) {
            createWindow(project, deferred, closeDashboard)
            return
        }

        runOnGuiThread { createWindow(project, deferred, closeDashboard) }
    }

    private fun createWindow(
        project: ProjectBase,
        deferred: CompletableDeferred<ProjectViewWindow>,
        closeDashboard: Boolean
    ) {
        try {
            val window = ProjectViewWindow(project)
            window.show()

            try {
                window.raise()
                window.activateWindow()
            } catch (t: Throwable) {
                logger.debug("Failed to raise / activate window for '{}'", project.name, t)
            }

            deferred.complete(window)

            window.destroyed.connect { openWindows.remove(project.path.toString().trim()) }
            if(closeDashboard) {
                Dashboard.I?.close()
            }
        } catch (t: Throwable) {
            deferred.completeExceptionally(t)
            openWindows.remove(project.path.toString().trim())
            logger.error("Failed to create {} for '{}'", ProjectViewWindow::class.qualifiedName, project.name, t)
        }
    }

    private fun isGuiThread(): Boolean {
        val app = QApplication.instance() ?: return false
        return QThread.currentThread() == app.thread()
    }
}
