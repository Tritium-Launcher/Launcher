/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.core.project.descriptors

import com.akuleshov7.ktoml.Toml
import com.akuleshov7.ktoml.TomlInputConfig
import io.github.tritium_launcher.api.core.project.ProjectBase
import io.github.tritium_launcher.api.fromTR
import io.github.tritium_launcher.api.io.VPath
import io.github.tritium_launcher.api.logger
import io.github.tritium_launcher.launcher.core.HttpClientProvider
import io.github.tritium_launcher.launcher.core.mod.ModDatabase
import io.github.tritium_launcher.launcher.core.mod.readModJarInfo
import io.github.tritium_launcher.launcher.registrydb.RegistryDatabase
import io.github.tritium_launcher.launcher.ui.project.sidebar.*
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.utils.io.*
import kotlinx.io.readByteArray
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours

object CommunityDescriptors {
    private val client: HttpClient = HttpClientProvider.client()
    private val logger = logger()
    private val json = Json { ignoreUnknownKeys = true }

    private const val BASE_URL = "https://raw.githubusercontent.com/Tritium-Launcher/community-recipe-descriptors/main"

    private val cacheDir: VPath by lazy {
        fromTR("cache", "community-descriptors").also { it.mkdirs() }
    }

    private val toml = Toml(
        inputConfig = TomlInputConfig(
            ignoreUnknownNames = true,
            allowEmptyValues = true
        )
    )

    internal fun getCachedDescriptor(namespace: String): RecipeTypeData? {
        val tomlFile = cacheDir.resolve("mods/$namespace/descriptor.toml")
        if (!tomlFile.exists()) return null
        val text = tomlFile.readTextOrNull() ?: return null
        val spritePath = cacheDir.resolve("mods/$namespace/sprite.png")
        return parseDescriptor(text, namespace, if (spritePath.exists()) spritePath else null)
    }

    internal suspend fun loadForProject(project: ProjectBase): List<RecipeTypeData> {
        val dbModIds = ModDatabase(project.projectDir).use { db ->
            db.getAll().map { it.modId }.toSet()
        }
        val fileModIds = scanModsDirectory(project)
        val modIds = dbModIds + fileModIds
        if (modIds.isEmpty()) {
            logger.debug("No mods installed, skipping community descriptors")
            return emptyList()
        }

        val tritiumApiNamespaces = RegistryDatabase.allRecipeTypes(project)
            .filter { hasTritiumApiTemplates(it.rawJson) }
            .map { it.id.substringBefore(":") }
            .toSet()

        val needsDescriptors = modIds - tritiumApiNamespaces
        if (needsDescriptors.isEmpty()) {
            logger.debug("All installed mods already have Tritium API recipe types")
            return emptyList()
        }

        logger.debug("Looking up community descriptors for: {}", needsDescriptors)

        val index = fetchIndex()
        if (index == null) {
            logger.warn("Failed to fetch community descriptor index")
            return emptyList()
        }

        logger.debug("Community index contains keys: {}", index.keys)
        val available = index.filterKeys { it in needsDescriptors }
        if (available.isEmpty()) {
            logger.debug("No community descriptors available for {} (index keys: {})", needsDescriptors, index.keys)
            return emptyList()
        }

        return available.mapNotNull { (modId, _) ->
            try {
                val tomlStr = fetchDescriptor(modId)
                if (tomlStr == null) {
                    logger.warn("Failed to fetch community descriptor for mod: {}", modId)
                    return@mapNotNull null
                }
                val spritePath = fetchSprite(modId)
                val data = parseDescriptor(tomlStr, modId, spritePath)
                if (data != null) {
                    logger.info("Loaded community recipe type '{}' from {}", data.id, modId)
                } else {
                    logger.warn("Failed to parse community descriptor for mod: {}", modId)
                }
                data
            } catch (e: Exception) {
                logger.warn("Failed to load community descriptor for $modId", e)
                null
            }
        }
    }

    fun clearCache() {
        cacheDir.walk(recursive = true).sortedByDescending { it.toString().length }.forEach { it.delete() }
    }

    fun spriteUrl(modId: String): String = "$BASE_URL/mods/$modId/sprite.png"

    fun descriptorUrl(modId: String): String = "$BASE_URL/mods/$modId/descriptor.toml"

    private fun scanModsDirectory(project: ProjectBase): Set<String> {
        val modsDir = project.projectDir.resolve("mods")
        if (!modsDir.isDir()) return emptySet()
        return modsDir.listFiles { f -> f.fileName().endsWith(".jar", ignoreCase = true) }
            .mapNotNull { readModJarInfo(it) }
            .map { it.modId }
            .toSet()
            .also { ids ->
                if (ids.isNotEmpty()) {
                    logger.debug("Found {} mods via filesystem scan: {}", ids.size, ids)
                }
            }
    }

