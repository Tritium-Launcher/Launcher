package io.github.tritium_launcher.launcher.ui.project.editor

import io.github.tritium_launcher.launcher.connect
import io.github.tritium_launcher.launcher.core.project.ProjectBase
import io.github.tritium_launcher.launcher.extension.core.BuiltinRegistries
import io.github.tritium_launcher.launcher.extension.core.CoreSettingKeys
import io.github.tritium_launcher.launcher.extension.core.CoreSettingValues
import io.github.tritium_launcher.launcher.io.VPath
import io.github.tritium_launcher.launcher.registry.DeferredRegistryBuilder
import io.github.tritium_launcher.launcher.settings.SettingValueChangedEvent
import io.github.tritium_launcher.launcher.settings.SettingsMngr
import io.github.tritium_launcher.launcher.ui.project.editor.file.FileTypeDescriptor
import io.github.tritium_launcher.launcher.ui.project.editor.panes.TextEditorPane
import io.github.tritium_launcher.launcher.ui.theme.TColors
import io.github.tritium_launcher.launcher.ui.theme.qt.setThemedStyle
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.vBoxLayout
import io.qt.core.QTimer
import io.qt.gui.QIcon
import io.qt.widgets.*
import kotlinx.coroutines.*

/**
 * This is the main Editor area of [io.github.tritium_launcher.launcher.ui.project.ProjectViewWindow],
 * which includes the Tab Bar, Code Editor, and handles opening files.
 */
