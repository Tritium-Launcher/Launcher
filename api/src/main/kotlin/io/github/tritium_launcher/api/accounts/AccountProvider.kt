/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.api.accounts

import io.github.tritium_launcher.api.registry.Registrable
import io.qt.gui.QPixmap
import io.qt.widgets.QWidget

interface AccountProvider : Registrable {
    override val id: String
    val displayName: String
    val serviceIcon: QPixmap? get() = null
    suspend fun listAccounts(): List<AccountDescriptor>
    suspend fun signIn(parentWindow: QWidget? = null): AccountDescriptor?
    suspend fun signInWithToken(token: String, parentWindow: QWidget? = null): AccountDescriptor? = null
    suspend fun signOutAccount(accountId: String)
    suspend fun switchToAccount(accountId: String): Boolean
    suspend fun getAvatar(accountId: String): QPixmap? = null
    suspend fun getCredentials(accountId: String): Map<String, String>?
    val capabilities: Set<AccountCapability> get() = emptySet()

    val authMethod: AuthMethod get() = AuthMethod.OAUTH
    val tokenLabel: String? get() = null
    val tokenPageUrl: String? get() = null
    val supportsMultipleAccounts: Boolean get() = false
    val sectionColor: String? get() = null
    val infoDescription: String? get() = null

    /**
     * Optional widget shown in a dialog before opening [tokenPageUrl].
     */
    fun createTokenSetupWidget(parent: QWidget?): QWidget? = null
}

enum class AuthMethod {
    OAUTH,
    KEY,
    OAUTH_AND_KEY
}
