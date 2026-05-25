package io.github.tritium_launcher.launcher.ui.widgets

import io.github.tritium_launcher.launcher.connect
import io.github.tritium_launcher.launcher.currentDpr
import io.github.tritium_launcher.launcher.qs
import io.github.tritium_launcher.launcher.ui.theme.TColors
import io.github.tritium_launcher.launcher.ui.theme.TIcons
import io.github.tritium_launcher.launcher.ui.theme.ThemeMngr
import io.github.tritium_launcher.launcher.ui.theme.qt.setThemedStyle
import io.github.tritium_launcher.launcher.ui.widgets.pixel.pixelSkin
import io.qt.Nullable
import io.qt.core.QEvent
import io.qt.core.Qt
import io.qt.gui.*
import io.qt.widgets.*
import io.qt.widgets.QSizePolicy.Policy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min

/**
 * Minecraft-styled combo box.
 */
open class TComboBox(parent: QWidget? = null) : QComboBox(parent) {
    private var popupVisible = false
    private var lastDpr: Double = -1.0

    init {
        frame = false
        setContentsMargins(0, 0, 0, 0)
        minimumHeight = 34

        val listView = QListView()
        listView.objectName = "tComboBoxPopupList"
        listView.frameShape = QFrame.Shape.NoFrame
        listView.alternatingRowColors = false
        listView.verticalScrollMode = QAbstractItemView.ScrollMode.ScrollPerPixel
        setView(listView)
        AnimatedScrollController.attach(listView)
        applyPopupStyle()
    }

    override fun showPopup() {
        popupVisible = true
        super.showPopup()
        update()
    }

    override fun hidePopup() {
        popupVisible = false
        super.hidePopup()
        update()
    }

    override fun paintEvent(event: QPaintEvent?) {
        val painter = QPainter(this)
        painter.setRenderHint(QPainter.RenderHint.Antialiasing, false)
        painter.setRenderHint(QPainter.RenderHint.SmoothPixmapTransform, false)

        val w = width()
        val h = height()

        val state = when {
            !isEnabled -> State.Disabled
            popupVisible -> State.Pressed
            else -> State.Normal
        }

        val dpr = currentDpr(this)
        handleDprChange(dpr)
        val bg = skin.render(state.key, w, h, dpr)
        if (!bg.isNull) {
            painter.drawPixmap(0, 0, bg)
        }

        val opt = QStyleOptionComboBox()
        initStyleOption(opt)

        val arrowSize = min(16, (h - 10).coerceAtLeast(10))
        opt.rect = opt.rect.adjusted(8, 0, -(arrowSize + 10), 0)
        painter.save()
        painter.translate(0.0, -2.0)
        style()?.drawControl(QStyle.ControlElement.CE_ComboBoxLabel, opt, painter, this)
        painter.restore()

        val arrow = TIcons.SmallArrowDown
        if (!arrow.isNull) {
            val scaled = arrow.scaled(
                qs(arrowSize, arrowSize),
                Qt.AspectRatioMode.KeepAspectRatio,
                Qt.TransformationMode.FastTransformation
            )
            val x = w - arrowSize - 8
            val y = (h - arrowSize) / 2
            painter.drawPixmap(x, y, scaled)
        }

        painter.end()
    }

    override fun changeEvent(e: @Nullable QEvent?) {
        super.changeEvent(e)
        when (e?.type()) {
            QEvent.Type.StyleChange,
            QEvent.Type.PaletteChange,
            QEvent.Type.DevicePixelRatioChange,
            QEvent.Type.ScreenChangeInternal -> skin.clearCache(disposePixmaps = true)
            else -> {}
        }
    }

    override fun event(e: @Nullable QEvent?): Boolean {
        if (e?.type() == QEvent.Type.ScreenChangeInternal || e?.type() == QEvent.Type.DevicePixelRatioChange) {
            handleDprChange(currentDpr(this))
        }
        return super.event(e)
    }

    override fun moveEvent(event: @Nullable QMoveEvent?) {
        super.moveEvent(event)
        handleDprChange(currentDpr(this))
    }

    override fun showEvent(event: @Nullable QShowEvent?) {
        super.showEvent(event)
        handleDprChange(currentDpr(this))
    }

    /**
     * When DPR changes, update button to new values
     */
    private fun handleDprChange(dpr: Double) {
        if (lastDpr < 0.0 || abs(lastDpr - dpr) > 0.001) {
            skin.clearCache(disposePixmaps = true)
            lastDpr = dpr
            update()
        }
    }

