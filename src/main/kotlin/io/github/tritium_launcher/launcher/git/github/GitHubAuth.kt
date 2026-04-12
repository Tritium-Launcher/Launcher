package io.github.tritium_launcher.launcher.git.github

import io.github.tritium_launcher.launcher.asAlignment
import io.github.tritium_launcher.launcher.coroutines.UIDispatcher
import io.github.tritium_launcher.launcher.git.github.GHDeviceFlowAuth.Companion.showDeviceCodeUi
import io.github.tritium_launcher.launcher.logger
import io.github.tritium_launcher.launcher.onClicked
import io.github.tritium_launcher.launcher.platform.ClientIdentity
import io.github.tritium_launcher.launcher.platform.Platform.Companion.openBrowser
import io.github.tritium_launcher.launcher.ui.helpers.runOnGuiThread
import io.github.tritium_launcher.launcher.ui.widgets.BrowseLabel
import io.github.tritium_launcher.launcher.ui.widgets.TPushButton
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.hBoxLayout
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.label
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.vBoxLayout
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.qt.core.QTimer
import io.qt.core.Qt
import io.qt.widgets.QApplication
import io.qt.widgets.QDialog
import io.qt.widgets.QWidget
import kotlinx.coroutines.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.prefs.Preferences
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private const val CLIENT_ID     = "Ov23liYVqUaH4MPQ0mMH"
private val OAUTH_SCOPE         = listOf("repo", "read:org", "gist", "read:user")

//TODO: GH Auth isn't finished or being used yet, needs encryption.
private const val PREF_NODE = "io.github.tritium_launcher.launcher.gh"
private const val TOKEN_KEY = "github_access_token"

object GitHubAuth {
    //TODO: Don't use prefs
    private val prefs: Preferences = Preferences.userRoot().node(PREF_NODE)

    @Volatile
    private var cachedToken: String? = prefs.get(TOKEN_KEY, null)
    @Volatile
    private var cachedProfile: GitHubProfile? = null

    private val json = Json { ignoreUnknownKeys = true }
    private val client: HttpClient = HttpClient(CIO) {
        install(ContentNegotiation) { json(json) }
        defaultRequest {
            header("User-Agent", ClientIdentity.userAgent)
            header("X-Client-Info", ClientIdentity.clientInfoHeader)
        }
    }

    private val logger = logger()

    fun isSignedIn(): Boolean = !cachedToken.isNullOrBlank()

    @OptIn(ExperimentalCoroutinesApi::class)
    internal suspend fun signIn(parentWindow: QWidget? = null, timeout: Duration = 300.seconds): Boolean = withContext(Dispatchers.IO) {
        val deviceAuth = GHDeviceFlowAuth()
        val uiPairDeferred = CompletableDeferred<Pair<(String, String, Int) -> Unit, () -> Unit>>()

        runOnGuiThread {
            val (updateFn, closeFn) = showDeviceCodeUi(parentWindow) {
                if (!uiPairDeferred.isCompleted) {
                    uiPairDeferred.completeExceptionally(CancellationException("User cancelled"))
                }
            }
            uiPairDeferred.complete(Pair(updateFn, closeFn))
        }

        try {
            val uiPair = withTimeout(5000L) { uiPairDeferred.await() }
            val (updateFn, closeFn) = uiPair

            val result = try {
                deviceAuth.startDeviceFlowAndWait(showUI = { code, url, expiresIn ->
                    runOnGuiThread { updateFn(code, url, expiresIn) }
                }, timeout)
            } catch (e: CancellationException) {
                logger.info("Device flow cancelled by user")
                runOnGuiThread { closeFn() }
                throw e
            }

            val token = result.accessToken
            if (token.isNotBlank()) {
                onTokenObtained(token)

                cachedProfile = try {
                    fetchProfileInternal(token)
                } catch (t: Throwable) {
                    logger.warn("Failed to fetch GitHub profile after sign-in", t)
                    null
                }
                runOnGuiThread { closeFn() }
                return@withContext true
            }

            runOnGuiThread { closeFn() }
            return@withContext false
        } catch (_: CancellationException) {
            logger.info("GitHub sign-in cancelled")
            return@withContext false
        } catch (t: Throwable) {
            logger.error("GitHub device flow sign-in failed", t)
            uiPairDeferred.getCompleted().second.invoke()
            return@withContext false
        }
    }

