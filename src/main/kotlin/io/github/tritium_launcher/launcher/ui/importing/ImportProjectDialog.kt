package io.github.tritium_launcher.launcher.ui.importing

import io.github.tritium_launcher.launcher.connect
import io.github.tritium_launcher.launcher.core.project.ProjectBase
import io.github.tritium_launcher.launcher.importing.DetectedInstance
import io.github.tritium_launcher.launcher.importing.KnownLauncher
import io.github.tritium_launcher.launcher.importing.LauncherDetector
import io.github.tritium_launcher.launcher.io.VPath
import io.github.tritium_launcher.launcher.loadScaledPixmap
import io.github.tritium_launcher.launcher.qs
import io.github.tritium_launcher.launcher.ui.project.editor.file.FileTypeDescriptor
import io.github.tritium_launcher.launcher.ui.theme.TColors
import io.github.tritium_launcher.launcher.ui.theme.TIcons
import io.github.tritium_launcher.launcher.ui.theme.qt.icon
import io.github.tritium_launcher.launcher.ui.theme.qt.setThemedStyle
import io.github.tritium_launcher.launcher.ui.widgets.TPushButton
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.*
import io.github.tritium_launcher.launcher.userHome
import io.qt.core.QTimer
import io.qt.core.Qt
import io.qt.gui.QCursor
import io.qt.gui.QIcon
import io.qt.gui.QPixmap
import io.qt.widgets.*
import kotlinx.serialization.json.JsonObject

class ImportProjectDialog(parent: QWidget? = null) : QDialog(parent) {
    private val stacked = QStackedWidget()
    private val pageSelect = QWidget()
    private val pageReview = QWidget()

    // Import Sources
    private val launcherScroll = QScrollArea()
    private val launcherCards = mutableListOf<LauncherCard>()
    private var selectedCard: LauncherCard? = null
    private val instanceList = QListWidget()
    private val instanceListStack = QStackedWidget()
    private val instanceListPlaceholder = QLabel("Select a launcher to see instances.")

    // Instance Info and File Tree
    private val instanceIconLabel = QLabel()
    private val instanceNameLabel = QLabel()
    private val instanceGameVerLabel = QLabel()
    private val instanceLoaderLabel = QLabel()
    private val instanceLoaderVerLabel = QLabel()
    private val fileTree = QTreeWidget()
    private val fileTreeStack = QStackedWidget()
    private val fileTreeLoading = QLabel("Scanning files...")
    private val backBtn = TPushButton().apply { text = "Back" }
    private val importBtn = TPushButton().apply { text = "Import" }

    private val detectedLaunchers = mutableListOf<KnownLauncher>()
    private var currentLauncher: KnownLauncher? = null
    private var currentInstance: DetectedInstance? = null
    private var instances: List<DetectedInstance> = emptyList()

    private val dummyProject = ProjectBase("dummy", VPath.get("/tmp"), "dummy", "", JsonObject(emptyMap()))

    companion object {
        private val expandedState = mutableMapOf<String, Set<String>>()
    }

    private inner class LauncherCard(val launcher: KnownLauncher) : QFrame() {
        private val iconLabel = QLabel()
        val nameLabel = QLabel()
        val subtitleLabel = QLabel()
        var onClick: ((KnownLauncher) -> Unit)? = null

        init {
            objectName = "launcherCard"
            setFixedHeight(48)
            cursor = QCursor(Qt.CursorShape.PointingHandCursor)

            val layout = hBoxLayout(this) {
                setContentsMargins(8, 0, 8, 0)
                setSpacing(8)
            }

            iconLabel.setFixedSize(32, 32)
            layout.addWidget(iconLabel, 0)

            val textCol = QWidget()
            val textLayout = vBoxLayout(textCol) {
                setContentsMargins(0, 0, 0, 0)
                setSpacing(1)
            }
            nameLabel.objectName = "launcherCardName"
            textLayout.addWidget(nameLabel)
            subtitleLabel.objectName = "launcherCardSub"
            textLayout.addWidget(subtitleLabel)
            layout.addWidget(textCol, 1)
        }

        override fun mouseReleaseEvent(ev: io.qt.gui.QMouseEvent?) {
            if (ev?.button() == Qt.MouseButton.LeftButton) {
                onClick?.invoke(launcher)
            }
        }

        fun setSelected(sel: Boolean) {
            setProperty("selected", sel)
            style()?.unpolish(this)
            style()?.polish(this)
            update()
        }

        fun setIcon(pixmap: QPixmap) {
            iconLabel.pixmap = loadScaledPixmap(pixmap.toImage(), qs(32, 32), this)
        }
    }

