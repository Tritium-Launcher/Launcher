/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.api.modpack

sealed class ConfigNode

class ConfigObj(val entries: LinkedHashMap<String, ConfigNode> = linkedMapOf()): ConfigNode()
class ConfigArray(val items: MutableList<ConfigNode> = mutableListOf()): ConfigNode()
class ConfigString(val value: String): ConfigNode()
class ConfigInt(val value: Int): ConfigNode()
class ConfigDouble(val value: Double): ConfigNode()
class ConfigBool(val value: Boolean): ConfigNode()
class ConfigComment(val text: String, val inline: Boolean = false): ConfigNode()
class ConfigNull: ConfigNode()
