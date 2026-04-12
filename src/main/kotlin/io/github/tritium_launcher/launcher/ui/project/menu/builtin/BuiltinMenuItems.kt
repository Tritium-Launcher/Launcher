package io.github.tritium_launcher.launcher.ui.project.menu.builtin

import io.github.tritium_launcher.launcher.TConstants
import io.github.tritium_launcher.launcher.core.project.ProjectBase
import io.github.tritium_launcher.launcher.core.project.ProjectMngr
import io.github.tritium_launcher.launcher.fromTR
import io.github.tritium_launcher.launcher.io.VPath
import io.github.tritium_launcher.launcher.logger
import io.github.tritium_launcher.launcher.platform.CompanionBridge
import io.github.tritium_launcher.launcher.platform.CompanionBridgeResponse
import io.github.tritium_launcher.launcher.platform.GameLauncher
import io.github.tritium_launcher.launcher.platform.GameProcessMngr
import io.github.tritium_launcher.launcher.ui.dashboard.Dashboard
import io.github.tritium_launcher.launcher.ui.helpers.runOnGuiThread
import io.github.tritium_launcher.launcher.ui.notifications.NotificationMngr
import io.github.tritium_launcher.launcher.ui.project.ProjectTaskMngr
import io.github.tritium_launcher.launcher.ui.project.ProjectViewWindow
import io.github.tritium_launcher.launcher.ui.project.menu.MenuActionContext
import io.github.tritium_launcher.launcher.ui.project.menu.MenuItem
import io.github.tritium_launcher.launcher.ui.project.menu.MenuItemKind
import io.github.tritium_launcher.launcher.ui.project.menu.builtin.BuiltinMenuItems.All
import io.github.tritium_launcher.launcher.ui.project.sidebar.ProjectLogsSidePanelProvider
import io.github.tritium_launcher.launcher.ui.theme.TIcons
import io.github.tritium_launcher.launcher.ui.theme.qt.grayOverlay
import io.github.tritium_launcher.launcher.ui.theme.qt.icon
import io.github.tritium_launcher.launcher.ui.wizard.NewProjectDialog
import io.qt.core.Qt
import io.qt.gui.QGuiApplication
import io.qt.gui.QIcon
import io.qt.widgets.*
import kotlinx.coroutines.*
import java.nio.file.Files

/**
 * Built-in menu items contributed by the core extension.
 *
 * These entries are registered into the `ui.menu` registry and collected in [All].
 */
object BuiltinMenuItems {
    private val logger = logger()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private const val CLOSE_TIMEOUT_MS = 15_000L
    private const val COMPANION_PING_TIMEOUT_MS = 1_500L
    private const val STOP_POLL_INTERVAL_MS = 200L
    private const val STOP_POLL_TIMEOUT_MS = 8_000L
    private const val KILL_WAIT_TIMEOUT_MS = 4_000L
    private const val DOWNLOAD_POLL_INTERVAL_MS = 200L
    private const val DOWNLOAD_WAIT_TIMEOUT_MS = 10 * 60_000L

    val Play = MenuItem(
        id = "play",
        title = "Play",
        order = 1_000,
        kind = MenuItemKind.ACTION,
        meta = mapOf(
            "align" to "right",
            "iconOnly" to "true"
        ),
        icon = TIcons.Run.icon,
        iconResolver = { ctx ->
            val project = ctx.project
            if (project == null) {
                TIcons.Run.icon
            } else {
                when {
                    GameLauncher.isGameRunning(project) -> TIcons.Rerun.icon
                    isAssetGenerationActive(project) -> TIcons.Download.icon
                    GameLauncher.needsRuntimeDownload(project) -> TIcons.Download.icon
                    else -> TIcons.Run.icon
                }
            }
        },
        enabledResolver = { ctx ->
            val project = ctx.project
            project != null && !isAssetGenerationActive(project)
        },
        tooltip = "Play game (reruns if running, downloads runtime when missing)",
        action = { ctx ->
            val project = ctx.project ?: return@MenuItem
            scope.launch {
                launchOrPrepare(project)
            }
        }
    )

