/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.ui.project.editor.lsp

import io.github.tritium_launcher.api.core.project.ProjectBase
import io.github.tritium_launcher.api.currentDpr
import io.github.tritium_launcher.api.io.VPath
import io.github.tritium_launcher.launcher.registrydb.RegistryItemDetail
import io.github.tritium_launcher.launcher.ui.theme.TColors
import io.qt.core.Qt
import io.qt.gui.QFont
import io.qt.gui.QPixmap
import io.qt.widgets.QHBoxLayout
import io.qt.widgets.QLabel
import io.qt.widgets.QVBoxLayout
import io.qt.widgets.QWidget
import kotlinx.coroutines.*

class ItemPreviewWidget(parent: QWidget? = null) : HoverContentWidget(parent) {
    private val outerLayout = QVBoxLayout(this)
    private val headerRow = QWidget()
    private val headerLayout = QHBoxLayout(headerRow)
    private val iconLabel = QLabel()
    private val textColumn = QWidget()
    private val textColumnLayout = QVBoxLayout(textColumn)
    private val nameLabel = QLabel()
    private val namespaceLabel = QLabel()
    private val idLabel = QLabel()
    private val tagsRow = QWidget()
    private val tagsLayout = QHBoxLayout(tagsRow)
    private val propsContainer = QWidget()
    private val propsLayout = QHBoxLayout(propsContainer)
    private var iconLoadJob: Job? = null

    init {
        outerLayout.setContentsMargins(8, 8, 8, 8)
        outerLayout.setSpacing(6)

        headerLayout.setContentsMargins(0, 0, 0, 0)
        headerLayout.setSpacing(8)
        iconLabel.setFixedSize(48, 48)
        iconLabel.setAlignment(Qt.AlignmentFlag.AlignCenter)

        textColumnLayout.setContentsMargins(0, 0, 0, 0)
        textColumnLayout.setSpacing(2)

        nameLabel.font = QFont(nameLabel.font).apply { setPointSize(11); setBold(true) }
        nameLabel.wordWrap = true

        namespaceLabel.styleSheet = "color: ${TColors.Accent}; font-size: 10px;"
        idLabel.styleSheet = "color: ${TColors.Subtext}; font-size: 10px;"

        textColumnLayout.addWidget(nameLabel)
        textColumnLayout.addWidget(namespaceLabel)
        textColumnLayout.addWidget(idLabel)

        headerLayout.addWidget(iconLabel)
        headerLayout.addWidget(textColumn, 1)
        outerLayout.addWidget(headerRow)

        tagsLayout.setContentsMargins(0, 0, 0, 0)
        tagsLayout.setSpacing(3)
        tagsLayout.addStretch(1)
        outerLayout.addWidget(tagsRow)

        propsLayout.setContentsMargins(0, 0, 0, 0)
        propsLayout.setSpacing(8)
        propsLayout.addStretch(1)
        outerLayout.addWidget(propsContainer)

        setStyleSheet("background: ${TColors.Surface0}; border: 1px solid ${TColors.Surface2}; border-radius: 4px;")
    }

