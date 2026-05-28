package io.github.tritium_launcher.launcher.ui.project.sidebar

import io.github.tritium_launcher.launcher.connect
import io.github.tritium_launcher.launcher.core.TritiumEvent
import io.github.tritium_launcher.launcher.core.TritiumEventBus
import io.github.tritium_launcher.launcher.core.mod.*
import io.github.tritium_launcher.launcher.core.project.ProjectBase
import io.github.tritium_launcher.launcher.core.project.ProjectDirWatcher
import io.github.tritium_launcher.launcher.io.VPath
import io.github.tritium_launcher.launcher.logger
import io.github.tritium_launcher.launcher.ui.theme.TColors
import io.github.tritium_launcher.launcher.ui.theme.TIcons
import io.github.tritium_launcher.launcher.ui.theme.qt.icon
import io.github.tritium_launcher.launcher.ui.theme.qt.setThemedStyle
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.hBoxLayout
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.label
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.toolButton
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.vBoxLayout
import io.qt.core.QSize
import io.qt.core.Qt
import io.qt.gui.*
import io.qt.widgets.*
import kotlinx.coroutines.*
import java.io.File
import kotlin.time.ExperimentalTime

class ProjectInstalledModsSidePanelProvider : SidePanelProvider {
    override val id: String = "installed_mods"
    override val displayName: String = "Installed Mods"
    override val icon: QIcon = TIcons.CSV.icon
    override val order: Int = 6

    override val closeable: Boolean = false
    override val floatable: Boolean = false
    override val preferredArea: Qt.DockWidgetArea = Qt.DockWidgetArea.RightDockWidgetArea

    override fun create(project: ProjectBase): DockWidget {
        val dock = DockWidget(displayName, null)
        dock.setWidget(InstalledModsPanel(project))
        return dock
    }
}