    val Stop = MenuItem(
        id = "stop_game",
        title = "Stop",
        order = 1_010,
        kind = MenuItemKind.ACTION,
        meta = mapOf(
            "align" to "right",
            "iconOnly" to "true",
            "shiftHoverForceIcon" to "true"
        ),
        icon = TIcons.Stop.icon,
        enabledResolver = { ctx ->
            ctx.project?.let { GameLauncher.isGameRunning(it) } == true
        },
        iconResolver = { ctx ->
            val running = ctx.project?.let { GameLauncher.isGameRunning(it) } == true
            if (running) TIcons.Stop.icon else TIcons.Stop.icon.grayOverlay(alpha = 170)
        },
        tooltip = "Request game shutdown",
        action = { ctx ->
            val project = ctx.project ?: return@MenuItem
            val forceKill = QGuiApplication.queryKeyboardModifiers().testFlag(Qt.KeyboardModifier.ShiftModifier)
            scope.launch {
                val stop = stopGame(project, forceKill = forceKill)
                if (!stop.stopped) {
                    logger.warn("Stop action failed: {}", stop.message)
                }
            }
        }
    )

    val File = MenuItem("file", "&File", order = 0, kind = MenuItemKind.MENU)
    val Edit = MenuItem("edit", "&Edit", order = 10, kind = MenuItemKind.MENU)
    val View = MenuItem("view", "&View", order = 20, kind = MenuItemKind.MENU)
    val Game = MenuItem("game", "&Game", order = 30, kind = MenuItemKind.MENU)
    val Help = MenuItem("help", "&Help", order = 100, kind = MenuItemKind.MENU)

    val FileNew = MenuItem(
        id = "file_new",
        title = "New",
        parentId = File.id,
        order = 0,
        kind = MenuItemKind.MENU
    )

    val NewProject = MenuItem(
        id = "new_project",
        title = "Project...",
        parentId = FileNew.id,
        order = 0,
        kind = MenuItemKind.ACTION,
        icon = TIcons.NewProject.icon,
        action = { ctx ->
            NewProjectDialog(ctx.window).isVisible = true
        }
    )

    val NewProjectFromExisting = MenuItem(
        id = "new_project_existing_sources",
        title = "Project from Existing Sources...",
        parentId = FileNew.id,
        order = 10,
        kind = MenuItemKind.ACTION,
        icon = TIcons.Import.icon,
        action = { ctx ->
            importProjectFromExistingSources(ctx.window)
        }
    )

    val NewProjectFromGit = MenuItem(
        id = "new_project_git",
        title = "Project from Git...",
        parentId = FileNew.id,
        order = 20,
        kind = MenuItemKind.ACTION,
        icon = TIcons.Git.icon,
        enabled = false
    )

    val RecentProjects = MenuItem(
        id = "recent_projects",
        title = "Recent Projects",
        parentId = File.id,
        order = 10,
        kind = MenuItemKind.MENU,
        childrenProvider = { ctx ->
            val currentPath = ctx.project?.projectDir?.toAbsolute()
            val projects = ProjectMngr.projects
                .filter { it.typeId != ProjectMngr.INVALID_CATALOG_PROJECT_TYPE }
                .filter { currentPath == null || it.projectDir.toAbsolute() != currentPath }

            if (projects.isEmpty()) {
                listOf(
                    MenuItem(
                        id = "recent_projects_none",
                        title = "(None)",
                        kind = MenuItemKind.ACTION,
                        enabled = false,
                        order = 0
                    )
                )
            } else {
                projects.take(12).mapIndexed { idx, project ->
                    MenuItem(
                        id = "recent_project_${idx}_${project.path.toString().hashCode()}",
                        title = project.name,
                        kind = MenuItemKind.ACTION,
                        order = idx,
                        action = {
                            ProjectMngr.openProject(project)
                        }
                    )
                }
            }
        }
    )