    internal suspend fun signOut() = withContext(Dispatchers.IO) {
        try {
            prefs.remove(TOKEN_KEY)
            logger.info("GitHub sign out successful")
        } catch (t: Throwable) {
            logger.warn("Could not remove token key from preference node", t)
        }
        cachedToken = null
        cachedProfile = null
    }

    private suspend fun fetchProfileInternal(token: String): GitHubProfile? = withContext(Dispatchers.IO) {
            val resp: HttpResponse = client.get("https://api.github.com/user") {
                header(HttpHeaders.Authorization, "Bearer $token")
                header(HttpHeaders.Accept, "application/vnd.github+json")
                header("User-Agent", "Tritium")
            }

            if(resp.status.value in 200..299) {
                val bodyText: String = resp.body()
                val dto = json.decodeFromString(GitHubUserDto.serializer(), bodyText)
                return@withContext GitHubProfile(
                    dto.id.toString(),
                    dto.login,
                    dto.name,
                    dto.avatar_url
                )
            } else {
                if(resp.status.value == 401) {
                    logger.warn("Could not fetch GitHub profile due to authorization error")
                    prefs.remove(TOKEN_KEY)
                    cachedToken = null
                    cachedProfile = null
                }
                return@withContext null
            }
        }

    private fun onTokenObtained(token: String) {
        try {
            prefs.put(TOKEN_KEY, token)
            cachedToken = token
        } catch (t: Throwable) {
            logger.warn("Failed to persist GitHub token to prefs", t)
            cachedToken = token
        }
    }

    suspend fun getProfile(): GitHubProfile? = withContext(Dispatchers.IO) {
        val token = cachedToken ?: return@withContext null
        cachedProfile ?: run {
            val p = try { fetchProfileInternal(token) } catch (t: Throwable) {
                logger.error("Failed to fetch GitHub Profile", t)
                null
            }
            cachedProfile = p
            p
        }
    }

    suspend fun refreshProfile(): GitHubProfile? = withContext(Dispatchers.IO) {
        val token = cachedToken ?: return@withContext null
        val p = try { fetchProfileInternal(token) } catch (t: Throwable) {
            logger.info("Failed to fetch GitHub Profile on refresh", t)
            null
        }
        cachedProfile = p
        p
    }

    @Serializable
    private data class GitHubUserDto(
        val login: String,
        val id: Long,
        val name: String? = null,
        val avatar_url: String? = null
    )
}