    init {
        windowTitle = "Import Project"
        minimumSize = qs(800, 520)
        objectName = "ImportDialog"

        buildPageSelect()
        buildPageReview()
        stacked.addWidget(pageSelect)
        stacked.addWidget(pageReview)

        hBoxLayout(this) {
            setContentsMargins(0, 0, 0, 0)
            addWidget(stacked)
        }

        connectSignals()
        populateLaunchers()
        applyStyles()
    }

    private fun applyStyles() {
        setThemedStyle {
            selector("#ImportDialog") {
                backgroundColor(TColors.Surface0)
            }
            selector("#instanceList") {
                border()
                background("transparent")
                padding(4)
            }
            selector("QListView::item") {
                border()
                borderRadius(6)
                background("transparent")
                color(TColors.Text)
                padding(4, 8, 4, 8)
            }
            selector("QListView::item:selected") {
                backgroundColor(TColors.SelectedUI)
                color(TColors.SelectedText)
            }
            selector("QListView::item:hover") {
                backgroundColor(TColors.Surface2)
            }
            selector("QFrame#launcherCard") {
                border(1, TColors.Surface1)
                borderRadius(8)
                background("transparent")
                padding(0)
            }
            selector("QFrame#launcherCard:hover") {
                backgroundColor(TColors.Surface1)
            }
            selector("QFrame#launcherCard[selected=\"true\"]") {
                backgroundColor(TColors.SelectedUI)
                border(1, TColors.Accent)
            }
            selector("QLabel#launcherCardName") {
                fontSize(13)
                fontWeight(600)
                color(TColors.Text)
            }
            selector("QLabel#launcherCardSub") {
                fontSize(10)
                color(TColors.Subtext)
            }
            selector("#instanceInfoPanel") {
                backgroundColor(TColors.Surface1)
                border(color = TColors.Surface2, direction = "right")
                padding(12)
            }
            selector("QLabel#instanceName") {
                fontSize(14)
                fontWeight(700)
                color(TColors.Text)
            }
            selector("QLabel#instanceMeta") {
                fontSize(11)
                color(TColors.Subtext)
            }
            selector("QTreeWidget#fileTree") {
                border()
                background("transparent")
                color(TColors.Text)
            }
            selector("QTreeWidget#fileTree::item") {
                padding(2, 4)
                color(TColors.Text)
            }
            selector("QTreeWidget#fileTree::item:selected") {
                backgroundColor(TColors.SelectedUI)
                color(TColors.SelectedText)
            }
            selector("QTreeWidget#fileTree::item:hover") {
                backgroundColor(TColors.Surface2)
            }
            selector("#importFooter") {
                backgroundColor(TColors.Surface0)
                padding(8, 12)
            }
            selector("#sidebar") {
                backgroundColor(TColors.Surface1)
            }
            selector("QScrollArea#launcherScroll") {
                background("transparent")
            }
            selector("QScrollArea#launcherScroll > QWidget") {
                backgroundColor("transparent")
            }

            selector("#sectionHeader") {
                color(TColors.Subtext)
                fontSize(12)
                fontWeight(700)
                padding(8, 12)
            }
            selector("#treeHeader") {
                color(TColors.Subtext)
                fontSize(10)
                padding(8, 12)
            }
            selector("#emptyHint") {
                color(TColors.Subtext)
            }
        }
    }

    private fun buildPageSelect() {
        hBoxLayout(pageSelect) {
            setContentsMargins(0, 0, 0, 0)

            val sidebar = frame {
                objectName = "sidebar"
                minimumWidth = 200
                maximumWidth = 240
            }
            vBoxLayout(sidebar) {
                setContentsMargins(0, 0, 0, 0)
                setSpacing(0)

                addWidget(label("Import from...") { objectName = "sectionHeader"; setAlignment(Qt.AlignmentFlag.AlignCenter) })

                launcherScroll.apply {
                    widgetResizable = true
                    frameShape = QFrame.Shape.NoFrame
                    objectName = "launcherScroll"
                    verticalScrollBarPolicy = Qt.ScrollBarPolicy.ScrollBarAlwaysOff
                    horizontalScrollBarPolicy = Qt.ScrollBarPolicy.ScrollBarAlwaysOff
                }

                val scrollContent = QWidget()
                vBoxLayout(scrollContent) {
                    setContentsMargins(8, 8, 8, 8)
                    setSpacing(4)
                }
                launcherScroll.setWidget(scrollContent)
                launcherScroll.viewport()?.autoFillBackground = false
                scrollContent.autoFillBackground = false
                addWidget(launcherScroll, 1)
            }
            addWidget(sidebar, 0)

            val rightSide = qWidget()
            vBoxLayout(rightSide) {
                addWidget(label("Instances") { objectName = "sectionHeader" })

                instanceListPlaceholder.objectName = "emptyHint"
                instanceListPlaceholder.setAlignment(Qt.AlignmentFlag.AlignCenter)

                instanceList.apply {
                    objectName = "instanceList"
                    selectionMode = QAbstractItemView.SelectionMode.SingleSelection
                    iconSize = qs(32, 32)
                    frameShape = QFrame.Shape.NoFrame
                    spacing = 0
                    verticalScrollBarPolicy = Qt.ScrollBarPolicy.ScrollBarAlwaysOff
                }

                instanceListStack.apply {
                    addWidget(instanceListPlaceholder)
                    addWidget(instanceList)
                    currentIndex = 0
                }
                addWidget(instanceListStack, 1)
            }
            addWidget(rightSide, 1)
        }
    }