    val CloseProject = MenuItem(
        id = "close_project",
        title = "Close Project",
        parentId = File.id,
        order = 20,
        kind = MenuItemKind.ACTION,
        shortcut = "Ctrl+W",
        action = { ctx ->
            val window = ctx.window
            val wasVisible = window?.isVisible == true
            window?.close()
            if (wasVisible && window.isVisible) return@MenuItem

            if (Dashboard.I == null) {
                Dashboard.createAndShow()
            }
            val dashboard = Dashboard.I
            dashboard?.show()
            dashboard?.raise()
            dashboard?.activateWindow()
        }
    )

    val FileSepBeforeInvalidate = MenuItem(
        id = "file_sep_before_invalidate",
        title = "",
        parentId = File.id,
        order = 70,
        kind = MenuItemKind.SEPARATOR
    )

    val InvalidateCaches = MenuItem(
        id = "invalidate_caches",
        title = "Invalidate Caches",
        parentId = File.id,
        order = 80,
        kind = MenuItemKind.ACTION,
        enabledResolver = {
            GameProcessMngr.active().none { ctx -> ctx.isRunning }
        },
        tooltip = "Delete shared runtime caches so they regenerate on next launch",
        action = { ctx ->
            if (!confirmInvalidateCaches(ctx)) return@MenuItem

            scope.launch(Dispatchers.IO) {
                val result = invalidateCaches()
                runOnGuiThread {
                    showInvalidateResult(ctx, result)
                    (ctx.window as? ProjectViewWindow)?.rebuildMenus()
                }
            }
        }
    )

    val FileSepBeforeExit = MenuItem(
        id = "file_sep_before_exit",
        title = "",
        parentId = File.id,
        order = 90,
        kind = MenuItemKind.SEPARATOR
    )

    val ExitApp = MenuItem(
        id = "exit_app",
        title = "Exit",
        parentId = File.id,
        order = 100,
        kind = MenuItemKind.ACTION,
        shortcut = "Ctrl+Q",
        action = {
            QApplication.quit()
        }
    )

    val EditUndo = MenuItem(
        id = "edit_undo",
        title = "Undo",
        parentId = Edit.id,
        order = 0,
        kind = MenuItemKind.ACTION,
        shortcut = "Ctrl+Z",
        action = { ctx -> runEditCommand(ctx.window, EditCommand.UNDO) }
    )

    val EditRedo = MenuItem(
        id = "edit_redo",
        title = "Redo",
        parentId = Edit.id,
        order = 10,
        kind = MenuItemKind.ACTION,
        shortcut = "Ctrl+Shift+Z",
        action = { ctx -> runEditCommand(ctx.window, EditCommand.REDO) }
    )

    val EditSeparator = MenuItem(
        id = "edit_separator",
        title = "",
        parentId = Edit.id,
        order = 20,
        kind = MenuItemKind.SEPARATOR
    )

    val EditCut = MenuItem(
        id = "edit_cut",
        title = "Cut",
        parentId = Edit.id,
        order = 30,
        kind = MenuItemKind.ACTION,
        shortcut = "Ctrl+X",
        action = { ctx -> runEditCommand(ctx.window, EditCommand.CUT) }
    )

    val EditCopy = MenuItem(
        id = "edit_copy",
        title = "Copy",
        parentId = Edit.id,
        order = 40,
        kind = MenuItemKind.ACTION,
        shortcut = "Ctrl+C",
        action = { ctx -> runEditCommand(ctx.window, EditCommand.COPY) }
    )

    val EditPaste = MenuItem(
        id = "edit_paste",
        title = "Paste",
        parentId = Edit.id,
        order = 50,
        kind = MenuItemKind.ACTION,
        shortcut = "Ctrl+V",
        action = { ctx -> runEditCommand(ctx.window, EditCommand.PASTE) }
    )

    val EditDelete = MenuItem(
        id = "edit_delete",
        title = "Delete",
        parentId = Edit.id,
        order = 60,
        kind = MenuItemKind.ACTION,
        shortcut = "Delete",
        action = { ctx -> runEditCommand(ctx.window, EditCommand.DELETE) }
    )

