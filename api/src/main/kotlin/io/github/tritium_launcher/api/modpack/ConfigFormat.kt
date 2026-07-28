/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.api.modpack

import io.github.tritium_launcher.api.BuiltinRegistries
import io.github.tritium_launcher.api.registry.Registrable

interface ConfigFormat: Registrable {
    override val id: String
    val extensions: List<String>
    fun parse(text: String): ConfigNode
    fun serialize(node: ConfigNode): String

    companion object {
        fun of(ext: String): ConfigFormat? =
            BuiltinRegistries.ConfigFormat.all()
                .firstOrNull { ext in it.extensions }
    }
}
