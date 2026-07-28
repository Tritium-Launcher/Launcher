/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.registrydb

import io.github.tritium_launcher.api.core.project.ProjectBase
import io.github.tritium_launcher.api.io.VPath
import io.github.tritium_launcher.api.logger
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.sqlite.SQLiteConfig
import java.sql.Connection
import java.sql.ResultSet

private const val REGISTRY_DB_SCHEMA_VERSION = 2L

object  RegistryDatabase {
    private val logger = logger()
    private val json = Json { ignoreUnknownKeys = true }

    private data class CachedDbState(
        val status: RegistryDbStatus.Ready,
        val connection: Connection
    )

    @Volatile
    private var cachedState: CachedDbState? = null
    @Volatile
    private var cachedProjectDir: String? = null

    fun invalidateCachedConnection() {
        cachedState?.connection?.close()
        cachedState = null
        cachedProjectDir = null
    }

    fun status(project: ProjectBase): RegistryDbStatus {
        val locations = resolveLocations(project)
        if(!locations.rootDir.exists()) {
            return RegistryDbStatus.MissingRoot(locations.rootDir)
        }
        if(!locations.latestPointer.exists()) {
            return RegistryDbStatus.MissingLatestPointer(locations.latestPointer)
        }
        if(!locations.database.exists()) {
            return RegistryDbStatus.MissingDatabase(locations.database)
        }

        val latest = readLatestPointer(locations.latestPointer)
            ?: return RegistryDbStatus.InvalidLatestPointer(locations.latestPointer)
        val snapshotDir = locations.rootDir.resolve(latest.path).toAbsolute()
        val manifestPath = snapshotDir.resolve("manifest.json")
        if(!manifestPath.exists()) {
            return RegistryDbStatus.MissingManifest(manifestPath)
        }

        val manifest = readManifest(manifestPath)
            ?: return RegistryDbStatus.InvalidManifest(manifestPath)
        if(!manifest.complete) {
            return RegistryDbStatus.IncompleteDump(snapshotDir)
        }

        return runCatching {
            openConnection(locations.database).use { conn ->
                if (!tableExists(conn, "metadata")) {
                    return@runCatching RegistryDbStatus.InvalidDatabase(
                        locations.database,
                        "Missing metadata table."
                    )
                }

                val dbSchema = meta(conn, "schema_version")?.toLongOrNull()
                if(dbSchema != REGISTRY_DB_SCHEMA_VERSION) {
                    return@runCatching RegistryDbStatus.SchemaMismatch(
                        locations.database,
                        expected = REGISTRY_DB_SCHEMA_VERSION,
                        actual = dbSchema
                    )
                }

                val dbSnapshot = meta(conn, "snapshot_id")
                if(dbSnapshot.isNullOrBlank()) {
                    return@runCatching RegistryDbStatus.InvalidDatabase(
                        locations.database,
                        "Missing snapshot_id metadata."
                    )
                }

                if(dbSnapshot != latest.snapshotId) {
                    return@runCatching RegistryDbStatus.StaleDatabase(
                        locations.database,
                        expectedSnapshotId = latest.snapshotId,
                        actualSnapshotId = dbSnapshot
                    )
                }

                RegistryDbStatus.Ready(
                    database = locations.database,
                    snapshotId = dbSnapshot,
                    manifestPath = manifestPath
                )
            }
        }.getOrElse { t ->
            logger.warn("Registry DB status check failed for '{}'", project.name, t)
            RegistryDbStatus.InvalidDatabase(locations.database, t.message ?: t::class.simpleName.orEmpty())
        }
    }

