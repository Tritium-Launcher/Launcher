/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.ui.project.sidebar

import io.github.tritium_launcher.api.Quintuple
import io.github.tritium_launcher.api.connect
import io.github.tritium_launcher.api.core.project.ProjectBase
import io.github.tritium_launcher.api.docks.DockPanelProvider
import io.github.tritium_launcher.api.docks.DockWidget
import io.github.tritium_launcher.api.io.VPath
import io.github.tritium_launcher.api.logger
import io.github.tritium_launcher.api.state.FlushPolicy
import io.github.tritium_launcher.api.state.Persistable
import io.github.tritium_launcher.api.state.UIStateMngr
import io.github.tritium_launcher.launcher.core.project.descriptors.CommunityDescriptors
import io.github.tritium_launcher.launcher.extension.core.CoreSettingValues
import io.github.tritium_launcher.launcher.font.Fonts
import io.github.tritium_launcher.launcher.registrydb.RegistryDatabase
import io.github.tritium_launcher.launcher.registrydb.RegistryDbStatus
import io.github.tritium_launcher.launcher.registrydb.RegistryRefreshService
import io.github.tritium_launcher.launcher.ui.project.editor.panes.TextEditorPane
import io.github.tritium_launcher.launcher.ui.theme.TColors
import io.github.tritium_launcher.launcher.ui.theme.TIcons
import io.github.tritium_launcher.launcher.ui.theme.qt.icon
import io.qt.core.*
import io.qt.gui.*
import io.qt.widgets.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import recipe.TGenerationTypeRegistry
import java.util.concurrent.ConcurrentHashMap

class RecipeBuilderDockPanel : DockPanelProvider {
    override val id: String = "recipe_builder"
    override val displayName: String = "Recipe Builder"
    override var icon: QIcon? = TIcons.RecipeBuilder.icon
    override val order: Int = 17
    override val preferredArea: Qt.DockWidgetArea = Qt.DockWidgetArea.RightDockWidgetArea

    override fun create(project: ProjectBase): DockWidget {
        val dock = DockWidget(displayName, null)
        dock.setWidget(RecipeBuilderWidget(project))
        return dock
    }
}