    val ViewToolWindows = MenuItem(
        id = "view_tool_windows",
        title = "Tool Windows",
        parentId = View.id,
        order = 0,
        kind = MenuItemKind.MENU,
        childrenProvider = { ctx ->
            val window = ctx.window ?: return@MenuItem emptyList()
            val docks = window.findChildren(QDockWidget::class.java)
                .filter { !it.objectName.isNullOrBlank() }
                .sortedBy { it.windowTitle }
            if (docks.isEmpty()) {
                listOf(
                    MenuItem(
                        id = "tool_windows_none",
                        title = "(None)",
                        kind = MenuItemKind.ACTION,
                        enabled = false,
                        order = 0
                    )
                )
            } else {
                docks.mapIndexed { idx, dock ->
                    val title = dock.windowTitle.ifBlank { dock.objectName }
                    MenuItem(
                        id = "tool_window_${dock.objectName}",
                        title = title,
                        kind = MenuItemKind.ACTION,
                        order = idx,
                        action = {
                            dock.show()
                            dock.raise()
                        }
                    )
                }
            }
        }
    )

    val ViewIncreaseFont = MenuItem(
        id = "view_increase_font_size",
        title = "Increase Font Size",
        parentId = View.id,
        order = 10,
        kind = MenuItemKind.ACTION,
        shortcut = "Ctrl++",
        action = { ctx -> adjustFocusedTextWidgetFont(ctx.window, +1) }
    )

    val ViewDecreaseFont = MenuItem(
        id = "view_decrease_font_size",
        title = "Decrease Font Size",
        parentId = View.id,
        order = 20,
        kind = MenuItemKind.ACTION,
        shortcut = "Ctrl+-",
        action = { ctx -> adjustFocusedTextWidgetFont(ctx.window, -1) }
    )

    val LaunchGame = MenuItem(
        id = "launch_game",
        title = "Launch...",
        parentId = Game.id,
        order = 0,
        kind = MenuItemKind.ACTION,
        enabledResolver = { ctx ->
            val project = ctx.project
            project != null && !isAssetGenerationActive(project)
        },
        action = { ctx ->
            val project = ctx.project ?: return@MenuItem
            scope.launch {
                launchOrPrepare(project)
            }
        }
    )

    val StopGame = MenuItem(
        id = "stop_game_menu",
        title = "Stop",
        parentId = Game.id,
        order = 10,
        kind = MenuItemKind.ACTION,
        visibleResolver = { ctx ->
            val project = ctx.project ?: return@MenuItem false
            hasCompanionModInstalled(project)
        },
        enabledResolver = { ctx ->
            ctx.project?.let { GameLauncher.isGameRunning(it) } == true
        },
        action = { ctx ->
            val project = ctx.project ?: return@MenuItem
            scope.launch {
                stopGame(project, forceKill = false)
            }
        }
    )

    val StopAsKillGame = MenuItem(
        id = "stop_game_fallback_menu",
        title = "Kill Game",
        parentId = Game.id,
        order = 10,
        kind = MenuItemKind.ACTION,
        visibleResolver = { ctx ->
            val project = ctx.project ?: return@MenuItem false
            !hasCompanionModInstalled(project)
        },
        enabledResolver = { ctx ->
            ctx.project?.let { GameLauncher.isGameRunning(it) } == true
        },
        action = { ctx ->
            val project = ctx.project ?: return@MenuItem
            scope.launch {
                stopGame(project, forceKill = true)
            }
        }
    )

    val KillGame = MenuItem(
        id = "kill_game_menu",
        title = "Kill",
        parentId = Game.id,
        order = 20,
        kind = MenuItemKind.ACTION,
        enabledResolver = { ctx ->
            ctx.project?.let { GameLauncher.isGameRunning(it) } == true
        },
        action = { ctx ->
            val project = ctx.project ?: return@MenuItem
            scope.launch {
                stopGame(project, forceKill = true)
            }
        }
    )

