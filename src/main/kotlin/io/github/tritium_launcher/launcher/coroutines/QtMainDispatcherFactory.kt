/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.coroutines

import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.MainCoroutineDispatcher
import kotlinx.coroutines.internal.MainDispatcherFactory

/**
 * Factory for creating [QtDispatcher] for [kotlinx.coroutines.Dispatchers.Main].
 */
@InternalCoroutinesApi
class QtMainDispatcherFactory : MainDispatcherFactory {
    override val loadPriority: Int
        get() = 0

    override fun createDispatcher(allFactories: List<MainDispatcherFactory>): MainCoroutineDispatcher {
        return QtDispatcher()
    }
}
