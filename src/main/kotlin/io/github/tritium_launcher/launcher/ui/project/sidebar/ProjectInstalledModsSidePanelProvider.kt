package io.github.tritium_launcher.launcher.ui.project.sidebar

import io.github.tritium_launcher.launcher.connect
import io.github.tritium_launcher.launcher.core.TritiumEvent
import io.github.tritium_launcher.launcher.core.TritiumEventBus
import io.github.tritium_launcher.launcher.core.mod.*
import io.github.tritium_launcher.launcher.core.onEvent
import io.github.tritium_launcher.launcher.core.project.ModpackMeta
import io.github.tritium_launcher.launcher.core.project.Project
import io.github.tritium_launcher.launcher.core.project.ProjectBase
import io.github.tritium_launcher.launcher.core.project.ProjectDirWatcher
import io.github.tritium_launcher.launcher.core.source.HashFallbackProvider
import io.github.tritium_launcher.launcher.core.source.ModBrowserContext
import io.github.tritium_launcher.launcher.core.source.ModVersionOption
import io.github.tritium_launcher.launcher.core.source.resolveInstallDownload
import io.github.tritium_launcher.launcher.extension.core.BuiltinRegistries
import io.github.tritium_launcher.launcher.io.VPath
import io.github.tritium_launcher.launcher.logger
import io.github.tritium_launcher.launcher.ui.project.editor.EditorArea
import io.github.tritium_launcher.launcher.ui.project.editor.panes.ModDetailMeta
import io.github.tritium_launcher.launcher.ui.project.editor.panes.ModDetailPane
import io.github.tritium_launcher.launcher.ui.project.editor.panes.ModDetailPaneProvider
import io.github.tritium_launcher.launcher.ui.theme.TColors
import io.github.tritium_launcher.launcher.ui.theme.TIcons
import io.github.tritium_launcher.launcher.ui.theme.qt.icon
import io.github.tritium_launcher.launcher.ui.theme.qt.setThemedStyle
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.hBoxLayout
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.label
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.toolButton
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.vBoxLayout
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.qt.core.QSize
import io.qt.core.QTimer
import io.qt.core.Qt
import io.qt.gui.*
import io.qt.widgets.*
import kotlinx.coroutines.*
import java.io.File
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.ExperimentalTime

class ProjectInstalledModsSidePanelProvider : SidePanelProvider {
    override val id: String = "installed_mods"
    override val displayName: String = "Installed Mods"
    override var icon: QIcon? = TIcons.CSV.icon
    override val order: Int = 6

    override val closeable: Boolean = false
    override val floatable: Boolean = false
    override val preferredArea: Qt.DockWidgetArea = Qt.DockWidgetArea.RightDockWidgetArea

    override fun create(project: ProjectBase): DockWidget {
        val dock = DockWidget(displayName, null)
        dock.setWidget(InstalledModsPanel(project))
        return dock
    }

    override fun onDockCreated(project: ProjectBase, editorArea: EditorArea, dock: DockWidget, onStateChanged: () -> Unit) {
        val panel = dock.widget() as? InstalledModsPanel
        panel?.onOpenDetailRequested = { modId, title ->
            ModDetailMeta.register(modId, title)
            editorArea.openEditorPane(
                provider = ModDetailPaneProvider,
                title = title,
                paneFactory = { ModDetailPane(it, modId = modId) }
            )
        }
    }
}