    val GameSeparatorOne = MenuItem(
        id = "game_sep_one",
        title = "",
        parentId = Game.id,
        order = 30,
        kind = MenuItemKind.SEPARATOR
    )

    val ReloadServer = MenuItem(
        id = "reload_server",
        title = "Reload",
        parentId = Game.id,
        order = 40,
        kind = MenuItemKind.ACTION,
        action = { ctx ->
            val project = ctx.project
            scope.launch {
                val response = CompanionBridge.reloadServer()
                postBridgeResponse(project, "Reload Server", response)
            }
        }
    )

    val DumpRegistry = MenuItem(
        id = "dump_registry",
        title = "Dump Registry",
        parentId = Game.id,
        order = 50,
        kind = MenuItemKind.ACTION,
        action = { ctx ->
            val project = ctx.project
            scope.launch {
                val response = CompanionBridge.sendCommand("dumpRegistry")
                postBridgeResponse(project, "Dump Registry", response)
            }
        }
    )

    val RunCommand = MenuItem(
        id = "run_command",
        title = "Run Command...",
        parentId = Game.id,
        order = 60,
        kind = MenuItemKind.ACTION,
        action = { ctx ->
            val cmd = QInputDialog.getText(
                ctx.window,
                "Run Command",
                "Command:",
                QLineEdit.EchoMode.Normal,
                ""
            )?.trim().orEmpty()
            if (cmd.isBlank()) return@MenuItem
            val project = ctx.project
            scope.launch {
                val response = CompanionBridge.sendCommand(cmd)
                postBridgeResponse(project, "Run Command", response)
            }
        }
    )

    val GameSeparatorTwo = MenuItem(
        id = "game_sep_two",
        title = "",
        parentId = Game.id,
        order = 70,
        kind = MenuItemKind.SEPARATOR
    )

    val ShowLatestLog = MenuItem(
        id = "show_latest_log",
        title = "Show Latest Log",
        parentId = Game.id,
        order = 80,
        kind = MenuItemKind.ACTION,
        action = { ctx ->
            ctx.window?.let { ProjectLogsSidePanelProvider.focusLatestLog(it) }
        }
    )

    val ShowDebugLog = MenuItem(
        id = "show_debug_log",
        title = "Show Debug Log",
        parentId = Game.id,
        order = 90,
        kind = MenuItemKind.ACTION,
        visibleResolver = { ctx ->
            val project = ctx.project ?: return@MenuItem false
            ProjectLogsSidePanelProvider.hasDebugLog(project)
        },
        action = { ctx ->
            ctx.window?.let { ProjectLogsSidePanelProvider.focusDebugLog(it) }
        }
    )

    val About = MenuItem(
        id = "about_app",
        title = "About",
        parentId = Help.id,
        order = 0,
        kind = MenuItemKind.ACTION,
        action = { ctx ->
            QMessageBox.about(
                ctx.window,
                "About Tritium",
                "Tritium ${TConstants.VERSION}"
            )
        }
    )

    private enum class EditCommand {
        UNDO,
        REDO,
        CUT,
        COPY,
        PASTE,
        DELETE
    }

    private suspend fun launchOrPrepare(project: ProjectBase) {
        if (isAssetGenerationActive(project)) return
        val wasRunning = GameLauncher.isGameRunning(project)
        if (wasRunning) {
            val stop = stopGame(project)
            if (!stop.stopped) {
                logger.warn("Launch stop phase failed: {}", stop.message)
                return
            }
        }

        val needsDownload = GameLauncher.needsRuntimeDownload(project)
        if (!wasRunning && needsDownload) {
            GameLauncher.prepareRuntime(project)
            val finished = waitForRuntimePreparation(project, DOWNLOAD_WAIT_TIMEOUT_MS)
            if (!finished) {
                logger.warn("Timed out waiting for runtime preparation to finish for '{}'", project.name)
                return
            }

            val stillNeedsDownload = GameLauncher.needsRuntimeDownload(project)
            if (!stillNeedsDownload) {
                logger.info("Runtime downloads finished for '{}'", project.name)
                postActionStatus(
                    project = project,
                    header = "Downloads Finished",
                    message = "Runtime assets and loader files are ready.",
                    icon = TIcons.Download.icon
                )
            } else {
                logger.warn("Runtime preparation finished but runtime is still incomplete for '{}'", project.name)
            }
            return
        }

        GameLauncher.launch(project)
    }

