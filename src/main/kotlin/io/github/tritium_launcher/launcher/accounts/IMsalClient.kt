/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.accounts

import com.microsoft.aad.msal4j.IAccount
import java.util.concurrent.CompletableFuture

/**
 * Abstraction over MSAL account listing for testability.
 */
interface IMsalClient {
    /** Returns the MSAL account set. */
    fun accounts(): CompletableFuture<MutableSet<IAccount>>
}
