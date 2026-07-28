/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.core.project

import io.github.tritium_launcher.api.core.project.ProjectBase
import io.github.tritium_launcher.api.io.VPath
import kotlinx.serialization.json.JsonObject

/**
 * Typed project with decoded metadata.
 *
 * @param typeId Project type id registered in the template registry.
 * @param projectDir Root directory of the project.
 * @param name Display name shown in the dashboard.
 * @param icon Icon path (absolute or project-relative).
 * @param rawMeta Raw metadata JSON used for display or fallback.
 * @param typedMeta Strongly typed metadata for the project type.
 */
class Project<T: Any>(
    typeId: String,
    projectDir: VPath,
    name: String,
    icon: String,
    rawMeta: JsonObject,
    val typedMeta: T
): ProjectBase(typeId, projectDir, name, icon, rawMeta)
