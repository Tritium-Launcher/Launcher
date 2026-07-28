/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.core

import io.ktor.client.*
import io.ktor.client.engine.cio.*

object HttpClientProvider {
    private val engine = CIO.create()

    fun client(block: HttpClientConfig<*>.() -> Unit = {}): HttpClient =
        HttpClient(engine) { block() }
}