internal class RecipeBuilderWidget(
    private val project: ProjectBase
) : QWidget(), Persistable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override val persistKey = "recipe_builder"
    override val flushPolicy = FlushPolicy.Immediate

    private val logger = logger()
    private val json = Json { ignoreUnknownKeys = true }

    private val recipeTypeCombo = QComboBox()
    private val spriteWidget: SpriteWidget
    private val formatCombo = QComboBox()
    private val optionWidgets = mutableMapOf<String, QWidget>()
    private val optionsLayout = QVBoxLayout()
    private val variantLayout = QHBoxLayout()
    private val variantGroup = QButtonGroup(this)
    private val copyBtn = QPushButton("Copy")
    private val preview = QPlainTextEdit()
    private val codeGenWidget = QWidget()

    private var snapshotDir: VPath? = null
    private var currentRecipeTypes: List<RecipeTypeData> = emptyList()
    private var currentGenOptions: List<GenerationOption> = emptyList()
    private var currentVariantOptionKey: String? = null

    private var linkedSource: LinkedSource? = null
    private var pushingToLinkedEditor = false

    private var pendingRecipeTypeId: String? = null

    private class RecipeTypeDelegate : QStyledItemDelegate() {
        override fun paint(painter: QPainter?, option: QStyleOptionViewItem, index: QModelIndex) {
            val opt = QStyleOptionViewItem(option)
            val p = painter ?: return
            val rect = opt.rect
            val palette = opt.palette

            p.save()
            if (opt.state.testFlag(QStyle.StateFlag.State_Selected) ||
                opt.state.testFlag(QStyle.StateFlag.State_MouseOver)) {
                p.fillRect(rect, palette.color(QPalette.ColorRole.Highlight))
                p.setPen(palette.color(QPalette.ColorRole.HighlightedText))
            } else {
                p.setPen(palette.color(QPalette.ColorRole.Text))
            }

            val text = index.data(Qt.ItemDataRole.DisplayRole).toString()
            val namespace = index.data(NAMESPACE_ROLE)?.toString() ?: ""

            val font = p.font()
            val metrics = QFontMetrics(font)

            val leftMargin = 6
            val rightMargin = 6
            val spacing = 12

            val nsWidth = metrics.horizontalAdvance(namespace)
            val textRect = rect.adjusted(leftMargin, 0, -(rightMargin + nsWidth + spacing), 0)
            p.drawText(textRect, Qt.AlignmentFlag.AlignVCenter.value(), text)

            val nsRect = rect.adjusted(rect.width() - rightMargin - nsWidth, 0, -rightMargin, 0)
            val mutedPen = QColor(palette.color(QPalette.ColorRole.Text))
            mutedPen.setAlpha(140)
            p.setPen(mutedPen)
            p.drawText(nsRect, Qt.AlignmentFlag.AlignVCenter.value(), namespace)
            p.restore()
        }
    }
    private var pendingFormatId: String? = null
    private var pendingSlots: JsonObject? = null
    private var pendingOptions: JsonObject? = null

    init {
        destroyed.connect { _: QObject? ->
            UIStateMngr.unregister(this)
            scope.cancel()
        }
        UIStateMngr.register(this)

        spriteWidget = SpriteWidget(project)

        val topBar = QHBoxLayout()
        topBar.setContentsMargins(8, 4, 8, 4)
        topBar.addWidget(QLabel("Recipe Type:"))
        topBar.addWidget(recipeTypeCombo, 1)

        optionsLayout.setContentsMargins(8, 0, 8, 4)
        optionsLayout.setSpacing(4)

        variantLayout.setContentsMargins(8, 2, 8, 2)

        val previewBar = QHBoxLayout()
        previewBar.setContentsMargins(8, 2, 8, 2)
        previewBar.addWidget(QLabel("Format:"))
        previewBar.addWidget(formatCombo)
        previewBar.addStretch()
        previewBar.addWidget(copyBtn)

        preview.setReadOnly(true)
        preview.setFont(QFont(CoreSettingValues.editorFont().first, CoreSettingValues.editorFont().second))
        preview.setMaximumHeight(160)
        preview.minimumHeight = 80

        val codeGenLayout = QVBoxLayout(codeGenWidget)
        codeGenLayout.setContentsMargins(0, 0, 0, 0)
        codeGenLayout.setSpacing(0)
        codeGenLayout.addLayout(previewBar)
        codeGenLayout.addWidget(preview)

        val layout = QVBoxLayout(this)
        layout.setContentsMargins(0, 0, 0, 0)
        layout.setSpacing(0)
        layout.addLayout(topBar)
        spriteWidget.acceptDrops = true
        layout.addWidget(spriteWidget)
        layout.addLayout(optionsLayout)
        layout.addLayout(variantLayout)
        layout.addWidget(codeGenWidget)

        recipeTypeCombo.currentIndexChanged.connect({ _: Int -> onRecipeTypeSelected() })
        formatCombo.currentIndexChanged.connect({ _: Int ->
            val text = regeneratePreview()
            if (text != null && linkedSource != null) {
                pushToLinkedEditor(text)
            }
        })
        variantGroup.buttonClicked.connect({ btn: QAbstractButton? ->
            if (btn != null) {
                val vk = btn.property("variantKey")?.toString()
                val tmpl = spriteWidget.currentRecipeType?.templates
                if (vk != null && tmpl != null) {
                    val defaults = tmpl.variantDefaults[vk]
                    if (defaults != null) {
                        for ((key, value) in defaults) {
                            val widget = optionWidgets[key]
                            if (widget is QLineEdit) {
                                widget.text = value
                            }
                        }
                    }
                }
            }
            val text = regeneratePreview()
            if (text != null && linkedSource != null) {
                pushToLinkedEditor(text)
            }
        })
        copyBtn.clicked.connect {
            QApplication.clipboard()?.setText(preview.toPlainText())
        }
        spriteWidget.onItemsChanged = {
            val text = regeneratePreview()
            markDirty()
            if (text != null && linkedSource != null) {
                pushToLinkedEditor(text)
            }
        }

        populateFormats()
        loadRecipeTypes()
        connectDbUpdates()
    }

    private fun populateFormats() {
        val formats = TGenerationTypeRegistry.getAll().toList()
        formats.forEach { formatCombo.addItem(it.displayName(), it.id()) }
    }

    private fun loadRecipeTypes() {
        scope.launch(Dispatchers.Default) {
            runCatching {
                val status = RegistryDatabase.status(project)
                if (status is RegistryDbStatus.Ready) {
                    snapshotDir = status.manifestPath.parent()
                    RegistryDatabase.allRecipeTypes(project)
                } else emptyList()
            }.onSuccess { types ->
                val parsed = types.mapNotNull { type ->
                    val rt = parseRecipeTypeJson(type.id, type.displayName, type.rawJson)
                    if (rt.templates != null) rt else null
                }
                val native = parsed.groupBy { it.uiTexture }.map { (_, group) ->
                    group.first()
                }
                val community = CommunityDescriptors.loadForProject(project)
                val nativeIds = native.map { it.id }.toSet()
                val communityIds = community.map { it.id }.toSet()
                val merged = native + community.filter { it.id !in nativeIds }
                val sorted = merged.sortedWith(compareBy<RecipeTypeData> {
                    val ns = it.id.substringBefore(":")
                    if (ns == "minecraft") 0 else 1
                }.thenBy { it.id.substringBefore(":") }
                 .thenBy { it.displayName })
                withContext(Dispatchers.Main) {
                    currentRecipeTypes = sorted
                    recipeTypeCombo.clear()
                    for (rt in currentRecipeTypes) {
                        val namespace = rt.id.substringBefore(":")
                        val isCommunity = rt.id in communityIds
                        val label = if (isCommunity) "${rt.displayName}  (Community)" else rt.displayName
                        recipeTypeCombo.addItem(label, rt.id)
                        recipeTypeCombo.setItemData(recipeTypeCombo.count - 1, namespace, NAMESPACE_ROLE)
                        recipeTypeCombo.setItemData(recipeTypeCombo.count - 1, isCommunity, COMMUNITY_ROLE)
                    }
                    recipeTypeCombo.setItemDelegate(RecipeTypeDelegate())
                    if (currentRecipeTypes.isNotEmpty()) {
                        recipeTypeCombo.currentIndex = 0
                    }
                    applyPendingRestore()
                }
            }
        }
    }

    private fun parseRecipeTypeJson(id: String, displayName: String?, rawJson: String): RecipeTypeData {
        return runCatching {
            val root = json.parseToJsonElement(rawJson).jsonObject
            val uiTexture = root["uiTexture"]?.jsonPrimitive?.contentOrNull ?: ""
            val layoutObj = root["layout"]?.jsonObject
            val spriteW = layoutObj?.get("width")?.jsonPrimitive?.intOrNull ?: 176
            val spriteH = layoutObj?.get("height")?.jsonPrimitive?.intOrNull ?: 166
            val slotRegions = parseSlotRegions(root["components"]?.jsonArray)
            val genOpts = parseGenerationOptions(root["generationOptions"]?.jsonArray)
            val templates = parseTemplates(root["templates"]?.jsonObject)
            val kubeJsMethods = root["kubeJsMethods"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
            val importSkipArgs = root["importSkipArgs"]?.jsonPrimitive?.intOrNull ?: 0
            val importOptions = root["importPositionalOptions"]?.jsonArray?.mapNotNull { elem ->
                val obj = elem.jsonObject
                val key = obj["key"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val idx = obj["positionalIndex"]?.jsonPrimitive?.intOrNull
                ImportOptionDef(key, positionalIndex = idx)
            } ?: emptyList()
            RecipeTypeData(id, displayName ?: id, uiTexture, spriteW, spriteH, slotRegions,
                generationOptions = genOpts, templates = templates, kubeJsMethods = kubeJsMethods,
                importSkipArgs = importSkipArgs, importOptions = importOptions)
        }.getOrDefault(RecipeTypeData(id, displayName ?: id, "", 176, 166, emptyList()))
    }

    private fun parseTemplates(obj: JsonObject?): TemplatesData? {
        if (obj == null) return null
        val variantOption = obj["variantOption"]?.jsonPrimitive?.contentOrNull
        val autoValue = obj["autoValue"]?.jsonPrimitive?.contentOrNull
        val expectsGrid = obj["expectsGrid"]?.jsonPrimitive?.booleanOrNull ?: false
        val gridSlots = obj["gridSlots"]?.jsonPrimitive?.contentOrNull
        val gridCols = obj["gridCols"]?.jsonPrimitive?.intOrNull ?: 3
        val formatsJson = obj["formats"]?.jsonObject ?: return null
        val formats = mutableMapOf<String, Map<String, String>>()
        for ((fmtId, fmtVal) in formatsJson) {
            val templates: Map<String, String> = when {
                fmtVal is JsonPrimitive && fmtVal.isString ->
                    mapOf("_" to fmtVal.content)
                fmtVal is JsonObject -> {
                    fmtVal.entries.associate { it.key to it.value.jsonPrimitive.content }
                }
                else -> continue
            }
            if (templates.isNotEmpty()) formats[fmtId] = templates
        }
        if (formats.isEmpty()) return null
        val variantDefaults = mutableMapOf<String, Map<String, String>>()
        val vdObj = obj["variantDefaults"]?.jsonObject
        if (vdObj != null) {
            for ((vk, vv) in vdObj) {
                val vvObj = vv.jsonObject
                if (vvObj != null) {
                    variantDefaults[vk] = vvObj.entries.associate { it.key to it.value.jsonPrimitive.content }
                }
            }
        }
        return TemplatesData(variantOption, autoValue, expectsGrid, gridSlots, gridCols, formats, variantDefaults)
    }

    private fun parseGenerationOptions(arr: JsonArray?): List<GenerationOption> {
        if (arr == null) return emptyList()
        return arr.mapNotNull { elem ->
            val obj = elem.jsonObject
            val k = obj["key"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            GenerationOption(
                key = k,
                label = obj["label"]?.jsonPrimitive?.contentOrNull ?: k,
                type = obj["type"]?.jsonPrimitive?.contentOrNull ?: "text",
                placeholder = obj["placeholder"]?.jsonPrimitive?.contentOrNull ?: "",
                defaultValue = obj["defaultValue"]?.jsonPrimitive?.contentOrNull ?: ""
            )
        }
    }

    private fun parseSlotRegions(components: JsonArray?): List<SlotRegion> {
        if (components == null) return emptyList()
        return components.mapNotNull { elem ->
            val obj = elem.jsonObject
            val category = obj["category"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            if (category == "DURATION") return@mapNotNull null
            val data = obj["data"]?.jsonObject
            val isInput = data?.get("isInput")?.jsonPrimitive?.booleanOrNull
            val role = when {
                category == "ENERGY" -> "ENERGY"
                isInput == true -> "INPUT"
                isInput == false -> "OUTPUT"
                else -> "CUSTOM"
            }
            val explicitSlotType = data?.containsKey("slotType") == true
            val slotType = data?.get("slotType")?.jsonPrimitive?.contentOrNull ?: category
            val label = data?.get("displayName")?.jsonPrimitive?.contentOrNull
            val maxCapacity = data?.get("maxCapacity")?.jsonPrimitive?.longOrNull ?: 64
            val displayOnly = data?.get("displayOnly")?.jsonPrimitive?.booleanOrNull ?: false
            SlotRegion(
                id = obj["id"]?.jsonPrimitive?.contentOrNull ?: "",
                label = label ?: obj["id"]?.jsonPrimitive?.contentOrNull ?: "",
                role = role,
                slotType = slotType,
                explicitSlotType = explicitSlotType,
                x = obj["x"]?.jsonPrimitive?.intOrNull ?: 0,
                y = obj["y"]?.jsonPrimitive?.intOrNull ?: 0,
                width = obj["width"]?.jsonPrimitive?.intOrNull ?: 18,
                height = obj["height"]?.jsonPrimitive?.intOrNull ?: 18,
                maxCapacity = maxCapacity,
                displayOnly = displayOnly
            )
        }
    }

    private fun collectOptionValues(): Map<String, String> {
        val values = mutableMapOf<String, String>()
        for ((key, widget) in optionWidgets) {
            val value = when (widget) {
                is QLineEdit -> widget.text()
                else -> ""
            }
            if (value.isNotEmpty()) values[key] = value
        }
        val vok = currentVariantOptionKey
        if (vok != null) {
            val checked = variantGroup.checkedButton()
            if (checked != null) {
                val vk = checked.property("variantKey")?.toString()
                if (vk != null) values[vok] = vk
            }
        }
        return values
    }

    private fun clearLayout(layout: QLayout) {
        while (layout.count() > 0) {
            val item = layout.takeAt(0)
            val w = item?.widget()
            if (w != null) {
                w.dispose()
            } else {
                item?.layout()?.let { clearLayout(it) }
            }
        }
    }

    private fun rebuildOptionWidgets(options: List<GenerationOption>, templates: TemplatesData?) {
        currentGenOptions = options

        clearLayout(optionsLayout)
        optionWidgets.clear()

        for (radio in variantGroup.buttons()) {
            variantLayout.removeWidget(radio as QWidget)
            variantGroup.removeButton(radio)
            radio.dispose()
        }
        while (variantLayout.count() > 0) {
            val item = variantLayout.takeAt(0)
            item?.widget()?.dispose()
        }

        currentVariantOptionKey = templates?.variantOption
        if (templates?.variantOption != null) {
            val variantNames = templates.formats.values
                .flatMap { it.keys }
                .distinct()
                .sorted()
            if (templates.autoValue != null) {
                val autoRadio = QRadioButton("Auto-detect")
                autoRadio.setProperty("variantKey", templates.autoValue)
                variantGroup.addButton(autoRadio)
                variantLayout.addWidget(autoRadio)
                autoRadio.isChecked = true
            }
            for (variant in variantNames) {
                val display = variant.replaceFirstChar { it.uppercase() }
                val radio = QRadioButton(display)
                radio.setProperty("variantKey", variant)
                variantGroup.addButton(radio)
                variantLayout.addWidget(radio)
            }
            variantLayout.addStretch()
        }

        for ((key, label1, type, placeholder, defaultValue) in options) {
            when (type) {
                "text" -> {
                    val input = QLineEdit()
                    input.placeholderText = placeholder
                    if (defaultValue.isNotEmpty()) input.text = defaultValue
                    input.textChanged.connect { _: String? ->
                        val text = regeneratePreview()
                        markDirty()
                        if (text != null && linkedSource != null) {
                            pushToLinkedEditor(text)
                        }
                    }
                    val label = QLabel("$label1:")
                    val row = QHBoxLayout()
                    row.setContentsMargins(0, 0, 0, 0)
                    row.addWidget(label)
                    row.addWidget(input, 1)
                    optionsLayout.addLayout(row)
                    optionWidgets[key] = input
                }
            }
        }
        optionsLayout.addStretch()
    }

    private fun onRecipeTypeSelected() {
        breakLink()
        val idx = recipeTypeCombo.currentIndex()
        if (idx < 0 || idx >= currentRecipeTypes.size) return
        val rt = currentRecipeTypes[idx]
        loadSprite(rt)
        rebuildOptionWidgets(rt.generationOptions, rt.templates)
        spriteWidget.setRecipeType(rt, snapshotDir)
    }

    private fun loadSprite(rt: RecipeTypeData) {
        if (rt.uiTexture.isBlank()) {
            spriteWidget.updatePixmap(null)
            return
        }
        scope.launch(Dispatchers.Default) {
            val pixmap = runCatching {
                val file = when {
                    rt.uiTexture.startsWith("/") -> VPath.parse(rt.uiTexture)
                    snapshotDir != null -> snapshotDir!!.resolve(uiTextureToDumpPath(rt.uiTexture))
                    else -> null
                }
                if (file != null && file.exists()) {
                    QPixmap(file.toAbsolute().expandHome().toString())
                } else null
            }.getOrNull()
            withContext(Dispatchers.Main) {
                spriteWidget.updatePixmap(pixmap)
            }
        }
    }

    private fun uiTextureToDumpPath(uiTexture: String): String {
        val colonIdx = uiTexture.indexOf(':')
        if (colonIdx == -1) return "assets/textures/${uiTexture}.png"
        val namespace = uiTexture.substring(0, colonIdx)
        val path = uiTexture.substring(colonIdx + 1).removeSuffix(".png")
        return "assets/textures/$namespace/$path.png"
    }

    private fun regeneratePreview(): String? {
        val rt = spriteWidget.currentRecipeType ?: run { preview.plainText = "// No recipe type selected"; return null }
        val fills = spriteWidget.currentFills()
        logger.debug("regeneratePreview: fills={}", fills.entries.joinToString(", ") { "${it.key}=${it.value.itemId}" })
        val formatIdx = formatCombo.currentIndex()
        if (formatIdx < 0) { preview.plainText = "// No format selected"; return null }
        val formatId = formatCombo.itemData(formatIdx) as? String ?: run { preview.plainText = "// Invalid format"; return null }

        val templates = rt.templates ?: run {
            preview.plainText = "// No generation templates provided by this recipe type"
            return null
        }

        val formatTemplates = templates.formats[formatId] ?: run {
            preview.plainText = "// No template for format '$formatId'"
            return null
        }

        val options = collectOptionValues()
        val variant = resolveVariant(templates, formatTemplates, fills, options)
        val template = formatTemplates[variant] ?: run {
            preview.plainText = "// No template for variant '$variant'"
            return null
        }

        val rendered = renderTemplate(template, fills, options, formatId)

        val result = if (formatId == "kubejs_custom") {
            val jsonFormat = templates.formats["json"]
            if (jsonFormat != null) {
                val jsonVariant = resolveVariant(templates, jsonFormat, fills, options)
                val jsonTemplate = jsonFormat[jsonVariant]
                if (jsonTemplate != null) {
                    val jsonRendered = renderTemplate(jsonTemplate, fills, options, "json")
                    rendered.replace("{{ raw_json }}", jsonRendered)
                } else rendered
            } else rendered
        } else rendered

        preview.plainText = result
        return result
    }

    private fun resolveVariant(
        templates: TemplatesData,
        formatTemplates: Map<String, String>,
        fills: Map<String, SlotEntry>,
        options: Map<String, String>
    ): String {
        if (formatTemplates.size == 1 || templates.variantOption == null) {
            return formatTemplates.keys.first()
        }
        val selected = options[templates.variantOption] ?: templates.autoValue ?: return formatTemplates.keys.first()
        if (selected == templates.autoValue && templates.expectsGrid) {
            val detected = detectGridVariant(fills, templates.gridSlots ?: "", templates.gridCols)
            if (formatTemplates.containsKey(detected)) return detected
        }
        if (formatTemplates.containsKey(selected)) return selected
        return formatTemplates.keys.first()
    }

    private fun detectGridVariant(fills: Map<String, SlotEntry>, slots: String, cols: Int): String {
        val slotIds = expandSlotRange(slots)
        val inputs = slotIds.map { fills[it] }
        if (inputs.all { it == null }) return "shapeless"
        return if (isCompactGrid(inputs, cols)) "shaped" else "shapeless"
    }

    private fun expandSlotRange(range: String): List<String> {
        val parts = range.split("..")
        if (parts.size != 2) return listOf(range)
        val prefix = parts[0].substringBeforeLast("_")
        val startNum = parts[0].substringAfterLast("_").toIntOrNull() ?: return listOf(range)
        val endNum = parts[1].substringAfterLast("_").toIntOrNull() ?: return listOf(range)
        return (startNum..endNum).map { "${prefix}_$it" }
    }

    private fun isCompactGrid(inputs: List<SlotEntry?>, cols: Int): Boolean {
        var minRow = cols; var maxRow = -1; var minCol = cols; var maxCol = -1
        for (i in inputs.indices) {
            if (inputs[i] != null) {
                val r = i / cols; val c = i % cols
                minRow = minOf(minRow, r); maxRow = maxOf(maxRow, r)
                minCol = minOf(minCol, c); maxCol = maxOf(maxCol, c)
            }
        }
        if (maxRow < 0) return false
        val area = (maxRow - minRow + 1) * (maxCol - minCol + 1)
        val filled = inputs.count { it != null }
        return filled > 1 && filled == area
    }

    private fun renderTemplate(
        template: String,
        fills: Map<String, SlotEntry>,
        options: Map<String, String>,
        formatId: String
    ): String {
        val regex = Regex("""\{\{(.+?)}}""")
        return regex.replace(template) { match ->
            val content = match.groupValues[1].trim()
            val pipeIdx = content.lastIndexOf('|')
            val (expr, fmt) = if (pipeIdx != -1) {
                content.substring(0, pipeIdx).trim() to content.substring(pipeIdx + 1).trim()
            } else {
                content to formatId
            }
            val parts = expr.split(":")
            val processor = parts[0]
            val args = parts.drop(1)
            when (processor) {
                "fill" -> processFill(args, fills, fmt)
                "result" -> processResult(args, fills, fmt)
                "list" -> processList(args, fills, fmt)
                "grid" -> processGrid(args, fills, fmt)
                "keyMap" -> processKeyMap(args, fills, fmt)
                "qty" -> processQty(args, fills)
                "option" -> processOption(args, options)
                else -> {
                    val entry = fills[expr] ?: return@replace ""
                    formatItemRef(entry.itemId, fmt)
                }
            }
        }
    }

    private fun buildKeyGrid(inputs: List<SlotEntry?>, minRow: Int, maxRow: Int, minCol: Int, maxCol: Int, cols: Int): Array<CharArray> {
        val rows = maxRow - minRow + 1
        val gridCols = maxCol - minCol + 1
        val grid = Array(rows) { CharArray(gridCols) { ' ' } }
        val seen = mutableMapOf<String, Char>()
        var next = 'A'
        for (r in minRow..maxRow) {
            for (c in minCol..maxCol) {
                val idx = r * cols + c
                val fill = inputs[idx] ?: continue
                val key = seen.getOrPut(fill.itemId) { next++ }
                grid[r - minRow][c - minCol] = key
            }
        }
        return grid
    }

    private fun processFill(args: List<String>, fills: Map<String, SlotEntry>, formatId: String): String {
        val slotId = args.firstOrNull() ?: return ""
        val entry = fills[slotId] ?: return ""
        val fmt = if (args.size > 1) args[1] else formatId
        return formatItemRef(entry.itemId, fmt)
    }

    private fun processResult(args: List<String>, fills: Map<String, SlotEntry>, formatId: String): String {
        val entry = fills["output"]
        logger.debug("processResult: fills keys={} output={}", fills.keys, entry?.itemId)
        if (entry == null) return "// no result"
        val fmt = args.firstOrNull() ?: formatId
        val isTag = entry.itemId.startsWith("#")
        val cleanId = if (isTag) entry.itemId.removePrefix("#") else entry.itemId
        return when (fmt) {
            "json" -> {
                val sb = StringBuilder()
                if (isTag) {
                    sb.append("{\n    \"tag\": \"$cleanId\"")
                } else {
                    sb.append("{\n    \"id\": \"$cleanId\"")
                }
                if (entry.quantity > 1) sb.append(",\n    \"count\": ${entry.quantity}")
                sb.append("\n  }")
                sb.toString()
            }
            "kubejs" -> {
                if (isTag) {
                    if (entry.quantity > 1) "Ingredient.of('#$cleanId', ${entry.quantity})"
                    else "'#$cleanId'"
                } else {
                    if (entry.quantity > 1) "Item.of('$cleanId', ${entry.quantity})"
                    else "'$cleanId'"
                }
            }
            else -> cleanId
        }
    }

    private fun formatItemRef(itemId: String, fmt: String): String {
        val isTag = itemId.startsWith("#")
        return when (fmt) {
            "json" -> if (isTag) "{\"tag\": \"${itemId.removePrefix("#")}\"}" else "{\"item\": \"$itemId\"}"
            "kubejs" -> if (isTag) "'#${itemId.removePrefix("#")}'" else "'$itemId'"
            "id" -> "\"$itemId\""
            else -> itemId
        }
    }

    private fun processList(args: List<String>, fills: Map<String, SlotEntry>, formatId: String): String {
        val slotSpec = args.firstOrNull() ?: return ""
        val fmt = if (args.size > 1) args[1] else formatId
        val slots = expandSlotRange(slotSpec)
        val filled = slots.mapNotNull { fills[it] }
        if (filled.isEmpty()) return ""
        return filled.joinToString(",\n") { f ->
            val ref = formatItemRef(f.itemId, fmt)
            val indented = if (fmt == "json") "    $ref" else "    $ref"
            indented
        }
    }

    private fun processGrid(args: List<String>, fills: Map<String, SlotEntry>, formatId: String): String {
        val slotSpec = args.firstOrNull() ?: return ""
        val cols = args.find { it.startsWith("cols=") }?.substringAfter("=")?.toIntOrNull() ?: 3
        val fmt = args.find { it in setOf("json", "quote") } ?: ""
        val slots = expandSlotRange(slotSpec)
        val inputs = slots.map { fills[it] }
        logger.debug("processGrid: slotSpec={} slots={} inputs={}", slotSpec, slots, inputs.map { it?.itemId })

        var rMin = cols; var rMax = -1; var cMin = cols; var cMax = -1
        for (i in inputs.indices) {
            if (inputs[i] != null) {
                val r = i / cols; val col = i % cols
                rMin = minOf(rMin, r); rMax = maxOf(rMax, r)
                cMin = minOf(cMin, col); cMax = maxOf(cMax, col)
            }
        }
        if (rMax < 0) return ""

        val keyGrid = buildKeyGrid(inputs, rMin, rMax, cMin, cMax, cols)
        val lines = mutableListOf<String>()
        for (r in rMin..rMax) {
            val row = keyGrid[r - rMin].concatToString()
            val formatted = when (fmt) {
                "quote" -> "    '$row'"
                "json" -> "    \"$row\""
                else -> row
            }
            lines.add(formatted)
        }
        val gridLines = lines.mapIndexed { i, line ->
            if (i < lines.size - 1) "$line," else line
        }
        return gridLines.joinToString("\n")
    }

    private fun processKeyMap(args: List<String>, fills: Map<String, SlotEntry>, formatId: String): String {
        val slotSpec = args.firstOrNull() ?: return ""
        val cols = args.find { it.startsWith("cols=") }?.substringAfter("=")?.toIntOrNull() ?: 3
        val fmt = args.find { it in setOf("json", "quote") } ?: ""
        val slots = expandSlotRange(slotSpec)
        val inputs = slots.map { fills[it] }

        var rMin = cols; var rMax = -1; var cMin = cols; var cMax = -1
        for (i in inputs.indices) {
            if (inputs[i] != null) {
                val r = i / cols; val col = i % cols
                rMin = minOf(rMin, r); rMax = maxOf(rMax, r)
                cMin = minOf(cMin, col); cMax = maxOf(cMax, col)
            }
        }
        if (rMax < 0) return ""

        val keyGrid = buildKeyGrid(inputs, rMin, rMax, cMin, cMax, cols)
        val keyToItem = mutableMapOf<Char, String>()
        for (r in rMin..rMax) {
            for (col in cMin..cMax) {
                val key = keyGrid[r - rMin][col - cMin]
                if (key != ' ') {
                    val idx = r * cols + col
                    val fill = inputs[idx] ?: continue
                    keyToItem.putIfAbsent(key, fill.itemId)
                }
            }
        }

        val entries = keyToItem.entries.toList()
        return entries.joinToString(",\n") { (key, item) ->
            when (fmt) {
                "quote" -> "    $key: '$item'"
                "json" -> "    \"$key\": {\"item\": \"$item\"}"
                else -> "    $key: $item"
            }
        }
    }

    private fun processQty(args: List<String>, fills: Map<String, SlotEntry>): String {
        val slotId = args.firstOrNull() ?: return ""
        return fills[slotId]?.quantity?.toString() ?: ""
    }

    private fun processOption(args: List<String>, options: Map<String, String>): String {
        val key = args.firstOrNull() ?: return ""
        val value = options[key] ?: return ""
        if (value.isBlank()) return ""
        if (args.size > 1) {
            val format = args.drop(1).joinToString(":")
            return format.replace("$0", value)
        }
        return value
    }

    private fun connectDbUpdates() {
        scope.launch {
            RegistryRefreshService.dbUpdated.collect { updatedProject ->
                if (updatedProject.projectDir == project.projectDir) {
                    loadRecipeTypes()
                }
            }
        }
    }

    override fun captureState() = buildJsonObject {
        val recipeTypeId = currentRecipeTypes.getOrNull(recipeTypeCombo.currentIndex)?.id
        put("recipeTypeId", recipeTypeId)

        val formatId = formatCombo.itemData(formatCombo.currentIndex) as? String
        put("formatId", formatId)

        val slots = buildJsonObject {
            for((slotId, entry) in spriteWidget.currentFills()) {
                put(slotId, buildJsonObject {
                    put("itemId", entry.itemId)
                    put("quantity", entry.quantity)
                    entry.type?.let { put("type", it) }
                    put("isTag", entry.isTag)
                })
            }
        }
        put("slots", slots)

        val options = buildJsonObject {
            for((key, widget) in optionWidgets) {
                if(widget is QLineEdit) put(key, widget.text)
            }
        }
        put("options", options)
    }

    internal fun importRecipe(recipeTypeId: String, fills: Map<String, String>, options: Map<String, String> = emptyMap()) {
        logger.debug("importRecipe: recipeTypeId={} fills={} options={}", recipeTypeId, fills, options)
        breakLink()
        spriteWidget.clearAllSlots()
        pendingRecipeTypeId = recipeTypeId
        pendingFormatId = null
        pendingSlots = buildJsonObject {
            for ((slotId, itemId) in fills) {
                put(slotId, buildJsonObject {
                    put("itemId", itemId)
                    put("quantity", 1)
                })
            }
        }
        pendingOptions = if (options.isEmpty()) null else buildJsonObject {
            for ((key, value) in options) {
                put(key, JsonPrimitive(value))
            }
        }
        applyPendingRestore()
    }

    private data class LinkedSource(
        val editor: TextEditorPane,
        var lineStart: Int,
        var lineCount: Int,
        var snapshot: String
    )

    private fun pushToLinkedEditor(text: String) {
        val link = linkedSource ?: return
        val editorLines = link.editor.textContent.lines()
        val firstLine = editorLines.getOrNull(link.lineStart) ?: ""
        val indent = firstLine.takeWhile { it.isWhitespace() }
        val indentedText = if (indent.isEmpty()) text
        else text.lines().joinToString("\n") { line ->
            if (line.isEmpty()) line else indent + line
        }
        val pushText = if (indentedText.endsWith("\n")) indentedText else "$indentedText\n"
        val contentLineCount = indentedText.lines().size
        pushingToLinkedEditor = true
        link.editor.replaceLines(link.lineStart, link.lineCount, pushText)
        link.editor.rehighlight()
        link.lineCount = contentLineCount
        link.snapshot = indentedText
        link.editor.linkedLineRange = link.lineStart until (link.lineStart + link.lineCount)
        pushingToLinkedEditor = false
    }

    private fun checkLinkedSnapshot() {
        if (pushingToLinkedEditor) return
        val link = linkedSource ?: return
        val lines = link.editor.textContent.lines()
        if (link.lineStart >= lines.size) { breakLink(); return }
        val end = minOf(link.lineStart + link.lineCount, lines.size)
        val current = lines.subList(link.lineStart, end).joinToString("\n")
        if (current != link.snapshot) {
            breakLink()
        }
    }

    private fun checkLinkedEsc() {
        val link = linkedSource ?: return
        val cursorLine = link.editor.cursorLineNumber()
        if (cursorLine in link.lineStart until link.lineStart + link.lineCount) {
            breakLink()
        }
    }

    private fun establishLink(editor: TextEditorPane, lineStart: Int, lineCount: Int) {
        breakLink()
        val lines = editor.textContent.lines()
        val snapshot = if (lineStart < lines.size) {
            lines.subList(lineStart, minOf(lineStart + lineCount, lines.size)).joinToString("\n")
        } else ""
        linkedSource = LinkedSource(editor, lineStart, lineCount, snapshot)
        editor.linkedLineRange = lineStart until (lineStart + lineCount)
        editor.onTextChanged = { checkLinkedSnapshot() }
        editor.onEscPressed = { checkLinkedEsc() }
        codeGenWidget.hide()
    }

    private fun breakLink() {
        val editor = linkedSource?.editor
        if (editor != null) {
            editor.linkedLineRange = null
            editor.onTextChanged = null
            editor.onEscPressed = null
        }
        linkedSource = null
        codeGenWidget.show()
    }

    companion object {
        private const val NAMESPACE_ROLE = Qt.ItemDataRole.UserRole + 1
        private const val COMMUNITY_ROLE = Qt.ItemDataRole.UserRole + 2
        private val importHandlers = mutableListOf<(String) -> ImportResult?>()

        fun registerImportHandler(handler: (String) -> ImportResult?) {
            importHandlers.add(handler)
        }
    }

    fun importFromLine(line: String, lineIndex: Int = -1, fullText: String? = null, editor: TextEditorPane? = null, link: Boolean = false) {
        logger.debug("importFromLine: line='{}'", line)
        val match = Regex("""event\.(\w+)\s*\(""", RegexOption.IGNORE_CASE).find(line)
        if (match == null) {
            logger.debug("importFromLine: no regex match on line")
            return
        }
        val methodName = match.groupValues[1].lowercase()
        logger.debug("importFromLine: methodName='{}'", methodName)
        val kubeJsMethods = currentRecipeTypes.map { it.id to it.kubeJsMethods }
        logger.debug("importFromLine: currentRecipeTypes.size={}, methods={}", currentRecipeTypes.size, kubeJsMethods)
        val rt = currentRecipeTypes.firstOrNull { methodName in it.kubeJsMethods.map { m -> m.lowercase() } }
        if (rt == null) {
            logger.debug("importFromLine: no recipe type found for method '{}'", methodName)
            return
        }
        logger.debug("importFromLine: found recipe type '{}'", rt.id)

        for (handler in importHandlers) {
            val result = handler(line) ?: continue
            val id = result.recipeTypeId.ifEmpty { rt.id }
            if (currentRecipeTypes.none { it.id == id }) continue
            importRecipe(id, result.fills, result.options)
            if (link && editor != null) {
                establishLink(editor, lineIndex, 1)
            }
            return
        }

        val (searchText, openParenPos) = if (fullText != null && lineIndex >= 0) {
            val lines = fullText.lines()
            val fromLine = lines.drop(lineIndex).joinToString("\n")
            fromLine to match.range.last
        } else {
            line to match.range.last
        }
        val argsText = extractBalancedArgs(searchText, openParenPos)
        if (argsText == null) {
            logger.debug("importFromLine: could not extract balanced args (openParenPos={})", openParenPos)
            return
        }
        logger.debug("importFromLine: argsText='{}'", argsText.take(500))
        val args = splitKubeJsArgs(argsText)
        logger.debug("importFromLine: args={}", args)
        if (args.isEmpty()) {
            logger.debug("importFromLine: no args extracted")
            return
        }

        val closeParenPos = openParenPos + 1 + argsText.length + 1
        val chainText = extractImmediateChainCalls(searchText, closeParenPos)
        logger.debug("importFromLine: chainText='{}' closeParenPos={} searchText.length={}", chainText, closeParenPos, searchText.length)

        val parsed = KubeJsImportParser.parse(rt, methodName, args, chainText)
        if (parsed == null) {
            logger.debug("importFromLine: KubeJsImportParser returned null")
            return
        }
        logger.debug("importFromLine: fills={}, options={}", parsed.fills, parsed.options)
        importRecipe(rt.id, parsed.fills, parsed.options)

        if (link && editor != null && fullText != null) {
            val recipeEndInSearch = closeParenPos + chainText.length
            val recipeText = searchText.substring(0, recipeEndInSearch)
            val detectedLineCount = recipeText.count { it == '\n' } + 1
            establishLink(editor, lineIndex, detectedLineCount)
        }
    }

    private fun extractBalancedArgs(text: String, openParen: Int): String? {
        var depth = 1
        var i = openParen + 1
        while (i < text.length && depth > 0) {
            when (text[i]) {
                '(' -> depth++
                ')' -> depth--
            }
            i++
        }
        return if (depth == 0) text.substring(openParen + 1, i - 1) else null
    }

    private fun extractImmediateChainCalls(text: String, startPos: Int): String {
        val sb = StringBuilder()
        var pos = startPos
        while (pos < text.length) {
            while (pos < text.length && text[pos].isWhitespace()) pos++
            if (pos >= text.length || text[pos] != '.') break
            val dotPos = pos
            pos++
            while (pos < text.length && (text[pos].isLetterOrDigit() || text[pos] == '_')) pos++
            if (pos >= text.length || text[pos] != '(') break
            val argsContent = extractBalancedArgs(text, pos) ?: break
            val closeParen = pos + 1 + argsContent.length + 1
            sb.append(text.substring(dotPos, closeParen))
            pos = closeParen
        }
        return sb.toString()
    }

    private fun splitKubeJsArgs(text: String): List<String> {
        val args = mutableListOf<String>()
        var depth = 0
        var inString = false
        var stringChar = ' '
        var start = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (inString) {
                if (c == stringChar && (i == 0 || text[i - 1] != '\\')) inString = false
            } else when (c) {
                '\'', '"' -> { inString = true; stringChar = c }
                '(', '[', '{' -> depth++
                ')', ']', '}' -> depth--
                ',' -> if (depth == 0) {
                    args.add(text.substring(start, i).trim())
                    start = i + 1
                }
            }
            i++
        }
        args.add(text.substring(start).trim())
        return args.filter { it.isNotEmpty() }
    }

    override fun restoreState(state: JsonObject) {
        pendingRecipeTypeId = state["recipeTypeId"]?.jsonPrimitive?.contentOrNull
        pendingFormatId = state["formatId"]?.jsonPrimitive?.contentOrNull
        pendingSlots = state["slots"]?.jsonObject
        pendingOptions = state["options"]?.jsonObject
    }

    private fun applyPendingRestore() {
        val typeId = pendingRecipeTypeId ?: return
        pendingRecipeTypeId = null

        val idx = currentRecipeTypes.indexOfFirst { it.id == typeId }
        if(idx >= 0) recipeTypeCombo.currentIndex = idx

        pendingFormatId?.let { fmtId ->
            pendingFormatId = null
            for(i in 0 until formatCombo.count) {
                if(formatCombo.itemData(i) as? String == fmtId) {
                    formatCombo.currentIndex = i
                    break
                }
            }
        }

        pendingSlots?.let { slots ->
            pendingSlots = null
            spriteWidget.updateGeometry()
            spriteWidget.parentWidget()?.layout()?.activate()
            for ((slotId, elem) in slots) {
                val obj = elem as? JsonObject ?: continue
                val itemId = obj["itemId"]?.jsonPrimitive?.contentOrNull ?: continue
                val qty = obj["quantity"]?.jsonPrimitive?.intOrNull ?: 1
                val type = obj["type"]?.jsonPrimitive?.contentOrNull
                spriteWidget.setSlotItem(slotId, itemId, qty, type)
            }
        }

        pendingOptions?.let { opts ->
            pendingOptions = null
            for ((key, elem) in opts) {
                val value = elem.jsonPrimitive.contentOrNull ?: continue
                val widget = optionWidgets[key]
                if (widget is QLineEdit) widget.text = value
            }
        }
    }
}

private class SpriteWidget(
    private val project: ProjectBase
) : QWidget() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val logger = logger()
    private val json = Json { ignoreUnknownKeys = true }
    private var rt: RecipeTypeData? = null
    private var snapshotDir: VPath? = null
    private var spritePixmap: QPixmap? = null
    private val slotItems = ConcurrentHashMap<String, SlotEntry>()
    var currentRecipeType: RecipeTypeData? = null
        private set

    fun currentFills(): Map<String, SlotEntry> = slotItems.toMap()
    private var hoveredSlot: String? = null

    var onItemsChanged: (() -> Unit)? = null

    private val slotIconCache = ConcurrentHashMap<String, QPixmap>()
    private val animatedSlotTextures = ConcurrentHashMap<String, AnimatedItemMngr.ItemTexture.Animated>()
    private var animTickListener: (() -> Unit)? = null
    private var displayPixmap: QPixmap? = null
    private var lastScale: Int = 0
    private var copiedItem: SlotEntry? = null
    private var dragSourceSlot: String? = null
    private var dragStartPos: QPoint? = null
    private var wheelAccumulator: Int = 0

    private val slotTagItems = ConcurrentHashMap<String, List<String>>()
    private val slotTagIndex = ConcurrentHashMap<String, Int>()
    private val tagCycleTimer = QTimer(this)

    init {
        AnimatedItemMngr.ensureStarted()
        val listener = { updateAnimatedSlots() }
        animTickListener = listener
        AnimatedItemMngr.registerTickListener(listener)
        tagCycleTimer.interval = 1000
        tagCycleTimer.timeout.connect { cycleTagSlots() }
        tagCycleTimer.start()
        destroyed.connect { _: QObject? ->
            scope.cancel()
            animTickListener?.let { AnimatedItemMngr.unregisterTickListener(it) }
            tagCycleTimer.stop()
        }
        setSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Preferred)
        setMinimumSize(100, 60)
        focusPolicy = Qt.FocusPolicy.StrongFocus
    }

    private fun currentDisplayItemId(slotId: String): String {
        val entry = slotItems[slotId] ?: return ""
        if (!entry.isTag) return entry.itemId
        val items = slotTagItems[slotId] ?: return entry.itemId
        val idx = slotTagIndex[slotId] ?: 0
        return items.getOrNull(idx) ?: entry.itemId
    }

    private fun updateAnimatedSlots() {
        for ((slotId, tex) in animatedSlotTextures) {
            val size = (layoutParams()?.first?.times(16) ?: 32).coerceAtLeast(32)
            val pix = AnimatedItemMngr.currentAnimatedPixmap(slotId, tex, size)
            if (!pix.isNull) {
                slotIconCache[slotId] = pix
            }
        }
        if (animatedSlotTextures.isNotEmpty()) update()
    }

    private fun cycleTagSlots() {
        var changed = false
        for ((slotId, items) in slotTagItems) {
            if (items.isEmpty()) continue
            val idx = (slotTagIndex[slotId] ?: 0) + 1
            if (idx >= items.size) {
                slotTagIndex[slotId] = 0
            } else {
                slotTagIndex[slotId] = idx
            }
            val currentItemId = items[slotTagIndex[slotId]!!]
            val texPath = runCatching { RegistryDatabase.itemTexturePath(project, currentItemId) }.getOrNull()
//            val animJson = runCatching { RegistryDatabase.itemAnimationJson(project, currentItemId) }.getOrNull()
            val tintColor = runCatching { RegistryDatabase.customValueTintColor(project, currentItemId) }.getOrNull()
            val size = (layoutParams()?.first?.times(16) ?: 32).coerceAtLeast(32)
            val px = runCatching { loadItemIcon(currentItemId, texPath, snapshotDir, size, tintColor) }.getOrNull()
            if (px != null && !px.isNull) {
                slotIconCache[slotId] = px
                changed = true
            }
        }
        if (changed) update()
    }

    private fun showSlotContextMenu(slotId: String, globalPos: QPoint) {
        val entry = slotItems[slotId] ?: return
        val menu = QMenu(this)
        menu.addAction("Remove item")?.triggered?.connect { _ ->
            slotItems.remove(slotId)
            slotIconCache.remove(slotId)
            animatedSlotTextures.remove(slotId)
            slotTagItems.remove(slotId)
            slotTagIndex.remove(slotId)
            onItemsChanged?.invoke()
            update()
        }
        if (!entry.isTag) {
            val tags = runCatching { RegistryDatabase.tagsForItem(project, entry.itemId) }.getOrNull() ?: emptyList()
            if (tags.isNotEmpty()) {
                val tagSubmenu = menu.addMenu("Convert to tag \u25B8")
                for (tagId in tags) {
                    val tagItem = tagSubmenu?.addAction(tagId)
                    tagItem?.triggered?.connect { _ ->
                        convertSlotToTag(slotId, tagId)
                    }
                }
            }
        } else {
            menu.addAction("Remove tag")?.triggered?.connect { _ ->
                convertSlotToTag(slotId, null)
            }
        }
        menu.popup(globalPos)
    }

    private fun convertSlotToTag(slotId: String, tagId: String?) {
        if (tagId != null) {
            val itemIds = runCatching { RegistryDatabase.itemIdsForTag(project, tagId) }.getOrNull()
            if (itemIds.isNullOrEmpty()) {
                logger.info("tag {} resolved to no items", tagId)
                return
            }
            val entry = slotItems[slotId] ?: return
            slotItems[slotId] = entry.copy(itemId = "#$tagId", isTag = true)
            slotTagItems[slotId] = itemIds
            slotTagIndex[slotId] = 0
            animatedSlotTextures.remove(slotId)
            val firstItem = itemIds[0]
            val texPath = runCatching { RegistryDatabase.itemTexturePath(project, firstItem) }.getOrNull()
            val animJson = runCatching { RegistryDatabase.itemAnimationJson(project, firstItem) }.getOrNull()
            val tintColor = runCatching { RegistryDatabase.customValueTintColor(project, firstItem) }.getOrNull()
            loadSlotIcon(slotId, firstItem, texPath, animJson, tintColor)
        } else {
            val entry = slotItems[slotId] ?: return
            val itemIds = slotTagItems[slotId] ?: return
            if (itemIds.isEmpty()) return
            val firstItem = itemIds[slotTagIndex[slotId] ?: 0]
            val resolvedType = runCatching { RegistryDatabase.resolveRegistryType(project, firstItem) }.getOrNull()
            slotItems[slotId] = SlotEntry(firstItem, entry.quantity, resolvedType)
            slotTagItems.remove(slotId)
            slotTagIndex.remove(slotId)
            animatedSlotTextures.remove(slotId)
            slotIconCache.remove(slotId)
            val texPath = runCatching { RegistryDatabase.itemTexturePath(project, firstItem) }.getOrNull()
            val animJson = runCatching { RegistryDatabase.itemAnimationJson(project, firstItem) }.getOrNull()
            val tintColor = runCatching { RegistryDatabase.customValueTintColor(project, firstItem) }.getOrNull()
            loadSlotIcon(slotId, firstItem, texPath, animJson, tintColor)
        }
        onItemsChanged?.invoke()
        update()
    }

    private fun tagTooltipText(slotId: String): String? {
        val entry = slotItems[slotId] ?: return null
        if (!entry.isTag) return null
        val items = slotTagItems[slotId] ?: return null
        if (items.isEmpty()) return "<Tag: ${entry.itemId}>"
        val names = items.map { id ->
            runCatching { RegistryDatabase.itemDisplayName(project, id) }.getOrNull() ?: id
        }
        val limit = 50
        val shown = names.take(limit)
        val sb = StringBuilder()
        for (name in shown) {
            if (sb.isNotEmpty()) sb.append("\n")
            sb.append(name)
        }
        if (names.size > limit) {
            sb.append("\n...${names.size - limit} more")
        }
        return sb.toString()
    }

    fun setRecipeType(rt: RecipeTypeData, snapshotDir: VPath?) {
        this.rt = rt
        currentRecipeType = rt
        this.snapshotDir = snapshotDir
        slotItems.clear()
        slotIconCache.clear()
        animatedSlotTextures.clear()
        slotTagItems.clear()
        slotTagIndex.clear()
        hoveredSlot = null
        onItemsChanged?.invoke()
        updateGeometry()
        update()
    }

    fun updatePixmap(pixmap: QPixmap?) {
        spritePixmap = pixmap
        displayPixmap = null
        lastScale = 0
        updateGeometry()
        update()
    }

    fun getSlotItem(slotId: String): String? = slotItems[slotId]?.itemId

    fun clearAllSlots() {
        val ids = slotItems.keys.toList()
        for (slotId in ids) {
            setSlotItem(slotId, null)
        }
    }

    fun setSlotItem(slotId: String, itemId: String?, quantity: Int = 1, type: String? = null) {
        if (itemId != null) {
            val resolvedType = type ?: runCatching { RegistryDatabase.resolveRegistryType(project, itemId) }.getOrNull()
            slotItems[slotId] = SlotEntry(itemId, quantity, resolvedType)
            slotTagItems.remove(slotId)
            slotTagIndex.remove(slotId)
            animatedSlotTextures.remove(slotId)
            slotIconCache.remove(slotId)
            val texPath = runCatching { RegistryDatabase.itemTexturePath(project, itemId) }.getOrNull()
            val animJson = runCatching { RegistryDatabase.itemAnimationJson(project, itemId) }.getOrNull()
            val tintColor = runCatching { RegistryDatabase.customValueTintColor(project, itemId) }.getOrNull()
            loadSlotIcon(slotId, itemId, texPath, animJson, tintColor)
        } else {
            slotItems.remove(slotId)
            slotIconCache.remove(slotId)
            animatedSlotTextures.remove(slotId)
            slotTagItems.remove(slotId)
            slotTagIndex.remove(slotId)
        }
        onItemsChanged?.invoke()
        update()
    }

    fun setSlotQuantity(slotId: String, quantity: Int) {
        val entry = slotItems[slotId] ?: return
        slotItems[slotId] = entry.copy(quantity = quantity)
        onItemsChanged?.invoke()
    }

    private fun loadSlotIcon(slotId: String, itemId: String, texPath: String?, animJson: String?, tintColor: Long? = null) {
        val lp = layoutParams() ?: return
        val slotSize = lp.first * 16
        val effectiveSize = slotSize.coerceAtLeast(32)
        scope.launch(Dispatchers.Default) {
            if (animJson != null && snapshotDir != null) {
                val tex = RegistryBrowserDockPanel.loadItemTexture(itemId, texPath, animJson, snapshotDir, effectiveSize, tintColor)
                if (tex is AnimatedItemMngr.ItemTexture.Animated) {
                    if (slotItems[slotId]?.itemId != itemId) return@launch
                    val initialPix = AnimatedItemMngr.currentAnimatedPixmap(slotId, tex, effectiveSize)
                    if (!initialPix.isNull) {
                        animatedSlotTextures[slotId] = tex
                        slotIconCache[slotId] = initialPix
                        withContext(Dispatchers.Main) { update() }
                    }
                    return@launch
                }
                if (tex is AnimatedItemMngr.ItemTexture.Static) {
                    if (slotItems[slotId]?.itemId != itemId) return@launch
                    slotIconCache[slotId] = tex.pixmap
                    withContext(Dispatchers.Main) { update() }
                    return@launch
                }
            }
            val pixmap = loadItemIcon(itemId, texPath, snapshotDir, effectiveSize, tintColor)
            if (pixmap != null) {
                if (slotItems[slotId]?.itemId != itemId) return@launch
                slotIconCache[slotId] = pixmap
                withContext(Dispatchers.Main) { update() }
            }
        }
    }

    override fun sizeHint(): QSize {
        val w = rt?.spriteWidth ?: 176
        val h = rt?.spriteHeight ?: 166
        return QSize(w, h)
    }

    override fun hasHeightForWidth(): Boolean = true

    override fun heightForWidth(w: Int): Int {
        val rt = rt
        val pad = 8
        val availW = w - pad * 2
        if (availW <= 0) return minimumHeight()
        val spriteW = rt?.spriteWidth ?: 176 //TODO
        val spriteH = rt?.spriteHeight ?: 166
        val h = (availW.toFloat() * spriteH / spriteW + pad * 2).toInt()
        return h.coerceAtLeast(minimumHeight())
    }

    private fun hitTest(pos: QPoint): SlotRegion? {
        val rt = rt ?: return null
        val lp = layoutParams() ?: return null
        val (scale, drawX, drawY) = lp
        return rt.regions.find { region ->
            val rx = drawX + (region.x * scale)
            val ry = drawY + (region.y * scale)
            val rw = (region.width * scale)
            val rh = (region.height * scale)
            pos.x() in rx..(rx + rw) && pos.y() in ry..(ry + rh)
        }
    }

    private fun layoutParams(): Quintuple<Int, Int, Int, Int, Int>? {
        val rt = rt ?: return null
        val pad = 8
        val availW = width() - pad * 2
        val availH = height() - pad * 2
        if (availW <= 0 || availH <= 0) return null

        val scale = maxOf(1, minOf(availW / rt.spriteWidth, availH / rt.spriteHeight))
        val scaledW = rt.spriteWidth  * scale
        val scaledH = rt.spriteHeight * scale
        val drawX = pad + (availW - scaledW) / 2
        val drawY = pad + (availH - scaledH) / 2
        return Quintuple(scale, drawX, drawY, scaledW, scaledH)
    }

    private fun ensureDisplayPixmap(scale: Int) {
        val src = spritePixmap ?: run {
            displayPixmap = null
            lastScale = 0
            return
        }
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
        painter.fillRect(rect(), TColors.Surface1.toQC())

        val lp = layoutParams() ?: run { painter.end(); return }
        val (scale, drawX, drawY, scaledW, scaledH) = lp

        ensureDisplayPixmap(scale)
        if (displayPixmap != null) {
            painter.drawPixmap(drawX, drawY, displayPixmap!!)
        } else {
            painter.setPen(TColors.Subtext.toQC())
            painter.drawText(QRect(drawX, drawY, scaledW, scaledH),
                Qt.AlignmentFlag.AlignCenter.value(), "No sprite available")
        }

        val rt = rt ?: run { painter.end(); return }
        for ((id, _, _, _, _, x1, y1, width1, height1, _, displayOnly) in rt.regions) {
            val rx = drawX + (x1 * scale)
            val ry = drawY + (y1 * scale)
            val rw = (width1 * scale)
            val rh = (height1 * scale)
            val entry = slotItems[id]
            val hasItem = entry != null

            if (displayOnly) {
                painter.setPen(QPen(QColor(80, 80, 80, 160), 1.0))
                painter.setBrush(QBrush(QColor(0, 0, 0, 100)))
                painter.drawRect(QRect(rx, ry, rw, rh))

                painter.setPen(QColor(120, 120, 120, 80))
                painter.setFont(QFont(Fonts.Monocraft, (minOf(rw, rh) / 4).coerceIn(8, 14)))
                painter.drawText(QRect(rx, ry, rw, rh),
                    Qt.AlignmentFlag.AlignCenter.value(), "\u26ED")
                continue
            }

            val slotColor = when {
                hasItem && entry.isTag -> QColor(100, 150, 255, 80)
                hasItem -> QColor(100, 200, 100, 80)
                id == hoveredSlot -> {
                    if (copiedItem != null) QColor(100, 150, 255, 120)
                    else QColor(200, 200, 100, 100)
                }
                else -> QColor(255, 255, 255, 30)
            }

            painter.setPen(QPen(QColor(255, 255, 255, if (hasItem) 120 else 60), 1.0))
            painter.setBrush(QBrush(slotColor))
            painter.drawRect(QRect(rx, ry, rw, rh))

            val cachedIcon = slotIconCache[id]
            if (cachedIcon != null && !cachedIcon.isNull) {
                val ix = rx + (rw - cachedIcon.width()) / 2
                val iy = ry + (rh - cachedIcon.height()) / 2
                painter.drawPixmap(ix, iy, cachedIcon)
            }

            val fontSize = (minOf(rw, rh) / 3).coerceIn(8, 16)
            val qtyFont  = QFont(Fonts.Monocraft, fontSize)
            qtyFont.setHintingPreference(QFont.HintingPreference.PreferFullHinting)
            painter.setFont(qtyFont)

            if (entry != null && entry.quantity > 1) {
                val text = entry.quantity.toString()
                val textRect = QRect(rx, ry, rw - 2, rh - 2)

                painter.setPen(QColor(0, 0, 0, 180))

                painter.drawText(
                    QRect(
                        rx + 1, ry + 1, rw - 2, rh - 2
                    ),
                    Qt.AlignmentFlag.AlignBottom.value() or Qt.AlignmentFlag.AlignRight.value(),
                    text
                )

                painter.setPen(QColor(255, 255, 255))

                painter.drawText(
                    textRect,
                    Qt.AlignmentFlag.AlignBottom.value() or Qt.AlignmentFlag.AlignRight.value(),
                    text
                )
            }
        }

        painter.end()
    }

    override fun mouseMoveEvent(event: QMouseEvent?) {
        val pos = event?.pos() ?: return
        val hit = hitTest(pos)
        val prev = hoveredSlot
        hoveredSlot = hit?.id
        if (prev != hoveredSlot) update()

        if (dragSourceSlot != null && dragStartPos != null) {
            val dist = (pos - dragStartPos!!).let { kotlin.math.sqrt((it.x() * it.x() + it.y() * it.y()).toFloat()) }
            if (dist > QApplication.startDragDistance()) {
                val srcSlot = dragSourceSlot!!
                val entry = slotItems[srcSlot] ?: return
                val dragId = currentDisplayItemId(srcSlot)
                val dragData = buildJsonObject {
                    put("id", dragId)
                    put("count", entry.quantity)
                    entry.type?.let { put("type", it) }
                }.toString()
                logger.info("drag: encoding state={}", dragData)
                val drag = QDrag(this)
                val mimeData = QMimeData()
                mimeData.setText(dragId)
                mimeData.setData("application/x-tritium-item", QByteArray(dragData.toByteArray()))
                drag.setMimeData(mimeData)
                drag.setPixmap(slotIconCache[dragSourceSlot]?.scaled(32, 32, Qt.AspectRatioMode.KeepAspectRatio, Qt.TransformationMode.FastTransformation))
                val result = drag.exec(Qt.DropAction.MoveAction)
                logger.info("drag: exec result={} movedToSlot={} dragSourceSlot={}", result, movedToSlot, dragSourceSlot)
                if (result == Qt.DropAction.MoveAction && dragSourceSlot != null && movedToSlot != null && movedToSlot != dragSourceSlot) {
                    slotItems.remove(dragSourceSlot)
                    slotIconCache.remove(dragSourceSlot)
                    onItemsChanged?.invoke()
                    logger.info("drag: cleared source slot {}", dragSourceSlot)
                }
                dragSourceSlot = null
                dragStartPos = null
                movedToSlot = null
                update()
            }
        }

        super.mouseMoveEvent(event)
    }

    override fun mousePressEvent(event: QMouseEvent?) {
        val pos = event?.pos() ?: return
        val hit = hitTest(pos)

        if (event.button() == Qt.MouseButton.LeftButton) {
            if (copiedItem != null && hit != null && !hit.displayOnly && hit.role in setOf("INPUT", "OUTPUT", "FUEL")) {
                if (isTypeCompatible(copiedItem!!.type, hit, copiedItem!!.itemId)) {
                    setSlotItem(hit.id, copiedItem!!.itemId, copiedItem!!.quantity, copiedItem!!.type)
                } else {
                    logger.info("paste: rejected type mismatch slotType={} itemType={} itemId={}", hit.slotType, copiedItem!!.type, copiedItem!!.itemId)
                }
                return
            }
            if (hit != null && slotItems.containsKey(hit.id)) {
                dragSourceSlot = hit.id
                dragStartPos = pos
                return
            }
        }

        super.mousePressEvent(event)
    }

    override fun mouseReleaseEvent(event: QMouseEvent?) {
        dragSourceSlot = null
        dragStartPos = null
        super.mouseReleaseEvent(event)
    }

    override fun event(event: QEvent?): Boolean {
        if (event?.type() == QEvent.Type.MouseButtonPress) {
            val me = event as? QMouseEvent
            if (me != null) {
                val hit = hitTest(me.pos())
                if (hit != null && !hit.displayOnly && slotItems.containsKey(hit.id)) {
                    when (me.button()) {
                        Qt.MouseButton.MiddleButton -> {
                            val entry = slotItems[hit.id] ?: return true
                            val resolvedId = currentDisplayItemId(hit.id)
                            copiedItem = if (entry.isTag) {
                                val resolvedType = entry.type
                                    ?: runCatching { RegistryDatabase.resolveRegistryType(project, resolvedId) }.getOrNull()
                                SlotEntry(resolvedId, entry.quantity, resolvedType)
                            } else {
                                entry
                            }
                            logger.info("middle-click: copied item {} qty={}", copiedItem?.itemId, copiedItem?.quantity)
                            update()
                            return true
                        }
                        Qt.MouseButton.RightButton -> {
                            showSlotContextMenu(hit.id, me.globalPos())
                            return true
                        }
                        else -> {}
                    }
                }
            }
        }
        if (event?.type() == QEvent.Type.ToolTip) {
            val helpEvent = event as? QHelpEvent
            if (helpEvent != null) {
                val hit = hitTest(helpEvent.pos())
                if (hit != null) {
                    val text = tagTooltipText(hit.id)
                    if (text != null) {
                        QToolTip.showText(helpEvent.globalPos(), text, this)
                        return true
                    }
                }
            }
            QToolTip.hideText()
        }
        return super.event(event)
    }

    override fun keyPressEvent(event: QKeyEvent?) {
        if (event?.key() == Qt.Key.Key_Escape.value() && copiedItem != null) {
            logger.info("escape: clearing copied item {}", copiedItem?.itemId)
            copiedItem = null
            update()
            event.accept()
            return
        }
        super.keyPressEvent(event)
    }

    override fun wheelEvent(event: QWheelEvent?) {
        val pos = event?.position()?.toPoint() ?: return
        val hit = hitTest(pos)

        if (hit != null) {
            val entry = slotItems[hit.id]
            if (entry != null) {
                wheelAccumulator += event.angleDelta().y()
                val step = if (event.modifiers().testFlag(Qt.KeyboardModifier.ShiftModifier)) 16 else 1
                val notches = wheelAccumulator / 120
                if (notches != 0) {
                    wheelAccumulator -= notches * 120
                    val delta = notches * step
                    val maxQty = hit.maxCapacity.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                    val newQty = (entry.quantity + delta).coerceIn(1, maxQty)
                    slotItems[hit.id] = entry.copy(quantity = newQty)
                    onItemsChanged?.invoke()
                    update()
                }
            }
            event.accept()
        } else {
            wheelAccumulator = 0
            super.wheelEvent(event)
        }
    }

    private var movedToSlot: String? = null

    private fun isTypeCompatible(dropType: String?, slot: SlotRegion, id: String): Boolean {
        if (!slot.explicitSlotType) return true
        if (dropType == null) return true
        val slotLower = slot.slotType.lowercase()
        val dropLower = dropType.lowercase()
        return dropLower == slotLower ||
            dropLower.substringAfterLast(':') == slotLower ||
            slotLower.substringAfterLast(':') == dropLower
    }

    override fun dragEnterEvent(event: QDragEnterEvent?) {
        val mime = event?.mimeData() ?: return
        if (mime.hasText()) {
            event.acceptProposedAction()
        }
    }

    override fun dragMoveEvent(event: QDragMoveEvent?) {
        event?.acceptProposedAction()
    }

    override fun dropEvent(event: QDropEvent?) {
        val mime = event?.mimeData() ?: run { event?.ignore(); return }
        val pos = event.position().toPoint()
        val hit = hitTest(pos)
        logger.info("dropEvent: pos={} hit={}", pos, hit?.id)
        if (hit != null && !hit.displayOnly && mime.hasText() && hit.role in setOf("INPUT", "OUTPUT", "FUEL")) {
            val customData = mime.data("application/x-tritium-item")
            val text = if (customData != null && !customData.isEmpty()) customData.toString() else mime.text()
            logger.info("dropEvent: raw text='{}'", text)
            var itemId: String
            var qty: Int
            var dropType: String? = null
            try {
                val obj = json.parseToJsonElement(text).jsonObject
                itemId = obj["id"]?.jsonPrimitive?.contentOrNull ?: text
                qty = obj["count"]?.jsonPrimitive?.intOrNull ?: 1
                dropType = obj["type"]?.jsonPrimitive?.contentOrNull
            } catch (_: Exception) {
                itemId = text
                qty = 1
            }
            if (!isTypeCompatible(dropType, hit, itemId)) {
                logger.info("drop: rejected type mismatch slotType={} dropType={} itemId={}", hit.slotType, dropType, itemId)
                event.ignore()
                return
            }
            logger.info("drop: slot={} itemId={} qty={} type={}", hit.id, itemId, qty, dropType)
            setSlotItem(hit.id, itemId, qty, dropType)
            movedToSlot = hit.id
            event.acceptProposedAction()
        } else {
            logger.info("drop: ignored (no valid slot)")
            event.ignore()
        }
    }

    override fun leaveEvent(event: QEvent?) {
        hoveredSlot = null
        update()
        super.leaveEvent(event)
    }
}