class EditorArea(
    private val project: ProjectBase
) {
    var onOpenFilesChanged: (() -> Unit)? = null

    private val container = QWidget()
    private val mainLayout = vBoxLayout(container)
    private val tabBar = EditorTabBar()
    private val stack = QStackedWidget()
    private val tabDescriptors = mutableMapOf<Int, TabDescriptor>()

    data class TabDescriptor(
        val file: VPath,
        val icon: QIcon?,
        val title: String,
        val placeholder: QWidget,
        var pane: EditorPane? = null
    )
    private val providerRegistry = BuiltinRegistries.EditorPane
    private val syntaxRegistry = BuiltinRegistries.SyntaxLanguage
    private var providersSnapshot: List<EditorPaneProvider> = emptyList()

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val autoSaveTimer = QTimer(container)

    init {
        container.objectName = "editorArea"
        stack.objectName = "editorStack"
        tabBar.apply {
            onTabCloseRequest = { idx -> closeTab(idx) }
            onCurrentChanged = { idx -> onTabSelected(idx) }
        }
        container.setThemedStyle {
            val editorSurface = TColors.Surface1
            val bgImage = CoreSettingValues.uiBackgroundImage
            val isBgImageSet = !bgImage.isNullOrBlank()

            selector("#editorArea") {
                if (isBgImageSet) {
                    backgroundColor("transparent")
                } else {
                    backgroundColor(editorSurface)
                }
                border()
            }
            selector("#editorStack") {
                if (isBgImageSet) {
                    backgroundColor("transparent")
                } else {
                    backgroundColor(editorSurface)
                }
                border()
            }

            selector("#editorStack QTextEdit, #editorStack QPlainTextEdit") {
                if (isBgImageSet) {
                    backgroundColor("transparent")
                }
            }
            selector("#editorStack QTextEdit > QWidget, #editorStack QPlainTextEdit > QWidget") {
                if (isBgImageSet) {
                    backgroundColor("transparent")
                }
            }
        }
        mainLayout.setContentsMargins(0, 0, 0, 0)
        mainLayout.setSpacing(0)
        stack.frameShape = QFrame.Shape.NoFrame
        mainLayout.addWidget(tabBar)
        mainLayout.addWidget(stack)
        DeferredRegistryBuilder(providerRegistry) { list ->
            providersSnapshot = list.sortedBy { it.order }
        }

        autoSaveTimer.timeout.connect {
            if (CoreSettingValues.editorAutoSave) {
                autoSaveAll()
            }
        }
        updateAutoSaveTimer()
        val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        scope.launch {
            SettingsMngr.events.collect { event ->
                if (event is SettingValueChangedEvent<*>) {
                    if (event.node.key == CoreSettingKeys.EditorAutoSave || event.node.key == CoreSettingKeys.EditorAutoSaveInterval) {
                        updateAutoSaveTimer()
                    }
                }
            }
        }
        container.destroyed.connect { scope.cancel() }
    }

    private fun updateAutoSaveTimer() {
        if (CoreSettingValues.editorAutoSave) {
            autoSaveTimer.start(CoreSettingValues.editorAutoSaveInterval() * 1000)
        } else {
            autoSaveTimer.stop()
        }
    }

    fun saveActive() {
        val current = stack.currentIndex
        val pane = tabDescriptors[current]?.pane ?: return
        if (pane.modified) {
            scope.launch {
                pane.save()
            }
        }
    }

    fun saveAll() {
        tabDescriptors.values.mapNotNull { it.pane }.filter { it.modified }.forEach { pane ->
            scope.launch {
                pane.save()
            }
        }
    }

    private fun autoSaveAll() {
        tabDescriptors.values.mapNotNull { it.pane }.filter { it.modified && it.allowAutoSave }.forEach { pane ->
            scope.launch {
                pane.save()
            }
        }
    }

    fun widget(): QWidget = container

    fun openFile(file: VPath): EditorPane? {
        val absolute = file.toAbsolute()
        val existingEntry = tabDescriptors.entries.firstOrNull { it.value.file.toAbsolute() == absolute }

        if(existingEntry != null) {
            val idx = existingEntry.key
            tabBar.setCurrentIndex(idx)
            stack.currentIndex = idx
            return existingEntry.value.pane
        }

        val fileIcon = resolveFileIcon(file, project)
        val chosen = providersSnapshot.firstOrNull { it.canOpen(file, project) }
        val tabTitle = chosen?.tabTitle(file, project) ?: file.fileName()
        val resolvedTabIcon = chosen?.tabIcon(file, project) ?: fileIcon

        // Create a placeholder widget for the stacked widget
        val placeholder = QWidget()
        val idx = stack.addWidget(placeholder)
        
        val descriptor = TabDescriptor(file, resolvedTabIcon, tabTitle, placeholder)
        tabDescriptors[idx] = descriptor

        tabBar.insertTab(idx, resolvedTabIcon, tabTitle)
        tabBar.setCurrentIndex(idx)
        stack.currentIndex = idx
        
        // If it's the current tab, instantiate it immediately
        if (stack.currentIndex == idx) {
            ensurePaneInstantiated(idx)
        }

        onOpenFilesChanged?.invoke()
        return descriptor.pane
    }

    private fun ensurePaneInstantiated(idx: Int): EditorPane? {
        val desc = tabDescriptors[idx] ?: return null
        if (desc.pane != null) return desc.pane

        val chosen = providersSnapshot.firstOrNull { it.canOpen(desc.file, project) }
        val pane = chosen?.create(project, desc.file) ?: run {
            val lang = syntaxRegistry.all().find { it.matches(desc.file) }
            TextEditorPane(project, desc.file, lang)
        }
        
        desc.pane = pane
        val w = pane.widget()
        
        // Replace placeholder with actual widget at the same index
        val placeholderIndex = stack.indexOf(desc.placeholder)
        if (placeholderIndex >= 0) {
            stack.insertWidget(placeholderIndex, w)
            stack.removeWidget(desc.placeholder)
            desc.placeholder.disposeLater()
        } else {
            stack.addWidget(w)
        }
        
        pane.onModifiedChanged = { modified ->
            tabBar.setTabModifiedAt(idx, modified)
        }
        
        pane.onOpen()
        return pane
    }

    fun closeTab(idx: Int) {
        val desc = tabDescriptors[idx] ?: return
        val pane = desc.pane

        if (pane != null && pane.modified) {
            val box = QMessageBox(container)
            box.icon = QMessageBox.Icon.Question
            box.windowTitle = "Unsaved Changes"
            box.text = "File '${pane.file.fileName()}' has unsaved changes. Do you want to save them?"
            val saveBtn = box.addButton("Save", QMessageBox.ButtonRole.AcceptRole)
            val discardBtn = box.addButton("Discard", QMessageBox.ButtonRole.DestructiveRole)
            box.addButton(QMessageBox.StandardButton.Cancel)

            box.exec()
            val clicked = box.clickedButton()
            if (clicked == saveBtn) {
                scope.launch {
                    if (pane.save()) {
                        closeTabInternal(idx)
                    }
                }
                return
            } else if (clicked != discardBtn) {
                return
            }
        }

        closeTabInternal(idx)
    }

    private fun closeTabInternal(idx: Int) {
        val desc = tabDescriptors[idx] ?: return
        desc.pane?.onClose()
        val w = stack.widget(idx)
        stack.removeWidget(w)
        tabBar.removeTab(idx)
        tabDescriptors.remove(idx)
        rebuild()
        onOpenFilesChanged?.invoke()
    }

    private fun rebuild() {
        val new = HashMap<Int, TabDescriptor>()
        for(i in 0 until stack.count) {
            val w = stack.widget(i)
            val desc = tabDescriptors.values.find { it.pane?.widget() == w || it.placeholder == w }
            if(desc != null) new[i] = desc
        }
        tabDescriptors.clear()
        tabDescriptors.putAll(new)
    }

    private fun onTabSelected(idx: Int) {
        if(idx >= 0 && idx < stack.count) {
            ensurePaneInstantiated(idx)
            stack.currentIndex = idx
        }
    }

    fun openFiles(): List<String> = tabDescriptors.values.map { it.file.toAbsolute().toString() }

    fun restoreOpenFiles(paths: List<String>) {
        var changed = false
        for(p in paths) {
            try {
                val v = VPath.get(p)
                if(v.exists()) {
                    openFile(v)
                    changed = true
                }
            } catch (_: Throwable) {}
        }
        if (changed) onOpenFilesChanged?.invoke()
    }

    /**
     * Adjusts font size for the currently focused editor text widget.
     *
     * @return `true` when a text widget was found and updated.
     */
    fun adjustActiveEditorFont(delta: Int): Boolean {
        fun adjust(current: Int): Int {
            val base = if (current > 0) current else 11
            return (base + delta).coerceIn(7, 48)
        }

        val target = when (val focused = QApplication.focusWidget()) {
            is QTextEdit if isFromEditorArea(focused) -> focused
            is QPlainTextEdit if isFromEditorArea(focused) -> focused
            is QLineEdit if isFromEditorArea(focused) -> focused
            else -> findTextWidgetInCurrentPane()
        } ?: return false

        when (target) {
            is QTextEdit -> {
                val font = target.font()
                font.setPointSize(adjust(font.pointSize()))
                target.font = font
            }
            is QPlainTextEdit -> {
                val font = target.font()
                font.setPointSize(adjust(font.pointSize()))
                target.font = font
            }
            is QLineEdit -> {
                val font = target.font()
                font.setPointSize(adjust(font.pointSize()))
                target.font = font
            }
            else -> return false
        }
        return true
    }

    private fun findTextWidgetInCurrentPane(): QWidget? {
        val current = stack.currentWidget() ?: return null
        if (current is QTextEdit || current is QPlainTextEdit || current is QLineEdit) return current
        return current.findChild(QTextEdit::class.java)
            ?: current.findChild(QPlainTextEdit::class.java)
            ?: current.findChild(QLineEdit::class.java)
    }

    private fun isFromEditorArea(widget: QWidget?): Boolean {
        if (widget == null) return false
        if (widget.window() != container.window()) return false
        return widget == container || container.isAncestorOf(widget)
    }

    private fun resolveFileIcon(file: VPath, project: ProjectBase): QIcon? {
        return FileTypeDescriptor.primary(file, project)?.icon
    }
}