    fun registryCounts(project: ProjectBase): List<RegistryTypeCount> =
        withReadyDatabase(project) { conn ->
            conn.prepareStatement(
                //language=sql
                """
                SELECT registry_type, entry_count
                FROM v_registry_counts
                ORDER BY registry_type
                """.trimIndent()
            ).use { stmt ->
                stmt.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                RegistryTypeCount(
                                    registryType = rs.getString("registry_type"),
                                    entryCount = rs.getLong("entry_count")
                                )
                            )
                        }
                    }
                }
            }
        }

    fun modCounts(project: ProjectBase): List<ModContentCount> =
        withReadyDatabase(project) { conn ->
            conn.prepareStatement(
                //language=sqlite
                """
                SELECT namespace, item_count, recipe_count, recipe_type_count, tag_count, registry_entry_count, total_count
                FROM v_mod_counts
                ORDER BY total_count DESC, namespace ASC
                """.trimIndent()
            ).use { stmt ->
                stmt.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                ModContentCount(
                                    namespace = rs.getString("namespace"),
                                    itemCount = rs.getLong("item_count"),
                                    recipeCount = rs.getLong("recipe_count"),
                                    recipeTypeCount = rs.getLong("recipe_type_count"),
                                    tagCount = rs.getLong("tag_count"),
                                    registryEntryCount = rs.getLong("registry_entry_count"),
                                    totalCount = rs.getLong("total_count")
                                )
                            )
                        }
                    }
                }
            }
        }

    fun searchItems(project: ProjectBase, query: String, limit: Int = 100): List<RegistryItemSummary> =
        searchItems(project, query, offset = 0, limit = limit)

    /**
     * Parses a search query string and inventory filter into a SQL WHERE clause and parameter list.
     *
     * Supported syntax:
     *   `@modname`   — filter by namespace (LIKE)
     *   `#text`      — filter by display_name (LIKE)
     *   `$tagname`   — filter by tag_values (LIKE)
     *   `-term`      — negate any of the above
     *   `|`          — OR between groups
     *   space        — AND within a group
     *   bare text    — searches both id and display_name
     */
    private fun buildSearchWhere(query: String, inventoryIds: Set<String>?): Pair<String, List<String>> {
        val trimmed = query.trim()
        val clauses = mutableListOf<String>()
        val params = mutableListOf<String>()

        if (trimmed.isNotBlank()) {
            val orGroups = trimmed.split('|').filter { it.isNotBlank() }
            val orClauses = orGroups.mapNotNull { group ->
                val terms = group.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
                if (terms.isEmpty()) return@mapNotNull null

                val andClauses = mutableListOf<String>()
                val groupParams = mutableListOf<String>()

                for (term in terms) {
                    val negated = term.startsWith('-')
                    val body = if (negated) term.substring(1) else term
                    val prefix = when {
                        body.startsWith('@') -> '@'
                        body.startsWith('#') -> '#'
                        body.startsWith('$') -> '$'
                        else -> null
                    }
                    val searchValue = if (prefix != null) body.substring(1) else body
                    val pattern = "%${searchValue.lowercase()}%"

                    when (prefix) {
                        '@' -> {
                            andClauses.add(if (negated) "namespace NOT LIKE ?" else "namespace LIKE ?")
                            groupParams.add(pattern)
                        }
                        '#' -> {
                            andClauses.add(if (negated) "lower(COALESCE(display_name, '')) NOT LIKE ?" else "lower(COALESCE(display_name, '')) LIKE ?")
                            groupParams.add(pattern)
                        }
                        '$' -> {
                            andClauses.add(if (negated) "tag_values NOT LIKE ?" else "tag_values LIKE ?")
                            groupParams.add(pattern)
                        }
                        null -> {
                            if (negated) {
                                andClauses.add("(id NOT LIKE ? AND lower(COALESCE(display_name, '')) NOT LIKE ?)")
                            } else {
                                andClauses.add("(id LIKE ? OR lower(COALESCE(display_name, '')) LIKE ?)")
                            }
                            groupParams.add(pattern)
                            groupParams.add(pattern)
                        }
                    }
                }

                params.addAll(groupParams)
                andClauses.joinToString(" AND ", "(", ")")
            }

            if (orClauses.isNotEmpty()) {
                clauses.add(orClauses.joinToString(" OR "))
            }
        }

        if (inventoryIds != null && inventoryIds.isNotEmpty()) {
            val placeholders = inventoryIds.joinToString(", ") { "?" }
            clauses.add("id IN ($placeholders)")
            params.addAll(inventoryIds)
        }

        return if (clauses.isEmpty()) "" to emptyList()
               else clauses.joinToString(" AND ") to params
    }

    fun countItems(project: ProjectBase, query: String, inventoryIds: Set<String>? = null): Int =
        withReadyDatabase(project) { conn ->
            val (whereClause, whereParams) = buildSearchWhere(query, inventoryIds)

            val sql = buildString {
                append("SELECT COUNT(*) AS count FROM v_item_browser")
                if (whereClause.isNotBlank()) {
                    append(" WHERE $whereClause")
                }
            }

            conn.prepareStatement(sql).use { stmt ->
                whereParams.forEachIndexed { i, param ->
                    stmt.setString(i + 1, param)
                }
                stmt.executeQuery().use { rs ->
                    if (rs.next()) rs.getInt("count") else 0
                }
            }
        }

    fun searchItems(project: ProjectBase, query: String, offset: Int, limit: Int, inventoryIds: Set<String>? = null): List<RegistryItemSummary> =
        withReadyDatabase(project) { conn ->
            val (whereClause, whereParams) = buildSearchWhere(query, inventoryIds)

            val sql = buildString {
                append("SELECT namespace, id, path, display_name, max_count, max_damage, rarity, enchantability, texture_path, animation_json, tag_values FROM v_item_browser")
                if (whereClause.isNotBlank()) {
                    append(" WHERE $whereClause")
                }
                append(" ORDER BY CASE WHEN namespace = 'minecraft' THEN 0 ELSE 1 END, namespace ASC, COALESCE(display_name, id) ASC LIMIT ? OFFSET ?")
            }

            conn.prepareStatement(sql).use { stmt ->
                var idx = 1
                for (param in whereParams) {
                    stmt.setString(idx++, param)
                }
                stmt.setInt(idx++, limit)
                stmt.setInt(idx, offset)

                stmt.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(rs.toItemSummary())
                        }
                    }
                }
            }
        }

    fun itemDetail(project: ProjectBase, id: String): RegistryItemDetail? =
        withReadyDatabase(project) { conn ->
            conn.prepareStatement(
                //language=sql
                """
                SELECT id, namespace, path, display_name, max_count, max_damage, rarity, enchantability, texture_path, animation_json, raw_json
                FROM items
                WHERE id = ?
                """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, id)
                stmt.executeQuery().use { rs ->
                    if(!rs.next()) {
                        return@withReadyDatabase null
                    }

                    RegistryItemDetail(
                        id = rs.getString("id"),
                        namespace = rs.getString("namespace"),
                        path = rs.getString("path"),
                        displayName = rs.getString("display_name"),
                        maxCount = rs.getNullableInt("max_count"),
                        maxDamage = rs.getNullableInt("max_damage"),
                        rarity = rs.getString("rarity"),
                        enchantability = rs.getNullableInt("enchantability"),
                        texturePath = rs.getString("texture_path"),
                        animationJson = rs.getString("animation_json"),
                        rawJson = rs.getString("raw_json"),
                        tags = loadTagsForValue(conn, registryType = "item", value = id)
                    )
                }
            }
        }

    data class ItemDetailWithRecipes(
        val detail: RegistryItemDetail?,
        val recipeUsage: RegistryItemRecipeUsage,
        val recipeDetails: List<RegistryRecipeDetail>
    )

    fun suggestNamespaces(project: ProjectBase, filter: String): List<String> =
        withReadyDatabase(project) { conn ->
            conn.prepareStatement(
                //language=sql
                """
                SELECT DISTINCT namespace
                FROM v_item_browser
                WHERE namespace LIKE ?
                ORDER BY CASE WHEN namespace = 'minecraft' THEN 0 ELSE 1 END, namespace
                LIMIT 20
                """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, "%${filter.lowercase()}%")
                stmt.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) add(rs.getString("namespace"))
                    }
                }
            }
        }

    fun suggestTags(project: ProjectBase, filter: String): List<String> =
        withReadyDatabase(project) { conn ->
            conn.prepareStatement(
                //language=sql
                """
                SELECT DISTINCT id
                FROM tags
                WHERE registry_type = 'item' AND id LIKE ?
                ORDER BY CASE WHEN namespace = 'minecraft' THEN 0 ELSE 1 END, id
                LIMIT 20
                """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, "%${filter.lowercase()}%")
                stmt.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) add(rs.getString("id"))
                    }
                }
            }
        }

    fun itemDetailWithRecipes(project: ProjectBase, id: String): ItemDetailWithRecipes =
        withReadyDatabase(project) { conn ->
            val detail = conn.prepareStatement(
                //language=sql
                """
                SELECT id, namespace, path, display_name, max_count, max_damage, rarity, enchantability, texture_path, animation_json, raw_json
                FROM items
                WHERE id = ?
                """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, id)
                stmt.executeQuery().use { rs ->
                    if (!rs.next()) {
                        null to emptyList<String>()
                    } else {
                        RegistryItemDetail(
                            id = rs.getString("id"),
                            namespace = rs.getString("namespace"),
                            path = rs.getString("path"),
                            displayName = rs.getString("display_name"),
                            maxCount = rs.getNullableInt("max_count"),
                            maxDamage = rs.getNullableInt("max_damage"),
                            rarity = rs.getString("rarity"),
                            enchantability = rs.getNullableInt("enchantability"),
                            texturePath = rs.getString("texture_path"),
                            animationJson = rs.getString("animation_json"),
                            rawJson = rs.getString("raw_json"),
                            tags = loadTagsForValue(conn, registryType = "item", value = id)
                        ) to emptyList<String>()
                    }
                }
            }

            val recipeUsage = RegistryItemRecipeUsage(
                producedBy = recipesForItemRole(conn, id, "output"),
                usedIn = recipesForItemRole(conn, id, "input")
            )

            val allRecipeIds = recipeUsage.producedBy.map { it.id } + recipeUsage.usedIn.map { it.id }
            val recipeDetails = recipeDetailsFromIds(conn, allRecipeIds)

            ItemDetailWithRecipes(
                detail = detail.first,
                recipeUsage = recipeUsage,
                recipeDetails = recipeDetails
            )
        }

    private fun recipeDetailsFromIds(conn: Connection, recipeIds: Collection<String>): List<RegistryRecipeDetail> =
        if (recipeIds.isEmpty()) {
            emptyList()
        } else {
            val placeholders = recipeIds.joinToString(",") { "?" }
            conn.prepareStatement(
                //language=sql
                """
                SELECT r.id, r.namespace, r.path, r.recipe_type, r.group_name, r.raw_json, r.source_path,
                       rt.input_slots, rt.output_slots, rt.fuel_slots, rt.raw_json AS recipe_type_raw_json
                FROM recipes r
                LEFT JOIN recipe_types rt ON rt.id = r.recipe_type
                WHERE r.id IN ($placeholders)
                """.trimIndent()
            ).use { stmt ->
                recipeIds.forEachIndexed { index, rid ->
                    stmt.setString(index + 1, rid)
                }
                stmt.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                            RegistryRecipeDetail(
                                id = rs.getString("id"),
                                namespace = rs.getString("namespace"),
                                path = rs.getString("path"),
                                recipeType = rs.getString("recipe_type"),
                                groupName = rs.getString("group_name"),
                                inputSlots = rs.getNullableInt("input_slots"),
                                outputSlots = rs.getNullableInt("output_slots"),
                                fuelSlots = rs.getNullableInt("fuel_slots"),
                                rawJson = rs.getString("raw_json"),
                                recipeTypeRawJson = rs.getString("recipe_type_raw_json"),
                                sourcePath = rs.getString("source_path")
                            )
                            )
                        }
                    }
                }
            }
        }

    fun itemTexturePath(project: ProjectBase, id: String): String? =
        withReadyDatabase(project) { conn ->
            conn.prepareStatement(
                //language=sql
                """
                SELECT texture_path
                FROM items
                WHERE id = ?
                """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, id)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) rs.getString("texture_path") else null
                }
            }
        }

    fun itemDisplayName(project: ProjectBase, id: String): String? =
        withReadyDatabase(project) { conn ->
            conn.prepareStatement(
                """
                SELECT display_name
                FROM items
                WHERE id = ?
                """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, id)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) rs.getString("display_name") else null
                }
            }
        }

    fun itemAnimationJson(project: ProjectBase, id: String): String? =
        withReadyDatabase(project) { conn ->
            conn.prepareStatement(
                //language=sql
                """
                SELECT animation_json
                FROM items
                WHERE id = ?
                """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, id)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) rs.getString("animation_json") else null
                }
            }
        }

    fun customValueTintColor(project: ProjectBase, id: String): Long? =
        withReadyDatabase(project) { conn ->
            conn.prepareStatement(
                //language=sql
                """
                SELECT raw_json
                FROM custom_values
                WHERE id = ?
                LIMIT 1
                """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, id)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) {
                        val rawJson = rs.getString("raw_json")
                        rawJson?.let {
                            runCatching {
                                val obj = json.parseToJsonElement(it).jsonObject
                                obj["tintColor"]?.jsonPrimitive?.longOrNull
                                    ?: obj["rawData"]?.jsonObject?.get("tintColor")?.jsonPrimitive?.longOrNull
                            }.getOrNull()
                        }
                    } else null
                }
            }
        }

    fun customValueTypeId(project: ProjectBase, id: String): String? =
        withReadyDatabase(project) { conn ->
            conn.prepareStatement(
                //language=sql
                """
                SELECT type_id
                FROM custom_values
                WHERE id = ?
                LIMIT 1
                """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, id)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) rs.getString("type_id") else null
                }
            }
        }

    fun resolveRegistryType(project: ProjectBase, id: String): String? {
        val inItems = withReadyDatabase(project) { conn ->
            conn.prepareStatement("SELECT 1 FROM items WHERE id = ? LIMIT 1").use { stmt ->
                stmt.setString(1, id)
                stmt.executeQuery().use { rs -> if (rs.next()) "item" else null }
            }
        }
        if (inItems != null) return "item"
        return customValueTypeId(project, id)
    }

    fun itemIdsForTag(project: ProjectBase, tagId: String): List<String> =
        withReadyDatabase(project) { conn ->
            resolveTagValues(conn, registryType = "item", tagId = tagId, visitedTags = linkedSetOf())
        }

    fun tagsForItem(project: ProjectBase, itemId: String): List<String> =
        withReadyDatabase(project) { conn ->
            val ns = if (itemId.contains(":")) itemId.substringBefore(":") else "minecraft"
            val path = if (itemId.contains(":")) itemId.substringAfter(":") else itemId
            loadTagsForValue(conn, registryType = "item", value = "$ns:$path")
        }

    fun itemPreviewsForTag(project: ProjectBase, tagId: String): List<RegistryItemPreview> =
        withReadyDatabase(project) { conn ->
            val itemIds = resolveTagValues(conn, registryType = "item", tagId = tagId, visitedTags = linkedSetOf())
            if (itemIds.isEmpty()) return@withReadyDatabase emptyList()
            val placeholders = itemIds.joinToString(",") { "?" }
            conn.prepareStatement(
                //language=sql
                """
                SELECT id, texture_path
                FROM items
                WHERE id IN ($placeholders)
                """.trimIndent()
            ).use { stmt ->
                itemIds.forEachIndexed { index, id ->
                    stmt.setString(index + 1, id)
                }
                stmt.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                RegistryItemPreview(
                                    id = rs.getString("id"),
                                    texturePath = rs.getString("texture_path")
                                )
                            )
                        }
                    }
                }
            }
        }

    fun browseRecipes(project: ProjectBase, query: String, limit: Int = 100, offset: Int = 0): List<RegistryRecipeSummary> =
        withReadyDatabase(project) { conn ->
            val trimmed = query.trim().lowercase()
            val sql = if(trimmed.isBlank()) {
                //language=sql
                """
                SELECT namespace, id, path, recipe_type, group_name, input_slots, output_slots, fuel_slots
                FROM v_recipe_browser
                ORDER BY namespace ASC, id ASC
                LIMIT ? OFFSET ?
                """.trimIndent()
            } else {
                //language=sql
                """
                SELECT namespace, id, path, recipe_type, group_name, input_slots, output_slots, fuel_slots
                FROM v_recipe_browser
                WHERE id LIKE ?
                   OR lower(COALESCE(recipe_type, '')) LIKE ?
                   OR lower(COALESCE(group_name, '')) LIKE ?
                ORDER BY namespace ASC, id ASC
                LIMIT ? OFFSET ?
                """.trimIndent()
            }

            conn.prepareStatement(sql).use { stmt ->
                if(trimmed.isBlank()) {
                    stmt.setInt(1, limit)
                    stmt.setInt(2, offset)
                } else {
                    val pattern = "%$trimmed%"
                    stmt.setString(1, pattern)
                    stmt.setString(2, pattern)
                    stmt.setString(3, pattern)
                    stmt.setInt(4, limit)
                    stmt.setInt(5, offset)
                }

                stmt.executeQuery().use { rs ->
                    buildList {
                        while(rs.next()) {
                            add(rs.toRecipeSummary())
                        }
                    }
                }
            }
        }

    fun recipeDetail(project: ProjectBase, id: String): RegistryRecipeDetail? =
        withReadyDatabase(project) { conn ->
            conn.prepareStatement(
                //language=sql
                """
                SELECT r.id, r.namespace, r.path, r.recipe_type, r.group_name, r.raw_json,
                       rt.input_slots, rt.output_slots, rt.fuel_slots, rt.raw_json AS recipe_type_raw_json
                FROM recipes r
                LEFT JOIN recipe_types rt ON rt.id = r.recipe_type
                WHERE r.id = ?
                """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, id)
                stmt.executeQuery().use { rs ->
                    if(!rs.next()) {
                        return@withReadyDatabase null
                    }

                    RegistryRecipeDetail(
                        id = rs.getString("id"),
                        namespace = rs.getString("namespace"),
                        path = rs.getString("path"),
                        recipeType = rs.getString("recipe_type"),
                        groupName = rs.getString("group_name"),
                        inputSlots = rs.getNullableInt("input_slots"),
                        outputSlots = rs.getNullableInt("output_slots"),
                        fuelSlots = rs.getNullableInt("fuel_slots"),
                        rawJson = rs.getString("raw_json"),
                        recipeTypeRawJson = rs.getString("recipe_type_raw_json")
                    )
                }
            }
        }

    fun recipeDetails(project: ProjectBase, recipeIds: Collection<String>): List<RegistryRecipeDetail> =
        if (recipeIds.isEmpty()) {
            emptyList()
        } else {
            withReadyDatabase(project) { conn ->
                val placeholders = recipeIds.joinToString(",") { "?" }
                conn.prepareStatement(
                    //language=sql
                    """
                    SELECT r.id, r.namespace, r.path, r.recipe_type, r.group_name, r.raw_json,
                           rt.input_slots, rt.output_slots, rt.fuel_slots, rt.raw_json AS recipe_type_raw_json
                    FROM recipes r
                    LEFT JOIN recipe_types rt ON rt.id = r.recipe_type
                    WHERE r.id IN ($placeholders)
                    """.trimIndent()
                ).use { stmt ->
                    recipeIds.forEachIndexed { index, id ->
                        stmt.setString(index + 1, id)
                    }
                    stmt.executeQuery().use { rs ->
                        buildList {
                            while (rs.next()) {
                                add(
                                    RegistryRecipeDetail(
                                        id = rs.getString("id"),
                                        namespace = rs.getString("namespace"),
                                        path = rs.getString("path"),
                                        recipeType = rs.getString("recipe_type"),
                                        groupName = rs.getString("group_name"),
                                        inputSlots = rs.getNullableInt("input_slots"),
                                        outputSlots = rs.getNullableInt("output_slots"),
                                        fuelSlots = rs.getNullableInt("fuel_slots"),
                                        rawJson = rs.getString("raw_json"),
                                        recipeTypeRawJson = rs.getString("recipe_type_raw_json")
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }

    fun customValuePreview(project: ProjectBase, typeId: String, id: String): RegistryValuePreview? =
        withReadyDatabase(project) { conn ->
            conn.prepareStatement(
                //language=sql
                """
                SELECT cv.id, cv.display_name, cv.texture_path, vt.display_name AS type_display_name
                FROM custom_values cv
                LEFT JOIN value_types vt ON vt.id = cv.type_id
                WHERE cv.type_id = ? AND cv.id = ?
                """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, typeId)
                stmt.setString(2, id)
                stmt.executeQuery().use { rs ->
                    if(!rs.next()) {
                        return@withReadyDatabase null
                    }

                    RegistryValuePreview(
                        id = rs.getString("id"),
                        typeId = typeId,
                        displayName = rs.getString("display_name"),
                        typeDisplayName = rs.getString("type_display_name"),
                        texturePath = rs.getString("texture_path")
                    )
                }
            }
        }

    fun recipesForItem(project: ProjectBase, itemId: String): RegistryItemRecipeUsage =
        withReadyDatabase(project) { conn ->
            RegistryItemRecipeUsage(
                producedBy = recipesForItemRole(conn, itemId, "output"),
                usedIn = recipesForItemRole(conn, itemId, "input")
            )
        }

    fun recipesForProduct(project: ProjectBase, itemId: String): List<RegistryRecipeSummary> =
        recipesForItem(project, itemId).producedBy

    fun recipesForIngredient(project: ProjectBase, itemId: String): List<RegistryRecipeSummary> =
        recipesForItem(project, itemId).usedIn

    fun recipeTypesForItem(project: ProjectBase, itemId: String): List<RecipeTypeCatalyst> =
        withReadyDatabase(project) { conn ->
            conn.prepareStatement(
                //language=sql
                """
                SELECT DISTINCT rt.id, rt.display_name, rt.catalysts
                FROM recipe_links rl
                JOIN recipes r ON r.id = rl.recipe_id
                JOIN recipe_types rt ON rt.id = r.recipe_type
                WHERE rl.value = ?
                ORDER BY rt.id ASC
                """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, itemId)
                stmt.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            val catalystsRaw = rs.getString("catalysts")
                            val catalystIds = runCatching {
                                kotlinx.serialization.json.Json.decodeFromString<List<String>>(catalystsRaw ?: "[]")
                            }.getOrDefault(emptyList())
                            add(
                                RecipeTypeCatalyst(
                                    recipeTypeId = rs.getString("id"),
                                    displayName = rs.getString("display_name"),
                                    catalystIds = catalystIds
                                )
                            )
                        }
                    }
                }
            }
        }

    data class RecipeTypeSummary(
        val id: String,
        val displayName: String?,
        val rawJson: String
    )

    fun allRecipeTypes(project: ProjectBase): List<RecipeTypeSummary> =
        withReadyDatabase(project) { conn ->
            conn.prepareStatement(
                //language=sql
                """
                SELECT id, display_name, raw_json
                FROM recipe_types
                ORDER BY id ASC
                """.trimIndent()
            ).use { stmt ->
                stmt.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                RecipeTypeSummary(
                                    id = rs.getString("id"),
                                    displayName = rs.getString("display_name"),
                                    rawJson = rs.getString("raw_json")
                                )
                            )
                        }
                    }
                }
            }
        }

    fun itemSummariesByIds(project: ProjectBase, ids: Collection<String>): List<RegistryItemSummary> =
        if (ids.isEmpty()) emptyList() else withReadyDatabase(project) { conn ->
            val placeholders = ids.joinToString(",") { "?" }
            conn.prepareStatement(
                //language=sql
                """
                SELECT namespace, id, path, display_name, max_count, max_damage, rarity, enchantability, texture_path, animation_json, tag_values
                FROM v_item_browser
                WHERE id IN ($placeholders)
                ORDER BY id ASC
                """.trimIndent()
            ).use { stmt ->
                ids.forEachIndexed { index, id ->
                    stmt.setString(index + 1, id)
                }
                stmt.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(rs.toItemSummary())
                        }
                    }
                }
            }
        }

    fun browseableValueTypes(project: ProjectBase): List<BrowseableValueType> =
        withReadyDatabase(project) { conn ->
            val itemType = BrowseableValueType(
                id = "item",
                namespace = "minecraft",
                path = "item",
                displayName = "Items",
                iconTexture = null
            )

            val customTypes = runCatching {
                conn.prepareStatement(
                    //language=sql
                    """
                    SELECT id, namespace, path, display_name, icon_texture
                    FROM v_browseable_value_types
                    """.trimIndent()
                ).use { stmt ->
                    stmt.executeQuery().use { rs ->
                        buildList {
                            while (rs.next()) {
                                add(
                                    BrowseableValueType(
                                        id = rs.getString("id"),
                                        namespace = rs.getString("namespace"),
                                        path = rs.getString("path"),
                                        displayName = rs.getString("display_name"),
                                        iconTexture = rs.getString("icon_texture")
                                    )
                                )
                            }
                        }
                    }
                }
            }.getOrDefault(emptyList())

            if (customTypes.isNotEmpty()) {
                return@withReadyDatabase listOf(itemType) + customTypes
            }

            val fallbackTypes = runCatching {
                conn.prepareStatement(
                    //language=sql
                    """
                    SELECT DISTINCT type_id AS id,
                           '' AS namespace,
                           type_id AS path,
                           type_id AS display_name,
                           NULL AS icon_texture
                    FROM custom_values
                    ORDER BY type_id
                    """.trimIndent()
                ).use { stmt ->
                    stmt.executeQuery().use { rs ->
                        buildList {
                            while (rs.next()) {
                                add(
                                    BrowseableValueType(
                                        id = rs.getString("id"),
                                        namespace = rs.getString("namespace"),
                                        path = rs.getString("path"),
                                        displayName = rs.getString("display_name"),
                                        iconTexture = rs.getString("icon_texture")
                                    )
                                )
                            }
                        }
                    }
                }
            }.getOrDefault(emptyList())

            listOf(itemType) + fallbackTypes
        }

    fun searchCustomValues(
        project: ProjectBase,
        typeId: String,
        query: String,
        offset: Int,
        limit: Int
    ): List<RegistryValueSummary> =
        withReadyDatabase(project) { conn ->
            val trimmed = query.trim().lowercase()
            val sql = if (trimmed.isBlank()) {
                //language=sql
                """
                SELECT cv.type_id, cv.id, cv.namespace, cv.path, cv.display_name, cv.texture_path, cv.raw_json,
                       vt.display_name AS type_display_name
                FROM custom_values cv
                LEFT JOIN value_types vt ON vt.id = cv.type_id
                WHERE cv.type_id = ? AND cv.id NOT LIKE '%:flowing_%'
                  AND cv.id NOT IN (SELECT id FROM items)
                ORDER BY CASE WHEN cv.namespace = 'minecraft' THEN 0 ELSE 1 END, cv.namespace ASC, COALESCE(cv.display_name, cv.id) ASC
                LIMIT ? OFFSET ?
                """.trimIndent()
            } else {
                //language=sql
                """
                SELECT cv.type_id, cv.id, cv.namespace, cv.path, cv.display_name, cv.texture_path, cv.raw_json,
                       vt.display_name AS type_display_name
                FROM custom_values cv
                LEFT JOIN value_types vt ON vt.id = cv.type_id
                WHERE cv.type_id = ? AND cv.id NOT LIKE '%:flowing_%'
                  AND cv.id NOT IN (SELECT id FROM items)
                  AND (cv.id LIKE ? OR lower(COALESCE(cv.display_name, '')) LIKE ?)
                ORDER BY CASE WHEN cv.namespace = 'minecraft' THEN 0 ELSE 1 END, cv.namespace ASC, COALESCE(cv.display_name, cv.id) ASC
                LIMIT ? OFFSET ?
                """.trimIndent()
            }

            conn.prepareStatement(sql).use { stmt ->
                var idx = 1
                stmt.setString(idx++, typeId)
                if (trimmed.isNotBlank()) {
                    val pattern = "%$trimmed%"
                    stmt.setString(idx++, pattern)
                    stmt.setString(idx++, pattern)
                }
                stmt.setInt(idx++, limit)
                stmt.setInt(idx, offset)

                stmt.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            val rawJson = rs.getString("raw_json")
                            val tintColor = rawJson?.let {
                                runCatching {
                                    val obj = json.parseToJsonElement(it).jsonObject
                                    obj["tintColor"]?.jsonPrimitive?.longOrNull
                                        ?: obj["rawData"]?.jsonObject?.get("tintColor")?.jsonPrimitive?.longOrNull
                                }.getOrNull()
                            }

                            add(
                                RegistryValueSummary(
                                    typeId = rs.getString("type_id"),
                                    id = rs.getString("id"),
                                    namespace = rs.getString("namespace"),
                                    path = rs.getString("path"),
                                    displayName = rs.getString("display_name"),
                                    texturePath = rs.getString("texture_path"),
                                    typeDisplayName = rs.getString("type_display_name"),
                                    tintColor = tintColor
                                )
                            )
                        }
                    }
                }
            }
        }

    fun countCustomValues(project: ProjectBase, typeId: String, query: String): Int =
        withReadyDatabase(project) { conn ->
            val trimmed = query.trim().lowercase()
            val sql = if (trimmed.isBlank()) {
                //language=sql
                """
                SELECT COUNT(*) AS count
                FROM custom_values
                WHERE type_id = ? AND id NOT LIKE '%:flowing_%'
                  AND id NOT IN (SELECT id FROM items)
                """.trimIndent()
            } else {
                //language=sql
                """
                SELECT COUNT(*) AS count
                FROM custom_values
                WHERE type_id = ? AND id NOT LIKE '%:flowing_%'
                  AND id NOT IN (SELECT id FROM items)
                  AND (id LIKE ? OR lower(COALESCE(display_name, '')) LIKE ?)
                """.trimIndent()
            }

            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, typeId)
                if (trimmed.isNotBlank()) {
                    val pattern = "%$trimmed%"
                    stmt.setString(2, pattern)
                    stmt.setString(3, pattern)
                }
                stmt.executeQuery().use { rs ->
                    if (rs.next()) rs.getInt("count") else 0
                }
            }
        }

    fun customValueDetail(project: ProjectBase, typeId: String, id: String): RegistryValueDetail? =
        withReadyDatabase(project) { conn ->
            conn.prepareStatement(
                //language=sql
                """
                SELECT cv.type_id, cv.id, cv.namespace, cv.path, cv.display_name, cv.texture_path, cv.raw_json,
                       vt.display_name AS type_display_name
                FROM custom_values cv
                LEFT JOIN value_types vt ON vt.id = cv.type_id
                WHERE cv.type_id = ? AND cv.id = ?
                """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, typeId)
                stmt.setString(2, id)
                stmt.executeQuery().use { rs ->
                    if (!rs.next()) return@withReadyDatabase null

                    RegistryValueDetail(
                        typeId = rs.getString("type_id"),
                        id = rs.getString("id"),
                        namespace = rs.getString("namespace"),
                        path = rs.getString("path"),
                        displayName = rs.getString("display_name"),
                        texturePath = rs.getString("texture_path"),
                        typeDisplayName = rs.getString("type_display_name"),
                        rawJson = rs.getString("raw_json"),
                        tags = loadTagsForValue(conn, registryType = typeId, value = id)
                    )
                }
            }
        }

    fun registryEntryDetail(project: ProjectBase, registryType: String, id: String): RegistryEntryDetail? =
        withReadyDatabase(project) { conn ->
            conn.prepareStatement(
                //language=sql
                """
                SELECT registry_type, id, namespace, path, raw_json
                FROM registry_entries
                WHERE registry_type = ? AND id = ?
                """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, registryType)
                stmt.setString(2, id)
                stmt.executeQuery().use { rs ->
                    if(!rs.next()) {
                        return@withReadyDatabase null
                    }

                    RegistryEntryDetail(
                        registryType = rs.getString("registry_type"),
                        id = rs.getString("id"),
                        namespace = rs.getString("namespace"),
                        path = rs.getString("path"),
                        rawJson = rs.getString("raw_json")
                    )
                }
            }
        }

    private fun <T> withReadyDatabase(project: ProjectBase, block: (Connection) -> T): T {
        val projectDir = project.projectDir.toString().trim()
        val current = cachedState
        if (current != null && cachedProjectDir == projectDir) {
            return block(current.connection)
        }

        val state = status(project)
        return when (state) {
            is RegistryDbStatus.Ready -> {
                cachedState?.connection?.close()
                val conn = openConnection(state.database)
                cachedState = CachedDbState(state, conn)
                cachedProjectDir = projectDir
                block(conn)
            }
            else -> throw IllegalStateException("Registry DB is not ready for '${project.name}': $state")
        }
    }

    private fun openConnection(db: VPath): Connection {
        Class.forName("org.sqlite.JDBC")
        val config = SQLiteConfig().apply {
            setReadOnly(true)
        }
        val conn = config.createConnection("jdbc:sqlite:${db.toAbsolute()}")
        runCatching { conn.createStatement().execute("PRAGMA mmap_size = 268435456") }
        return conn
    }

    private fun meta(conn: Connection, key: String): String? {
        conn.prepareStatement("SELECT value FROM metadata WHERE key = ?").use { stmt ->
            stmt.setString(1, key)
            stmt.executeQuery().use { rs ->
                return if(rs.next()) rs.getString("value") else null
            }
        }
    }

    private fun tableExists(conn: Connection, tableName: String): Boolean {
        conn.prepareStatement(
            //language=sql
            """
            SELECT 1
            FROM sqlite_master
            WHERE type = 'table' AND name = ?
            LIMIT 1
            """.trimIndent()
        ).use { stmt ->
            stmt.setString(1, tableName)
            stmt.executeQuery().use { rs ->
                return rs.next()
            }
        }
    }

    private fun resolveTagValues(
        conn: Connection,
        registryType: String,
        tagId: String,
        visitedTags: MutableSet<String>
    ): List<String> {
        if (!visitedTags.add(tagId)) return emptyList()

        conn.prepareStatement(
            """
            SELECT value
            FROM tag_values
            WHERE registry_type = ? AND tag_id = ?
            ORDER BY ordinal ASC
            """.trimIndent()
        ).use { stmt ->
            stmt.setString(1, registryType)
            stmt.setString(2, tagId)
            stmt.executeQuery().use { rs ->
                val resolved = linkedSetOf<String>()
                while (rs.next()) {
                    val value = rs.getString("value").orEmpty()
                    if (value.startsWith("#")) {
                        resolved += resolveTagValues(
                            conn,
                            registryType = registryType,
                            tagId = value.removePrefix("#"),
                            visitedTags = visitedTags
                        )
                    } else if (value.isNotBlank()) {
                        resolved += value
                    }
                }
                return resolved.toList()
            }
        }
    }

    private fun loadTagsForValue(conn: Connection, registryType: String, value: String): List<String> {
        conn.prepareStatement(
            """
            SELECT tag_id
            FROM tag_values
            WHERE registry_type = ? AND value = ?
            ORDER BY tag_id ASC
            """.trimIndent()
        ).use { stmt ->
            stmt.setString(1, registryType)
            stmt.setString(2, value)
            stmt.executeQuery().use { rs ->
                return buildList {
                    while(rs.next()) {
                        add(rs.getString("tag_id"))
                    }
                }
            }
        }
    }

    private fun resolveLocations(project: ProjectBase): RegistryDbLocations {
        val root = project.projectDir.resolve("registryObjs").toAbsolute()
        return RegistryDbLocations(
            rootDir = root,
            latestPointer = root.resolve("latest.json"),
            database = root.resolve("game_registry.db")
        )
    }

    private fun readLatestPointer(path: VPath): RegistryLatestPointer? = runCatching {
        json.decodeFromString<RegistryLatestPointer>(path.readTextOrNull() ?: return null)
    }.onFailure { t ->
        logger.warn("Failed reading registry latest pointer '{}'", path.toAbsolute(), t)
    }.getOrNull()

    private fun readManifest(path: VPath): RegistryDumpManifest? = runCatching {
        json.decodeFromString<RegistryDumpManifest>(path.readTextOrNull() ?: return null)
    }.onFailure { t ->
        logger.warn("Failed reading registry manifest '{}'", path.toAbsolute(), t)
    }.getOrNull()

    private fun recipesForItemRole(conn: Connection, itemId: String, role: String): List<RegistryRecipeSummary> =
        conn.prepareStatement(
            //language=sql
            """
            SELECT DISTINCT
                r.namespace,
                r.id,
                r.path,
                r.recipe_type,
                r.group_name,
                rt.input_slots,
                rt.output_slots,
                rt.fuel_slots
            FROM recipe_links rl
            JOIN recipes r ON r.id = rl.recipe_id
            LEFT JOIN recipe_types rt ON rt.id = r.recipe_type
            WHERE rl.role = ?
              AND (
                (rl.value_kind = 'item' AND rl.value = ?)
                OR (
                    rl.value_kind = 'tag'
                    AND EXISTS (
                        SELECT 1
                        FROM tag_values tv
                        WHERE tv.registry_type = 'item'
                          AND tv.tag_id = rl.value
                          AND tv.value = ?
                    )
                )
              )
            ORDER BY r.namespace ASC, r.id ASC
            """.trimIndent()
        ).use { stmt ->
            stmt.setString(1, role)
            stmt.setString(2, itemId)
            stmt.setString(3, itemId)
            stmt.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(rs.toRecipeSummary())
                    }
                }
            }
        }

    private fun ResultSet.toItemSummary(): RegistryItemSummary {
        val rawTags = getString("tag_values").orEmpty()
        return RegistryItemSummary(
            id = getString("id"),
            namespace = getString("namespace"),
            path = getString("path"),
            displayName = getString("display_name"),
            maxCount = getNullableInt("max_count"),
            maxDamage = getNullableInt("max_damage"),
            rarity = getString("rarity"),
            enchantability = getNullableInt("enchantability"),
            texturePath = getString("texture_path"),
            animationJson = getString("animation_json"),
            tags = rawTags.lines().mapNotNull { it.trim().takeIf(String::isNotBlank) }
        )
    }

    private fun ResultSet.toRecipeSummary() = RegistryRecipeSummary(
        id = getString("id"),
        namespace = getString("namespace"),
        path = getString("path"),
        recipeType = getString("recipe_type"),
        groupName = getString("group_name"),
        inputSlots = getNullableInt("input_slots"),
        outputSlots = getNullableInt("output_slots"),
        fuelSlots = getNullableInt("fuel_slots")
    )
}