data class SlotEntry(val itemId: String, val quantity: Int = 1, val type: String? = null, val isTag: Boolean = false)

private fun loadItemIcon(id: String, texturePath: String?, snapshotDir: VPath?, size: Int, tintColor: Long? = null): QPixmap? {
    if (snapshotDir == null) return null
    val nsPath = id.split(":")
    if (nsPath.size != 2) {
        if (texturePath != null) {
            val px = QPixmap(snapshotDir.resolve(texturePath).toAbsolute().expandHome().toString())
            if (!px.isNull) {
                val display = if (tintColor != null) AnimatedItemMngr.applyTint(px, tintColor) else px
                return display.scaled(size, size, Qt.AspectRatioMode.KeepAspectRatio, Qt.TransformationMode.FastTransformation)
            }
        }
        return null
    }
    val ns = nsPath[0]
    val path = nsPath[1]
    val candidates = buildList {
        add("icons/$ns/$path.png")
        add("icons/${ns}_${path.replace('/', '_')}.png")
        texturePath?.let { add(it) }
        add("assets/textures/$ns/item/$path.png")
        add("assets/textures/$ns/block/$path.png")
        add("assets/$ns/textures/item/$path.png")
        add("assets/$ns/textures/block/$path.png")
    }
    for (candidate in candidates) {
        val file = snapshotDir.resolve(candidate)
        if (file.exists()) {
            val px = QPixmap(file.toAbsolute().expandHome().toString())
            if (!px.isNull) {
                val display = if (tintColor != null) AnimatedItemMngr.applyTint(px, tintColor) else px
                return display.scaled(size, size, Qt.AspectRatioMode.KeepAspectRatio, Qt.TransformationMode.FastTransformation)
            }
        }
    }
    return null
}

