package io.github.tritium_launcher.launcher.core.project.templates

import kotlinx.serialization.json.JsonElement
import java.util.concurrent.ConcurrentHashMap

/**
 * Migration registry for project metadata schemas.
 */
object MigrationRegistry {
    private val steps = ConcurrentHashMap<String, ConcurrentHashMap<Int, (JsonElement) -> JsonElement>>()

    /**
     * Register a migration step for a project type.
     *
     * @param typeId Project type id.
     * @param fromVersion Schema version to migrate from.
     * @param step Migration function to the next version.
     */
    fun registerStep(
        typeId: String,
        fromVersion: Int,
        step: (JsonElement) -> JsonElement
    ) {
        val map = steps.computeIfAbsent(typeId) { ConcurrentHashMap() }
        map[fromVersion] = step
    }

    /**
     * Apply migration steps from [from] (inclusive) to [to] (exclusive).
     */
    fun migrate(typeId: String, from: Int, to: Int, input: JsonElement): JsonElement {
        var v = from
        var cur = input
        val map = steps[typeId] ?: emptyMap()
        while(v < to) {
            val step = map[v] ?: throw IllegalStateException("No migration for $typeId from $v -> ${v + 1}")
            cur = step(cur)
            v++
        }
        return cur
    }
}