internal class GHDeviceFlowAuth(
    private val clientId: String = CLIENT_ID,
    private val scopes: List<String> = OAUTH_SCOPE
) {

    private val client: HttpClient = HttpClient(CIO) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }

    /**
     * Start device flow and suspend until success, failure, timeout or cancellation
     *
     * @param showUI Called once device_code is obtained. The lambda should show the user_code and verificationUri to
     * the user and open the browser. Signature: (userCode, verificationUri, expiresInSeconds) -> Unit
     * @param timeout Overall timeout (300 seconds default)
     */
    suspend fun startDeviceFlowAndWait(
        showUI: (userCode: String, verificationUri: String, expiresInSeconds: Int) -> Unit,
        timeout: Duration = 300.seconds
    ): DeviceAuthResult = coroutineScope {
        require(clientId.isNotBlank())

        val scopeStr = scopes.joinToString(" ")

        val deviceResp: DeviceCodeResponse = client.post("https://github.com/login/device/code") {
            header("Accept", "application/json")
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(FormDataContent(Parameters.build {
                append("client_id", clientId)
                append("scope", scopeStr)
            }))
        }.body()

        withContext(UIDispatcher) { showUI(deviceResp.userCode, deviceResp.verificationUri, deviceResp.expiresIn) }

        openBrowser(deviceResp.verificationUri) //TODO: Do something if this doesn't work

        val deviceCode = deviceResp.deviceCode
        var intervalSec = deviceResp.interval ?: 5
        val effectiveTimeout = timeout.coerceAtMost(deviceResp.expiresIn.seconds)

        withTimeout(effectiveTimeout) {
            var result: DeviceAuthResult? = null

            while(result == null) {
                ensureActive()

                val tokenResp: GHTokenResponse = client.post("https://github.com/login/oauth/access_token") {
                    header("Accept", "application/json")
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody(FormDataContent(Parameters.build {
                        append("client_id", clientId)
                        append("device_code", deviceCode)
                        append("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                    }))
                }.body<GHTokenResponse>()

                if(!tokenResp.accessToken.isNullOrBlank()) {
                    result = DeviceAuthResult(tokenResp.accessToken, tokenResp.tokenType, tokenResp.scope)
                } else {
                    when(tokenResp.error) {
                        "authorization_pending" -> Unit
                        "slow_down" -> intervalSec += 5
                        "access_denied" -> throw IllegalStateException("User denied access")
                        "expired_token" -> throw IllegalStateException("Device code expired")
                        null -> Unit
                        else -> throw IllegalStateException("OAuth Error: ${tokenResp.error}: ${tokenResp.errorDescription}")
                    }

                    delay(intervalSec * 1000L)
                }
            }

            result
        }
    }

    companion object {
        fun showDeviceCodeUi(
            parent: QWidget?,
            onCancel: () -> Unit
        ): Pair<(String, String, Int)-> Unit, () -> Unit> {
            val dialog = QDialog(parent).apply {
                windowTitle = "GitHub Sign-in"
                minimumWidth = 480
            }

            val layout = vBoxLayout(dialog)
            val info = label("Open the URL below and enter the code to sign in to GitHub:")
            info.wordWrap = true
            layout.addWidget(info)

            val urlLabel = BrowseLabel("<i>Waiting for verification URL...</i>")
            layout.addWidget(urlLabel)

            val codeLabel = label("<b style='font-size:18px;'>...</b>").apply {
                alignment = Qt.AlignmentFlag.AlignCenter.asAlignment()
            }
            layout.addWidget(codeLabel)

            val hintLabel = label("Waiting for code...")
            layout.addWidget(hintLabel)

            val btnRow = QWidget()
            val btnLayout = hBoxLayout(btnRow)
            val openBtn = TPushButton { text = "Open in browser" }
            val cancelBtn = TPushButton { text = "Cancel" }
            btnLayout.addStretch(1)
            btnLayout.addWidget(openBtn)
            btnLayout.addWidget(cancelBtn)
            layout.addWidget(btnRow)

            openBtn.onClicked {
                val text = urlLabel.text
                val href = Regex("href=\"([^\"]+)\"").find(text)?.groups?.get(1)?.value ?: text
                openBrowser(href)
            }

            cancelBtn.onClicked {
                try { onCancel() } catch (_: Throwable) {}
                dialog.reject()
            }

            dialog.show()

            val updateFn: (String, String, Int) -> Unit = { userCode, verificationUri, expiresIn ->
                QTimer.singleShot(0) {
                    codeLabel.text = "<b style='font-size:18px;'>$userCode</b>"
                    urlLabel.text = "<a href=\"$verificationUri\">$verificationUri</a>"
                    hintLabel.text = "This code will expire in $expiresIn seconds."

                    val clipboard = QApplication.clipboard()
                    clipboard?.setText(userCode.replace("-", ""))
                }
            }
            val closeFn: () -> Unit = {
                QTimer.singleShot(0) {
                    try {
                        if (!dialog.isHidden) dialog.accept()
                    } catch (_: Throwable) {
                    }
                }
            }

            return Pair(updateFn, closeFn)
        }
    }
}

@Serializable
data class GHTokenResponse(
    @SerialName("access_token")      val accessToken: String? = null,
    @SerialName("token_type")        val tokenType: String? = null,
    @SerialName("scope")             val scope: String? = null,
    @SerialName("error")             val error: String? = null,
    @SerialName("error_description") val errorDescription: String? = null
)

@Serializable
private data class DeviceCodeResponse(
    @SerialName("device_code") val deviceCode: String,
    @SerialName("user_code") val userCode: String,
    @SerialName("verification_uri") val verificationUri: String,
    @SerialName("expires_in") val expiresIn: Int,
    @SerialName("interval") val interval: Int? = null
)

data class DeviceAuthResult(val accessToken: String, val tokenType: String?, val scope: String?)

