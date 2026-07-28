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
import io.github.tritium_launcher.launcher.m
import io.github.tritium_launcher.launcher.toUrl
import io.github.tritium_launcher.launcher.ui.theme.TColors
import io.github.tritium_launcher.launcher.ui.theme.TIcons
import io.github.tritium_launcher.launcher.ui.theme.icon
import io.github.tritium_launcher.launcher.ui.theme.qt.qtStyle
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.frame
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.hBoxLayout
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.label
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.vBoxLayout
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.qt.gui.QPixmap
import io.qt.widgets.QFrame
import io.qt.widgets.QWidget
import kotlinx.coroutines.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.SocketTimeoutException

class ModrinthAccount : AccountProvider {
    private val logger = logger()
    override val id: String = "modrinth_account"
    override val displayName: String = "Modrinth"
    override val serviceIcon: QPixmap get() = TIcons.MODRINTH.icon(64)
    override val capabilities: Set<AccountCapability> = setOf(AccountCapability.UPLOAD, AccountCapability.VIEW_PROJECTS)
    override val authMethod: AuthMethod = AuthMethod.OAUTH_AND_KEY
    override val tokenLabel: String = "PAT:"
    override val tokenPageUrl: String get() = TOKEN_PAGE
    override val supportsMultipleAccounts: Boolean = true
    override val sectionColor: String = "254C34"
    override val infoDescription: String = "Used for uploading and managing ModPacks"

    override fun createTokenSetupWidget(parent: QWidget?): QWidget {
        val container = QFrame(parent).apply {
            frameShape = QFrame.Shape.NoFrame
        }
        val layout = vBoxLayout(container) {
            contentsMargins = 16.m
            setSpacing(12)
        }

        val title = label("Modrinth Personal Access Token Scopes") {
            styleSheet = qtStyle {
                selector(objectName) {
                    fontSize(15)
                    fontWeight(700)
                    color(TColors.Text)
                }
            }.toStyleSheet()
        }
        layout.addWidget(title)

        val desc = label("Create a PAT at modrinth.com/settings/pats and select these permissions:") {
            styleSheet = qtStyle {
                selector(objectName) {
                    fontSize(12)
                    color(TColors.Subtext)
                }
            }.toStyleSheet()
            wordWrap = true
        }
        layout.addWidget(desc)

        data class ScopeInfo(val label: String, val required: Boolean)

        val scopes = listOf(
            ScopeInfo("Read user email", true),
            ScopeInfo("Read user state", true),
            ScopeInfo("Create projects", true),
            ScopeInfo("Read projects", true),
            ScopeInfo("Write projects", true),
            ScopeInfo("Create versions", true),
            ScopeInfo("Read versions", true),
            ScopeInfo("Write versions", true),
            ScopeInfo("Delete versions", false),
        )

        for (scope in scopes) {
            val row = frame {
                frameShape = QFrame.Shape.NoFrame
                val rowLayout = hBoxLayout(this) {
                    setContentsMargins(8, 4, 8, 4)
                    setSpacing(8)
                }

                val dot = label(if (scope.required) "●" else "○") {
                    styleSheet = "color: ${if (scope.required) TColors.Green else TColors.Subtext}; font-size: 10px;"
                    setFixedWidth(16)
                }
                rowLayout.addWidget(dot)

                val label = label(scope.label) {
                    styleSheet = "font-size: 12px; color: ${TColors.Text};"
                }
                rowLayout.addWidget(label)

                if (!scope.required) {
                    val tag = label("optional") {
                        styleSheet = "font-size: 10px; color: ${TColors.Subtext}; padding: 1px 6px; border: 1px solid ${TColors.Surface2}; border-radius: 4px;"
                    }
                    rowLayout.addWidget(tag)
                }

                rowLayout.addStretch()
            }
            layout.addWidget(row)
        }

        layout.addStretch()
        return container
    }

    private companion object {
        // If you are forking, you are not allowed to use this client
        const val CLIENT_ID = "APqt36J5"
        const val CLIENT_SECRET = "ORsVS5OuxRGYl1IjQY70zken5oPo6KP3"
        const val REDIRECT_PORT = 58420
        const val REDIRECT_URI = "http://127.0.0.1:$REDIRECT_PORT/callback"
        const val AUTH_URL = "https://modrinth.com/auth/authorize"
        const val TOKEN_URL = "https://api.modrinth.com/_internal/oauth/token"
        const val API_BASE = "https://api.modrinth.com/v2/"
        const val STORAGE_SERVICE = "tritium_modrinth"
        const val TOKEN_PAGE = "https://modrinth.com/settings/pats"
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val httpClient = HttpClientProvider.client() {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 30_000
        }
        defaultRequest {
            url(API_BASE)
            header("User-Agent", ClientIdentity.userAgent)
        }
    }

