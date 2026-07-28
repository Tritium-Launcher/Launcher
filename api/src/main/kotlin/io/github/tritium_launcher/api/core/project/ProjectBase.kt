/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.api.core.project

import io.github.tritium_launcher.api.io.VPath
import io.github.tritium_launcher.api.io.VPath.Companion.fromHome
import kotlinx.serialization.json.JsonObject
import java.time.Instant

/**
 * Base representation of a project on disk.
 */
open class ProjectBase(
    val typeId: String,
    val projectDir: VPath,
    val name: String,
    val icon: String,
    val rawMeta: JsonObject,
) {
    var lastAccessed: Instant? = null

    val path: VPath
        get() = projectDir.toAbsolute()

    fun fromProject(path: String): VPath = projectDir.resolve(path)

    fun fromProject(path: VPath): VPath = projectDir.resolve(path)

    /**
     * Resolve the icon path, expanding a leading "~" to the user home directory.
     */
    fun getIconPath(): String {
        if(icon.startsWith("~")) {
            val removed = icon.removePrefix("~")
            return fromHome(removed).normalize().toString()
        }

        val v = VPath.get(icon)
        return if(v.isAbsolute) {
            v.toAbsolute().toString()
        } else {
            projectDir.resolve(icon).toAbsolute().toString()
        }
    }
}
