/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.ui.project.sidebar

import io.github.tritium_launcher.api.connect
import io.github.tritium_launcher.api.core.project.ProjectBase
import io.github.tritium_launcher.api.docks.DockPanelProvider
import io.github.tritium_launcher.api.docks.DockWidget
import io.github.tritium_launcher.api.fromTR
import io.github.tritium_launcher.api.io.VPath
import io.github.tritium_launcher.api.logger
import io.github.tritium_launcher.api.runOnGuiThread
import io.github.tritium_launcher.launcher.asAlignment
import io.github.tritium_launcher.launcher.companion.CapturedItem
import io.github.tritium_launcher.launcher.platform.CompanionBridge
import io.github.tritium_launcher.launcher.registrydb.RegistryDatabase
import io.github.tritium_launcher.launcher.registrydb.RegistryDbStatus
import io.github.tritium_launcher.launcher.ui.project.editor.lsp.ItemPreviewWidget
import io.github.tritium_launcher.launcher.ui.theme.TIcons
import io.github.tritium_launcher.launcher.ui.theme.qt.icon
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.hBoxLayout
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.qWidget
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.vBoxLayout
import io.qt.NonNull
import io.qt.core.*
import io.qt.gui.QFont
import io.qt.gui.QIcon
import io.qt.gui.QPixmap
import io.qt.widgets.*
import kotlinx.coroutines.*
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.max

private const val GRID_CELL_SIZE = 28
private const val GRID_SPACING = 2
private const val GRID_ROWS = 2