    private fun buildPageReview() {
        hBoxLayout(pageReview) {
            val infoPanel = widget {
                objectName = "instanceInfoPanel"
                minimumWidth = 220
                maximumWidth = 280
            }
            vBoxLayout(infoPanel) {
                setContentsMargins(12, 12, 12, 12)
                setSpacing(8)

                instanceIconLabel.apply {
                    setFixedSize(48, 48)
                    setAlignment(Qt.AlignmentFlag.AlignCenter)
                }
                addWidget(instanceIconLabel, 0, Qt.AlignmentFlag.AlignCenter)

                instanceNameLabel.apply { objectName = "instanceName"; wordWrap = true }
                addWidget(instanceNameLabel, 0, Qt.AlignmentFlag.AlignCenter)

                fun metaRow(key: String, label: QLabel): QWidget {
                    val row = qWidget()
                    hBoxLayout(row) {
                        setContentsMargins(0, 0, 0, 0)
                        setSpacing(8)
                        addWidget(label("$key:") {
                            styleSheet = "color: ${TColors.Subtext}; font-weight: bold;"
                            setFixedWidth(80)
                        })
                        addWidget(label.apply { objectName = "instanceMeta"; wordWrap = true }, 1)
                    }
                    return row
                }

                addWidget(metaRow("Game", instanceGameVerLabel))
                addWidget(metaRow("Loader", instanceLoaderLabel))
                addWidget(metaRow("Version", instanceLoaderVerLabel))
                addStretch(1)
            }
            addWidget(infoPanel, 0)

            val rightPanel = qWidget()
            vBoxLayout(rightPanel) {
                addWidget(label("Select files to import:") { objectName = "treeHeader" })

                fileTree.apply {
                    objectName = "fileTree"
                    header()?.isVisible = false
                    rootIsDecorated = true
                    animated = true
                    indentation = 16
                }
                fileTreeLoading.setAlignment(Qt.AlignmentFlag.AlignCenter)
                fileTreeLoading.objectName = "emptyHint"

                fileTreeStack.apply {
                    addWidget(fileTreeLoading)
                    addWidget(fileTree)
                    currentIndex = 0
                }
                addWidget(fileTreeStack, 1)

                val footer = widget { objectName = "importFooter" }
                hBoxLayout(footer) {
                    addStretch(1)
                    addWidget(backBtn.apply { minimumHeight = 36 })
                    addWidget(importBtn.apply { minimumHeight = 36 })
                }
                addWidget(footer, 0)
            }
            addWidget(rightPanel, 1)
        }
    }

    private fun connectSignals() {
        instanceList.itemDoubleClicked.connect {
            val idx = instanceList.row(it)
            if (idx >= 0 && idx < instances.size) onInstanceSelected(instances[idx])
        }

        backBtn.clicked.connect {
            currentInstance?.let { saveExpandedState(it) }
            stacked.currentIndex = 0
            currentInstance = null
        }

        importBtn.clicked.connect { onImport() }
    }

