/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.api.registry

/**
 * Generic registry for ID lookup
 */
interface TRegistry<T> {
    fun register(item: T): T?

    fun deregister(id: String): T?

    fun get(id: String): T?

    fun getIgnoreCase(id: String): T?

    fun find(predicate: (T) -> Boolean): T?

    fun list(): List<T>

    fun clear()
}