data class RegistryDbLocations(
    val rootDir: VPath,
    val latestPointer: VPath,
    val database: VPath
)

sealed interface RegistryDbStatus {
    data class MissingRoot(val path: VPath): RegistryDbStatus
    data class MissingLatestPointer(val path: VPath): RegistryDbStatus
    data class MissingDatabase(val path: VPath): RegistryDbStatus
    data class MissingManifest(val path: VPath): RegistryDbStatus
    data class InvalidLatestPointer(val path: VPath): RegistryDbStatus
    data class InvalidManifest(val path: VPath): RegistryDbStatus
    data class IncompleteDump(val snapshotDir: VPath): RegistryDbStatus
    data class SchemaMismatch(val path: VPath, val expected: Long, val actual: Long?): RegistryDbStatus
    data class StaleDatabase(val path: VPath, val expectedSnapshotId: String, val actualSnapshotId: String): RegistryDbStatus
    data class InvalidDatabase(val path: VPath, val reason: String): RegistryDbStatus
    data class Ready(val database: VPath, val snapshotId: String, val manifestPath: VPath): RegistryDbStatus
}

data class RegistryTypeCount(
    val registryType: String,
    val entryCount: Long
)

data class ModContentCount(
    val namespace: String,
    val itemCount: Long,
    val recipeCount: Long,
    val recipeTypeCount: Long,
    val tagCount: Long,
    val registryEntryCount: Long,
    val totalCount: Long
)