    private fun importProjectFromExistingSources(window: QWidget?) {
        val startDir = try {
            ProjectMngr.projectsDir.toAbsolute().toString()
        } catch (_: Throwable) {
            fromTR().toString()
        }
        val chosen = QFileDialog.getOpenFileName(
            window,
            "Import Project",
            startDir,
            "Tritium Project (trproj.json);;JSON Files (*.json);;All Files (*)"
        )
        val selectedPath = chosen.result.trim()
        if (selectedPath.isBlank()) return

        val selected = VPath.get(selectedPath).expandHome().toAbsolute().normalize()
        val projectDir = selected.parent()
        val projectFile = if (selected.fileName() == "trproj.json") selected else projectDir.resolve("trproj.json")
        if (!projectFile.exists()) {
            QMessageBox.warning(
                window,
                "Import Project",
                "Selected path does not contain trproj.json."
            )
            return
        }

        val project = try {
            ProjectMngr.loadProject(projectDir)
        } catch (t: Throwable) {
            logger.warn("Failed to import project from {}", projectDir, t)
            null
        }
        if (project == null) {
            QMessageBox.warning(
                window,
                "Import Project",
                "Failed to load project from selected path."
            )
            return
        }

        ProjectMngr.notifyCreatedExternal(project)
        ProjectMngr.openProject(project)
    }

    private fun hasCompanionModInstalled(project: ProjectBase): Boolean {
        val modsDir = project.projectDir.resolve("mods")
        if (!modsDir.exists() || !modsDir.isDir()) return false
        return modsDir.list().any { file ->
            val name = file.fileName().lowercase()
            file.isFile() && name.endsWith(".jar") && name.contains("tritiumcompanion")
        }
    }

    private fun runEditCommand(window: QMainWindow?, command: EditCommand) {
        val focused = QApplication.focusWidget() ?: window?.focusWidget()
        when (focused) {
            is QTextEdit -> when (command) {
                EditCommand.UNDO -> focused.undo()
                EditCommand.REDO -> focused.redo()
                EditCommand.CUT -> focused.cut()
                EditCommand.COPY -> focused.copy()
                EditCommand.PASTE -> focused.paste()
                EditCommand.DELETE -> {
                    val cursor = focused.textCursor()
                    if (cursor.hasSelection()) cursor.removeSelectedText()
                }
            }
            is QPlainTextEdit -> when (command) {
                EditCommand.UNDO -> focused.undo()
                EditCommand.REDO -> focused.redo()
                EditCommand.CUT -> focused.cut()
                EditCommand.COPY -> focused.copy()
                EditCommand.PASTE -> focused.paste()
                EditCommand.DELETE -> {
                    val cursor = focused.textCursor()
                    if (cursor.hasSelection()) cursor.removeSelectedText()
                }
            }
            is QLineEdit -> when (command) {
                EditCommand.UNDO -> focused.undo()
                EditCommand.REDO -> focused.redo()
                EditCommand.CUT -> focused.cut()
                EditCommand.COPY -> focused.copy()
                EditCommand.PASTE -> focused.paste()
                EditCommand.DELETE -> focused.del()
            }
        }
    }

    private fun adjustFocusedTextWidgetFont(window: QMainWindow?, delta: Int) {
        val projectWindow = window as? ProjectViewWindow
        if (projectWindow?.adjustEditorFontSize(delta) == true) {
            return
        }

        val focused = QApplication.focusWidget() ?: window?.focusWidget()
        fun adjust(current: Int): Int {
            val base = if (current > 0) current else 11
            return (base + delta).coerceIn(7, 48)
        }
        when (focused) {
            is QTextEdit -> {
                val font = focused.font()
                font.setPointSize(adjust(font.pointSize()))
                focused.font = font
            }
            is QPlainTextEdit -> {
                val font = focused.font()
                font.setPointSize(adjust(font.pointSize()))
                focused.font = font
            }
            is QLineEdit -> {
                val font = focused.font()
                font.setPointSize(adjust(font.pointSize()))
                focused.font = font
            }
        }
    }

