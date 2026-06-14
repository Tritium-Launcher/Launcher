package io.github.tritium_launcher.launcher.import.ui

import io.github.tritium_launcher.launcher.accounts.ModrinthProject
import io.github.tritium_launcher.launcher.connect
import io.github.tritium_launcher.launcher.core.mod.ModSide
import io.github.tritium_launcher.launcher.import.DetectedInstance
import io.github.tritium_launcher.launcher.import.ImportableMod
import io.github.tritium_launcher.launcher.import.KnownLauncher
import io.github.tritium_launcher.launcher.import.LauncherDetector
import io.github.tritium_launcher.launcher.loadScaledPixmap
import io.github.tritium_launcher.launcher.qs
import io.github.tritium_launcher.launcher.ui.theme.TColors
import io.github.tritium_launcher.launcher.ui.theme.TIcons
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.hBoxLayout
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.label
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.vBoxLayout
import io.qt.core.QSize
import io.qt.core.Qt
import io.qt.gui.QCursor
import io.qt.gui.QIcon
import io.qt.gui.QMouseEvent
import io.qt.gui.QPixmap
import io.qt.widgets.QCheckBox
import io.qt.widgets.QFrame
import io.qt.widgets.QLabel
import io.qt.widgets.QWidget

// --- Launcher selection card ---

class ImportOption(val launcher: KnownLauncher) : QFrame() {
    private val iconLabel = QLabel()
    val nameLabel = QLabel()
    val subtitleLabel = QLabel()
    var onClick: ((KnownLauncher) -> Unit)? = null