    private fun populateLaunchers() {
        detectedLaunchers.clear()
        launcherCards.clear()
        selectedCard = null

        val content = launcherScroll.widget()
        val layout = content?.layout() as? QVBoxLayout ?: return

        detectedLaunchers.addAll(LauncherDetector.detectInstalled().sortedBy { it.displayName })

        for (launcher in detectedLaunchers) {
            val card = createLauncherCard(launcher, withSubtitle = true)
            card.onClick = { l ->
                selectCard(card)
                onLauncherSelected(l)
            }
            layout.addWidget(card)
            launcherCards.add(card)
        }

        val browseCard = LauncherCard(KnownLauncher.BROWSE_FOLDER).apply {
            setIcon(iconForLauncher(KnownLauncher.BROWSE_FOLDER, 32))
            nameLabel.text = "Existing Project"
            subtitleLabel.text = "Select a folder..."
            onClick = {
                selectCard(this)
                openBrowseDialog()
            }
        }
        layout.addWidget(browseCard)
        launcherCards.add(browseCard)

        layout.addStretch(1)

        if (detectedLaunchers.isNotEmpty() && launcherCards.isNotEmpty()) {
            val first = launcherCards[0]
            selectCard(first)
            onLauncherSelected(first.launcher)
        }
    }

    private fun createLauncherCard(launcher: KnownLauncher, withSubtitle: Boolean = false): LauncherCard {
        val card = LauncherCard(launcher)
        card.setIcon(iconForLauncher(launcher, 32))
        card.nameLabel.text = launcher.displayName
        if (withSubtitle) {
            val existingDirs = launcher.instanceDirs.count { it.exists() }
            card.subtitleLabel.text = "$existingDirs location${if (existingDirs != 1) "s" else ""}"
        }
        return card
    }

    private fun selectCard(card: LauncherCard) {
        selectedCard?.setSelected(false)
        card.setSelected(true)
        selectedCard = card
    }

    private fun onLauncherSelected(launcher: KnownLauncher) {
        if (launcher.id == "_browse") { openBrowseDialog(); return }
        currentLauncher = launcher

        val items = LauncherDetector.scanInstances(launcher).sortedWith(compareBy({ it.name.firstOrNull()?.isLetter() != true }, { it.name.lowercase() }))
        instances = items
        instanceList.clear()

        if (items.isEmpty()) {
            instanceListPlaceholder.text = "No instances found for ${launcher.displayName}."
            instanceListStack.currentIndex = 0
            return
        }

        for (i in items) {
            val iconPath = LauncherDetector.resolveInstanceIcon(i)
            val listIcon = if (iconPath != null) {
                QIcon(QPixmap(iconPath.toAbsolute().toString()).scaled(qs(32, 32), Qt.AspectRatioMode.KeepAspectRatio, Qt.TransformationMode.SmoothTransformation))
            } else QIcon()
            instanceList.addItem(QListWidgetItem(listIcon, i.name).apply {
                setSizeHint(qs(0, 40))
            })
        }
        instanceListStack.currentIndex = 1
    }

    private fun openBrowseDialog() {
        val prev = currentLauncher
        val chosen = QFileDialog.getExistingDirectory(this, "Select Instance Directory", userHome.toString())
        if (chosen.isNullOrBlank()) { restoreSelection(prev); return }

        val dir = VPath.get(chosen)
        val instance = LauncherDetector.inspectDirectory(dir)
        if (instance != null) {
            onInstanceSelected(instance)
        } else {
            QMessageBox.warning(this, "Invalid Directory", "Selected directory does not contain a recognizable instance.")
            restoreSelection(prev)
        }
    }

    private fun restoreSelection(launcher: KnownLauncher?) {
        val card = launcherCards.firstOrNull { it.launcher.id == launcher?.id }
        if (card != null) {
            selectCard(card)
            onLauncherSelected(launcher!!)
        } else {
            selectedCard?.setSelected(false)
            selectedCard = null
        }
    }

    private fun onInstanceSelected(instance: DetectedInstance) {
        currentInstance?.let { saveExpandedState(it) }
        currentInstance = instance
        instanceNameLabel.text = instance.name
        instanceGameVerLabel.text = instance.gameVersion ?: "Unknown"
        instanceLoaderLabel.text = instance.loader ?: "Unknown"
        instanceLoaderVerLabel.text = instance.loaderVersion ?: "Unknown"

        val iconPath = LauncherDetector.resolveInstanceIcon(instance)
        val pixmap = if (iconPath != null) QPixmap(iconPath.toAbsolute().toString()) else QPixmap()
        if (!pixmap.isNull) {
            instanceIconLabel.pixmap = pixmap.scaled(qs(48, 48), Qt.AspectRatioMode.KeepAspectRatio, Qt.TransformationMode.SmoothTransformation)
        } else {
            instanceIconLabel.pixmap = QPixmap()
            instanceIconLabel.text = instance.name.take(2).uppercase()
        }

        stacked.currentIndex = 1
        populateFileTreeAsync(instance)
    }