    /**
     * Apply styling to option list popup
     */
    private fun applyPopupStyle() {
        view()?.setThemedStyle {
            selector("QListView#tComboBoxPopupList") {
                backgroundColor(TColors.Surface0)
                color(TColors.Text)
                border(1, TColors.Button0)
                padding(3)
                any("show-decoration-selected", "1")
                any("outline", "none")
            }
            selector("QListView#tComboBoxPopupList::item") {
                backgroundColor("transparent")
                color(TColors.Text)
                border()
                padding(6, 8, 6, 8)
                margin(0, 0, 1, 0)
            }
            selector("QListView#tComboBoxPopupList::item:hover") {
                backgroundColor(TColors.Surface1)
                color(TColors.Text)
            }
            selector("QListView#tComboBoxPopupList::item:selected") {
                backgroundColor(TColors.SelectedUI)
                color(TColors.SelectedText)
            }
            selector("QListView#tComboBoxPopupList::item:selected:hover") {
                backgroundColor(TColors.SelectedUI)
                color(TColors.SelectedText)
            }
        }
    }

    /**
     * Button states
     */
    private enum class State(val key: String) { Normal("normal"), Pressed("pressed"), Disabled("disabled") }

    companion object {
        private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        private var skin = buildSkin()

        init {
            scope.launch {
                ThemeMngr.currentThemeId.collect {
                    val prev = skin
                    skin = buildSkin()
                    prev.clearCache(disposePixmaps = true)
                }
            }
        }

        /**
         * Build sprites
         */
        private fun buildSkin() = pixelSkin {
            pixelSize = 2
            palette {
                color("border", TColors.Button0)
                color("shadow", TColors.Button1)
                color("primary", TColors.Button2)
                color("bright", TColors.Button3)
                color("disabled", TColors.ButtonDisabled0)
                color("disabledBorder", TColors.ButtonDisabled1)
            }

            state("normal") {
                draw {
                    val p = px
                    val w = width
                    val h = height
                    fillRect(0, 0, w, h, "border")
                    fillRect(p, p, w - p * 2, h - p * 2, "primary")
                    fillRect(p, p, w - p * 2, p, "bright")
                    fillRect(p, p, p, h - p * 5, "bright")
                    fillRect(w - p * 2, p, p, h - p * 5, "bright")
                    fillRect(p, h - p * 4, w - p * 2, p, "bright")
                    fillRect(p, h - p * 3, w - p * 2, p * 2, "shadow")
                }
            }

            state("pressed") {
                draw {
                    val p = px
                    val w = width
                    val h = height
                    fillRect(0, p, w, h - p, "border")
                    fillRect(p, p + 2, w - p * 2, h - p - 4, "primary")
                    fillRect(p, p + 2, w - p * 2, p, "bright")
                    fillRect(p, p + 2, p, h - p * 4, "bright")
                    fillRect(w - p * 2, p + 2, p, h - p * 4, "bright")
                    fillRect(p, h - p * 2, w - p * 2, p, "bright")
                }
            }

            state("disabled") {
                draw {
                    val p = px
                    val w = width
                    val h = height
                    fillRect(0, 0, w, h, "disabledBorder")
                    fillRect(p, p, w - p * 2, h - p * 2, "disabled")
                    fillRect(p, p, w - p * 2, p, "disabled")
                    fillRect(p, p, p, h - p * 3, "disabled")
                    fillRect(w - p * 2, p, p, h - p * 3, "disabled")
                    fillRect(p, h - p * 2, w - p * 2, p, "disabled")
                }
            }
        }

        operator fun invoke(parent: QWidget? = null, block: TComboBox.() -> Unit = {}): TComboBox =
            TComboBox(parent).apply(block)
    }
}

/**
 * Combo box that opens a custom popup for including or excluding category ids.
 *
 * The visible combo text acts as a summary of the current include/exclude state,
 * while the popup provides per-category toggle buttons.
 */
class TMultiStateCategoryComboBox(parent: QWidget? = null) : TComboBox(parent) {
    /**
     * Selection state tracked for each category entry
     */
    enum class State { NEUTRAL, INCLUDE, EXCLUDE }

    /**
     * Mutable popup row model for a single category.
     *
     * @property id Stable category id.
     * @property label User-facing category label.
     * @property state Current include/exclude state.
     */
    data class Entry(
        val id: String,
        val label: String,
        var state: State = State.NEUTRAL
    )