    private val oauthClient = HttpClientProvider.client() {
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 30_000
        }
        defaultRequest {
            header("User-Agent", ClientIdentity.userAgent)
        }
    }

    override suspend fun listAccounts(): List<AccountDescriptor> = withContext(Dispatchers.IO) {
        val accounts = SecureStorage.listAccounts(STORAGE_SERVICE)
        accounts.mapNotNull { id ->
            AccountCache.getCached(STORAGE_SERVICE, id) ?: run {
                val token = SecureStorage.retrieve(STORAGE_SERVICE, id) ?: return@mapNotNull null
                val user = fetchUser(token)
                if (user != null) {
                    val descriptor = AccountDescriptor(
                        id = id,
                        username = user.username,
                        subtitle = user.id,
                        avatarUrl = user.avatarUrl,
                        label = user.name ?: user.username
                    )
                    AccountCache.save(STORAGE_SERVICE, id, descriptor)
                    descriptor
                } else {
                    AccountDescriptor(id = id, username = id, subtitle = id)
                }
            }
        }
    }

    override suspend fun signIn(parentWindow: QWidget?): AccountDescriptor? {
        val code = withContext(Dispatchers.IO) { startOAuthServer() } ?: return null
        return withContext(Dispatchers.IO) { exchangeCodeAndStore(code) }
    }

    override suspend fun signInWithToken(token: String, parentWindow: QWidget?): AccountDescriptor? {
        return withContext(Dispatchers.IO) {
            val clean = token.trim().removePrefix("Bearer ").removePrefix("bearer ")
            val user = fetchUser(clean)
            if (user == null) {
                logger.warn("Modrinth PAT validation failed: invalid token")
                return@withContext null
            }
            SecureStorage.store(STORAGE_SERVICE, user.id, clean)
            val descriptor = AccountDescriptor(
                id = user.id,
                username = user.username,
                subtitle = user.id,
                avatarUrl = user.avatarUrl,
                label = user.name ?: user.username
            )
            AccountCache.save(STORAGE_SERVICE, user.id, descriptor)
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

    override suspend fun getAvatar(accountId: String): QPixmap? = withContext(Dispatchers.IO) {
        try {
            val token = SecureStorage.retrieve(STORAGE_SERVICE, accountId) ?: run {
                logger.warn("Modrinth getAvatar: no token for $accountId")
                return@withContext null
            }
            val user = fetchUser(token) ?: run {
                logger.warn("Modrinth getAvatar: fetchUser returned null for $accountId")
                return@withContext null
            }
            val avatarUrl = user.avatarUrl ?: run {
                logger.warn("Modrinth getAvatar: avatarUrl is null for ${user.username}")
                return@withContext null
            }
            logger.info("Modrinth getAvatar: downloading from $avatarUrl")
            val url = avatarUrl.toUrl()
            val conn = url.openConnection()
            conn.setRequestProperty("User-Agent", ClientIdentity.userAgent)
            val bytes = conn.inputStream.readBytes()
            if (bytes.isEmpty()) {
                logger.warn("Modrinth getAvatar: empty response from $avatarUrl")
                return@withContext null
            }
            val pix = QPixmap()
            if (!pix.loadFromData(bytes)) {
                logger.warn("Modrinth getAvatar: QPixmap.loadFromData failed for ${bytes.size} bytes from $avatarUrl")
                return@withContext null
            }
            logger.info("Modrinth getAvatar: loaded ${pix.width()}x${pix.height()}")
            return@withContext pix
        } catch (e: Exception) {
            logger.warn("Failed to fetch Modrinth avatar for $accountId", e)
            null
        }
    }

    override suspend fun getCredentials(accountId: String): Map<String, String>? {
        val token = SecureStorage.retrieve(STORAGE_SERVICE, accountId) ?: return null
        return mapOf("Authorization" to token)
    }

    private fun startOAuthServer(): String? {
        val serverSocket = ServerSocket(REDIRECT_PORT, 1, java.net.InetAddress.getByName("127.0.0.1"))
        serverSocket.soTimeout = 5 * 60 * 1000
        serverSocket.reuseAddress = true
        logger.info("Modrinth OAuth server listening on 127.0.0.1:{}", REDIRECT_PORT)
        try {
            val authUrl = URLBuilder(AUTH_URL).apply {
                parameters.append("client_id", CLIENT_ID)
                parameters.append("redirect_uri", REDIRECT_URI)
                parameters.append("scope", "USER_READ PROJECT_READ VERSION_CREATE")
                parameters.append("response_type", "code")
            }.buildString()
            Platform.openBrowser(authUrl)
            logger.info("Waiting for OAuth callback on 127.0.0.1:{}", REDIRECT_PORT)

            val clientSocket = serverSocket.accept()
            val reader = BufferedReader(InputStreamReader(clientSocket.inputStream))
            val requestLine = reader.readLine() ?: return null
            logger.info("Modrinth OAuth request: {}", requestLine)

            val parts = requestLine.split(" ")
            if (parts.size < 2) return null
            val uri = parts[1]
            val queryParams = uri.substringAfter("?").split("&").associate {
                val kv = it.split("=", limit = 2)
                kv[0] to (kv.getOrNull(1) ?: "")
            }
            val code = queryParams["code"]

            val writer = OutputStreamWriter(clientSocket.outputStream)
            if (code != null) {
                logger.info("Modrinth OAuth code received")
                val body = "<html><body><h1>Signed in!</h1><p>You can close this window.</p></body></html>"
                writer.write("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body")
                writer.flush()
                clientSocket.close()
                return code
            } else {
                val error = queryParams["error"] ?: "unknown"
                logger.warn("Modrinth OAuth error: {}", error)
                val body = "<html><body><h1>Sign-in failed</h1><p>Error: $error</p></body></html>"
                writer.write("HTTP/1.1 400 Bad Request\r\nContent-Type: text/html\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body")
                writer.flush()
                clientSocket.close()
                return null
            }
        } catch (t: SocketTimeoutException) {
            logger.warn("Modrinth OAuth timed out waiting for callback", t)
            return null
        } catch (t: Throwable) {
            logger.warn("Modrinth OAuth failed", t)
            return null
        } finally {
            serverSocket.close()
        }
    }

    private suspend fun exchangeCodeAndStore(code: String): AccountDescriptor? {
        return try {
            val response: HttpResponse = oauthClient.post(TOKEN_URL) {
                header("Authorization", CLIENT_SECRET)
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(FormDataContent(Parameters.build {
                    append("client_id", CLIENT_ID)
                    append("code", code)
                    append("redirect_uri", REDIRECT_URI)
                    append("grant_type", "authorization_code")
                }))
            }
            if (!response.status.isSuccess()) {
                val body = response.bodyAsText()
                logger.warn("Modrinth token exchange failed: HTTP {} body={}", response.status.value, body.take(500))
                return null
            }
            val body = response.bodyAsText()
            val tokenResponse = json.decodeFromString<ModrinthTokenResponse>(body)
            val accessToken = tokenResponse.accessToken
            val user = fetchUser(accessToken) ?: return null
            SecureStorage.store(STORAGE_SERVICE, user.id, accessToken)
            logger.info("Modrinth account signed in: {} ({})", user.username, user.id)
            val descriptor = AccountDescriptor(
                id = user.id,
                username = user.username,
                subtitle = user.id,
                avatarUrl = user.avatarUrl,
                label = user.name ?: user.username
            )
            AccountCache.save(STORAGE_SERVICE, user.id, descriptor)
            descriptor
        } catch (t: Throwable) {
            logger.warn("Modrinth token exchange failed", t)
            null
        }
    }

    private suspend fun fetchUser(token: String): ModrinthUser? {
        return try {
            val response: HttpResponse = httpClient.get("user") {
                header("Authorization", token)
            }
            if (!response.status.isSuccess()) {
                logger.warn("Modrinth user fetch failed: HTTP {}", response.status.value)
                return null
            }
            val userResponse = json.decodeFromString<ModrinthUserResponse>(response.bodyAsText())
            logger.info("Modrinth user fetched: {} ({})", userResponse.username, userResponse.id)
            ModrinthUser(
                id = userResponse.id,
                username = userResponse.username,
                name = userResponse.name,
                avatarUrl = userResponse.avatarUrl
            )
        } catch (t: Throwable) {
            logger.warn("Failed to fetch Modrinth user", t)
            null
        }
    }

    suspend fun fetchConnectedModpackProjects(): List<ModrinthProject> = withContext(Dispatchers.IO) {
        val accounts = SecureStorage.listAccounts(STORAGE_SERVICE)
        val results = mutableListOf<ModrinthProject>()
        for (accountId in accounts) {
            val token = SecureStorage.retrieve(STORAGE_SERVICE, accountId) ?: continue
            try {
                results.addAll(fetchAndEnrichModpackProjects(token, accountId))
            } catch (t: Throwable) {
                logger.warn("Failed to fetch Modrinth projects for account $accountId", t)
            }
        }
        results
    }

    suspend fun fetchModpackProjectsForAccount(accountId: String): List<ModrinthProject> = withContext(Dispatchers.IO) {
        val token = SecureStorage.retrieve(STORAGE_SERVICE, accountId) ?: return@withContext emptyList()
        try {
            fetchAndEnrichModpackProjects(token, accountId)
        } catch (t: Throwable) {
            logger.warn("Failed to fetch Modrinth projects for account $accountId", t)
            emptyList()
        }
    }

    private suspend fun fetchAndEnrichModpackProjects(token: String, accountId: String): List<ModrinthProject> {
        val projects = fetchProjects(token, accountId).filter { it.projectType == "modpack" }
        return coroutineScope {
            projects.map { project ->
                async {
                    val info = fetchLatestVersionInfo(token, project.id)
                    if (info != null) project.copy(
                        latestGameVersion = info.first.firstOrNull(),
                        latestLoaders = info.second,
                        latestVersionName = info.third
                    ) else project
                }
            }.awaitAll()
        }
    }

    private suspend fun fetchLatestVersionInfo(token: String, projectId: String): Triple<List<String>, List<String>, String>? {
        return try {
            val response: HttpResponse = httpClient.get("project/$projectId/version") {
                parameter("featured", "true")
                parameter("limit", "1")
                header("Authorization", token)
            }
            if (!response.status.isSuccess()) return null
            val versions = json.decodeFromString<List<ModrinthVersionResponse>>(response.bodyAsText())
            versions.firstOrNull()?.let { Triple(it.gameVersions, it.loaders, it.name) }
        } catch (t: Throwable) {
            logger.warn("Failed to fetch latest version info for $projectId", t)
            null
        }
    }

    private suspend fun fetchProjects(token: String, userId: String): List<ModrinthProject> {
        return try {
            val response: HttpResponse = httpClient.get("user/$userId/projects") {
                header("Authorization", token)
            }
            if (!response.status.isSuccess()) {
                logger.warn("Modrinth projects fetch failed: HTTP {}", response.status.value)
                return emptyList()
            }
            val projects = json.decodeFromString<List<ModrinthProjectResponse>>(response.bodyAsText())
            projects.map { p ->
                ModrinthProject(
                    id = p.id,
                    slug = p.slug,
                    title = p.title,
                    description = p.description,
                    projectType = p.projectType,
                    iconUrl = p.iconUrl,
                    versions = p.versions
                )
            }
        } catch (t: Throwable) {
            logger.warn("Failed to fetch Modrinth projects", t)
            emptyList()
        }
    }

    @Serializable
    private data class ModrinthProjectResponse(
        val id: String,
        val slug: String? = null,
        val title: String,
        val description: String? = null,
        @SerialName("project_type") val projectType: String,
        @SerialName("icon_url") val iconUrl: String? = null,
        val versions: List<String> = emptyList()
    )

    @Serializable
    private data class ModrinthVersionResponse(
        val id: String,
        val name: String = "",
        @SerialName("game_versions") val gameVersions: List<String> = emptyList(),
        val loaders: List<String> = emptyList()
    )

    @Serializable
    private data class ModrinthTokenResponse(
        @SerialName("access_token") val accessToken: String,
        @SerialName("token_type") val tokenType: String = "Bearer",
        @SerialName("expires_in") val expiresIn: Long = 0,
        val scope: String = ""
    )

    @Serializable
    private data class ModrinthUserResponse(
        val id: String,
        val username: String,
        val name: String? = null,
        @SerialName("avatar_url") val avatarUrl: String? = null
    )

    private data class ModrinthUser(
        val id: String,
        val username: String,
        val name: String?,
        val avatarUrl: String?
    )
}

data class ModrinthProject(
    val id: String,
    val slug: String?,
    val title: String,
    val description: String?,
    val projectType: String,
    val iconUrl: String?,
    val versions: List<String>,
    val latestGameVersion: String? = null,
    val latestLoaders: List<String> = emptyList(),
    val latestVersionName: String = ""
)