    private fun postBridgeResponse(project: ProjectBase?, actionName: String, response: CompanionBridgeResponse) {
        if (!response.ok) {
            logger.warn("Companion action '{}' failed: {}", actionName, response.message)
        }

        postActionStatus(
            project,
            if (response.ok) actionName else "$actionName Failed",
            response.message,
            if (response.ok) TIcons.Run.icon else TIcons.Cross.icon
        )
    }

    private fun postActionStatus(project: ProjectBase?, header: String, message: String, icon: QIcon) {
        runOnGuiThread {
            NotificationMngr.post(
                id = "generic",
                project = project,
                header = header,
                description = message,
                icon = icon
            )
        }
    }

    private data class InvalidateCachesResult(
        val deleted: List<String>,
        val failed: List<String>
    )

    private fun confirmInvalidateCaches(ctx: MenuActionContext): Boolean {
        val box = QMessageBox(ctx.window)
        box.icon = QMessageBox.Icon.Question
        box.windowTitle = "Invalidate Caches"
        box.text = "Invalidate shared launcher caches?"
        box.informativeText = "This will delete cache, loaders, and assets under ~/tritium. They will be regenerated on next launch."
        val invalidateButton = box.addButton("Invalidate", QMessageBox.ButtonRole.AcceptRole)
        box.addButton(QMessageBox.StandardButton.Cancel)
        box.exec()
        return box.clickedButton() == invalidateButton
    }

    private fun showInvalidateResult(ctx: MenuActionContext, result: InvalidateCachesResult) {
        val box = QMessageBox(ctx.window)
        if (result.failed.isEmpty()) {
            box.icon = QMessageBox.Icon.Information
            box.windowTitle = "Caches Invalidated"
            box.text = "Shared caches were invalidated."
            box.informativeText = "Deleted: ${result.deleted.joinToString(", ")}"
        } else {
            box.icon = QMessageBox.Icon.Warning
            box.windowTitle = "Cache Invalidation Incomplete"
            box.text = "Some cache directories could not be invalidated."
            box.informativeText = "Deleted: ${result.deleted.joinToString(", ")}\nFailed: ${result.failed.joinToString(", ")}"
        }
        box.addButton(QMessageBox.StandardButton.Ok)
        box.exec()
    }

    private fun invalidateCaches(): InvalidateCachesResult {
        val targets = listOf(
            TConstants.Dirs.CACHE,
            TConstants.Dirs.LOADERS,
            TConstants.Dirs.ASSETS
        )

        val deleted = mutableListOf<String>()
        val failed = mutableListOf<String>()
        for (name in targets) {
            val dir = fromTR(name)
            val ok = deleteTree(dir)
            if (ok) {
                deleted += name
                runCatching { dir.mkdirs() }
            } else {
                failed += name
            }
        }
        return InvalidateCachesResult(deleted = deleted, failed = failed)
    }

    private fun deleteTree(path: VPath): Boolean {
        if (!path.exists()) return true
        return try {
            Files.walk(path.toJPath()).use { stream ->
                stream.sorted(Comparator.reverseOrder()).forEach { p ->
                    Files.deleteIfExists(p)
                }
            }
            true
        } catch (t: Throwable) {
            logger.warn("Failed to delete cache directory {}", path.toAbsolute(), t)
            false
        }
    }