    // ── Internal helpers ──────────────────────────────────────────────

    private suspend fun fetchIndex(): Map<String, List<String>>? {
        val url = "$BASE_URL/mods/index.json"
        val cacheFile = cacheDir.resolve("mods/index.json")

        val cached = cacheFile.readTextOrNull()
        val age = cacheFile.lastModifiedOrNull()
        val stale = age == null || (Clock.System.now() - age) > 1.hours

        if (cached != null && !stale) return parseIndexJson(cached)

        logger.debug("Fetching community descriptor index from {}", url)
        val response = runCatching {
            client.get(url).bodyAsText()
        }.onFailure { e ->
            logger.warn("HTTP request failed for index: {}", e.message)
        }.getOrNull()

        if (response != null) {
            cacheFile.writeBytesAtomic(response.toByteArray())
            return parseIndexJson(response)
        }

        logger.warn("No cached index available either")
        return null
    }

    private suspend fun fetchDescriptor(modId: String): String? {
        val url = descriptorUrl(modId)
        val cacheFile = cacheDir.resolve("mods/$modId/descriptor.toml")

        val cached = cacheFile.readTextOrNull()
        val age = cacheFile.lastModifiedOrNull()
        val stale = age == null || (Clock.System.now() - age) > 1.hours

        if (cached != null && !stale) return cached

        logger.debug("Fetching community descriptor for {} from {}", modId, url)
        val response = runCatching {
            client.get(url).bodyAsText()
        }.onFailure { e ->
            logger.warn("HTTP request failed for {}: {}", modId, e.message)
        }.getOrNull()

        if (response != null) {
            cacheFile.writeBytesAtomic(response.toByteArray())
            return response
        }

        return cached
    }

    private suspend fun fetchSprite(modId: String): VPath? {
        val cacheFile = cacheDir.resolve("mods/$modId/sprite.png")
        if (cacheFile.exists()) return cacheFile

        val url = spriteUrl(modId)
        logger.debug("Fetching community sprite for {} from {}", modId, url)
        val bytes = runCatching {
            client.get(url).bodyAsChannel().readRemaining().readByteArray()
        }.onFailure { e ->
            logger.warn("Failed to fetch sprite for {}: {}", modId, e.message)
        }.getOrNull()

        if (bytes != null && bytes.isNotEmpty()) {
            cacheFile.writeBytesAtomic(bytes)
            return cacheFile
        }

        return null
    }

    private fun parseIndexJson(text: String): Map<String, List<String>>? {
        return runCatching {
            json.decodeFromString<Map<String, List<String>>>(text)
        }.onFailure { e ->
            logger.warn("Failed to parse index JSON: {}", e.message)
        }.getOrNull()
    }

    private fun parseDescriptor(text: String, modId: String, spritePath: VPath?): RecipeTypeData? {
        return runCatching {
            val root = toml.decodeFromString(RecipeDescriptorToml.serializer(), text)

            val id = root.recipe.id
            val displayName = root.recipe.displayName ?: root.recipe.id
            val spriteW = root.recipe.width
            val spriteH = root.recipe.height
            val uiTexture = spritePath?.toAbsolute()?.toString() ?: ""

            val regions = root.slot.map {
                val role = it.role.uppercase()
                SlotRegion(
                    id = it.id,
                    label = it.label ?: it.id,
                    role = role,
                    slotType = it.slotType,
                    explicitSlotType = it.slotType != "ITEM",
                    x = it.x,
                    y = it.y,
                    width = it.w,
                    height = it.h,
                    maxCapacity = it.maxCapacity,
                    displayOnly = it.displayOnly
                )
            }

            val generationOptions = root.option?.map {
                GenerationOption(
                    key = it.key,
                    label = it.label ?: it.key,
                    type = it.type,
                    placeholder = it.placeholder,
                    defaultValue = it.defaultValue
                )
            } ?: emptyList()

            val templates = root.templates?.let { t ->
                parseTemplatesSection(t)
            }

            val kubeJsCustom = synthesizeKubeJsCustom(templates)
            val finalTemplates = if (kubeJsCustom != null) {
                templates?.copy(formats = templates.formats + kubeJsCustom)
            } else templates

            val importSkipArgs = root.`import`?.skipArgs ?: 0
            val importOptions = root.`import`?.option?.map {
                ImportOptionDef(
                    key = it.key,
                    chainPattern = it.chainPattern,
                    positionalIndex = it.positionalIndex
                )
            } ?: emptyList()

            RecipeTypeData(
                id = id,
                displayName = displayName,
                uiTexture = uiTexture,
                spriteWidth = spriteW,
                spriteHeight = spriteH,
                regions = regions,
                slotTextures = emptyMap(),
                generationOptions = generationOptions,
                templates = finalTemplates,
                importSkipArgs = importSkipArgs,
                importOptions = importOptions
            )
        }.getOrNull()
    }

