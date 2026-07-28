/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.api.registry

import kotlin.reflect.KClass

/**
 * Key used to uniquely identify a registry by name and element type.
 */
data class RegistryKey(val name: String, val type: KClass<*>)
