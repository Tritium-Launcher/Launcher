package io.github.tritium_launcher.launcher.ui.dashboard

import io.github.tritium_launcher.launcher.connect
import io.github.tritium_launcher.launcher.logger
import io.github.tritium_launcher.launcher.m
import io.github.tritium_launcher.launcher.platform.Platform
import io.github.tritium_launcher.launcher.ui.theme.TColors
import io.github.tritium_launcher.launcher.ui.theme.ThemeMngr
import io.github.tritium_launcher.launcher.ui.theme.ThemeType
import io.github.tritium_launcher.launcher.ui.theme.qt.setThemedStyle
import io.github.tritium_launcher.launcher.ui.widgets.TComboBox
import io.github.tritium_launcher.launcher.ui.widgets.TPushButton
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.hBoxLayout
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.label
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.vBoxLayout
import io.qt.core.QModelIndex
import io.qt.core.QSignalBlocker
import io.qt.core.Qt
import io.qt.gui.*
import io.qt.widgets.*
import java.util.prefs.Preferences
import kotlin.math.absoluteValue

/**
 * Dashboard panel for configuring Themes and Fonts.
 */
class ThemesPanel internal constructor(): QWidget() {
    private val logger = logger()
    private val prefs: Preferences = Preferences.userRoot().node("/tritium")

    private val mainLayout = QVBoxLayout()

    private val themeComboBox = TComboBox()
    private val openFolderBtn = TPushButton {
        text = "Folder"
        minimumHeight = 30
    }
    private val refreshBtn = TPushButton {
        text = "Refresh"
        minimumHeight = 30
    }

    private val globalFontComboBox = TComboBox()
    private val editorFontComboBox = TComboBox()
    private val globalFontSizeSpinner = QSpinBox().apply {
        minimum = 8
        maximum = 32
        value = 12
    }
    private val editorFontSizeSpinner = QSpinBox().apply {
        minimum = 8
        maximum = 32
        value = 12
    }

    private val themeListener: () -> Unit = {
        refreshThemeList()
        refreshSelection()
        updateThemeItemBackgrounds()
    }
    private var isUpdating = false

    companion object {
        private val SeparatorRole = Qt.ItemDataRole.UserRole.absoluteValue + 1
    }

    init {
        setLayout(mainLayout)
        mainLayout.contentsMargins = 16.m
        mainLayout.widgetSpacing = 16

        themeComboBox.view()?.setItemDelegate(ThemeItemDelegate())
        applyThemeComboPopupStyle()

        val themeGroup = createThemeSection()
        mainLayout.addWidget(themeGroup)

        val fontGroup = createFontSection()
        mainLayout.addWidget(fontGroup)

        mainLayout.addStretch(1)

        loadAvailableFonts()
        refreshThemeList()
        refreshSelection()
        loadCurrentFontSettings()
        updateThemeItemBackgrounds()

        setupConnections()

        ThemeMngr.addListener(themeListener)
    }

    private fun createThemeSection(): QGroupBox {
        val group = QGroupBox("Themes")
        val layout = vBoxLayout(group) {
            contentsMargins = 12.m
            widgetSpacing = 12
        }

        val comboRow = QWidget()
        val comboLayout = hBoxLayout(comboRow) {
            contentsMargins = 0.m
            widgetSpacing = 8
        }

        val label = label("Theme:") { minimumWidth = 80 }

        comboLayout.addWidget(label)
        comboLayout.addWidget(themeComboBox, 1)
        layout.addWidget(comboRow)

        val btnRow = QWidget()
        val btnLayout = hBoxLayout(btnRow)
        btnLayout.contentsMargins = 0.m
        btnLayout.widgetSpacing = 8
        btnLayout.addWidget(openFolderBtn)
        btnLayout.addWidget(refreshBtn)
        btnLayout.addStretch(1)
        layout.addWidget(btnRow)

        return group
    }

    private fun createFontSection(): QGroupBox {
        val group = QGroupBox("Fonts")
        val layout = vBoxLayout(group) {
            contentsMargins = 12.m
            widgetSpacing = 12
        }

        val fontsRow = QWidget()
        val fontsLayout = hBoxLayout(fontsRow) {
            contentsMargins = 0.m
            widgetSpacing = 8
        }

        val fontsLabel = label("Global Font:") { minimumWidth = 80 }
        fontsLayout.addWidget(fontsLabel)
        fontsLayout.addWidget(globalFontComboBox, 1)
        fontsLayout.addWidget(label("Size:"))
        fontsLayout.addWidget(globalFontSizeSpinner)
        layout.addWidget(fontsRow)

        val editorRow = QWidget()
        val editorLayout = hBoxLayout(editorRow) {
            contentsMargins = 0.m
            widgetSpacing = 8
        }

        val editorLabel = label("Editor Font:") { minimumWidth = 80 }
        editorLayout.addWidget(editorLabel)
        editorLayout.addWidget(editorFontComboBox, 1)
        editorLayout.addWidget(label("Size:"))
        editorLayout.addWidget(editorFontSizeSpinner)
        layout.addWidget(editorRow)

        return group
    }

