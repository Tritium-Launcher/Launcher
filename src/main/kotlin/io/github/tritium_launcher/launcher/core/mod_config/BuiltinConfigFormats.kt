/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.core.mod_config

import io.github.tritium_launcher.launcher.core.mod_config.formats.*
import io.github.tritium_launcher.launcher.core.mod_config.formats.JsonConfigFormat.Variant.*

object BuiltinConfigFormats {
    val All = listOf(
        PropertiesConfigFormat(),
        ForgeCfgConfigFormat(),
        TomlConfigFormat(),
        YamlConfigFormat(),
        JsonConfigFormat(J),
        JsonConfigFormat(JC),
        JsonConfigFormat(J5)
    )
}