    init {
        objectName = "launcherCard"
        frameShape = Shape.NoFrame
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

    override fun mouseReleaseEvent(ev: QMouseEvent?) {
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

// --- Importable mod row (review page) ---

class ImportableModRow(
    private val mod: ImportableMod,
    private val index: Int,
    private val onCheckedChanged: (Int, Boolean) -> Unit,
    private val onFetchOnlineIcon: (Int, String) -> Unit
) : QFrame() {
    private val checkbox = QCheckBox()
    val iconLabel = QLabel()
    private val nameLabel = QLabel()
    private val metaLabel = QLabel()
    private val badgeLabel = QLabel()

    override fun sizeHint(): QSize = QSize(200, 40)

    init {
        objectName = "importableModRow"
        setFixedHeight(40)
        frameShape = Shape.NoFrame

        val layout = hBoxLayout(this) {
            setContentsMargins(8, 2, 8, 2)
            setSpacing(8)
        }

        // Checkbox
        checkbox.isChecked = mod.checked
        checkbox.stateChanged.connect { state: Int ->
            onCheckedChanged(index, state == Qt.CheckState.Checked.value())
        }
        layout.addWidget(checkbox, 0, Qt.AlignmentFlag.AlignVCenter)

        // Icon
        iconLabel.apply {
            setFixedSize(32, 32)
            setAlignment(Qt.AlignmentFlag.AlignCenter)
        }
        var pix: QPixmap? = null
        if (mod.iconBytes != null) {
            val p = QPixmap()
            if (p.loadFromData(mod.iconBytes)) {
                pix = p.scaled(32, 32, Qt.AspectRatioMode.KeepAspectRatio, Qt.TransformationMode.SmoothTransformation)
            }
        }
        if (pix == null) {
            pix = TIcons.Search.scaled(32, 32, Qt.AspectRatioMode.KeepAspectRatio, Qt.TransformationMode.SmoothTransformation)
        }
        iconLabel.pixmap = pix
        layout.addWidget(iconLabel, 0, Qt.AlignmentFlag.AlignVCenter)

        // Name + meta
        val textColumn = QWidget()
        val textLayout = vBoxLayout(textColumn) {
            setContentsMargins(0, 0, 0, 0)
            setSpacing(2)
        }

        nameLabel.apply {
            text = mod.displayName
            objectName = "importableModName"
        }
        textLayout.addWidget(nameLabel)

        val metaText = buildString {
            append(mod.modId)
            if (mod.side != ModSide.BOTH) append(" · ${mod.side.name}")
        }
        metaLabel.apply {
            text = metaText
            objectName = "importableModMeta"
        }
        textLayout.addWidget(metaLabel)

        layout.addWidget(textColumn, 1)

        // Badge
        badgeLabel.apply {
            if (mod.sourceAvailable == null && mod.sourceStatus == null) {
                visible = false
            } else {
                objectName = when (mod.sourceAvailable) {
                    true if mod.sourceStatus?.contains("name match") == true -> "badgeNameMatch"
                    true  -> "badgeAvailable"
                    false -> "badgeUnavailable"
                    else  -> "badgeChecking"
                }
                text = mod.sourceStatus ?: when (mod.sourceAvailable) {
                    true  -> "Available"
                    false -> "Not Available"
                    null  -> "Checking..."
                }
            }
        }
        layout.addWidget(badgeLabel, 0, Qt.AlignmentFlag.AlignVCenter)
    }

    fun updateAvailability(available: Boolean?, projectId: String?, iconUrl: String?, status: String? = null) {
        badgeLabel.visible = true
        val displayStatus = status ?: when (available) {
            true -> "Available"
            false -> "Not Available"
            null -> "Checking..."
        }
        badgeLabel.objectName = when (available) {
            true if status?.contains("name match") == true -> "badgeNameMatch"
            true  -> "badgeAvailable"
            false -> "badgeUnavailable"
            else  -> "badgeChecking"
        }
        badgeLabel.text = displayStatus
        badgeLabel.style()?.unpolish(badgeLabel)
        badgeLabel.style()?.polish(badgeLabel)

        if (!iconUrl.isNullOrBlank()) {
            onFetchOnlineIcon(index, iconUrl)
        }
    }

    fun setIconFromQIcon(icon: QIcon) {
        val p = icon.pixmap(32, 32)
        if (!p.isNull) {
            iconLabel.pixmap = p
        }
    }
}

// --- Modrinth pack item widget (instance list) ---

class ModrinthPackItemWidget(val project: ModrinthProject) : QFrame() {
    val iconLabel = QLabel()

    override fun sizeHint(): QSize = QSize(200, 44)

    init {
        objectName = "importableModRow"
        setFixedHeight(44)
        frameShape = Shape.NoFrame

        val layout = hBoxLayout(this) {
            setContentsMargins(8, 4, 12, 4)
            setSpacing(8)
        }

        iconLabel.setFixedSize(32, 32)
        iconLabel.setAlignment(Qt.AlignmentFlag.AlignCenter)
        layout.addWidget(iconLabel, 0, Qt.AlignmentFlag.AlignVCenter)

        label(project.title) {
            styleSheet = "font-size: 13px; font-weight: 600; color: ${TColors.Text};"
        }.let { layout.addWidget(it, 1, Qt.AlignmentFlag.AlignVCenter) }

        val loaderName = project.latestLoaders.firstOrNull()
        val pills = buildMetaTagsWidget(project.latestGameVersion, loaderName)
        if (pills != null) {
            layout.addWidget(pills, 0, Qt.AlignmentFlag.AlignVCenter)
        }
    }
}

// --- Instance item widget (instance list) ---

class InstanceItemWidget(val instance: DetectedInstance) : QFrame() {
    override fun sizeHint(): QSize = QSize(200, 44)

    init {
        objectName = "importableModRow"
        setFixedHeight(44)
        frameShape = Shape.NoFrame

        val layout = hBoxLayout(this) {
            setContentsMargins(8, 4, 12, 4)
            setSpacing(8)
        }

        val iconPath = LauncherDetector.resolveInstanceIcon(instance)
        val iconLabel = label {
            if (iconPath != null) {
                val sourcePix = QPixmap(iconPath.toAbsolute().toString())
                val mode = if (sourcePix.width() <= 64 || sourcePix.height() <= 64)
                    Qt.TransformationMode.FastTransformation else Qt.TransformationMode.SmoothTransformation
                pixmap = sourcePix.scaled(qs(32, 32), Qt.AspectRatioMode.KeepAspectRatio, mode)
            } else {
                pixmap = TIcons.Unknown.scaled(32, 32, Qt.AspectRatioMode.KeepAspectRatio, Qt.TransformationMode.FastTransformation)
            }
            setFixedSize(32, 32)
            setAlignment(Qt.AlignmentFlag.AlignCenter)
        }
        layout.addWidget(iconLabel, 0, Qt.AlignmentFlag.AlignVCenter)

        label(instance.name) {
            styleSheet = "font-size: 13px; font-weight: 600; color: ${TColors.Text};"
        }.let { layout.addWidget(it, 1, Qt.AlignmentFlag.AlignVCenter) }

        val pills = buildMetaTagsWidget(instance.gameVersion, instance.loader)
        if (pills != null) {
            layout.addWidget(pills, 0, Qt.AlignmentFlag.AlignVCenter)
        }
    }
}