data class RegistryItemSummary(
    val id: String,
    val namespace: String,
    val path: String,
    val displayName: String?,
    val maxCount: Int?,
    val maxDamage: Int?,
    val rarity: String?,
    val enchantability: Int?,
    val texturePath: String?,
    val animationJson: String? = null,
    val tags: List<String>
)

data class RegistryItemDetail(
    val id: String,
    val namespace: String,
    val path: String,
    val displayName: String?,
    val maxCount: Int?,
    val maxDamage: Int?,
    val rarity: String?,
    val enchantability: Int?,
    val texturePath: String?,
    val animationJson: String? = null,
    val rawJson: String,
    val tags: List<String>
)

data class RegistryRecipeSummary(
    val id: String,
    val namespace: String,
    val path: String,
    val recipeType: String?,
    val groupName: String?,
    val inputSlots: Int?,
    val outputSlots: Int?,
    val fuelSlots: Int?
)

data class RegistryItemRecipeUsage(
    val producedBy: List<RegistryRecipeSummary>,
    val usedIn: List<RegistryRecipeSummary>
)

data class RegistryItemPreview(
    val id: String,
    val texturePath: String?
)

data class RecipeTypeCatalyst(
    val recipeTypeId: String,
    val displayName: String?,
    val catalystIds: List<String>
)