internal data class RecipeTypeData(
    val id: String,
    val displayName: String,
    val uiTexture: String,
    val spriteWidth: Int,
    val spriteHeight: Int,
    val regions: List<SlotRegion>,
    val slotTextures: Map<String, String> = emptyMap(),
    val generationOptions: List<GenerationOption> = emptyList(),
    val templates: TemplatesData? = null,
    val kubeJsMethods: List<String> = emptyList(),
    val importSkipArgs: Int = 0,
    val importOptions: List<ImportOptionDef> = emptyList()
)

data class ImportOptionDef(
    val key: String,
    val chainPattern: String? = null,
    val positionalIndex: Int? = null
)

data class ImportResult(
    val recipeTypeId: String,
    val fills: Map<String, String>,
    val options: Map<String, String> = emptyMap()
)

internal data class TemplatesData(
    val variantOption: String?,
    val autoValue: String?,
    val expectsGrid: Boolean,
    val gridSlots: String?,
    val gridCols: Int,
    val formats: Map<String, Map<String, String>>,
    val variantDefaults: Map<String, Map<String, String>> = emptyMap()
)

internal data class GenerationOption(
    val key: String,
    val label: String,
    val type: String,
    val placeholder: String,
    val defaultValue: String
)

internal data class SlotRegion(
    val id: String,
    val label: String,
    val role: String,
    val slotType: String,
    val explicitSlotType: Boolean,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val maxCapacity: Long = 64,
    val displayOnly: Boolean = false
)

