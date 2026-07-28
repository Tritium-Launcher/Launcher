/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.api.project.template

import io.github.tritium_launcher.api.core.project.ProjectBase
import io.github.tritium_launcher.api.io.VPath
import io.github.tritium_launcher.api.registry.Registrable
import kotlinx.serialization.KSerializer

interface TemplateDescriptor<T: Any> : Registrable {
    override val id: String
    val serializer: KSerializer<T>
    val projectName: String
    val defaultIcon: String
    val currentSchema: Int

    fun createProjectFromMeta(meta: T, schemaVersion: Int, projectDir: VPath): ProjectBase
}