private class InstalledModsPanel(
    private val project: ProjectBase
) : QWidget() {
    private val logger = logger()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val listWidget = QListWidget()
    private val dominantColorMap = HashMap<String, Triple<Int, Int, Int>>()
    private val watcher = ProjectDirWatcher(project.projectDir.resolve("mods"))

    init {
        objectName = "installedModsPanel"
        val layout = vBoxLayout(this) {
            setContentsMargins(4, 4, 4, 4)
            setSpacing(4)
        }

        val refreshButton = toolButton {
            icon = TIcons.Rerun.icon
            iconSize = QSize(16, 16)
            autoRaise = true
            toolTip = "Refresh"
        }

        val headerRow = QWidget()
        hBoxLayout(headerRow) {
            setContentsMargins(0, 0, 0, 0)
            setSpacing(4)
            addStretch(1)
            addWidget(refreshButton)
        }

        listWidget.apply {
            objectName = "installedModsList"
            wordWrap = true
            spacing = 2
            selectionMode = QAbstractItemView.SelectionMode.NoSelection
            focusPolicy = Qt.FocusPolicy.NoFocus
        }

        val countLabel = label {
            objectName = "installedModsCount"
        }

        layout.addWidget(headerRow)
        layout.addWidget(listWidget, 1)
        layout.addWidget(countLabel)

        setThemedStyle {
            selector("#installedModsPanel") {
                backgroundColor(TColors.Surface0)
            }
            selector("#installedModsList") {
                backgroundColor(TColors.Surface0)
                color(TColors.Text)
                border()
            }
            selector("#installedModsList::item") {
                padding(6)
            }
            selector("#installedModsCount") {
                color(TColors.Subtext)
                fontSize(10)
            }
        }

        refreshButton.clicked.connect { refreshMods() }
        project.projectDir.resolve("mods").mkdirs()
        watcher.start(::refreshMods)
        scope.launch {
            TritiumEventBus.events.collect { event ->
                if (event is TritiumEvent.ModsInstalled) refreshMods()
            }
        }
        listWidget.currentItemChanged.connect { current, _ ->
            updateSelectedRowGradient(current?.data(Qt.ItemDataRole.UserRole) as? String)
        }
        destroyed.connect {
            watcher.stop()
            scope.cancel()
        }
        refreshMods()
    }

    private fun refreshMods() {
        scope.launch {
            val mods = withContext(Dispatchers.IO) {
                ModDatabase(project.projectDir).use { db ->
                    syncFromModsDir(db)
                    populateMissingIcons(db)
                    db.getAll()
                }
            }
            listWidget.clear()
            dominantColorMap.clear()
            mods.forEach { mod -> addModItem(mod) }
            val labels = findChildren(QLabel::class.java)
            labels.firstOrNull { it.objectName == "installedModsCount" }?.text = "${mods.size} mod(s) installed"
        }
    }

    @OptIn(ExperimentalTime::class)
    private fun syncFromModsDir(db: ModDatabase) {
        val modsDirFile = project.projectDir.resolve("mods")
        if (!modsDirFile.isDir()) return

        val registry = ModRegistryStore(project.projectDir)
        val existingHashes = db.getAll().mapNotNull { it.fileHash }.toSet()
        val existingIds = db.getAll().map { it.projectId }.toSet()

        val jarFiles = modsDirFile.listFiles { f -> f.fileName().endsWith(".jar", ignoreCase = true) }
        for (jarFile in jarFiles) {
            val bytes = try { jarFile.bytesOrNothing() } catch (_: Exception) { continue }
            val hash = ModDatabase.sha1(bytes)
            if (hash in existingHashes) continue

            val info = readModJarInfo(VPath.parse(jarFile.toAbsoluteString()))
            if (info == null) {
                logger.warn("Could not read metadata from '{}', skipping db import", jarFile.fileName())
                continue
            }

            val registryEntry = registry.getEntryByModId(info.modId)
            val projectId = registryEntry?.projectId ?: info.modId
            if (projectId in existingIds) continue

            val iconBytes = readModJarIcon(VPath.parse(jarFile.toAbsoluteString()))
            val iconPath: String? = if (iconBytes != null) {
                val iconFile = ModDatabase.iconPathFor(projectId)
                iconFile.writeBytesAtomic(iconBytes)
                iconFile.toAbsolute().toString()
            } else null

            val installedMod = if (registryEntry != null) {
                registry.toInstalledMod(registryEntry).copy(
                    fileName = jarFile.fileName(),
                    iconPath = iconPath ?: registryEntry.iconPath,
                    fileHash = hash,
                    installedAt = jarFile.lastModifiedOrNull()
                )
            } else {
                InstalledMod(
                    projectId = projectId,
                    modId = info.modId,
                    fileName = jarFile.fileName(),
                    displayName = info.displayName,
                    side = info.side,
                    releaseType = "release",
                    source = "unknown",
                    versionId = projectId,
                    versionLabel = "",
                    iconPath = iconPath,
                    projectUrl = null,
                    fileHash = hash,
                    installedAt = jarFile.lastModifiedOrNull()
                )
            }
            db.install(installedMod)
            if (registryEntry != null && registryEntry.dependencies.isNotEmpty()) {
                db.setDependencies(registryEntry.projectId, registryEntry.dependencies)
            }
            registry.updateEntry(registry.entryFromInstalledMod(installedMod))
            logger.info("Imported existing mod '{}' from '{}' into mods database", info.displayName, jarFile.fileName())
        }
    }

    private fun populateMissingIcons(db: ModDatabase) {
        val modsDirFile = project.projectDir.resolve("mods")
        if (!modsDirFile.isDir()) return

        val allMods = db.getAll()
        for (mod in allMods) {
            if (mod.iconPath?.isNotBlank() == true) {
                val iconFile = File(mod.iconPath)
                if (iconFile.exists()) continue
            }

            if (mod.fileName.isBlank()) continue
            val jarFile = modsDirFile.resolve(mod.fileName)
            if (!jarFile.exists()) continue

            val iconBytes = readModJarIcon(jarFile) ?: continue
            val iconFile = ModDatabase.iconPathFor(mod.projectId)
            iconFile.writeBytesAtomic(iconBytes)
            val absPath = iconFile.toAbsolute().toString()
            db.updateIconPath(mod.projectId, absPath)
            logger.info("Extracted icon for mod '{}' from '{}'", mod.displayName, mod.fileName)
        }
    }

    private fun addModItem(mod: InstalledMod) {
        val item = QListWidgetItem()
        item.setData(Qt.ItemDataRole.UserRole, mod.projectId)
        val row = ModListRow(mod)
        item.setSizeHint(row.sizeHint())
        listWidget.addItem(item)
        listWidget.setItemWidget(item, row)
        row.removeRequested.connect { modId ->
            confirmAndUninstall(modId)
        }
        row.enableToggled.connect { modId ->
            toggleEnabled(modId)
        }
        row.releaseToggled.connect { modId ->
            toggleRelease(modId)
        }
        dominantColorMap[mod.projectId]?.let {} // skip if already cached
        val color = extractDominantColor(row.iconLabel.pixmap() ?: return@addModItem)
        if (color != null) dominantColorMap[mod.projectId] = color
    }

    private fun extractDominantColor(pixmap: QPixmap): Triple<Int, Int, Int>? {
        val small = pixmap.scaled(4, 4, Qt.AspectRatioMode.IgnoreAspectRatio, Qt.TransformationMode.SmoothTransformation)
        if (small.isNull) return null
        val image = small.toImage() ?: return null
        if (image.isNull) return null
        for (y in 0 until image.height()) {
            for (x in 0 until image.width()) {
                val argb = image.pixel(x, y)
                val alpha = (argb ushr 24) and 0xFF
                if (alpha >= 128) {
                    return Triple(
                        (argb ushr 16) and 0xFF,
                        (argb ushr 8) and 0xFF,
                        argb and 0xFF
                    )
                }
            }
        }
        return null
    }

    private fun updateSelectedRowGradient(projectId: String?) {
        for (i in 0 until listWidget.count()) {
            listWidget.item(i)?.setBackground(QBrush())
        }
        if (projectId == null) return
        val (r, g, b) = dominantColorMap[projectId] ?: return
        for (i in 0 until listWidget.count()) {
            val item = listWidget.item(i) ?: continue
            if (item.data(Qt.ItemDataRole.UserRole) as? String == projectId) {
                val gradient = QLinearGradient(0.0, 0.0, 1.0, 0.0).apply {
                    setCoordinateMode(QGradient.CoordinateMode.ObjectBoundingMode)
                    setColorAt(0.0, QColor(r, g, b, 110))
                    setColorAt(1.0, QColor(0, 0, 0, 0))
                }
                item.setBackground(QBrush(gradient))
                return
            }
        }
    }

    private fun confirmAndUninstall(projectId: String) {
        scope.launch {
            val mod = withContext(Dispatchers.IO) {
                ModDatabase(project.projectDir).use { db -> db.getByProjectId(projectId) }
            }
            if (mod == null) {
                refreshMods()
                return@launch
            }
            withContext(Dispatchers.Main) {
                val dialog = QMessageBox(window())
                dialog.icon = QMessageBox.Icon.Question
                dialog.windowTitle = "Delete Mod"
                dialog.text = "Delete \"${mod.displayName}\"?\nThe jar file will be moved to trash."
                dialog.addButton(QMessageBox.StandardButton.Yes)
                dialog.addButton(QMessageBox.StandardButton.No)
                dialog.exec()
                if (dialog.clickedButton() == dialog.buttons().firstOrNull { it?.text() == "&Yes" }) {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            project.projectDir.resolve("mods").resolve(mod.fileName).moveToTrash()
                            ModDatabase(project.projectDir).use { db -> db.uninstall(projectId) }
                            ModRegistryStore(project.projectDir).removeEntry(projectId)
                        }
                        refreshMods()
                    }
                }
            }
        }
    }

    private fun toggleEnabled(projectId: String) {
        scope.launch {
            withContext(Dispatchers.IO) {
                val registry = ModRegistryStore(project.projectDir)
                ModDatabase(project.projectDir).use { db ->
                    val mod = db.getByProjectId(projectId) ?: return@use
                    db.setEnabled(projectId, !mod.enabled)
                }
                registry.getEntry(projectId)?.let { entry ->
                    registry.updateEntry(entry.copy(enabled = !entry.enabled))
                }
            }
            refreshMods()
        }
    }

    private fun toggleRelease(projectId: String) {
        scope.launch {
            withContext(Dispatchers.IO) {
                val registry = ModRegistryStore(project.projectDir)
                ModDatabase(project.projectDir).use { db ->
                    val mod = db.getByProjectId(projectId) ?: return@use
                    db.setExcludedFromRelease(projectId, !mod.excludedFromRelease)
                }
                registry.getEntry(projectId)?.let { entry ->
                    registry.updateEntry(entry.copy(excludedFromRelease = !entry.excludedFromRelease))
                }
            }
            refreshMods()
        }
    }
}

