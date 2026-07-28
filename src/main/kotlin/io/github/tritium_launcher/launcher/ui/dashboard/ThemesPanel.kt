/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.ui.dashboard

import io.github.tritium_launcher.api.connect
import io.github.tritium_launcher.api.logger
import io.github.tritium_launcher.api.platform.Platform
import io.github.tritium_launcher.api.settings.SettingNode
import io.github.tritium_launcher.api.theme.ThemeType
import io.github.tritium_launcher.launcher.extension.core.CoreSettingKeys
import io.github.tritium_launcher.launcher.extension.core.CoreSettingValues
import io.github.tritium_launcher.launcher.font.FontMngr
import io.github.tritium_launcher.launcher.m
import io.github.tritium_launcher.launcher.settings.SettingsMngr
import io.github.tritium_launcher.launcher.ui.theme.TColors
import io.github.tritium_launcher.launcher.ui.theme.ThemeMngr
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue

/**
 * Dashboard panel for configuring Themes and Fonts.
 */
class ThemesPanel internal constructor(): QWidget() {
    private val logger = logger()

    private val mainLayout = QVBoxLayout()

    private val colorThemeCombo = TComboBox()
    private val iconSetCombo = TComboBox()
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

    private var isUpdating = false

    companion object {
        private val SeparatorRole = Qt.ItemDataRole.UserRole.absoluteValue + 1
    }

    init {
        setLayout(mainLayout)
        mainLayout.contentsMargins = 16.m
        mainLayout.widgetSpacing = 16

        colorThemeCombo.view()?.setItemDelegate(ColorThemeItemDelegate())
        applyComboPopupStyle(colorThemeCombo)
        iconSetCombo.view()?.setItemDelegate(IconSetItemDelegate())
        applyComboPopupStyle(iconSetCombo)

        val themeGroup = createThemeSection()
        mainLayout.addWidget(themeGroup)

        val fontGroup = createFontSection()
        mainLayout.addWidget(fontGroup)

        mainLayout.addStretch(1)

        loadAvailableFonts()
        refreshColorThemeList()
        refreshIconSetList()
        refreshSelections()
        loadCurrentFontSettings()
        updateColorThemeItemBackgrounds()

        setupConnections()

        CoroutineScope(Dispatchers.Main).launch {
            ThemeMngr.currentColorThemeId.collect { _ ->
                refreshColorThemeList()
                refreshSelections()
                updateColorThemeItemBackgrounds()
            }
        }
        CoroutineScope(Dispatchers.Main).launch {
            ThemeMngr.currentIconSetId.collect { _ ->
                refreshIconSetList()
                refreshSelections()
            }
        }
    }

    private fun createThemeSection(): QGroupBox {
        val group = QGroupBox("Themes")
        val layout = vBoxLayout(group) {
            contentsMargins = 12.m
            widgetSpacing = 12
        }

        val colorRow = QWidget()
        val colorLayout = hBoxLayout(colorRow) {
            contentsMargins = 0.m
            widgetSpacing = 8
        }
        val colorLabel = label("Colors:") { minimumWidth = 80 }
        colorLayout.addWidget(colorLabel)
        colorLayout.addWidget(colorThemeCombo, 1)
        layout.addWidget(colorRow)

        val iconRow = QWidget()
        val iconLayout = hBoxLayout(iconRow) {
            contentsMargins = 0.m
            widgetSpacing = 8
        }
        val iconLabel = label("Icons:") { minimumWidth = 80 }
        iconLayout.addWidget(iconLabel)
        iconLayout.addWidget(iconSetCombo, 1)
        layout.addWidget(iconRow)

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
        val fonts = FontMngr.availableFontFamilies()
        globalFontComboBox.clear()
        editorFontComboBox.clear()
        fonts.forEach { f ->
            globalFontComboBox.addItem(f)
            editorFontComboBox.addItem(f)
        }
    }