    private fun parseTemplatesSection(t: TemplatesToml): TemplatesData? {
        if (t.formats.isEmpty()) return null

        return TemplatesData(
            variantOption = t.variantOption,
            autoValue = t.autoValue,
            expectsGrid = t.expectsGrid,
            gridSlots = t.gridSlots,
            gridCols = t.gridCols,
            formats = t.formats,
            variantDefaults = t.variantDefaults ?: emptyMap()
        )
    }

    private fun synthesizeKubeJsCustom(templates: TemplatesData?): Map<String, Map<String, String>>? {
        if (templates == null) return null
        val hasJson = templates.formats.containsKey("json")
        val hasKubeJsCustom = templates.formats.containsKey("kubejs_custom")
        if (!hasJson || hasKubeJsCustom) return null
        return mapOf(
            "kubejs_custom" to mapOf(
                "_" to "event.custom(\n{{ raw_json }}\n)"
            )
        )
    }

    private fun hasTritiumApiTemplates(rawJson: String): Boolean {
        return runCatching {
            val obj = json.parseToJsonElement(rawJson).jsonObject
            val templates = obj["templates"]?.jsonObject ?: return@runCatching false
            val formats = templates["formats"]?.jsonObject
            formats != null && formats.isNotEmpty()
        }.getOrDefault(false)
    }

    // ── TOML model ────────────────────────────────────────────────────

    @Serializable
    private data class RecipeDescriptorToml(
        val recipe: RecipeSection,
        val slot: List<SlotSection> = emptyList(),
        val option: List<OptionSection>? = null,
        val templates: TemplatesToml? = null,
        val `import`: ImportSection? = null
    )

    @Serializable
    private data class ImportSection(
        @kotlinx.serialization.SerialName("skip_args")
        val skipArgs: Int = 0,
        val option: List<ImportOptionToml>? = null
    )

    @Serializable
    private data class ImportOptionToml(
        val key: String,
        @kotlinx.serialization.SerialName("chain_pattern")
        val chainPattern: String? = null,
        @kotlinx.serialization.SerialName("positional_index")
        val positionalIndex: Int? = null
    )

    @Serializable
    private data class RecipeSection(
        val id: String,
        @kotlinx.serialization.SerialName("display_name")
        val displayName: String? = null,
        val width: Int = 176,
        val height: Int = 166,
        val catalysts: List<String> = emptyList()
    )

    @Serializable
    private data class SlotSection(
        val id: String,
        val label: String? = null,
        val role: String,
        @kotlinx.serialization.SerialName("slot_type")
        val slotType: String = "ITEM",
        val x: Int,
        val y: Int,
        val w: Int = 18,
        val h: Int = 18,
        @kotlinx.serialization.SerialName("max_capacity")
        val maxCapacity: Long = 64,
        @kotlinx.serialization.SerialName("display_only")
        val displayOnly: Boolean = false
    )

    @Serializable
    private data class OptionSection(
        val key: String,
        val label: String? = null,
        val type: String = "text",
        val placeholder: String = "",
        @kotlinx.serialization.SerialName("default")
        val defaultValue: String = ""
    )

    @Serializable
    private data class TemplatesToml(
        val formats: Map<String, Map<String, String>> = emptyMap(),
        @kotlinx.serialization.SerialName("variant_option")
        val variantOption: String? = null,
        @kotlinx.serialization.SerialName("auto_value")
        val autoValue: String? = null,
        @kotlinx.serialization.SerialName("expects_grid")
        val expectsGrid: Boolean = false,
        @kotlinx.serialization.SerialName("grid_slots")
        val gridSlots: String? = null,
        @kotlinx.serialization.SerialName("grid_cols")
        val gridCols: Int = 3,
        @kotlinx.serialization.SerialName("variant_defaults")
        val variantDefaults: Map<String, Map<String, String>>? = null,
        val requirements: Map<String, FormatRequirement>? = null
    )

    @Serializable
    private data class FormatRequirement(
        val mod: String,
        val sources: Map<String, String> = emptyMap()
    )
}
