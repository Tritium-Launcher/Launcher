/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.ui.theme

import io.github.tritium_launcher.api.connect
import io.github.tritium_launcher.api.settings.RefreshableSettingWidget
import io.github.tritium_launcher.api.settings.SettingWidgetContext
import io.github.tritium_launcher.api.theme.ThemeType
import io.github.tritium_launcher.launcher.ui.theme.qt.setThemedStyle
import io.github.tritium_launcher.launcher.ui.widgets.TComboBox
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.hBoxLayout
import io.qt.core.QModelIndex
import io.qt.core.QSignalBlocker
import io.qt.core.Qt
import io.qt.gui.*
import io.qt.widgets.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue

private val SeparatorRole = Qt.ItemDataRole.UserRole.absoluteValue + 1

private class ColorThemeComboWidget(
    private val ctx: SettingWidgetContext<String>
) : QWidget(), RefreshableSettingWidget {
    private val combo = TComboBox()
    private var isRefreshing = false

    init {
        hBoxLayout(this) {
            setContentsMargins(0, 0, 0, 0)
            addWidget(combo, 1)
        }
        combo.view()?.setItemDelegate(ColorThemeItemDelegate())
        applyComboPopupStyle(combo)
        refreshList()
        selectCurrent()
        updateItemBackgrounds()
        combo.currentIndexChanged.connect { idx: Int ->
            if (isRefreshing) return@connect
            if (idx < 0) return@connect
            if (combo.itemData(idx, SeparatorRole) as? Boolean == true) return@connect
            val id = combo.itemData(idx) as? String ?: return@connect
            ThemeMngr.setColorTheme(id)
            commit(id)
        }
        CoroutineScope(Dispatchers.Main).launch {
            ThemeMngr.currentColorThemeId.collect {
                refreshList()
                selectCurrent()
                updateItemBackgrounds()
            }
        }
    }

    private fun refreshList() {
        isRefreshing = true
        val blocker = QSignalBlocker(combo)
        try {
            val entries = ThemeMngr.availableColorThemeIds().map { id ->
                ThemeEntry(id, ThemeMngr.getThemeName(id) ?: id, ThemeMngr.getThemeType(id) ?: ThemeType.Dark)
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
            combo.setModel(model)
        } finally {
            blocker.unblock()
        }
        isRefreshing = false
    }

    private fun selectCurrent() {
        isRefreshing = true
        val idx = combo.findData(ThemeMngr.currentColorThemeIdValue)
        if (idx >= 0 && idx < combo.count) {
            val blocker = QSignalBlocker(combo)
            try { combo.currentIndex = idx } finally { blocker.unblock() }
        }
        isRefreshing = false
    }

    private fun updateItemBackgrounds() {
        for (i in 0 until combo.count) {
            if (combo.itemData(i, SeparatorRole) as? Boolean == true) continue
            val id = combo.itemData(i) as? String ?: continue
            val bgHex = ThemeMngr.getThemeColorHex(id, "Surface0") ?: continue
            val textHex = ThemeMngr.getThemeColorHex(id, "Text") ?: continue
            combo.setItemData(i, QBrush(QColor(bgHex)), Qt.ItemDataRole.BackgroundRole)
            combo.setItemData(i, QBrush(QColor(textHex)), Qt.ItemDataRole.ForegroundRole)
        }
        combo.view()?.viewport()?.update()
    }

    override fun refreshFromSettingValue() {
        val persisted = ctx.currentValue()
        if (persisted.isNotBlank() && persisted != ThemeMngr.currentColorThemeIdValue) {
            ThemeMngr.setColorTheme(persisted)
        }
        selectCurrent()
        updateItemBackgrounds()
    }

    private fun commit(id: String) {
        ctx.updateValue(id)
    }

    private data class ThemeEntry(val id: String, val label: String, val type: ThemeType)

    companion object {
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
    }
}

private class IconSetComboWidget(
    private val ctx: SettingWidgetContext<String>
) : QWidget(), RefreshableSettingWidget {
    private val combo = TComboBox()
    private var isRefreshing = false

    init {
        hBoxLayout(this) {
            setContentsMargins(0, 0, 0, 0)
            addWidget(combo, 1)
        }
        combo.view()?.setItemDelegate(IconSetItemDelegate())
        applyComboPopupStyle(combo)
        refreshList()
        selectCurrent()
        combo.currentIndexChanged.connect { idx: Int ->
            if (isRefreshing) return@connect
            if (idx < 0) return@connect
            val id = combo.itemData(idx) as? String ?: return@connect
            ThemeMngr.setIconSet(id)
            commit(id)
        }
        CoroutineScope(Dispatchers.Main).launch {
            ThemeMngr.currentIconSetId.collect {
                refreshList()
                selectCurrent()
            }
        }
    }

    private fun refreshList() {
        isRefreshing = true
        val blocker = QSignalBlocker(combo)
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
            combo.setModel(model)
        } finally {
            blocker.unblock()
        }
        isRefreshing = false
    }

    private fun selectCurrent() {
        isRefreshing = true
        val idx = combo.findData(ThemeMngr.currentIconSetIdValue)
        if (idx >= 0 && idx < combo.count) {
            val blocker = QSignalBlocker(combo)
            try { combo.currentIndex = idx } finally { blocker.unblock() }
        }
        isRefreshing = false
    }

    override fun refreshFromSettingValue() {
        val persisted = ctx.currentValue()
        if (persisted.isNotBlank() && persisted != ThemeMngr.currentIconSetIdValue) {
            ThemeMngr.setIconSet(persisted)
        }
        selectCurrent()
    }

    private fun commit(id: String) {
        ctx.updateValue(id)
    }
}

private open class BaseItemDelegate : QStyledItemDelegate() {
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

private class ColorThemeItemDelegate : BaseItemDelegate() {
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

private class IconSetItemDelegate : BaseItemDelegate() {
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

internal fun colorThemeSettingWidget(ctx: SettingWidgetContext<String>): QWidget = ColorThemeComboWidget(ctx)
internal fun iconSetSettingWidget(ctx: SettingWidgetContext<String>): QWidget = IconSetComboWidget(ctx)
