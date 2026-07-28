/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.ui.search

import io.github.tritium_launcher.api.connect
import io.github.tritium_launcher.api.core.project.ProjectBase
import io.github.tritium_launcher.api.io.VPath
import io.github.tritium_launcher.api.logger
import io.github.tritium_launcher.api.search.SearchDetailContext
import io.github.tritium_launcher.api.search.SearchResult
import io.github.tritium_launcher.api.search.SearchResultRenderer
import io.github.tritium_launcher.launcher.core.project.ProjectMngr
import io.github.tritium_launcher.launcher.core.project.descriptors.CommunityDescriptors
import io.github.tritium_launcher.launcher.font.Fonts
import io.github.tritium_launcher.launcher.m
import io.github.tritium_launcher.launcher.registrydb.RegistryDatabase
import io.github.tritium_launcher.launcher.registrydb.RegistryDbStatus
import io.github.tritium_launcher.launcher.ui.project.sidebar.AnimatedItemMngr
import io.github.tritium_launcher.launcher.ui.project.sidebar.RegistryBrowserDockPanel
import io.github.tritium_launcher.launcher.ui.theme.TColors
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.vBoxLayout
import io.qt.core.QObject
import io.qt.core.QRect
import io.qt.core.QSize
import io.qt.core.Qt
import io.qt.gui.*
import io.qt.widgets.QLabel
import io.qt.widgets.QSizePolicy
import io.qt.widgets.QWidget
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.util.concurrent.ConcurrentHashMap

class RecipeSearchResultRenderer : SearchResultRenderer {
    override val id = "recipe"
    override val handledKinds = setOf("recipe")
    override val detailMinimumWidth: Int get() = 380

    private val logger = logger()
    private val json = Json { ignoreUnknownKeys = true }

    override fun buildDetailPane(result: SearchResult, context: SearchDetailContext): QWidget {
        return QWidget().apply {
            objectName = "recipeDetailBody"
            vBoxLayout(this) {
                contentsMargins = 16.m
                widgetSpacing = 8

                val project = ProjectMngr.activeProject

                logger.debug("buildDetailPane: kind={} id={} recipeType={} outputId={} project={}", result.kind, result.id, result.recipeType, result.outputId, project?.name)

                var recipeSourcePath: String? = null
                var hasBuilderSupport = false
                if (project != null) {
                    val detail = when (result.kind) {
                        "recipe" -> runCatching {
                            val id = result.id.removePrefix("recipe:")
                            RegistryDatabase.recipeDetail(project, id)
                        }.getOrNull()
                        else -> null
                    }
                    recipeSourcePath = detail?.sourcePath

                    hasBuilderSupport = hasBuilderSupport(detail?.rawJson)
                    logger.debug("hasBuilderSupport={}", hasBuilderSupport)

                    if (detail != null && detail.recipeTypeRawJson != null && hasBuilderSupport) {
                        val rtData = parseRecipeTypeData(detail.recipeType, detail.recipeTypeRawJson)
                        if (rtData != null) {
                            val fills = parseSlotFills(detail.rawJson, rtData.regions)
                            addWidget(RecipeSpritePreview(project, rtData, fills))
                        }
                    }

                    if (!hasBuilderSupport) {
                        val recipeType = detail?.recipeType ?: ""
                        val namespace = recipeType.substringBefore(":")
                        val communityRtData = if (namespace.isNotEmpty()) {
                            CommunityDescriptors.getCachedDescriptor(namespace)
                        } else null

                        if (communityRtData != null) {
                            logger.debug("Using community descriptor for fallback recipe type: {}", recipeType)
                            val localRegions = communityRtData.regions.map { it.toLocal() }
                            val fills = parseSlotFills(detail!!.rawJson, localRegions)
                            val localRt = RecipeTypeData(
                                id = communityRtData.id,
                                uiTexture = communityRtData.uiTexture,
                                spriteWidth = communityRtData.spriteWidth,
                                spriteHeight = communityRtData.spriteHeight,
                                regions = localRegions
                            )
                            addWidget(RecipeSpritePreview(project, localRt, fills))
                        } else {
                            val sourceJson = extractSourceJson(detail?.rawJson)
                            logger.debug("no builder support - sourceJson={}", sourceJson != null)
                            if (sourceJson != null) {
                                addWidget(SearchIconLoader.subtextHeader("RAW RECIPE DATA"))
                                addWidget(QWidget().apply {
                                    objectName = "rawJsonContainer"
                                    vBoxLayout(this) {
                                        contentsMargins = 8.m
                                        addWidget(QLabel().apply {
                                            val escaped = formatJson(sourceJson)
                                                .replace("&", "&amp;")
                                                .replace("<", "&lt;")
                                                .replace(">", "&gt;")
                                            text = "<pre style='margin:0;white-space:pre-wrap;color:${TColors.Text};font-size:12px;font-family:monospace;'>$escaped</pre>"
                                            wordWrap = true
                                            setTextInteractionFlags(Qt.TextInteractionFlag.TextSelectableByMouse)
                                        })
                                    }
                                })
                            }
                        }
                    }
                }

                val sourceFile = recipeSourcePath ?: result.path
                if (sourceFile.isNotEmpty()) {
                    addWidget(SearchIconLoader.subtextHeader("SOURCE FILE"))
                    addWidget(QLabel(sourceFile).apply {
                        wordWrap = true
                        styleSheet = "color: ${TColors.Text}; font-size: 13px;"
                    })
                }

                addStretch(1)
            }
        }
    }