    private val popup = QMenu(this)
    private val popupScroll = QScrollArea()
    private val popupContent = QWidget()
    private val popupLayout = QVBoxLayout(popupContent)
    private val entries = linkedMapOf<String, Entry>()
    private var popupVisible = false

    var onSelectionChanged: (() -> Unit)? = null

    init {
        isEditable = false
        minimumHeight = 26
        maximumHeight = 26
        sizePolicy = QSizePolicy(Policy.Expanding, Policy.Fixed)
        popup.objectName = "tMultiStateComboPopup"
        popupScroll.objectName = "tMultiStateComboScroll"
        popupContent.objectName = "tMultiStateComboContent"
        popupScroll.apply {
            widgetResizable = true
            frameShape = QFrame.Shape.NoFrame
            setWidget(popupContent)
            minimumWidth = 280
            minimumHeight = 180
        }
        AnimatedScrollController.attach(popupScroll)
        popupLayout.apply {
            setContentsMargins(0, 0, 0, 0)
            setSpacing(0)
        }
        val action = QWidgetAction(popup)
        action.setDefaultWidget(popupScroll)
        popup.addAction(action)
        popup.setThemedStyle {
            selector("QMenu#tMultiStateComboPopup") {
                backgroundColor(TColors.Surface0)
                color(TColors.Text)
                border(1, TColors.Button0)
                padding(0)
            }
            selector("QScrollArea#tMultiStateComboScroll") {
                backgroundColor(TColors.Surface0)
                border()
            }
            selector("#tMultiStateComboContent") {
                backgroundColor(TColors.Surface0)
                border()
            }
            selector("#tMultiStateComboRow") {
                backgroundColor(TColors.Surface0)
                border(1, TColors.Button0, "bottom")
            }
            selector("#tMultiStateComboRow QLabel") {
                color(TColors.Text)
                fontWeight(600)
            }
            selector("QPushButton#tMultiStateComboAction") {
                minWidth(68)
                border(1, TColors.Button0)
                borderRadius(3)
                padding(5, 10, 5, 10)
                backgroundColor(TColors.Surface1)
                color(TColors.Subtext)
            }
            selector("QPushButton#tMultiStateComboAction:hover") {
                backgroundColor(TColors.Surface2)
                color(TColors.Text)
            }
            selector("QPushButton#tMultiStateComboAction[categoryState=\"active\"]") {
                backgroundColor(TColors.SelectedUI)
                color(TColors.SelectedText)
                border(1, TColors.Button3)
            }
            selector("QPushButton#tMultiStateComboAction[categoryState=\"active\"]:hover") {
                backgroundColor(TColors.SelectedUI)
                color(TColors.SelectedText)
            }
        }
        refreshSummary()
    }

    override fun showPopup() {
        refreshRows()
        val pos = mapToGlobal(rect().bottomLeft())
        popup.minimumWidth = width().coerceAtLeast(320)
        popupVisible = true
        update()
        popup.exec(pos)
        popupVisible = false
        update()
    }

    override fun hidePopup() {
        popupVisible = false
        update()
        super.hidePopup()
    }

    override fun paintEvent(event: QPaintEvent?) {
        val painter = QPainter(this)
        painter.setRenderHint(QPainter.RenderHint.Antialiasing, true)
        painter.setRenderHint(QPainter.RenderHint.TextAntialiasing, true)

        val contentRect = rect().adjusted(0, 0, -1, -1)

        val borderColor = QColor(TColors.Button0)
        borderColor.setAlpha(if (popupVisible) 220 else 160)
        painter.setPen(borderColor)
        painter.drawLine(contentRect.left(), 0, contentRect.right(), 0)

        val labelText = currentText().ifBlank { "All categories" }.uppercase()
        val labelColor = QColor(if (popupVisible) TColors.Text else TColors.Subtext)
        if (!isEnabled) {
            labelColor.setAlpha(120)
        } else if (!popupVisible) {
            labelColor.setAlpha(210)
        }

        painter.setPen(labelColor)
        val metrics = painter.fontMetrics()
        val textRect = contentRect.adjusted(0, 0, -18, 0)
        painter.drawText(textRect, Qt.AlignmentFlag.AlignLeft.value() or Qt.AlignmentFlag.AlignVCenter.value(), labelText)

        val lineLeft = metrics.horizontalAdvance(labelText) + 10
        val lineRight = contentRect.right() - 16
        if (lineRight > lineLeft) {
            val dividerColor = QColor(TColors.Button0)
            dividerColor.setAlpha(if (popupVisible) 220 else 150)
            painter.setPen(dividerColor)
            val y = contentRect.center().y()
            painter.drawLine(lineLeft, y, lineRight, y)
        }

        val arrow = TIcons.SmallArrowDown
        if (!arrow.isNull) {
            val scaled = arrow.scaled(
                qs(10, 10),
                Qt.AspectRatioMode.KeepAspectRatio,
                Qt.TransformationMode.SmoothTransformation
            )
            val x = contentRect.right() - scaled.width()
            val y = contentRect.center().y() - (scaled.height() / 2)
            painter.drawPixmap(x, y, scaled)
        }

        painter.end()
    }