    private fun loadAvailableFonts() {
        val fonts = mutableSetOf<String>()

        try {
            val sysFonts = QFontDatabase.families()
            fonts.addAll(sysFonts)
        } catch (e: Exception) {
            logger.warn("Failed to load system fonts", e)
        }

        val sortedFonts = fonts.sorted()
        globalFontComboBox.clear()
        editorFontComboBox.clear()

        sortedFonts.forEach { f ->
            globalFontComboBox.addItem(f)
            editorFontComboBox.addItem(f)
        }
    }

    private fun refreshThemeList() {
        isUpdating = true
        val current = ThemeMngr.currentThemeId
        val prev = themeComboBox.currentData() as? String ?: current
        val blocker = QSignalBlocker(themeComboBox)
        try {
            val entries = ThemeMngr.availableThemeIds().map { id ->
                val label = ThemeMngr.getThemeName(id) ?: id
                val type = ThemeMngr.getThemeType(id) ?: ThemeType.Dark
                ThemeEntry(id, label, type)
            }
            val order = listOf(ThemeType.Dark, ThemeType.Light)
            val model = QStandardItemModel()
            for (type in order) {
                val group = entries.filter { it.type == type }.sortedBy { it.label.lowercase() }
                if (group.isEmpty()) continue
                model.appendRow(separatorItem(type))
                group.forEach { entry ->
                    val item = QStandardItem(entry.label)
                    item.setData(entry.id, Qt.ItemDataRole.UserRole)
                    item.setFlags(Qt.ItemFlag.ItemIsEnabled, Qt.ItemFlag.ItemIsSelectable)
                    model.appendRow(item)
                }
            }
            themeComboBox.setModel(model)
            val target = entries.firstOrNull { it.id == prev }?.id
                ?: entries.firstOrNull { it.id == current }?.id
                ?: entries.firstOrNull()?.id
            val idx = target?.let { themeComboBox.findData(it) } ?: -1
            if(idx >= 0) themeComboBox.currentIndex = idx
        } finally {
            blocker.unblock()
        }
        isUpdating = false
    }

    private fun refreshSelection() {
        isUpdating = true
        val idx = themeComboBox.findData(ThemeMngr.currentThemeId)
        if(idx >= 0 && idx < themeComboBox.count) {
            val blocker = QSignalBlocker(themeComboBox)
            try {
                themeComboBox.currentIndex = idx
            } finally {
                blocker.unblock()
            }
        }
        isUpdating = false
    }

    private fun loadCurrentFontSettings() {
        isUpdating = true
        val appFont = QApplication.font()
        val globalFamily = prefs.get("globalFontFamily", appFont.family())
        val globalSize = prefs.getInt("globalFontSize", appFont.pointSize())
        ensureFontInCombo(globalFontComboBox, globalFamily)
        globalFontComboBox.currentText = globalFamily
        globalFontSizeSpinner.value = globalSize.coerceIn(globalFontSizeSpinner.minimum, globalFontSizeSpinner.maximum)

        val editorFamily = prefs.get("editorFontFamily", globalFamily)
        val editorSize = prefs.getInt("editorFontSize", globalSize)
        ensureFontInCombo(editorFontComboBox, editorFamily)
        editorFontComboBox.currentText = editorFamily
        editorFontSizeSpinner.value = editorSize.coerceIn(editorFontSizeSpinner.minimum, editorFontSizeSpinner.maximum)
        isUpdating = false
    }

    private fun ensureFontInCombo(combo: QComboBox, family: String) {
        if((0 until combo.count).none { combo.itemText(it) == family }) {
            combo.addItem(family)
        }
    }

    private fun updateThemeItemBackgrounds() {
        for (i in 0 until themeComboBox.count) {
            val isSeparator = themeComboBox.itemData(i, SeparatorRole) as? Boolean ?: false
            if (isSeparator) continue
            val id = themeComboBox.itemData(i) as? String ?: continue
            val bgHex = ThemeMngr.getThemeColorHex(id, "Surface0") ?: continue
            val textHex = ThemeMngr.getThemeColorHex(id, "Text") ?: continue
            themeComboBox.setItemData(i, QBrush(QColor(bgHex)), Qt.ItemDataRole.BackgroundRole)
            themeComboBox.setItemData(i, QBrush(QColor(textHex)), Qt.ItemDataRole.ForegroundRole)
        }
        themeComboBox.view()?.viewport()?.update()
    }