    private fun parseRecipeTypeData(id: String?, rawJson: String): RecipeTypeData? {
        if (rawJson.isBlank()) return null
        return runCatching {
            val root = json.parseToJsonElement(rawJson).jsonObject
            val uiTexture = root["uiTexture"]?.jsonPrimitive?.contentOrNull ?: return@runCatching null
            val layoutObj = root["layout"]?.jsonObject
            val spriteW = layoutObj?.get("width")?.jsonPrimitive?.intOrNull ?: 176
            val spriteH = layoutObj?.get("height")?.jsonPrimitive?.intOrNull ?: 166
            val components = root["components"]?.jsonArray
            val regions = parseSlotRegions(components)
            RecipeTypeData(id ?: "", uiTexture, spriteW, spriteH, regions)
        }.getOrNull()
    }

    private fun parseSlotRegions(components: JsonArray?): List<SlotRegion> {
        if (components == null) return emptyList()
        return components.mapNotNull { elem ->
            val obj = elem.jsonObject
            val category = obj["category"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            if (category == "DURATION") return@mapNotNull null
            val data = obj["data"]?.jsonObject
            val compId = obj["id"]?.jsonPrimitive?.contentOrNull ?: ""
            val isInput = data?.get("isInput")?.jsonPrimitive?.booleanOrNull
            val role = when {
                category == "ENERGY" -> "ENERGY"
                isInput == true -> "INPUT"
                isInput == false -> "OUTPUT"
                isInput == null && compId.startsWith("input") -> "INPUT"
                isInput == null && compId.startsWith("output") -> "OUTPUT"
                else -> "CUSTOM"
            }
            val explicitSlotType = data?.containsKey("slotType") == true
            val slotType = data?.get("slotType")?.jsonPrimitive?.contentOrNull ?: category
            val label = data?.get("displayName")?.jsonPrimitive?.contentOrNull
            val displayOnly = data?.get("displayOnly")?.jsonPrimitive?.booleanOrNull ?: false
            SlotRegion(
                id = compId,
                label = label ?: compId,
                role = role,
                slotType = slotType,
                explicitSlotType = explicitSlotType,
                x = obj["x"]?.jsonPrimitive?.intOrNull ?: 0,
                y = obj["y"]?.jsonPrimitive?.intOrNull ?: 0,
                width = obj["width"]?.jsonPrimitive?.intOrNull ?: 18,
                height = obj["height"]?.jsonPrimitive?.intOrNull ?: 18,
                displayOnly = displayOnly
            )
        }
    }

    private fun hasBuilderSupport(rawJson: String?): Boolean {
        if (rawJson.isNullOrBlank()) return false
        return runCatching {
            val root = json.parseToJsonElement(rawJson).jsonObject
            val mode = root["display"]?.jsonObject?.get("mode")?.jsonPrimitive?.contentOrNull
            mode != "fallback"
        }.getOrDefault(false)
    }

    private fun extractSourceJson(rawJson: String?): String? {
        if (rawJson.isNullOrBlank()) return null
        return runCatching {
            val root = json.parseToJsonElement(rawJson).jsonObject
            root["sourceJson"]?.toString()
        }.getOrNull()
    }

    private fun formatJson(jsonStr: String): String {
        return runCatching {
            val element = json.parseToJsonElement(jsonStr)
            Json { prettyPrint = true }.encodeToString(element)
        }.getOrElse { jsonStr }
    }

    private fun parseSlotFills(rawJson: String, regions: List<SlotRegion>): Map<String, String> {
        if (rawJson.isBlank()) return emptyMap()
        return runCatching {
            val root = json.parseToJsonElement(rawJson).jsonObject
            val display = root["display"]?.jsonObject ?: return@runCatching emptyMap()
            val fills = mutableMapOf<String, String>()
            val regionIds = regions.map { it.id }
            val bindings = display["bindings"]?.jsonArray

            if (bindings != null && bindings.isNotEmpty()) {
                val bindingItems = bindings.mapNotNull { binding ->
                    val bObj = binding.jsonObject
                    val entries = bObj["entries"]?.jsonArray
                    val firstEntry = entries?.firstOrNull()?.jsonObject
                    firstEntry?.get("id")?.jsonPrimitive?.contentOrNull
                }
                for ((i, itemId) in bindingItems.withIndex()) {
                    val regionId = if (i < regionIds.size) regionIds[i] else continue
                    val binding = bindings.getOrNull(i)?.jsonObject
                    val componentId = binding?.get("componentId")?.jsonPrimitive?.contentOrNull
                    val targetId = if (componentId != null && componentId in regionIds) componentId else regionId
                    fills[targetId] = itemId
                }
                return@runCatching fills
            }

            val inputSlots = regions.filter { it.role == "INPUT" }
            val outputSlots = regions.filter { it.role == "OUTPUT" }

            val inputs = display["inputs"]?.jsonArray
            if (inputs != null) {
                for ((i, value) in inputs.withIndex()) {
                    val itemId = value.jsonObject["id"]?.jsonPrimitive?.contentOrNull ?: continue
                    val slot = inputSlots.getOrNull(i) ?: continue
                    fills[slot.id] = itemId
                }
            }

            val outputs = display["outputs"]?.jsonArray
            if (outputs != null) {
                for ((i, value) in outputs.withIndex()) {
                    val itemId = value.jsonObject["id"]?.jsonPrimitive?.contentOrNull ?: continue
                    val slot = outputSlots.getOrNull(i) ?: continue
                    fills[slot.id] = itemId
                }
            }

            fills
        }.getOrElse { emptyMap() }
    }

}

private class RecipeSpritePreview(
    private val project: ProjectBase,
    private val rt: RecipeTypeData,
    private val fills: Map<String, String>
) : QWidget() {
    companion object {
        private val DISPLAY_ONLY_BORDER = QColor(80, 80, 80, 160)
        private val DISPLAY_ONLY_FILL = QColor(0, 0, 0, 100)
        private val DISPLAY_ONLY_ICON = QColor(120, 120, 120, 80)
        private val FILLED_SLOT_FILL = QColor(100, 200, 100, 80)
        private val EMPTY_SLOT_FILL = QColor(255, 255, 255, 30)
        private val FILLED_SLOT_BORDER = QColor(255, 255, 255, 120)
        private val EMPTY_SLOT_BORDER = QColor(255, 255, 255, 60)
    }

    private val logger = logger()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var spritePixmap: QPixmap? = null
    private var displayPixmap: QPixmap? = null
    private var lastScale: Int = 0
    private val slotIconCache = ConcurrentHashMap<String, QPixmap>()
    private val animatedSlotTextures = ConcurrentHashMap<String, AnimatedItemMngr.ItemTexture.Animated>()

    private val snapshotDir: VPath? by lazy {
        val status = RegistryDatabase.status(project)
        if (status is RegistryDbStatus.Ready) status.manifestPath.parent() else null
    }

    private val animTickListener: () -> Unit = {
        val size = currentIconSize()
        for ((slotId, tex) in animatedSlotTextures) {
            val pix = AnimatedItemMngr.currentAnimatedPixmap(slotId, tex, size)
            if (!pix.isNull) slotIconCache[slotId] = pix
        }
        if (animatedSlotTextures.isNotEmpty()) update()
    }

    private var iconsLoadedAtSize = 0

    init {
        destroyed.connect { _: QObject? ->
            scope.cancel()
            AnimatedItemMngr.unregisterTickListener(animTickListener)
        }

        spritePixmap = SearchIconLoader.loadSpritePixmap(project, rt.uiTexture)
        logger.debug("RecipeSpritePreview: uiTexture={} pixmap={} regions={} fills={}", rt.uiTexture, spritePixmap != null && !spritePixmap!!.isNull, rt.regions.size, fills.size)

        AnimatedItemMngr.ensureStarted()
        AnimatedItemMngr.registerTickListener(animTickListener)
        setMinimumSize(100, 60)
        setSizePolicy(
            QSizePolicy.Policy.Expanding,
            QSizePolicy.Policy.Preferred
        )
    }

    private fun currentIconSize(): Int {
        val pad = 8
        val availW = width() - pad * 2
        val scale = if (rt.spriteWidth > 0) maxOf(1, availW / rt.spriteWidth) else 1
        return (16 * scale).coerceIn(16, 64)
    }

    private fun loadSlotIcons(size: Int) {
        if (snapshotDir == null || size <= 0) return
        iconsLoadedAtSize = size
        scope.launch(Dispatchers.Default) {
            for ((slotId, itemId) in fills) {
                if (slotIconCache.containsKey(slotId)) continue
                val texPath = runCatching { RegistryDatabase.itemTexturePath(project, itemId) }.getOrNull()
                val animJson = runCatching { RegistryDatabase.itemAnimationJson(project, itemId) }.getOrNull()
                val tex = RegistryBrowserDockPanel.loadItemTexture(itemId, texPath, animJson, snapshotDir, size)
                if (tex is AnimatedItemMngr.ItemTexture.Animated) {
                    val initialPix = AnimatedItemMngr.currentAnimatedPixmap(slotId, tex, size)
                    if (!initialPix.isNull) {
                        animatedSlotTextures[slotId] = tex
                        slotIconCache[slotId] = initialPix
                    }
                } else if (tex is AnimatedItemMngr.ItemTexture.Static) {
                    slotIconCache[slotId] = tex.pixmap
                }
            }
            withContext(Dispatchers.Main) { update() }
        }
    }

    override fun sizeHint(): QSize = QSize(rt.spriteWidth, rt.spriteHeight)

    override fun resizeEvent(event: io.qt.gui.QResizeEvent?) {
        super.resizeEvent(event)
        val size = currentIconSize()
        if (size != iconsLoadedAtSize) {
            animatedSlotTextures.clear()
            slotIconCache.clear()
            loadSlotIcons(size)
        }
    }

    override fun hasHeightForWidth(): Boolean = true

    override fun heightForWidth(w: Int): Int {
        val pad = 8
        val availW = w - pad * 2
        if (availW <= 0) return minimumHeight()
        val h = (availW.toFloat() * rt.spriteHeight / rt.spriteWidth + pad * 2).toInt()
        return h.coerceAtLeast(minimumHeight())
    }

    private fun ensureDisplayPixmap(scale: Int) {
        val src = spritePixmap ?: return
        if (scale == lastScale && displayPixmap != null) return
        displayPixmap = src.scaled(
            (src.width() * scale).coerceAtLeast(1),
            (src.height() * scale).coerceAtLeast(1),
            Qt.AspectRatioMode.IgnoreAspectRatio,
            Qt.TransformationMode.FastTransformation
        )
        lastScale = scale
    }

    override fun paintEvent(event: QPaintEvent?) {
        val painter = QPainter(this)
        painter.fillRect(rect(), TColors.Surface0.toQC())

        val pad = 8
        val availW = width() - pad * 2
        val availH = height() - pad * 2
        if (availW <= 0 || availH <= 0) { painter.end(); return }

        val scale = maxOf(1, minOf(availW / rt.spriteWidth, availH / rt.spriteHeight))
        val scaledW = rt.spriteWidth * scale
        val scaledH = rt.spriteHeight * scale
        val drawX = pad + (availW - scaledW) / 2
        val drawY = pad + (availH - scaledH) / 2

        ensureDisplayPixmap(scale)
        if (displayPixmap != null) {
            painter.drawPixmap(drawX, drawY, displayPixmap!!)
        } else {
            painter.setPen(TColors.Subtext.toQC())
            painter.drawText(QRect(drawX, drawY, scaledW, scaledH),
                Qt.AlignmentFlag.AlignCenter.value(), "No sprite available")
        }

        for (region in rt.regions) {
            val rx = drawX + (region.x * scale)
            val ry = drawY + (region.y * scale)
            val rw = (region.width * scale)
            val rh = (region.height * scale)
            val fillId = fills[region.id]

            if (region.displayOnly) {
                painter.setPen(QPen(DISPLAY_ONLY_BORDER, 1.0))
                painter.setBrush(QBrush(DISPLAY_ONLY_FILL))
                painter.drawRect(QRect(rx, ry, rw, rh))
                painter.setPen(DISPLAY_ONLY_ICON)
                painter.setFont(QFont(Fonts.Monocraft, (minOf(rw, rh) / 4).coerceIn(8, 14)))
                painter.drawText(QRect(rx, ry, rw, rh),
                    Qt.AlignmentFlag.AlignCenter.value(), "\u26ED")
                continue
            }

            val hasItem = fillId != null
            val slotColor = if (hasItem) FILLED_SLOT_FILL else EMPTY_SLOT_FILL

            painter.setPen(QPen(if (hasItem) FILLED_SLOT_BORDER else EMPTY_SLOT_BORDER, 1.0))
            painter.setBrush(QBrush(slotColor))
            painter.drawRect(QRect(rx, ry, rw, rh))

            val icon = slotIconCache[region.id]
            if (icon != null && !icon.isNull) {
                val ix = rx + (rw - icon.width()) / 2
                val iy = ry + (rh - icon.height()) / 2
                painter.drawPixmap(ix, iy, icon)
            }
        }

        painter.end()
    }
}

private data class RecipeTypeData(
    val id: String,
    val uiTexture: String,
    val spriteWidth: Int,
    val spriteHeight: Int,
    val regions: List<SlotRegion>
)

private data class SlotRegion(
    val id: String,
    val label: String,
    val role: String,
    val slotType: String,
    val explicitSlotType: Boolean,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val displayOnly: Boolean = false
)

private fun io.github.tritium_launcher.launcher.ui.project.sidebar.SlotRegion.toLocal() =
    SlotRegion(id, label, role, slotType, explicitSlotType, x, y, width, height, displayOnly)