    private fun refreshColorThemeList() {
        isUpdating = true
        val blocker = QSignalBlocker(colorThemeCombo)
        try {
            val entries = ThemeMngr.availableColorThemeIds().map { id ->
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
            colorThemeCombo.setModel(model)
        } finally {
            blocker.unblock()
        }
        isUpdating = false
    }

    private fun refreshIconSetList() {
        isUpdating = true
        val blocker = QSignalBlocker(iconSetCombo)
        try {
            val ids = ThemeMngr.availableIconSetIds().sortedBy { (ThemeMngr.getThemeName(it) ?: it).lowercase() }
            val model = QStandardItemModel()
            ids.forEach { id ->
                val label = ThemeMngr.getThemeName(id) ?: id
                val item = QStandardItem(label)
                item.setData(id, Qt.ItemDataRole.UserRole)
                item.setFlags(Qt.ItemFlag.ItemIsEnabled, Qt.ItemFlag.ItemIsSelectable)
                model.appendRow(item)
            }
            iconSetCombo.setModel(model)
        } finally {
            blocker.unblock()
        }
        isUpdating = false
    }

    private fun refreshSelections() {
        isUpdating = true
        var idx = colorThemeCombo.findData(ThemeMngr.currentColorThemeIdValue)
        if (idx >= 0 && idx < colorThemeCombo.count) {
            val blocker = QSignalBlocker(colorThemeCombo)
            try { colorThemeCombo.currentIndex = idx } finally { blocker.unblock() }
        }
        idx = iconSetCombo.findData(ThemeMngr.currentIconSetIdValue)
        if (idx >= 0 && idx < iconSetCombo.count) {
            val blocker = QSignalBlocker(iconSetCombo)
            try { iconSetCombo.currentIndex = idx } finally { blocker.unblock() }
        }
        isUpdating = false
    }

    private fun loadCurrentFontSettings() {
        isUpdating = true
        val (globalFamily, globalSize) = CoreSettingValues.globalFont()
        ensureFontInCombo(globalFontComboBox, globalFamily)
        globalFontComboBox.currentText = globalFamily
        globalFontSizeSpinner.value = globalSize.coerceIn(globalFontSizeSpinner.minimum, globalFontSizeSpinner.maximum)

        val (editorFamily, editorSize) = CoreSettingValues.editorFont()
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

    private fun updateColorThemeItemBackgrounds() {
        for (i in 0 until colorThemeCombo.count) {
            val isSeparator = colorThemeCombo.itemData(i, SeparatorRole) as? Boolean ?: false
            if (isSeparator) continue
            val id = colorThemeCombo.itemData(i) as? String ?: continue
            val bgHex = ThemeMngr.getThemeColorHex(id, "Surface0") ?: continue
            val textHex = ThemeMngr.getThemeColorHex(id, "Text") ?: continue
            colorThemeCombo.setItemData(i, QBrush(QColor(bgHex)), Qt.ItemDataRole.BackgroundRole)
            colorThemeCombo.setItemData(i, QBrush(QColor(textHex)), Qt.ItemDataRole.ForegroundRole)
        }
        colorThemeCombo.view()?.viewport()?.update()
    }

    private open inner class BaseItemDelegate : QStyledItemDelegate() {
        override fun paint(painter: QPainter?, option: QStyleOptionViewItem, index: QModelIndex) {
            val opt = QStyleOptionViewItem(option)
            val isSeparator = index.data(SeparatorRole) as? Boolean ?: false
            if (isSeparator) {
                painter ?: return
                val rect = opt.rect
                painter.save()
                painter.fillRect(rect, TColors.Surface1.toQB())
                painter.setPen(TColors.Subtext.toQC())
                val font = QFont(opt.font)
                painter.setFont(font)
                painter.drawText(rect, Qt.AlignmentFlag.AlignCenter.value(), index.data(Qt.ItemDataRole.DisplayRole).toString())
                painter.restore()
                return
            }
            drawItem(painter, opt, index)
        }

        protected open fun drawItem(painter: QPainter?, opt: QStyleOptionViewItem, index: QModelIndex) {}
    }

    private inner class ColorThemeItemDelegate : BaseItemDelegate() {
        override fun drawItem(painter: QPainter?, opt: QStyleOptionViewItem, index: QModelIndex) {
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

    private inner class IconSetItemDelegate : BaseItemDelegate() {
        override fun drawItem(painter: QPainter?, opt: QStyleOptionViewItem, index: QModelIndex) {
            val p = painter ?: return
            val rect = opt.rect
            val isHot = opt.state.testFlag(QStyle.StateFlag.State_MouseOver) || opt.state.testFlag(QStyle.StateFlag.State_Selected)
            if (isHot) {
                val highlight = opt.palette.color(QPalette.ColorRole.Highlight)
                p.fillRect(rect, highlight)
            }
            val text = index.data(Qt.ItemDataRole.DisplayRole).toString()
            p.setPen(opt.palette.color(QPalette.ColorRole.Text))
            val textRect = rect.adjusted(6, 0, -6, 0)
            p.drawText(textRect, opt.displayAlignment.value(), text)
        }
    }

    private fun setupConnections() {
        colorThemeCombo.currentIndexChanged.connect { idx: Int ->
            if(isUpdating) return@connect
            if(idx < 0) return@connect
            val isSeparator = colorThemeCombo.itemData(idx, SeparatorRole) as? Boolean ?: false
            if (isSeparator) return@connect
            val id = colorThemeCombo.itemData(idx) as? String ?: return@connect
            ThemeMngr.setColorTheme(id)
        }

        iconSetCombo.currentIndexChanged.connect { idx: Int ->
            if(isUpdating) return@connect
            if(idx < 0) return@connect
            val id = iconSetCombo.itemData(idx) as? String ?: return@connect
            ThemeMngr.setIconSet(id)
        }

        refreshBtn.clicked.connect {
            refreshColorThemeList()
            refreshIconSetList()
            refreshSelections()
        }

        openFolderBtn.clicked.connect {
            try {
                val dir = ThemeMngr.userThemesDir.toAbsolute()
                if (!dir.exists()) dir.mkdirs()
                val localPath = dir.toString()
                val opened = Platform.openFile(dir)
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

    private fun applyComboPopupStyle(combo: TComboBox) {
        val view = combo.view() ?: return
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
            @Suppress("unchecked_cast")
            val node = SettingsMngr.findSetting(CoreSettingKeys.GlobalFont) as? SettingNode<String>
            node?.let { SettingsMngr.updateValue(it, "$family|$size") }
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
        @Suppress("unchecked_cast")
        val node = SettingsMngr.findSetting(CoreSettingKeys.EditorFont) as? SettingNode<String>
        node?.let { SettingsMngr.updateValue(it, "$family|$size") }
    }
}