    /**
     * Replaces the available category entries while preserving prior selection state by id.
     *
     * @param values Pairs of category id and display label.
     */
    fun setEntries(values: List<Pair<String, String>>) {
        val preserved = entries.mapValues { it.value.state }
        entries.clear()
        values.forEach { (id, label) ->
            entries[id] = Entry(id = id, label = label, state = preserved[id] ?: State.NEUTRAL)
        }
        refreshRows()
        refreshSummary()
    }

    /**
     * Returns ids currently marked for inclusion.
     */
    fun includedIds(): Set<String> = entries.values.filter { it.state == State.INCLUDE }.map { it.id }.toSet()

    /**
     * Returns ids currently marked for exclusion.
     */
    fun excludedIds(): Set<String> = entries.values.filter { it.state == State.EXCLUDE }.map { it.id }.toSet()

    /**
     * Rebuilds popup rows to reflect the current entry states.
     */
    private fun refreshRows() {
        while (popupLayout.count() > 0) {
            val item = popupLayout.takeAt(0)
            item?.widget()?.let { widget ->
                widget.hide()
                widget.setParent(null)
                widget.dispose()
            }
        }
        entries.values.forEach { entry ->
            val row = QWidget()
            row.objectName = "tMultiStateComboRow"
            val layout = QHBoxLayout(row)
            layout.setContentsMargins(12, 8, 12, 8)
            layout.setSpacing(8)
            val label = QLabel(entry.label)
            label.sizePolicy = QSizePolicy(Policy.Expanding, Policy.Preferred)
            label.wordWrap = false
            val include = QPushButton("Include")
            val exclude = QPushButton("Exclude")
            include.objectName = "tMultiStateComboAction"
            exclude.objectName = "tMultiStateComboAction"
            include.setFixedWidth(72)
            exclude.setFixedWidth(72)
            updateButtonState(include, entry.state == State.INCLUDE)
            updateButtonState(exclude, entry.state == State.EXCLUDE)
            include.clicked.connect { _ ->
                entry.state = if (entry.state == State.INCLUDE) State.NEUTRAL else State.INCLUDE
                refreshRows()
                refreshSummary()
                onSelectionChanged?.invoke()
            }
            exclude.clicked.connect { _ ->
                entry.state = if (entry.state == State.EXCLUDE) State.NEUTRAL else State.EXCLUDE
                refreshRows()
                refreshSummary()
                onSelectionChanged?.invoke()
            }
            layout.addWidget(label, 1)
            layout.addSpacing(8)
            layout.addWidget(include, 0, Qt.AlignmentFlag.AlignRight)
            layout.addWidget(exclude, 0, Qt.AlignmentFlag.AlignRight)
            popupLayout.addWidget(row)
        }
        popupLayout.addStretch(1)
    }

    /**
     * Updates the combo's single visible item to summarize the current selection state.
     */
    private fun refreshSummary() {
        clear()
        val includeCount = includedIds().size
        val excludeCount = excludedIds().size
        val text = when {
            includeCount == 0 && excludeCount == 0 -> "All categories"
            excludeCount == 0 -> "$includeCount included"
            includeCount == 0 -> "$excludeCount excluded"
            else -> "$includeCount included, $excludeCount excluded"
        }
        addItem(text)
        currentIndex = 0
    }

    /**
     * Applies visual state to a popup action button.
     *
     * @param button Button to restyle.
     * @param active Whether the button represents the currently active state.
     */
    private fun updateButtonState(button: QPushButton, active: Boolean) {
        button.isFlat = !active
        button.setProperty("categoryState", if (active) "active" else "idle")
        button.style()?.unpolish(button)
        button.style()?.polish(button)
    }
}
