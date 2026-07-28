/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.ui.search

import io.github.tritium_launcher.api.search.SearchResult
import io.github.tritium_launcher.api.search.SearchResultAction
import io.github.tritium_launcher.launcher.core.project.ProjectMngr
import io.github.tritium_launcher.launcher.core.project.descriptors.CommunityDescriptors
import io.github.tritium_launcher.launcher.registrydb.RegistryDatabase
import io.github.tritium_launcher.launcher.ui.project.ProjectWindows
import io.github.tritium_launcher.launcher.ui.project.sidebar.RecipeBuilderWidget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

class ImportToRecipeBuilderAction : SearchResultAction {
    override val id = "import_to_recipe_builder"
    override val label = "Import to Recipe Builder"
    override val icon = "recipe"
    override val handledKinds = setOf("recipe")

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(result: SearchResult) {
        withContext(Dispatchers.Main) {
            val project = ProjectMngr.activeProject ?: return@withContext
            val window = ProjectWindows.anyOpenWindow() ?: return@withContext
            val dock = window.dockPanelMngr.getDock("recipe_builder") ?: return@withContext
            val widget = dock.widget() as? RecipeBuilderWidget ?: return@withContext

            dock.show()
            dock.raise()

            val recipeId = result.id.removePrefix("recipe:")
            val detail = runCatching { RegistryDatabase.recipeDetail(project, recipeId) }.getOrNull() ?: return@withContext
            val recipeType = detail.recipeType ?: return@withContext

            val rtData = if (detail.recipeTypeRawJson != null && hasBuilderSupport(detail.rawJson)) {
                parseImportRecipeTypeData(recipeType, detail.recipeTypeRawJson)
            } else {
                val namespace = recipeType.substringBefore(":")
                val communityRt = CommunityDescriptors.getCachedDescriptor(namespace)
                communityRt?.let { ImportRecipeTypeData(it.id, it.uiTexture, it.spriteWidth, it.spriteHeight, it.regions.map { ImportSlotRegion(it.id, it.label, it.role, it.x, it.y, it.width, it.height, it.displayOnly) }) }
            } ?: return@withContext

            val fills = parseSlotFills(detail.rawJson, rtData.regions)
            widget.importRecipe(recipeType, fills)
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

    private fun parseImportRecipeTypeData(id: String?, rawJson: String): ImportRecipeTypeData? {
        if (rawJson.isBlank()) return null
        return runCatching {
            val root = json.parseToJsonElement(rawJson).jsonObject
            val uiTexture = root["uiTexture"]?.jsonPrimitive?.contentOrNull ?: return@runCatching null
            val layoutObj = root["layout"]?.jsonObject
            val spriteW = layoutObj?.get("width")?.jsonPrimitive?.intOrNull ?: 176
            val spriteH = layoutObj?.get("height")?.jsonPrimitive?.intOrNull ?: 166
            val components = root["components"]?.jsonArray
            val regions = parseImportSlotRegions(components)
            ImportRecipeTypeData(id ?: "", uiTexture, spriteW, spriteH, regions)
        }.getOrNull()
    }

    private fun parseImportSlotRegions(components: JsonArray?): List<ImportSlotRegion> {
        if (components == null) return emptyList()
        return components.mapNotNull { elem ->
            val obj = elem.jsonObject
            val compId = obj["id"]?.jsonPrimitive?.contentOrNull ?: ""
            val isInput = obj["data"]?.jsonObject?.get("isInput")?.jsonPrimitive?.booleanOrNull
            val role = when {
                obj["category"]?.jsonPrimitive?.contentOrNull == "ENERGY" -> "ENERGY"
                isInput == true -> "INPUT"
                isInput == false -> "OUTPUT"
                isInput == null && compId.startsWith("input") -> "INPUT"
                isInput == null && compId.startsWith("output") -> "OUTPUT"
                else -> "CUSTOM"
            }
            val displayOnly = obj["data"]?.jsonObject?.get("displayOnly")?.jsonPrimitive?.booleanOrNull ?: false
            ImportSlotRegion(
                id = compId,
                label = compId,
                role = role,
                x = obj["x"]?.jsonPrimitive?.intOrNull ?: 0,
                y = obj["y"]?.jsonPrimitive?.intOrNull ?: 0,
                width = obj["width"]?.jsonPrimitive?.intOrNull ?: 18,
                height = obj["height"]?.jsonPrimitive?.intOrNull ?: 18,
                displayOnly = displayOnly
            )
        }
    }

    private fun parseSlotFills(rawJson: String, regions: List<ImportSlotRegion>): Map<String, String> {
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

private data class ImportRecipeTypeData(
    val id: String,
    val uiTexture: String,
    val spriteWidth: Int,
    val spriteHeight: Int,
    val regions: List<ImportSlotRegion>
)

private data class ImportSlotRegion(
    val id: String,
    val label: String,
    val role: String,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val displayOnly: Boolean = false
)