    private fun saveExpandedState(instance: DetectedInstance) {
        val path = instance.minecraftDir.toAbsolute().toString()
        val expanded = mutableSetOf<String>()
        fun walk(item: QTreeWidgetItem) {
            val data = item.data(0, Qt.ItemDataRole.UserRole) as? String
            if (data != null && item.isExpanded) expanded.add(data)
            for (i in 0 until item.childCount()) {
                val child = item.child(i) ?: continue
                walk(child)
            }
        }
        val root = fileTree.invisibleRootItem() ?: return
        for (i in 0 until root.childCount()) {
            val child = root.child(i) ?: continue
            walk(child)
        }
        expandedState[path] = expanded
    }

    private fun restoreExpandedState(instancePath: String) {
        val saved = expandedState[instancePath] ?: return
        fun walk(item: QTreeWidgetItem) {
            val data = item.data(0, Qt.ItemDataRole.UserRole) as? String
            if (data != null && data in saved) item.isExpanded = true
            for (i in 0 until item.childCount()) {
                val child = item.child(i) ?: continue
                walk(child)
            }
        }
        val root = fileTree.invisibleRootItem() ?: return
        for (i in 0 until root.childCount()) {
            val child = root.child(i) ?: continue
            walk(child)
        }
    }

    private fun populateFileTreeAsync(instance: DetectedInstance) {
        fileTree.clear()
        fileTreeStack.currentIndex = 0
        importBtn.isEnabled = false

        if (!instance.minecraftDir.exists()) {
            fileTreeLoading.text = "Minecraft directory not found."
            return
        }

        QTimer.singleShot(0) {
            try {
                fileTree.blockSignals(true)
                val root = QTreeWidgetItem(fileTree)
                val instancePath = instance.minecraftDir.toAbsolute().toString()
                root.setText(0, instance.minecraftDir.fileName())
                root.setIcon(0, QIcon(TIcons.Folder))
                root.setFlags(Qt.ItemFlag.ItemIsEnabled, Qt.ItemFlag.ItemIsSelectable, Qt.ItemFlag.ItemIsUserCheckable, Qt.ItemFlag.ItemIsAutoTristate)
                root.setCheckState(0, Qt.CheckState.Checked)
                root.setData(0, Qt.ItemDataRole.UserRole, instancePath)
                populateTreeItems(root, instance.minecraftDir)
                if (expandedState.containsKey(instancePath)) {
                    restoreExpandedState(instancePath)
                } else {
                    root.isExpanded = true
                }
                fileTree.blockSignals(false)
                fileTreeStack.currentIndex = 1
                importBtn.isEnabled = true
            } catch (_: Exception) {
                fileTreeLoading.text = "Failed to read directory."
            }
        }
    }

    private fun populateTreeItems(parentItem: QTreeWidgetItem, dir: VPath) {
        val entries = dir.list()
            .sortedWith(compareBy<VPath> { !it.isDir() }.thenBy { it.fileName().lowercase() })
        for (entry in entries) {
            val isDir = entry.isDir()
            val item = QTreeWidgetItem(parentItem)
            item.setText(0, entry.fileName())
            if (isDir) {
                item.setFlags(Qt.ItemFlag.ItemIsEnabled, Qt.ItemFlag.ItemIsSelectable, Qt.ItemFlag.ItemIsUserCheckable, Qt.ItemFlag.ItemIsAutoTristate)
                item.setIcon(0, QIcon(TIcons.Folder))
                item.setChildIndicatorPolicy(QTreeWidgetItem.ChildIndicatorPolicy.ShowIndicator)
                populateTreeItems(item, entry)
            } else {
                item.setFlags(Qt.ItemFlag.ItemIsEnabled, Qt.ItemFlag.ItemIsSelectable, Qt.ItemFlag.ItemIsUserCheckable)
                item.setIcon(0, iconForFile(entry))
            }
            item.setCheckState(0, Qt.CheckState.Checked)
            item.setData(0, Qt.ItemDataRole.UserRole, entry.toString())
        }
    }

    private fun onImport() {
        val instance = currentInstance ?: return
        expandedState.clear()
        QMessageBox.information(this, "Import", "Import of '${instance.name}' will be implemented next.")
        accept()
    }

    private fun iconForLauncher(launcher: KnownLauncher, size: Int): QPixmap {
        val icon = launcher.icon
        return icon.pixmap(size, size)
    }

    private fun iconForFile(path: VPath): QIcon {
        val descriptor = FileTypeDescriptor.primary(path, dummyProject)
        return descriptor?.icon ?: TIcons.File.icon
    }
}