    fun setItem(detail: RegistryItemDetail, snapshotDir: VPath?, project: ProjectBase, scope: CoroutineScope) {
        nameLabel.text = detail.displayName ?: detail.path
        idLabel.text = detail.id
        namespaceLabel.text = detail.namespace

        iconLabel.pixmap = null
        iconLabel.text = detail.displayName?.take(1) ?: "?"
        iconLoadJob?.cancel()
        val texPath = detail.texturePath
        iconLoadJob = scope.launch {
            val pixmap = withContext(Dispatchers.IO) { loadItemIcon(detail.id, texPath, snapshotDir, 48) }
            if (isActive) {
                if (pixmap != null) {
                    iconLabel.pixmap = pixmap
                    iconLabel.text = ""
                }
            }
        }

        while (tagsLayout.count() > 1) {
            tagsLayout.takeAt(0)?.widget()?.disposeLater()
        }
        detail.tags.forEach { tag ->
            tagsLayout.insertWidget(tagsLayout.count() - 1, QLabel(tag).apply {
                styleSheet = """
                    background-color: ${TColors.Surface2};
                    color: ${TColors.Text};
                    border-radius: 3px;
                    padding: 1px 5px;
                    font-size: 9px;
                """.trimIndent()
            })
        }
        tagsRow.isVisible = detail.tags.isNotEmpty()

        while (propsLayout.count() > 1) {
            propsLayout.takeAt(0)?.widget()?.disposeLater()
        }
        val props = mutableListOf<String>()
        detail.maxCount?.let { props.add("Max: $it") }
        detail.maxDamage?.let { props.add("Durability: $it") }
        detail.rarity?.let { props.add(it.replaceFirstChar { c -> c.uppercase() }) }
        detail.enchantability?.let { props.add("Enchant: $it") }
        props.forEach { p ->
            propsLayout.insertWidget(propsLayout.count() - 1, QLabel(p).apply {
                styleSheet = "color: ${TColors.Subtext}; font-size: 9px;"
            })
        }
        propsContainer.isVisible = props.isNotEmpty()

        adjustSize()
    }

    override fun clear() {
        iconLoadJob?.cancel()
        iconLabel.pixmap = null
        iconLabel.text = ""
        nameLabel.text = ""
        idLabel.text = ""
        namespaceLabel.text = ""
        while (tagsLayout.count() > 1) {
            tagsLayout.takeAt(0)?.widget()?.disposeLater()
        }
        while (propsLayout.count() > 1) {
            propsLayout.takeAt(0)?.widget()?.disposeLater()
        }
    }

    companion object {
        private val pixmapCache = object : LinkedHashMap<String, QPixmap>(128, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, QPixmap>?): Boolean = size > 256
        }

        internal fun loadItemIcon(id: String, texturePath: String?, snapshotDir: VPath?, size: Int): QPixmap? {
            if (snapshotDir == null) return null
            val dpr = currentDpr(QWidget(null as QWidget?))
            val physicalSize = (size * dpr).toInt()
            val cacheKey = "$id|$texturePath|$size|$dpr|${snapshotDir.toAbsolute()}"
            pixmapCache[cacheKey]?.let { return it }

            val candidates = buildList {
                val parts = id.split(':')
                if (parts.size == 2) {
                    val ns = parts[0]; val path = parts[1]
                    add("icons/${ns}/${path}.png")
                    add("icons/${ns}_${path.replace('/', '_')}.png")
                }
                texturePath?.let { add(it) }
                if (parts.size == 2) {
                    val ns = parts[0]; val path = parts[1]
                    addAll(listOf(
                        "assets/textures/${ns}/item/${path}.png",
                        "assets/textures/${ns}/block/${path}.png",
                        "assets/${ns}/textures/item/${path}.png",
                        "assets/${ns}/textures/block/${path}.png"
                    ))
                }
            }

            for (relPath in candidates) {
                val iconPath = snapshotDir.resolve(relPath)
                if (iconPath.exists()) {
                    val pixmap = QPixmap()
                    if (pixmap.load(iconPath.toAbsolute().toString())) {
                        if (pixmap.width() <= 32 && pixmap.height() <= 32) {
                            val scaled = pixmap.scaled(physicalSize, physicalSize,
                                Qt.AspectRatioMode.KeepAspectRatio, Qt.TransformationMode.FastTransformation)
                            scaled.setDevicePixelRatio(dpr)
                            pixmapCache[cacheKey] = scaled
                            return scaled
                        }
                        var img = pixmap.toImage()
                        while (img.width() / 2 >= physicalSize && img.height() / 2 >= physicalSize) {
                            img = img.scaled(img.width() / 2, img.height() / 2,
                                Qt.AspectRatioMode.IgnoreAspectRatio, Qt.TransformationMode.SmoothTransformation)
                        }
                        val result = QPixmap.fromImage(img)
                        result.setDevicePixelRatio(dpr)
                        pixmapCache[cacheKey] = result
                        return result
                    }
                }
            }
            return null
        }
    }
}