//TODO: Either remove for finish by 0.1.7 release
class ItemInspectorDockPanelProvider : DockPanelProvider {
    override val id: String = "item_inspector"
    override val displayName: String = "Item Inspector"
    override var icon: QIcon? = TIcons.ItemBrowser.icon
    override val order: Int = 16
    override val closeable: Boolean = false
    override val floatable: Boolean = false
    override val preferredArea: Qt.DockWidgetArea = Qt.DockWidgetArea.LeftDockWidgetArea
    override val allowSplit: Boolean = false

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val persistenceFile by lazy { fromTR("settings", "captured_items.json") }
    }

    override fun create(project: ProjectBase): DockWidget {
        val dock = DockWidget(displayName, null).apply {
            minimumWidth = 280
        }
        val panel = ItemInspectorPanel(project, dock)
        dock.setWidget(panel.root)
        dock.destroyed.connect(QMetaObject.Slot1<QObject?> { panel.cleanup() })
        return dock
    }

    private class ItemInspectorPanel(
        private val project: ProjectBase,
        private val dock: DockWidget
    ) {
        private val log = logger()
        private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        private var items = loadPersisted().toMutableList()
        private var selectedIndex = -1
        private var gridColumns = 1
        private var gridButtons = mutableListOf<QPushButton>()

        val root: QWidget
        private val detailScroll: QScrollArea
        private val detailContent: QWidget
        private val iconLabel: QLabel
        private val nameLabel: QLabel
        private val componentContainer: QWidget
        private val kubejsContainer: QWidget
        private val gridWidget: QWidget
        private val gridLayout: QGridLayout
        private val emptyLabel: QLabel
        private val bottomSpacer: QWidget

        init {
            CompanionBridge.ensureConnected()
            val captureEventJob = scope.launch {
                CompanionBridge.events.collect { response ->
                    if (response.action == "captured_item") {
                        val id = response.data["id"]?.jsonPrimitive?.contentOrNull ?: return@collect
                        val count = response.data["count"]?.jsonPrimitive?.intOrNull ?: 1
                        val displayName = response.data["displayName"]?.jsonPrimitive?.contentOrNull ?: id
                        val nbtFormatted = response.data["nbtFormatted"]?.jsonPrimitive?.contentOrNull ?: ""
                        val item = CapturedItem(id, count, displayName, nbtFormatted)
                        items.add(0, item)
                        persist()
                        runOnGuiThread { rebuildGrid() }
                    }
                }
            }
            dock.destroyed.connect(QMetaObject.Slot1<QObject?> { captureEventJob.cancel() })

            iconLabel = QLabel().apply {
                setFixedSize(32, 32)
            }
            nameLabel = QLabel().apply {
                wordWrap = true
            }

            val header = qWidget {
                setLayout(hBoxLayout {
                    addWidget(iconLabel)
                    addWidget(nameLabel)
                })
            }

            componentContainer = QWidget().apply {
                setSizePolicy(QSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Preferred))
                setLayout(vBoxLayout())
            }

            kubejsContainer = QWidget().apply {
                setSizePolicy(QSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Preferred))
                setLayout(vBoxLayout())
            }

            detailContent = qWidget {
                setSizePolicy(QSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Preferred))
                setLayout(vBoxLayout {
                    addWidget(header)
                    addWidget(componentContainer)
                    addWidget(kubejsContainer)
                    addStretch()
                    sizeConstraint = QLayout.SizeConstraint.SetMinimumSize
                })
            }

            detailScroll = QScrollArea().apply {
                setWidget(detailContent)
                setWidgetResizable(true)
                horizontalScrollBarPolicy = Qt.ScrollBarPolicy.ScrollBarAlwaysOff
                visible = false
                setSizePolicy(QSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Expanding))
            }

            emptyLabel = QLabel("No items captured.\nPress Shift+T while hovering an item in a container.\n\nCaptured items persist between sessions.").apply {
                alignment = Qt.AlignmentFlag.AlignCenter.asAlignment()
                wordWrap = true
                setStyleSheet("color: #888; padding: 20px;")
            }

            gridWidget = QWidget()
            gridLayout = QGridLayout(gridWidget).apply {
                setContentsMargins(2, 2, 2, 2)
                setHorizontalSpacing(GRID_SPACING)
                setVerticalSpacing(GRID_SPACING)
            }

            bottomSpacer = QWidget().apply {
                setSizePolicy(QSizePolicy(QSizePolicy.Policy.Preferred, QSizePolicy.Policy.Expanding))
                visible = false
            }

            root = QWidget().apply {
                installEventFilter(object : QObject() {
                    override fun eventFilter(obj: QObject?, event: QEvent?): Boolean {
                        if (event?.type() == QEvent.Type.Resize) {
                            scheduleGridResize()
                        }
                        return super.eventFilter(obj, event)
                    }
                })
                setLayout(vBoxLayout {
                    addWidget(detailScroll, 1)
                    addWidget(bottomSpacer)
                    addWidget(emptyLabel)
                    addWidget(gridWidget)
                })
            }

            rebuildGrid()
        }

        private var resizeDebounce: QTimer? = null

        private fun scheduleGridResize() {
            if (resizeDebounce == null) {
                resizeDebounce = QTimer(root).apply {
                    isSingleShot = true
                    interval = 50
                    timeout.connect {
                        updateGridGeometry()
                    }
                }
            }
            resizeDebounce?.start()
        }

        private fun updateGridGeometry(): Boolean {
            val availableWidth = gridWidget.width() - 4
            val newColumns = max(1, (availableWidth + GRID_SPACING) / (GRID_CELL_SIZE + GRID_SPACING))
            if (newColumns != gridColumns) {
                gridColumns = newColumns
                rebuildGrid()
                return true
            }
            return false
        }

        private fun rebuildGrid() {
            if (selectedIndex >= items.size) selectedIndex = -1

            val dir = snapshotDir()
            val maxItems = gridColumns * GRID_ROWS

            for (btn in gridButtons) {
                gridLayout.removeWidget(btn)
                btn.disposeLater()
            }
            gridButtons.clear()

            val shown = items.take(maxItems)
            for ((i, item) in shown.withIndex()) {
                val itemIndex = items.indexOf(item)
                val btn = QPushButton(gridWidget).apply {
                    objectName = "inspectorGridSlot"
                    minimumSize = QSize(GRID_CELL_SIZE, GRID_CELL_SIZE)
                    maximumSize = QSize(GRID_CELL_SIZE, GRID_CELL_SIZE)
                    toolTip = "${item.displayName} x${item.count}\n${item.id}"
                    isCheckable = true
                    isChecked = itemIndex == selectedIndex
                    clicked.connect(QMetaObject.Slot0 {
                        onSlotClicked(itemIndex)
                    })
                    setContextMenuPolicy(Qt.ContextMenuPolicy.CustomContextMenu)
                    customContextMenuRequested.connect { pos ->
                        showSlotContextMenu(itemIndex, mapToGlobal(pos))
                    }
                }

                val iconPixmap = if (dir != null) {
                    ItemPreviewWidget.loadItemIcon(item.id, null, dir, GRID_CELL_SIZE)
                } else null
                if (iconPixmap != null) {
                    btn.icon = QIcon(iconPixmap)
                    btn.iconSize = QSize(GRID_CELL_SIZE - 4, GRID_CELL_SIZE - 4)
                }

                gridButtons.add(btn)
                gridLayout.addWidget(btn, i / gridColumns, i % gridColumns)
            }

            gridWidget.visible = items.isNotEmpty()
            emptyLabel.visible = items.isEmpty()
        }

        private fun onSlotClicked(index: Int) {
            if (index == selectedIndex) return
            selectedIndex = index
            showDetail(index)
            rebuildGrid()
        }

        private fun showSlotContextMenu(index: Int, globalPos: QPoint) {
            val menu = QMenu()
            menu.addAction("Remove")?.triggered?.connect(QMetaObject.Slot1<Boolean> {
                items.removeAt(index)
                persist()
                if (selectedIndex == index) {
                    selectedIndex = -1
                    detailScroll.visible = false
                } else if (selectedIndex > index) {
                    selectedIndex--
                }
                rebuildGrid()
            })
            menu.popup(globalPos)
        }

        private fun showDetail(index: Int) {
            val visible = index >= 0 && index < items.size
            detailScroll.visible = visible
            bottomSpacer.visible = !visible && items.isNotEmpty()
            emptyLabel.visible = items.isEmpty()

            if (!visible) return

            val item = items[index]
            val dir = snapshotDir()
            val iconPixmap = if (dir != null) {
                ItemPreviewWidget.loadItemIcon(item.id, null, dir, 32)
            } else null
            if (iconPixmap != null) iconLabel.pixmap = QPixmap(iconPixmap)
            nameLabel.text = "${item.displayName}  x${item.count}"

            rebuildComponentSections(item)
            rebuildKubejsSections(item)

            detailScroll.verticalScrollBar()?.value = 0
        }

        private fun rebuildComponentSections(item: CapturedItem) {
            val layout = componentContainer.layout() as QVBoxLayout
            clearLayout(layout)
            val components = parseComponents(item.nbtFormatted)
                .sortedBy { it.second.length }
            for ((key, value) in components) {
                layout.addWidget(createComponentGroup(key, value))
            }
        }

        private fun parseComponents(nbtFormatted: String): List<Pair<String, String>> {
            val result = mutableListOf<Pair<String, String>>()
            val lines = nbtFormatted.lines()
            val keyRegex = Regex("^  ([a-z_][a-z0-9_]*:[a-zA-Z0-9_./-]+): (.*)")
            var currentKey: String? = null
            val currentValue = StringBuilder()
            for (line in lines) {
                val match = keyRegex.matchEntire(line)
                if (match != null) {
                    if (currentKey != null) {
                        result.add(currentKey to currentValue.toString().trimEnd())
                    }
                    currentKey = match.groupValues[1]
                    currentValue.clear()
                    currentValue.append(match.groupValues[2])
                } else if (currentKey != null) {
                    if (currentValue.isNotEmpty()) currentValue.append("\n")
                    currentValue.append(line)
                }
            }
            if (currentKey != null) {
                result.add(currentKey to currentValue.toString().trimEnd())
            }
            return result
        }

        private fun createComponentGroup(key: String, value: String): QGroupBox {
            val content = object : QLabel(value) {
                override fun minimumSizeHint(): @NonNull QSize = QSize(0, super.minimumSizeHint.height())
                override fun sizeHint(): @NonNull QSize = QSize(0, super.heightForWidth(width))
            }.apply {
                wordWrap = true
                setFont(QFont("monospace", 10))
                setStyleSheet("background-color: #1e1e1e; color: #d4d4d4; padding: 6px; border: none;")
                setTextInteractionFlags(Qt.TextInteractionFlag.TextSelectableByMouse)
                alignment = Qt.AlignmentFlag.AlignTop.asAlignment()

            }
            val isLong = value.count { it == '\n' } > 5 || value.length > 300
            return QGroupBox(key).apply {
                isCheckable = true
                setFlat(true)
                setMinimumWidth(0)
                setSizePolicy(QSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Preferred))
                setLayout(QVBoxLayout().apply {
                    setContentsMargins(0, 0, 0, 0)
                    addWidget(content)
                })
                content.visible = !isLong
                isChecked = !isLong
                toggled.connect { checked -> content.visible = checked }
            }
        }

        private fun rebuildKubejsSections(item: CapturedItem) {
            val layout = kubejsContainer.layout() as QVBoxLayout
            clearLayout(layout)

            layout.addWidget(QLabel("KubeJS Snippets").apply {
                setStyleSheet("font-weight: bold; margin-top: 8px;")
            })

            layout.addWidget(createSnippetRow(
                "Item.of('${item.id}')",
                "Item.of('${item.id}')"
            ))
            layout.addWidget(createSnippetRow(
                "Item.of('${item.id}', '{{nbt}}')",
                "Item.of('${item.id}', '${escapeForSnippet(item.nbtFormatted)}')"
            ))
        }

        private fun createSnippetRow(displayText: String, copyText: String): QWidget {
            return qWidget {
                setLayout(hBoxLayout {
                    addWidget(QLabel(displayText).apply {
                        setFont(QFont("monospace", 10))
                        setStyleSheet("background-color: #1e1e1e; color: #d4d4d4; padding: 4px;")
                        setTextInteractionFlags(Qt.TextInteractionFlag.TextSelectableByMouse)
                    }, 1)
                    addWidget(QPushButton("Copy").apply {
                        clicked.connect(QMetaObject.Slot0 {
                            QApplication.clipboard()?.setText(copyText)
                        })
                    })
                })
            }
        }

        private fun clearLayout(layout: QVBoxLayout) {
            while (layout.count() > 0) {
                val item = layout.takeAt(0)
                item?.widget()?.let {
                    layout.removeWidget(it)
                    it.disposeLater()
                }
            }
        }

        private fun snapshotDir(): VPath? = runCatching<VPath?> {
            val status = RegistryDatabase.status(project)
            if (status is RegistryDbStatus.Ready) {
                status.manifestPath.parent()
            } else null
        }.getOrNull()

        private fun escapeForSnippet(nbt: String): String {
            return nbt.replace("\\", "\\\\").replace("'", "\\'")
        }

        private fun persist() {
            runCatching {
                val data = json.encodeToString(ListSerializer(CapturedItem.serializer()), items.toList())
                persistenceFile.parent().mkdirs()
                persistenceFile.writeTextAtomic(data)
            }
        }

        private fun loadPersisted(): List<CapturedItem> {
            return runCatching {
                val raw = persistenceFile.readTextOrNull()
                if (raw != null) {
                    json.decodeFromString(ListSerializer(CapturedItem.serializer()), raw)
                } else emptyList()
            }.getOrElse {
                log.warn("Failed to load captured items", it)
                emptyList()
            }
        }

        fun cleanup() {
            scope.cancel()
        }
    }
}