    private class ThemeItemDelegate : QStyledItemDelegate() {
        override fun paint(painter: QPainter?, option: QStyleOptionViewItem, index: QModelIndex) {
            val opt = QStyleOptionViewItem(option)
            val isSeparator = index.data(SeparatorRole) as? Boolean ?: false
            if (isSeparator) {
                painter ?: return
                val rect = opt.rect
                painter.save()
                painter.fillRect(rect, QBrush(QColor(TColors.Surface1)))
                painter.setPen(QColor(TColors.Subtext))
                val font = QFont(opt.font)
                painter.setFont(font)
                painter.drawText(rect, Qt.AlignmentFlag.AlignCenter.value(), index.data(Qt.ItemDataRole.DisplayRole).toString())
                painter.restore()
                return
            }
            val bg = index.data(Qt.ItemDataRole.BackgroundRole) as? QBrush
            val fg = index.data(Qt.ItemDataRole.ForegroundRole) as? QBrush
            val p = painter ?: return
            val rect = opt.rect
            if (bg != null) {
                p.fillRect(rect, bg)
            }
            val isHot = opt.state.testFlag(QStyle.StateFlag.State_MouseOver) || opt.state.testFlag(QStyle.StateFlag.State_Selected)
            if (isHot) {
                val highlight = opt.palette.color(QPalette.ColorRole.Highlight)
                p.fillRect(rect, highlight)
            }
            val text = index.data(Qt.ItemDataRole.DisplayRole).toString()
            val palette = QPalette(opt.palette)
            if (fg != null) {
                palette.setBrush(QPalette.ColorRole.Text, fg)
                palette.setBrush(QPalette.ColorRole.HighlightedText, fg)
            }
            p.setPen(palette.color(QPalette.ColorRole.Text))
            val textRect = rect.adjusted(6, 0, -6, 0)
            p.drawText(textRect, opt.displayAlignment.value(), text)
        }
    }

    private fun setupConnections() {
        themeComboBox.currentIndexChanged.connect { idx: Int ->
            if(isUpdating) return@connect
            if(idx < 0) return@connect
            val isSeparator = themeComboBox.itemData(idx, SeparatorRole) as? Boolean ?: false
            if (isSeparator) return@connect
            val id = themeComboBox.itemData(idx) as? String ?: return@connect
            ThemeMngr.setTheme(id)
        }

        refreshBtn.clicked.connect {
            refreshThemeList()
            refreshSelection()
        }

        openFolderBtn.clicked.connect {
            try {
                val dir = ThemeMngr.userThemesDir.toAbsolute()
                if (!dir.exists()) dir.mkdirs()
                val localPath = dir.toString()
                val opened = QDesktopServices.openUrl(dir.toQUrl())
                if (!opened) {
                    val fallbackOpened = Platform.openBrowser(dir.toJFile().toURI().toString())
                    if (!fallbackOpened) {
                        logger.warn("Failed to open themes folder '{}'", localPath)
                    }
                }
            } catch (t: Throwable) {
                logger.warn("Failed to open themes folder", t)
            }
        }

        globalFontComboBox.currentTextChanged.connect { applyGlobalFont() }
        globalFontSizeSpinner.valueChanged.connect { applyGlobalFont() }

        editorFontComboBox.currentTextChanged.connect { saveEditorFont() }
        editorFontSizeSpinner.valueChanged.connect { saveEditorFont() }
    }

    private fun applyThemeComboPopupStyle() {
        val view = themeComboBox.view() ?: return
        view.frameShape = QFrame.Shape.Box
        view.frameShadow = QFrame.Shadow.Plain
        view.lineWidth = 2
        view.setThemedStyle {
            selector("QListView") {
                border(2, TColors.Button0)
                borderRadius(4)
                backgroundColor(TColors.Surface1)
                color(TColors.Text)
                selectionColor(TColors.Text)
                padding(2)
                any("alternate-background-color", TColors.Surface0)
            }
            selector("QListView::item") {
                border()
                padding(4, 6, 4, 6)
            }
            selector("QListView::item:selected") {
                color(TColors.Text)
            }
            selector("QListView::item:hover") {
                color(TColors.Text)
            }
        }
    }

    private fun separatorItem(type: ThemeType): QStandardItem {
        val title = when (type) {
            ThemeType.Dark -> "-- Dark Themes --"
            ThemeType.Light -> "-- Light Themes --"
        }
        return QStandardItem(title).apply {
            setFlags(Qt.ItemFlag.ItemIsEnabled)
            setData(true, SeparatorRole)
        }
    }

    private data class ThemeEntry(val id: String, val label: String, val type: ThemeType)

    private fun applyGlobalFont() {
        if(isUpdating) return
        val family = globalFontComboBox.currentText.takeIf { it.isNotBlank() } ?: return
        val size = globalFontSizeSpinner.value
        try {
            val font = QFont(family, size)
            QApplication.setFont(font)
            applyFontToWidgets(font)
            prefs.put("globalFontFamily", family)
            prefs.putInt("globalFontSize", size)
        } catch (e: Exception) {
            logger.warn("Failed to apply global font '{}': {}", family, e.message)
        }
    }

    private fun applyFontToWidgets(font: QFont) {
        for (w in QApplication.topLevelWidgets()) {
            if (w != null) {
                applyFontRecursively(w, font)
            }
        }
    }

    private fun applyFontRecursively(widget: QWidget, font: QFont) {
        widget.font = font
        widget.update()
        for (child in widget.findChildren(QWidget::class.java)) {
            child.font = font
            child.update()
        }
    }

    private fun saveEditorFont() {
        if(isUpdating) return
        val family = editorFontComboBox.currentText.takeIf { it.isNotBlank() } ?: return
        val size = editorFontSizeSpinner.value
        prefs.put("editorFontFamily", family)
        prefs.putInt("editorFontSize", size)
    }
}