private class ModListRow(
    private val mod: InstalledMod
) : QWidget() {
    val removeRequested = Signal1<String>()
    val enableToggled = Signal1<String>()
    val releaseToggled = Signal1<String>()
    val iconLabel: QLabel

    override fun sizeHint(): QSize = QSize(200, 48)

    init {
        objectName = "modListRow"
        val layout = QHBoxLayout(this).apply {
            setContentsMargins(4, 4, 4, 4)
            setSpacing(8)
        }

        val iconFile = mod.iconPath?.takeIf { it.isNotBlank() }?.let { File(it) }
        iconLabel = QLabel().apply {
            setFixedSize(32, 32)
            scaledContents = true
            setAlignment(Qt.AlignmentFlag.AlignCenter)
            val pix = if (iconFile != null && iconFile.exists()) {
                QPixmap(iconFile.absolutePath)
            } else {
                TIcons.Search
            }
            pixmap = pix.scaled(32, 32, Qt.AspectRatioMode.KeepAspectRatio, Qt.TransformationMode.SmoothTransformation)
        }

        val textColumn = QWidget()
        val textLayout = QVBoxLayout(textColumn).apply {
            setContentsMargins(0, 0, 0, 0)
            setSpacing(2)
        }

        val nameLabel = QLabel(mod.displayName).apply {
            val f = QFont(font())
            f.setBold(true)
            font = f
            objectName = "modListName"
        }
        if (!mod.enabled) {
            nameLabel.objectName = "modListNameDisabled"
        }
        val metaText = buildString {
            append(mod.modId)
            if (mod.versionLabel.isNotBlank()) append(" · ${mod.versionLabel}")
            if (mod.side != "BOTH") append(" · ${mod.side}")
            append(" · ${mod.releaseType}")
            if (!mod.enabled) append(" · DISABLED")
            if (mod.excludedFromRelease) append(" · DEV")
        }
        val metaLabel = QLabel(metaText).apply {
            val f = QFont(font())
            f.setPointSize(9)
            font = f
            objectName = "modListMeta"
        }

        textLayout.addWidget(nameLabel)
        textLayout.addWidget(metaLabel)

        val menuButton = QToolButton().apply {
            icon = TIcons.SmallMenu.icon
            iconSize = QSize(14, 14)
            autoRaise = true
            toolTip = "Mod actions"
        }

        val menu = QMenu(this)
        menu.addAction("Update Mod")?.let {
            it.enabled = false
            it.toolTip = "TODO" // TODO
        }

        menu.addSeparator()

        val enabledLabel = if (mod.enabled) "Disable" else "Enable"
        menu.addAction(enabledLabel)?.let {
            it.triggered.connect { enableToggled.emit(mod.projectId) }
        }

        val releaseLabel = if (mod.excludedFromRelease) "Include in Release" else "Exclude from Release"
        menu.addAction(releaseLabel)?.let {
            it.triggered.connect { releaseToggled.emit(mod.projectId) }
        }

        menu.addSeparator()

        menu.addAction("Delete")?.let {
            it.triggered.connect { removeRequested.emit(mod.projectId) }
        }

        menuButton.setMenu(menu)
        menuButton.popupMode = QToolButton.ToolButtonPopupMode.InstantPopup

        layout.addWidget(iconLabel, 0, Qt.AlignmentFlag.AlignVCenter)
        layout.addWidget(textColumn, 1)
        layout.addWidget(menuButton)

        setThemedStyle {
            selector("#modListName") {
                color(TColors.Text)
            }
            selector("#modListNameDisabled") {
                color(TColors.Surface2)
            }
            selector("#modListMeta") {
                color(TColors.Subtext)
            }
        }

        if (!mod.enabled) {
            iconLabel.setGraphicsEffect(QGraphicsOpacityEffect().apply { opacity = 0.45 })
        }
    }
}