    private suspend fun stopGame(project: ProjectBase, forceKill: Boolean = false): StopResult {
        if (!GameLauncher.isGameRunning(project)) {
            return StopResult(true, "Game is not running.")
        }

        val companionAvailable = isCompanionAvailable()
        if (!companionAvailable) {
            return forceKillAndAwait(project, "Companion is unavailable; game process was force-stopped.")
        }

        if (forceKill) {
            return forceKillAndAwait(project, "Game process was force-stopped.")
        }

        val closeResponse = try {
            CompanionBridge.closeGame(timeoutMs = CLOSE_TIMEOUT_MS)
        } catch (t: Throwable) {
            logger.warn("Companion close_game request failed", t)
            CompanionBridgeResponse(false, "Companion close_game request failed.")
        }

        val stopped = waitForStopped(project, STOP_POLL_TIMEOUT_MS)
        if (stopped) {
            val msg = if (closeResponse.ok) {
                "Game closed."
            } else {
                "Game stopped."
            }
            return StopResult(true, msg)
        }

        val fallback = closeResponse.message.ifBlank { "Unable to stop game gracefully." }
        return forceKillAndAwait(project, fallback)
    }

    private suspend fun waitForStopped(project: ProjectBase, timeoutMs: Long): Boolean {
        val end = System.currentTimeMillis() + timeoutMs.coerceAtLeast(0L)
        while (System.currentTimeMillis() < end) {
            if (!GameLauncher.isGameRunning(project)) return true
            delay(STOP_POLL_INTERVAL_MS)
        }
        return !GameLauncher.isGameRunning(project)
    }

    private suspend fun isCompanionAvailable(): Boolean {
        val ping = try {
            CompanionBridge.ping(timeoutMs = COMPANION_PING_TIMEOUT_MS)
        } catch (t: Throwable) {
            logger.debug("Companion ping failed", t)
            return false
        }
        return ping.ok
    }

    private suspend fun waitForRuntimePreparation(project: ProjectBase, timeoutMs: Long): Boolean {
        val end = System.currentTimeMillis() + timeoutMs.coerceAtLeast(0L)
        while (System.currentTimeMillis() < end) {
            if (!GameLauncher.isRuntimePreparationActive(project)) return true
            delay(DOWNLOAD_POLL_INTERVAL_MS)
        }
        return !GameLauncher.isRuntimePreparationActive(project)
    }

    private suspend fun forceKillAndAwait(project: ProjectBase, baseMessage: String): StopResult {
        val killed = GameLauncher.killGameProcess(project, force = true)
        if (!killed) {
            return StopResult(false, baseMessage.ifBlank { "Unable to stop game process." })
        }
        val stoppedAfterKill = waitForStopped(project, KILL_WAIT_TIMEOUT_MS)
        if (!stoppedAfterKill) {
            return StopResult(false, "Stop requested, but the game process is still running.")
        }
        return StopResult(true, baseMessage.ifBlank { "Game process was force-stopped." })
    }

    private fun isAssetGenerationActive(project: ProjectBase): Boolean {
        if (ProjectMngr.generationActive) return true
        if (GameLauncher.isRuntimePreparationActive(project)) return true
        return ProjectTaskMngr.activeForProject(project).any { task ->
            task.title.startsWith("Bootstrapping ", ignoreCase = true)
        }
    }

    private data class StopResult(
        val stopped: Boolean,
        val message: String
    )

    val All = listOf(
        Play,
        Stop,
        File,
        FileNew,
        NewProject,
        NewProjectFromExisting,
        NewProjectFromGit,
        RecentProjects,
        CloseProject,
        FileSepBeforeInvalidate,
        InvalidateCaches,
        FileSepBeforeExit,
        ExitApp,
        Edit,
        EditUndo,
        EditRedo,
        EditSeparator,
        EditCut,
        EditCopy,
        EditPaste,
        EditDelete,
        View,
        ViewToolWindows,
        ViewIncreaseFont,
        ViewDecreaseFont,
        Game,
        LaunchGame,
        StopGame,
        StopAsKillGame,
        KillGame,
        GameSeparatorOne,
        ReloadServer,
        DumpRegistry,
        RunCommand,
        GameSeparatorTwo,
        ShowLatestLog,
        ShowDebugLog,
        Help,
        About
    )
}
