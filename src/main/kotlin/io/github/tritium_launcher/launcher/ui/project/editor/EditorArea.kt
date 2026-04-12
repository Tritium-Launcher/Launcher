package io.github.tritium_launcher.launcher.ui.project.editor

import io.github.tritium_launcher.launcher.core.project.ProjectBase
import io.github.tritium_launcher.launcher.extension.core.BuiltinRegistries
import io.github.tritium_launcher.launcher.io.VPath
import io.github.tritium_launcher.launcher.logger
import io.github.tritium_launcher.launcher.registry.DeferredRegistryBuilder
import io.github.tritium_launcher.launcher.ui.theme.TColors
import io.github.tritium_launcher.launcher.ui.theme.qt.setThemedStyle
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.vBoxLayout
import io.qt.gui.QIcon
import io.qt.widgets.*

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
    private val paneIdx = mutableMapOf<Int, EditorPane>()
    private val providerRegistry = BuiltinRegistries.EditorPane
    private val syntaxRegistry = BuiltinRegistries.SyntaxLanguage
    private var providersSnapshot: List<EditorPaneProvider> = emptyList()

    private val logger = logger()

    init {
        container.objectName = "editorArea"
        stack.objectName = "editorStack"
        tabBar.apply {
            onTabCloseRequest = { idx -> closeTab(idx) }
            onCurrentChanged = { idx -> onTabSelected(idx) }
        }
        container.setThemedStyle {
            val editorSurface = TColors.Surface1
            selector("#editorArea") {
                backgroundColor(editorSurface)
                border()
            }
            selector("#editorStack") {
                backgroundColor(editorSurface)
                border()
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
    }

    fun widget(): QWidget = container

    fun openFile(file: VPath): EditorPane {
        val absolute = file.toAbsolute()
        val existing = paneIdx.entries.firstOrNull { it.value.file.toAbsolute() == absolute }

        val fileIcon = resolveFileIcon(file, project)

        if(existing != null) {
            val idx = existing.key
            tabBar.setCurrentIndex(idx)
            stack.currentIndex  = idx
            return existing.value
        }

        val chosen = providersSnapshot.firstOrNull { it.canOpen(file, project) }
        val pane = chosen?.create(project, file) ?: run {
            val lang = syntaxRegistry.all().find { it.matches(file) }
            TextEditorPane(project, file, lang)
        }
        val w = pane.widget()
        val idx = stack.addWidget(w)
        paneIdx[idx] = pane
        tabBar.insertTab(idx, fileIcon, file.fileName())
        tabBar.setCurrentIndex(idx)
        stack.currentIndex  = idx
        pane.onOpen()
        onOpenFilesChanged?.invoke()
        return pane
    }

    private fun defaultTextPane(project: ProjectBase, file: VPath): EditorPane = object : EditorPane(project, file) {
        private val text = QTextEdit()

        init {
            text.lineWrapMode = QTextEdit.LineWrapMode.NoWrap
            text.frameShape = QFrame.Shape.NoFrame
            text.viewport()?.setContentsMargins(0, 0, 0, 0)
            try { if(file.exists()) text.plainText = file.readTextOr("") } catch (_: Throwable) {}
        }

        override fun widget(): QWidget = text
        override suspend fun save(): Boolean = try {
            file.writeBytes(text.toPlainText().toByteArray())
            true
        } catch (t: Throwable) {
            logger.warn("DefaultTextPane save failed for {}", file.toAbsolute(), t)
            false
        }
    }

    fun closeTab(idx: Int) {
        val pane = paneIdx[idx] ?: return
        pane.onClose()
        val w = stack.widget(idx)
        stack.removeWidget(w)
        tabBar.removeTab(idx)
        paneIdx.remove(idx)
        rebuild()
        onOpenFilesChanged?.invoke()
    }

    private fun rebuild() {
        val new = HashMap<Int, EditorPane>()
        for(i in 0 until stack.count) {
            val w = stack.widget(i)
            val pane = paneIdx.values.find { it.widget() == w }
            if(pane != null) new[i] = pane
        }
        paneIdx.clear()
        paneIdx.putAll(new)
    }

    private fun onTabSelected(idx: Int) {
        if(idx >= 0 && idx < stack.count) stack.currentIndex = idx
    }

    fun openFiles(): List<String> = paneIdx.values.map { it.file.toAbsolute().toString() }

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
        val ftr = BuiltinRegistries.FileType
        val matches = ftr.all().filter { desc -> desc.matches(file, project) }.sortedBy { it.order }
        val primary = matches.firstOrNull()
        return primary?.icon
    }
}