data class RegistryRecipeDetail(
    val id: String,
    val namespace: String,
    val path: String,
    val recipeType: String?,
    val groupName: String?,
    val inputSlots: Int?,
    val outputSlots: Int?,
    val fuelSlots: Int?,
    val rawJson: String,
    val recipeTypeRawJson: String?,
    val sourcePath: String? = null
)

data class RegistryEntryDetail(
    val registryType: String,
    val id: String,
    val namespace: String,
    val path: String,
    val rawJson: String
)

data class RegistryValuePreview(
    val id: String,
    val typeId: String,
    val displayName: String?,
    val typeDisplayName: String?,
    val texturePath: String?
)

data class BrowseableValueType(
    val id: String,
    val namespace: String,
    val path: String,
    val displayName: String?,
    val iconTexture: String?
)

data class RegistryValueSummary(
    val typeId: String,
    val id: String,
    val namespace: String,
    val path: String,
    val displayName: String?,
    val texturePath: String?,
    val typeDisplayName: String?,
    val tintColor: Long? = null
)

data class RegistryValueDetail(
    val typeId: String,
    val id: String,
    val namespace: String,
    val path: String,
    val displayName: String?,
    val texturePath: String?,
    val typeDisplayName: String?,
    val rawJson: String,
    val tags: List<String>
)

@Serializable
private data class RegistryLatestPointer(
    val path: String,
    @SerialName("snapshotId")
    val snapshotId: String
)

@Serializable
private data class RegistryDumpManifest(
    val complete: Boolean
)

private fun ResultSet.getNullableInt(column: String): Int? {
    val value = getInt(column)
    return if(wasNull()) null else value
}
