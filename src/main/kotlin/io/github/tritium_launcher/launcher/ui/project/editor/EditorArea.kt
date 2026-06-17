package io.github.tritium_launcher.launcher.ui.project.editor

import io.github.tritium_launcher.launcher.connect
import io.github.tritium_launcher.launcher.core.TritiumEvent
import io.github.tritium_launcher.launcher.core.TritiumEventBus
import io.github.tritium_launcher.launcher.core.onEvent
import io.github.tritium_launcher.launcher.core.project.ProjectBase
import io.github.tritium_launcher.launcher.extension.core.BuiltinRegistries
import io.github.tritium_launcher.launcher.extension.core.CoreSettingKeys
import io.github.tritium_launcher.launcher.extension.core.CoreSettingValues
import io.github.tritium_launcher.launcher.io.VPath
import io.github.tritium_launcher.launcher.registry.DeferredRegistryBuilder
import io.github.tritium_launcher.launcher.ui.project.editor.file.FileTypeDescriptor
import io.github.tritium_launcher.launcher.ui.project.editor.panes.TextEditorPane
import io.github.tritium_launcher.launcher.ui.theme.TColors
import io.github.tritium_launcher.launcher.ui.theme.qt.setThemedStyle
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.hBoxLayout
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.label
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.qWidget
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
        val file: VPath?,
        val icon: QIcon?,
        val title: String,
        val placeholder: QWidget,
        val providerId: String? = null,
        var pane: EditorPane? = null
    )
    private val providerRegistry = BuiltinRegistries.EditorPane
    private val syntaxRegistry = BuiltinRegistries.SyntaxLanguage
    private var providersSnapshot: List<EditorPaneProvider> = emptyList()

    private val emptyStateWidget = createEmptyStateWidget()
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
        mainLayout.addWidget(stack, 1)
        mainLayout.addWidget(emptyStateWidget, 1)
        updateEmptyStateVisibility()
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
        scope.onEvent<TritiumEvent.SettingChanged> { event ->
            val key = "${event.namespace}:${event.nodeKey}"
            if (key == CoreSettingKeys.EditorAutoSave.toString() || key == CoreSettingKeys.EditorAutoSaveInterval.toString()) {
                updateAutoSaveTimer()
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
                if (pane.save()) {
                    TritiumEventBus.publish(TritiumEvent.FileSaved(null, pane.file?.toAbsolute()?.toString()))
                }
            }
        }
    }

    fun saveAll() {
        tabDescriptors.values.mapNotNull { it.pane }.filter { it.modified }.forEach { pane ->
            scope.launch {
                if (pane.save()) {
                    TritiumEventBus.publish(TritiumEvent.FileSaved(null, pane.file?.toAbsolute()?.toString()))
                }
            }
        }
    }

    private fun autoSaveAll() {
        tabDescriptors.values.mapNotNull { it.pane }.filter { it.modified && it.allowAutoSave }.forEach { pane ->
            scope.launch {
                if (pane.save()) {
                    TritiumEventBus.publish(TritiumEvent.FileSaved(null, pane.file?.toAbsolute()?.toString()))
                }
            }
        }
    }

    fun widget(): QWidget = container

    fun openFile(file: VPath): EditorPane? {
        val absolute = file.toAbsolute()
        val existingEntry = tabDescriptors.entries.firstOrNull { it.value.file?.toAbsolute() == absolute }

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

        val singletonGroup = chosen?.singletonGroup
        if (singletonGroup != null) {
            val existingSingleton = tabDescriptors.entries.firstOrNull { (_, desc) ->
                val provider = resolveProvider(desc)
                provider?.singletonGroup == singletonGroup
            }
            if (existingSingleton != null) {
                closeTabInternal(existingSingleton.key)
            }
        }

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

        updateEmptyStateVisibility()
        onOpenFilesChanged?.invoke()
        TritiumEventBus.publish(TritiumEvent.EditorOpened(null, file.toAbsolute().toString()))
        return descriptor.pane
    }

    fun openEditorPane(
        provider: EditorPaneProvider,
        title: String,
        icon: QIcon? = null,
        paneFactory: (ProjectBase) -> EditorPane
    ): EditorPane {
        val singletonGroup = provider.singletonGroup
        if (singletonGroup != null) {
            val existingSingleton = tabDescriptors.entries.firstOrNull { (_, desc) ->
                val p = resolveProvider(desc)
                p?.singletonGroup == singletonGroup
            }
            if (existingSingleton != null) {
                closeTabInternal(existingSingleton.key)
            }
        }

        val pane = paneFactory(project)
        val placeholder = QWidget()
        val idx = stack.addWidget(placeholder)

        val descriptor = TabDescriptor(
            file = null,
            icon = icon,
            title = title,
            placeholder = placeholder,
            providerId = provider.id,
            pane = pane
        )
        tabDescriptors[idx] = descriptor

        stack.insertWidget(idx, pane.widget())
        stack.removeWidget(placeholder)
        placeholder.disposeLater()

        tabBar.insertTab(idx, icon, title)
        tabBar.setCurrentIndex(idx)
        stack.currentIndex = idx

        pane.onModifiedChanged = { modified ->
            tabBar.setTabModifiedAt(idx, modified)
        }
        pane.onTitleChanged = { newTitle ->
            tabBar.setTabText(idx, newTitle)
        }
        pane.onIconChanged = { newIcon ->
            tabBar.setTabIconAt(idx, newIcon)
        }

        pane.onOpen()

        updateEmptyStateVisibility()
        onOpenFilesChanged?.invoke()
        TritiumEventBus.publish(TritiumEvent.EditorOpened(provider.id, null))
        return pane
    }

    private fun resolveProvider(desc: TabDescriptor): EditorPaneProvider? {
        if (desc.providerId != null) {
            return providersSnapshot.firstOrNull { it.id == desc.providerId }
        }
        val file = desc.file ?: return null
        return providersSnapshot.firstOrNull { it.canOpen(file, project) }
    }

    private fun ensurePaneInstantiated(idx: Int): EditorPane? {
        val desc = tabDescriptors[idx] ?: return null
        if (desc.pane != null) return desc.pane

        val file = desc.file ?: return null
        val chosen = providersSnapshot.firstOrNull { it.canOpen(file, project) }
        val pane = chosen?.create(project, file) ?: run {
            val lang = syntaxRegistry.all().find { it.matches(file) }
            TextEditorPane(project, file, lang)
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
        pane.onTitleChanged = { title ->
            tabBar.setTabText(idx, title)
        }
        pane.onIconChanged = { icon ->
            tabBar.setTabIconAt(idx, icon)
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
            val fileName = pane.file?.fileName() ?: pane::class.simpleName ?: "Untitled"
            box.text = "'$fileName' has unsaved changes. Do you want to save them?"
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
        updateEmptyStateVisibility()
        onOpenFilesChanged?.invoke()
        TritiumEventBus.publish(TritiumEvent.EditorClosed(desc.providerId, desc.file?.toAbsolute()?.toString()))
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

    fun openFiles(): List<String> = tabDescriptors.values.mapNotNull { it.file?.toAbsolute()?.toString() }

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

    private fun updateEmptyStateVisibility() {
        val hasTabs = tabBar.count > 0
        stack.isVisible = hasTabs
        emptyStateWidget.isVisible = !hasTabs
    }

    private fun createEmptyStateWidget(): QWidget {
        return qWidget {
            objectName = "editorEmptyState"

            val root = vBoxLayout(this) {
                setContentsMargins(32, 24, 32, 24)
                setSpacing(0)
            }

            val leftHint = label("←  Select a File to edit") {
                objectName = "emptyStateLeftHint"
            }

            val rightHint = label("Installed Mods  →") {
                objectName = "emptyStateRightHint"
            }

            val bottomHint = label("Item Browser  ↓") {
                objectName = "emptyStateBottomHint"
            }

            val topRow = hBoxLayout() {
                addWidget(leftHint)
                addStretch()
                addWidget(rightHint)
            }
            root.addLayout(topRow)
            root.addStretch(1)

            val bottomRow = hBoxLayout() {
                addWidget(bottomHint)
                addStretch()
            }
            root.addLayout(bottomRow)

            setThemedStyle {
                selector("#editorEmptyState") {
                    backgroundColor(TColors.Surface0)
                }
                selector("#emptyStateLeftHint, #emptyStateRightHint, #emptyStateBottomHint") {
                    color(TColors.Subtext)
                    fontSize(13)
                }
            }
        }
    }

    private fun resolveFileIcon(file: VPath, project: ProjectBase): QIcon? {
        return FileTypeDescriptor.primary(file, project)?.icon
    }
}