class InstalledModsPanel(
    private val project: ProjectBase
) : QWidget() {
    var onOpenDetailRequested: ((modId: String, title: String) -> Unit)? = null
    private val logger = logger()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val listWidget = QListWidget()
    private val dominantColorMap = HashMap<String, Triple<Int, Int, Int>>()
    private val watcher = ProjectDirWatcher(project.projectDir.resolve("mods"))
    private val updateAvailability = HashMap<String, ModVersionOption>()
    private val httpClient = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 60_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 60_000
        }
    }

    init {
        objectName = "installedModsPanel"
        val layout = vBoxLayout(this) {
            setContentsMargins(4, 4, 4, 4)
            setSpacing(4)
        }

        val checkUpdatesButton = toolButton {
            icon = TIcons.Download.icon
            autoRaise = true
            toolTip = "Check for updates"
        }

        val refreshButton = toolButton {
            icon = TIcons.Rerun.icon
            autoRaise = true
            toolTip = "Refresh"
        }

        val headerRow = QWidget()
        hBoxLayout(headerRow) {
            setContentsMargins(0, 0, 0, 0)
            setSpacing(4)
            addStretch(1)
            addWidget(checkUpdatesButton)
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
        checkUpdatesButton.clicked.connect { checkUpdates() }

        project.projectDir.resolve("mods").mkdirs()
        watcher.start(::refreshMods)
        scope.onEvent<TritiumEvent.ModsInstalled> { refreshMods() }
        scope.onEvent<TritiumEvent.UpdateCheckRequested> { checkUpdates() }
        listWidget.currentItemChanged.connect { current, _ ->
            updateSelectedRowGradient(current?.data(Qt.ItemDataRole.UserRole) as? String)
        }

        listWidget.itemDoubleClicked.connect { item ->
            val projectId = item?.data(Qt.ItemDataRole.UserRole) as? String ?: return@connect
            scope.launch {
                val mod = withContext(Dispatchers.IO) {
                    ModDatabase(project.projectDir).use { db -> db.getByProjectId(projectId) }
                }
                val title = mod?.displayName ?: projectId
                onOpenDetailRequested?.invoke(projectId, title)
            }
        }

        val hourlyTimer = QTimer(this).apply {
            interval = 1.hours.inWholeMilliseconds.toInt()
            timeout.connect { checkUpdates() }
            start()
        }

        destroyed.connect {
            watcher.stop()
            hourlyTimer.stop()
            scope.cancel()
            httpClient.close()
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
            val updates = ModUpdateChecker.checkAll(project, mods)
            updateAvailability.clear()
            updateAvailability.putAll(updates)

            listWidget.clear()
            dominantColorMap.clear()
            mods.forEach { mod ->
                val updateOption = updateAvailability[mod.projectId]
                addModItem(mod, updateOption)
            }
            val labels = findChildren(QLabel::class.java)
            val updateCount = updateAvailability.size
            val countText = "${mods.size} mod(s) installed"
            labels.firstOrNull { it.objectName == "installedModsCount" }?.text =
                if (updateCount > 0) "$countText | $updateCount update(s) available"
                else countText
        }
    }

    private fun checkUpdates() {
        scope.launch {
            val mods = withContext(Dispatchers.IO) {
                ModDatabase(project.projectDir).use { db -> db.getAll() }
            }
            val updates = ModUpdateChecker.checkAll(project, mods)
            updateAvailability.clear()
            updateAvailability.putAll(updates)
            val updateCount = updateAvailability.size
            val labels = findChildren(QLabel::class.java)
            labels.firstOrNull { it.objectName == "installedModsCount" }?.let { label ->
                val current = label.text
                val base = current.substringBefore(" | ")
                label.text = if (updateCount > 0) "$base | $updateCount update(s) available" else base
            }
            for (i in 0 until listWidget.count()) {
                val item = listWidget.item(i)
                val projectId = item?.data(Qt.ItemDataRole.UserRole) as? String ?: continue
                val row = listWidget.itemWidget(item) as? ModListRow ?: continue
                row.updateUpdateOption(updateAvailability[projectId])
            }
        }
    }

    @OptIn(ExperimentalTime::class)
    private fun syncFromModsDir(db: ModDatabase) {
        ModDatabase.restoreFromRegistryIfNeeded(db, project.projectDir)
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

    private suspend fun addModItem(mod: InstalledMod, updateOption: ModVersionOption? = null) {
        val prevVersion = withContext(Dispatchers.IO) {
            ModDatabase(project.projectDir).use { it.getPreviousVersion(mod.projectId) }
        }
        val skippedVersion = withContext(Dispatchers.IO) {
            ModDatabase(project.projectDir).use { it.getSkippedVersion(mod.projectId, mod.versionId) }
        }
        val item = QListWidgetItem()
        item.setData(Qt.ItemDataRole.UserRole, mod.projectId)
        val row = ModListRow(mod, updateOption, prevVersion, skippedVersion)
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
        row.updateRequested.connect { modId ->
            performUpdate(modId)
        }
        row.downgradeRequested.connect { modId ->
            performDowngrade(modId)
        }
        row.skipRequested.connect { modId ->
            performSkip(modId)
        }
        row.installSkippedRequested.connect { modId ->
            performInstallSkipped(modId)
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

    private fun performUpdate(modId: String) {
        val updateOption = updateAvailability[modId] ?: return
        scope.launch {
            val mod = withContext(Dispatchers.IO) {
                ModDatabase(project.projectDir).use { db -> db.getByProjectId(modId) }
            } ?: return@launch

            withContext(Dispatchers.IO) {
                val source = BuiltinRegistries.ModSource.all().find { it.id == mod.source } ?: return@withContext
                val meta = (project as? Project<*>)?.typedMeta as? ModpackMeta ?: return@withContext
                val context = ModBrowserContext(
                    project = project,
                    minecraftVersion = meta.minecraftVersion,
                    modLoaderId = meta.loader
                )

                try {
                    val fallbacks = BuiltinRegistries.ModSource.all()
                        .filterIsInstance<HashFallbackProvider>()
                        .sortedBy { it.priority }
                    val resolved = resolveInstallDownload(context, source, modId, updateOption.id, fallbacks)
                    if (resolved.downloadUrl == null) {
                        error("Update requires manual download (blocked by mod author)")
                    }
                    val modsDir = project.projectDir.resolve("mods")
                    modsDir.mkdirs()
                    val bytes = httpClient.get(resolved.downloadUrl).bodyAsBytes()
                    val oldJar = modsDir.resolve(mod.fileName)
                    if (oldJar.exists()) oldJar.moveToTrash()

                    val jarPath = modsDir.resolve(resolved.fileName)
                    jarPath.writeBytesAtomic(bytes)

                    val fileHash = ModDatabase.sha1(bytes)
                    ModDatabase(project.projectDir).use { db ->
                        db.recordVersionChange(
                            projectId = modId,
                            oldVersionId = mod.versionId,
                            oldVersionLabel = mod.versionLabel,
                            oldFileHash = mod.fileHash,
                            newVersionId = resolved.plan.versionId,
                            newVersionLabel = resolved.plan.versionLabel
                        )
                        db.install(mod.copy(
                            fileName = resolved.fileName,
                            versionId = resolved.plan.versionId,
                            versionLabel = resolved.plan.versionLabel,
                            fileHash = fileHash,
                            installedAt = Clock.System.now(),
                            requiresManualDownload = resolved.requiresManualDownload,
                        ))
                    }
                    TritiumEventBus.publish(TritiumEvent.ModUpdated(project, modId, mod.displayName, mod.versionId, resolved.plan.versionId))
                    TritiumEventBus.publish(TritiumEvent.ModInstalled(project, modId, mod.modId, mod.displayName, resolved.plan.versionId, resolved.plan.versionLabel))
                } catch (t: Throwable) {
                    logger.warn("Failed to update mod '{}'", mod.displayName, t)
                }
            }
            refreshMods()
        }
    }

    private fun performDowngrade(modId: String) {
        scope.launch {
            val mod = withContext(Dispatchers.IO) {
                ModDatabase(project.projectDir).use { db -> db.getByProjectId(modId) }
            } ?: return@launch

            withContext(Dispatchers.IO) {
                val source = BuiltinRegistries.ModSource.all().find { it.id == mod.source } ?: return@withContext
                val meta = (project as? Project<*>)?.typedMeta as? ModpackMeta ?: return@withContext
                val context = ModBrowserContext(
                    project = project,
                    minecraftVersion = meta.minecraftVersion,
                    modLoaderId = meta.loader
                )

                try {
                    val prev = ModDatabase(project.projectDir).use { it.getPreviousVersion(modId) } ?: return@withContext
                    val fallbacks = BuiltinRegistries.ModSource.all()
                        .filterIsInstance<HashFallbackProvider>()
                        .sortedBy { it.priority }
                    val resolved = resolveInstallDownload(context, source, modId, prev.oldVersionId, fallbacks)
                    if (resolved.downloadUrl == null) {
                        error("Downgrade requires manual download (blocked by mod author)")
                    }
                    val modsDir = project.projectDir.resolve("mods")
                    modsDir.mkdirs()
                    val bytes = httpClient.get(resolved.downloadUrl).bodyAsBytes()
                    val oldJar = modsDir.resolve(mod.fileName)
                    if (oldJar.exists()) oldJar.moveToTrash()

                    val jarPath = modsDir.resolve(resolved.fileName)
                    jarPath.writeBytesAtomic(bytes)

                    val fileHash = ModDatabase.sha1(bytes)
                    ModDatabase(project.projectDir).use { db ->
                        db.recordVersionChange(
                            projectId = modId,
                            oldVersionId = mod.versionId,
                            oldVersionLabel = mod.versionLabel,
                            oldFileHash = mod.fileHash,
                            newVersionId = prev.oldVersionId,
                            newVersionLabel = prev.oldVersionLabel
                        )
                        db.install(mod.copy(
                            fileName = resolved.fileName,
                            versionId = prev.oldVersionId,
                            versionLabel = prev.oldVersionLabel,
                            fileHash = fileHash,
                            installedAt = Clock.System.now(),
                            requiresManualDownload = resolved.requiresManualDownload,
                        ))
                    }
                    TritiumEventBus.publish(TritiumEvent.ModDowngraded(project, modId, mod.displayName, mod.versionId, prev.oldVersionId))
                    TritiumEventBus.publish(TritiumEvent.ModInstalled(project, modId, mod.modId, mod.displayName, prev.oldVersionId, prev.oldVersionLabel))
                } catch (t: Throwable) {
                    logger.warn("Failed to downgrade mod '{}'", mod.displayName, t)
                }
            }
            refreshMods()
        }
    }

    private fun performSkip(modId: String) {
        val updateOption = updateAvailability[modId] ?: return
        scope.launch {
            withContext(Dispatchers.IO) {
                val mod = ModDatabase(project.projectDir).use { db -> db.getByProjectId(modId) }
                if (mod != null) {
                    ModDatabase(project.projectDir).use { db ->
                        db.recordVersionChange(
                            projectId = modId,
                            oldVersionId = mod.versionId,
                            oldVersionLabel = mod.versionLabel,
                            oldFileHash = mod.fileHash,
                            newVersionId = updateOption.id,
                            newVersionLabel = updateOption.label,
                            skipped = true
                        )
                    }
                    TritiumEventBus.publish(TritiumEvent.ModSkipped(project, modId, mod.displayName, updateOption.id, updateOption.label))
                }
            }
            updateAvailability.remove(modId)
            for (i in 0 until listWidget.count()) {
                val item = listWidget.item(i)
                val pid = item?.data(Qt.ItemDataRole.UserRole) as? String ?: continue
                if (pid == modId) {
                    val row = listWidget.itemWidget(item) as? ModListRow ?: continue
                    row.updateUpdateOption(null)
                    break
                }
            }
        }
    }

    private fun performInstallSkipped(modId: String) {
        scope.launch {
            val mod = withContext(Dispatchers.IO) {
                ModDatabase(project.projectDir).use { db -> db.getByProjectId(modId) }
            } ?: return@launch

            val skippedRecord = withContext(Dispatchers.IO) {
                ModDatabase(project.projectDir).use { it.getSkippedVersion(modId, mod.versionId) }
            } ?: return@launch

            withContext(Dispatchers.IO) {
                val source = BuiltinRegistries.ModSource.all().find { it.id == mod.source } ?: return@withContext
                val meta = (project as? Project<*>)?.typedMeta as? ModpackMeta ?: return@withContext
                val context = ModBrowserContext(
                    project = project,
                    minecraftVersion = meta.minecraftVersion,
                    modLoaderId = meta.loader
                )

                try {
                    val fallbacks = BuiltinRegistries.ModSource.all()
                        .filterIsInstance<HashFallbackProvider>()
                        .sortedBy { it.priority }
                    val resolved = resolveInstallDownload(context, source, modId, skippedRecord.newVersionId, fallbacks)
                    if (resolved.downloadUrl == null) {
                        error("Install requires manual download (blocked by mod author)")
                    }
                    val modsDir = project.projectDir.resolve("mods")
                    modsDir.mkdirs()
                    val bytes = httpClient.get(resolved.downloadUrl).bodyAsBytes()
                    val oldJar = modsDir.resolve(mod.fileName)
                    if (oldJar.exists()) oldJar.moveToTrash()

                    val jarPath = modsDir.resolve(resolved.fileName)
                    jarPath.writeBytesAtomic(bytes)

                    val fileHash = ModDatabase.sha1(bytes)
                    ModDatabase(project.projectDir).use { db ->
                        db.recordVersionChange(
                            projectId = modId,
                            oldVersionId = mod.versionId,
                            oldVersionLabel = mod.versionLabel,
                            oldFileHash = mod.fileHash,
                            newVersionId = skippedRecord.newVersionId,
                            newVersionLabel = skippedRecord.newVersionLabel
                        )
                        db.install(mod.copy(
                            fileName = resolved.fileName,
                            versionId = skippedRecord.newVersionId,
                            versionLabel = skippedRecord.newVersionLabel,
                            fileHash = fileHash,
                            installedAt = Clock.System.now(),
                            requiresManualDownload = resolved.requiresManualDownload,
                        ))
                    }
                    TritiumEventBus.publish(TritiumEvent.ModUpdated(project, modId, mod.displayName, mod.versionId, skippedRecord.newVersionId))
                    TritiumEventBus.publish(TritiumEvent.ModInstalled(project, modId, mod.modId, mod.displayName, skippedRecord.newVersionId, skippedRecord.newVersionLabel))
                } catch (t: Throwable) {
                    logger.warn("Failed to install skipped version for mod '{}'", mod.displayName, t)
                }
            }
            refreshMods()
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
                            TritiumEventBus.publish(TritiumEvent.ModUninstalled(project, projectId, mod.modId, mod.displayName))
                        }
                        refreshMods()
                    }
                }
            }
        }
    }

    private fun toggleEnabled(projectId: String) {
        scope.launch {
            var enabled = false
            withContext(Dispatchers.IO) {
                ModDatabase(project.projectDir).use { db ->
                    val mod = db.getByProjectId(projectId) ?: return@use
                    enabled = !mod.enabled
                    db.setEnabled(projectId, enabled)
                }
            }
            TritiumEventBus.publish(TritiumEvent.ModEnabledToggled(project, projectId, enabled))
            refreshMods()
        }
    }

    private fun toggleRelease(projectId: String) {
        scope.launch {
            var excluded = false
            withContext(Dispatchers.IO) {
                ModDatabase(project.projectDir).use { db ->
                    val mod = db.getByProjectId(projectId) ?: return@use
                    if (mod.localOnly) return@use
                    excluded = !mod.excludedFromRelease
                    db.setExcludedFromRelease(projectId, excluded)
                }
            }
            TritiumEventBus.publish(TritiumEvent.ModReleaseToggled(project, projectId, excluded))
            refreshMods()
        }
    }
}

