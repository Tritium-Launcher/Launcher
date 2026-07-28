/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.accounts

import io.github.tritium_launcher.api.accounts.*
import io.github.tritium_launcher.api.logger
import io.github.tritium_launcher.api.platform.ClientIdentity
import io.github.tritium_launcher.api.platform.Platform
import io.github.tritium_launcher.launcher.core.HttpClientProvider
import io.github.tritium_launcher.launcher.ui.theme.TIcons
import io.github.tritium_launcher.launcher.ui.theme.icon
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.qt.gui.QPixmap
import io.qt.widgets.QWidget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CurseForgeAccount : AccountProvider {
    private val logger = logger()
    override val id: String = "curseforge_account"
    override val displayName: String = "CurseForge"
    override val serviceIcon: QPixmap get() = TIcons.CURSEFORGE.icon(64)
    override val capabilities: Set<AccountCapability> = setOf(AccountCapability.UPLOAD)
    override val authMethod: AuthMethod = AuthMethod.KEY
    override val tokenLabel: String = "API Key:"
    override val tokenPageUrl: String get() = TOKEN_PAGE
    override val supportsMultipleAccounts: Boolean = true
    override val sectionColor: String = "151515"
    override val infoDescription: String = "Used for uploading and managing ModPacks"

    private companion object {
        const val UPLOAD_API_BASE = "https://minecraft.curseforge.com/api/"
        const val TOKEN_PAGE = "https://www.curseforge.com/account/api-tokens"
        const val STORAGE_SERVICE = "tritium_curseforge"
    }

    private val httpClient = HttpClientProvider.client() {
        install(HttpTimeout) {
            requestTimeoutMillis = 15_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 15_000
        }
        defaultRequest {
            header("User-Agent", ClientIdentity.userAgent)
        }
    }

    override suspend fun listAccounts(): List<AccountDescriptor> = withContext(Dispatchers.IO) {
        SecureStorage.listAccounts(STORAGE_SERVICE).map { id ->
            AccountCache.getCached(STORAGE_SERVICE, id) ?: AccountDescriptor(
                id = id,
                username = id,
                subtitle = id,
                label = "CurseForge API Token"
            )
        }
    }

    override suspend fun signIn(parentWindow: QWidget?): AccountDescriptor? {
        Platform.openBrowser(TOKEN_PAGE)
        return null
    }

    override suspend fun signInWithToken(token: String, parentWindow: QWidget?): AccountDescriptor? {
        return withContext(Dispatchers.IO) {
            val clean = token.trim().removePrefix("X-Api-Token ").removePrefix("x-api-token ")
            if (!validateToken(clean)) {
                logger.warn("CurseForge token validation failed")
                return@withContext null
            }
            val accountId = "cf_${clean.take(8)}"
            SecureStorage.store(STORAGE_SERVICE, accountId, clean)
            val descriptor = AccountDescriptor(
                id = accountId,
                username = accountId,
                subtitle = accountId,
                label = "CurseForge API Token"
            )
            AccountCache.save(STORAGE_SERVICE, accountId, descriptor)
            descriptor
        }
    }

    override suspend fun signOutAccount(accountId: String) {
        SecureStorage.delete(STORAGE_SERVICE, accountId)
        AccountCache.remove(STORAGE_SERVICE, accountId)
    }

    override suspend fun switchToAccount(accountId: String): Boolean {
        return SecureStorage.retrieve(STORAGE_SERVICE, accountId) != null
    }

    override suspend fun getAvatar(accountId: String): QPixmap? = null

    override suspend fun getCredentials(accountId: String): Map<String, String>? {
        val token = SecureStorage.retrieve(STORAGE_SERVICE, accountId) ?: return null
        return mapOf("X-Api-Token" to token)
    }

    private suspend fun validateToken(token: String): Boolean {
        return try {
            val response: HttpResponse = httpClient.get("${UPLOAD_API_BASE}game/versions") {
                header("X-Api-Token", token)
            }
            response.status.isSuccess()
        } catch (t: Throwable) {
            logger.warn("CurseForge token validation request failed", t)
            false
        }
    }
}