private class ModListRow(
    private val mod: InstalledMod,
    private var updateOption: ModVersionOption? = null,
    private var previousVersion: VersionHistoryRecord? = null,
    private var skippedVersion: VersionHistoryRecord? = null
) : QWidget() {
    val removeRequested = Signal1<String>()
    val enableToggled = Signal1<String>()
    val releaseToggled = Signal1<String>()
    val updateRequested = Signal1<String>()
    val downgradeRequested = Signal1<String>()
    val skipRequested = Signal1<String>()
    val installSkippedRequested = Signal1<String>()
    val iconLabel: QLabel
    private val nameLabel: QLabel
    private val metaLabel: QLabel
    private val menu: QMenu
    private val updateAction: QAction?
    private val downgradeAction: QAction?
    private val skipAction: QAction?
    private val installSkippedAction: QAction?

    override fun sizeHint(): QSize = QSize(200, 54)

    fun updateUpdateOption(option: ModVersionOption?) {
        updateOption = option
        refreshMenuActions()
    }

    fun updatePreviousVersion(prev: VersionHistoryRecord?) {
        previousVersion = prev
        refreshMenuActions()
    }

    fun updateSkippedVersion(version: VersionHistoryRecord?) {
        skippedVersion = version
        refreshMenuActions()
    }

    private fun refreshMenuActions() {
        updateAction?.let {
            if (updateOption != null) {
                it.text = "Update to v${updateOption!!.label}"
                it.enabled = true
                it.toolTip = ""
                it.visible = true
            } else {
                it.text = "Up to date"
                it.enabled = false
                it.toolTip = "No updates available"
                it.visible = true
            }
        }
        downgradeAction?.let {
            if (previousVersion != null) {
                it.text = "Downgrade to v${previousVersion!!.oldVersionLabel}"
                it.enabled = true
                it.toolTip = ""
                it.visible = true
            } else {
                it.visible = false
            }
        }
        skipAction?.let {
            if (updateOption != null) {
                it.text = "Skip v${updateOption!!.label}"
                it.enabled = true
                it.visible = true
            } else {
                it.visible = false
            }
        }
        installSkippedAction?.let {
            if (skippedVersion != null) {
                it.text = "Install Skipped v${skippedVersion!!.newVersionLabel}"
                it.enabled = true
                it.visible = true
            } else {
                it.visible = false
            }
        }
        val hasUpdateIcon = updateOption != null
        if (hasUpdateIcon) {
            nameLabel.objectName = "modListNameUpdate"
        } else if (!mod.enabled) {
            nameLabel.objectName = "modListNameDisabled"
        } else {
            nameLabel.objectName = "modListName"
        }
        nameLabel.style()?.unpolish(nameLabel)
        nameLabel.style()?.polish(nameLabel)
    }

    init {
        objectName = "modListRow"
        val layout = QHBoxLayout(this).apply {
            setContentsMargins(4, 2, 4, 2)
            setSpacing(8)
        }

        val iconFile = mod.iconPath?.takeIf { it.isNotBlank() }?.let { File(it) }
        iconLabel = QLabel().apply {
            setFixedSize(32, 32)
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

        nameLabel = QLabel(mod.displayName).apply {
            val f = QFont(font())
            f.setBold(true)
            font = f
            objectName = "modListName"
        }
        if (updateOption != null) {
            nameLabel.objectName = "modListNameUpdate"
        } else if (!mod.enabled) {
            nameLabel.objectName = "modListNameDisabled"
        }
        val metaText = buildString {
            append(mod.modId)
            if (mod.versionLabel.isNotBlank()) append(" · ${mod.versionLabel}")
            if (mod.side != ModSide.BOTH) append(" · ${mod.side.name}")
            append(" · ${mod.releaseType}")
            if (!mod.enabled) append(" · DISABLED")
            if (mod.excludedFromRelease) append(" · DEV")
            if (mod.localOnly) append(" · LOCAL-ONLY")
        }
        metaLabel = QLabel(metaText).apply {
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

        menu = QMenu(this)

        updateAction = menu.addAction("")?.apply {
            if (updateOption != null) {
                text = "Update to v${updateOption!!.label}"
                enabled = true
            } else {
                text = "Up to date"
                enabled = false
                toolTip = "No updates available"
            }
            triggered.connect { updateRequested.emit(mod.projectId) }
        }

        skipAction = menu.addAction("")?.apply {
            if (updateOption != null) {
                text = "Skip v${updateOption!!.label}"
                visible = true
            } else {
                visible = false
            }
            triggered.connect { skipRequested.emit(mod.projectId) }
        }

        downgradeAction = menu.addAction("")?.apply {
            if (previousVersion != null) {
                text = "Downgrade to v${previousVersion!!.oldVersionLabel}"
                visible = true
            } else {
                visible = false
            }
            triggered.connect { downgradeRequested.emit(mod.projectId) }
        }

        installSkippedAction = menu.addAction("")?.apply {
            if (skippedVersion != null) {
                text = "Install Skipped v${skippedVersion!!.newVersionLabel}"
                visible = true
            } else {
                visible = false
            }
            triggered.connect { installSkippedRequested.emit(mod.projectId) }
        }!!

        menu.addSeparator()

        val enabledLabel = if (mod.enabled) "Disable" else "Enable"
        menu.addAction(enabledLabel)?.let {
            it.triggered.connect { enableToggled.emit(mod.projectId) }
        }

        val releaseLabel = if (mod.excludedFromRelease) "Include in Release" else "Exclude from Release"
        menu.addAction(releaseLabel)?.let { action ->
            if (mod.localOnly) {
                action.enabled = false
                action.toolTip = "This mod is local-only and won't be included in releases"
            }
            action.triggered.connect { releaseToggled.emit(mod.projectId) }
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
            selector("#modListNameUpdate") {
                color(TColors.Green)
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
