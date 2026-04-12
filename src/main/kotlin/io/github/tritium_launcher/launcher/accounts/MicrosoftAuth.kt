package io.github.tritium_launcher.launcher.accounts

import com.microsoft.aad.msal4j.DeviceCodeFlowParameters
import com.microsoft.aad.msal4j.IAccount
import com.microsoft.aad.msal4j.InteractiveRequestParameters
import com.microsoft.aad.msal4j.SilentParameters
import io.github.tritium_launcher.launcher.*
import io.github.tritium_launcher.launcher.io.IODispatchers
import io.github.tritium_launcher.launcher.io.VPath
import io.github.tritium_launcher.launcher.io.linkOrCopyFromCache
import io.github.tritium_launcher.launcher.platform.ClientIdentity
import io.github.tritium_launcher.launcher.platform.Platform
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.network.sockets.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ThreadLocalRandom
import java.util.jar.JarFile
import java.util.zip.ZipInputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

val authLogger = logger(Auth::class)
internal object Auth // Useful for tracking log calls in IJ

/**
 * Handles Microsoft and Xbox Live authentication for Minecraft.
 * Implements OAuth2 flow for Microsoft accounts and manages token lifecycle.
 *
 * TODO: Set up DeviceCode authentication alongside OAuth.
 */
object MicrosoftAuth {
    private const val LIBS_CONCURRENCY_MIN = 8
    private const val LIBS_CONCURRENCY_MAX = 24
    private const val ASSET_CONCURRENCY_MIN = 16
    private const val ASSET_CONCURRENCY_MAX = 64
    private const val CACHE_MAINTENANCE_INTERVAL_MS = 12L * 60L * 60L * 1000L
    private const val CACHE_SCRUB_ASSET_SAMPLE_SIZE = 160
    private const val CACHE_SCRUB_LIBRARY_SAMPLE_SIZE = 64
    private const val CACHE_GC_MAX_DELETE_PER_RUN = 200_000

    private val json = Json { ignoreUnknownKeys = true }
    private val sha1FileNameRegex = Regex("^[0-9a-f]{40}$")
    private val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(json)
        }
        install(HttpTimeout) {
            this.connectTimeoutMillis = 10000L
            this.requestTimeoutMillis = 30000L
            this.socketTimeoutMillis = 30000L
        }

        install(HttpRequestRetry) {
            maxRetries = 3
            retryOnExceptionOrServerErrors(maxRetries = 3)
            retryIf { _, response ->
                response.status == HttpStatusCode.TooManyRequests ||
                    response.status == HttpStatusCode.RequestTimeout
            }
            delayMillis { retry ->
                (500L * (1L shl (retry - 1))).coerceAtMost(5000L)
            }
        }

        defaultRequest {
            header("User-Agent", ClientIdentity.userAgent)
            header("X-Client-Info", ClientIdentity.clientInfoHeader)
        }
    }

    private var account: IAccount? = null
    @Volatile
    internal var currentAccountHomeId: String? = null

    private const val XBL_AUTH_URL = "https://user.auth.xboxlive.com/user/authenticate"
    private const val XSTS_AUTH_URL = "https://xsts.auth.xboxlive.com/xsts/authorize"
    private val authScopes = setOf("XboxLive.signin", "offline_access")

    private val AppAuthScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Checks if a user is currently signed in.
     * @return true if there is an active account session
     */
    suspend fun isSignedIn(): Boolean = MSAL.app.accounts.await().isNotEmpty()

    private suspend fun <T> CompletableFuture<T>.await(): T =
        suspendCancellableCoroutine { cont ->
            this.whenComplete { res, err ->
                if (err != null) cont.resumeWithException(err) else cont.resume(res)
            }
        }

    /**
     * Initiates a new sign-in flow with a retry mechanism.
     * @param onSignedIn Callback function called with the Minecraft profile after successful sign-in
     * @throws AuthenticationException if sign-in fails after all retry attempts
     */
    suspend fun newSignIn(): ProfileMngr.MCProfile? {
        try {
            authLogger.info("Starting Microsoft sign-in")
            val parameters = InteractiveRequestParameters.builder("http://localhost".toURI())
                .scopes(authScopes)
                .systemBrowserOptions(MSAL.systemBrowserOptions())
                .build()

            val result = MSAL.app.acquireToken(parameters).await()
            account = result.account()
            currentAccountHomeId = account?.homeAccountId()
            authLogger.info("MS token acquired; expires at ${result.expiresOnDate()}")

            val (xblToken, hash) = authWithLive(result.accessToken())
            val xstsToken = authWithXSTS(xblToken)
            val mcToken = getMCToken(xstsToken, hash)

            authLogger.info("Microsoft sign-in successful. MC token obtained.")

            ProfileMngr.Cache.init(mcToken)
            return ProfileMngr.Cache.get()
        } catch (e: Exception) {
            authLogger.error("Microsoft interactive sign-in failed", e)
            throw AuthenticationException("Microsoft interactive sign-in failed", e)
        }
    }

    /**
     * Initiates device-code sign-in and returns the Minecraft profile on success.
     */
    suspend fun deviceCodeSignIn(deviceCodeConsumer: (String) -> Unit): ProfileMngr.MCProfile? {
        val deviceParams = DeviceCodeFlowParameters.builder(
            authScopes
        ) { deviceCode -> deviceCodeConsumer(deviceCode.message()) }
            .build()

        val result = try {
            MSAL.app.acquireToken(deviceParams).await()
        } catch (e: Exception) {
            authLogger.error("Microsoft Device-code sign-in failed")
            throw AuthenticationException("Microsoft Device-code sign-in failed", e)
        }

        account = result.account()
        currentAccountHomeId = account?.homeAccountId()
        val token = result.accessToken()
        authLogger.info("Microsoft Device-code token acquired; expires at ${result.expiresOnDate()}")

        val (xblToken, hash) = authWithLive(token)
        val xstsToken = authWithXSTS(xblToken)
        val mcToken = getMCToken(xstsToken, hash)

        ProfileMngr.Cache.init(mcToken)
        return ProfileMngr.Cache.get()
    }

    /**
     * Ensures a valid access token is available.
     * @return the valid access token
     * @throws TokenException if token refresh fails
     */
    suspend fun ensureValidAccessToken(): String {
        val homeId = currentAccountHomeId ?: throw IllegalStateException("No active account selected.")
        val account = MSAL.findAccount(homeAccountId = homeId)
            ?: throw IllegalStateException("Account $homeId not found in MSAL cache.")

        return try {
            val silentParams = SilentParameters.builder(authScopes, account).build()
            val result = MSAL.app.acquireTokenSilently(silentParams).await()
            authLogger.info("Token refreshed silently; expires {}", result.expiresOnDate())
            result.accessToken()
        } catch (e: Exception) {
            authLogger.error("Silent token refresh failed", e)
            throw TokenException("Silent token refresh failed", e)
        }
    }

    /**
     * Fetches a Minecraft profile for the provided Microsoft account id.
     */
    suspend fun getMcProfileForAccount(homeAccountId: String): ProfileMngr.MCProfile? = withContext(Dispatchers.IO) {
        val account = MSAL.findAccount(homeAccountId = homeAccountId)
            ?: return@withContext null

        try {
            val silentParams = SilentParameters.builder(authScopes, account).build()
            val result = MSAL.app.acquireTokenSilently(silentParams).await()
            val msToken = result.accessToken()
            val (xblToken, hash) = authWithLive(msToken)
            val xstsToken = authWithXSTS(xblToken)
            val mcToken = getMCToken(xstsToken, hash)
            ProfileMngr.Cache.initForAccount(homeAccountId, mcToken)
            return@withContext ProfileMngr.Cache.getForAccount(homeAccountId)
        } catch (e: Exception) {
            authLogger.warn("Could not get MC profile for account", e)
            return@withContext null
        }
    }

    /**
     * Switches the active account to the provided home id.
     */
    suspend fun switchToAccount(homeAccountId: String): Boolean {
        val account = MSAL.findAccount(homeAccountId = homeAccountId) ?: return false
        try {
            val silentParams = SilentParameters.builder(authScopes, account).build()
            val result = MSAL.app.acquireTokenSilently(silentParams).await()
            currentAccountHomeId = homeAccountId
            authLogger.info("Switched active Microsoft account")
            return true
        } catch (e: Exception) {
            authLogger.warn("Failed to switch account silently", e)
            currentAccountHomeId = homeAccountId
            return false
        }
    }

    /**
     * Gets a Minecraft token from a given Microsoft token.
     * @param msToken the Microsoft token
     * @return the Minecraft token
     * @throws TokenException if token refresh fails
     */
    suspend fun getMCToken(msToken: String): String {
        val (xblToken, hash) = authWithLive(msToken)
        val xstsToken = authWithXSTS(xblToken)
        return getMCToken(xstsToken, hash)
    }

    /**
     * Attempt to refresh the active account's Minecraft access token silently.
     * Returns null if no active account is available or refresh fails.
     */
    suspend fun getActiveMcTokenOrNull(): String? {
        val homeId = currentAccountHomeId ?: return null
        val account = MSAL.findAccount(homeAccountId = homeId) ?: return null
        return try {
            val silentParams = SilentParameters.builder(authScopes, account).build()
            val result = MSAL.app.acquireTokenSilently(silentParams).await()
            val msToken = result.accessToken()
            val mcToken = getMCToken(msToken)
            ProfileMngr.Cache.initForAccount(homeId, mcToken)
            mcToken
        } catch (e: Exception) {
            authLogger.warn("Failed to refresh MC token silently", e)
            null
        }
    }

    /**
     * Authenticates with Xbox Live using a Microsoft token.
     * @param token the Microsoft token
     * @return the Xbox Live token and user hash
     * @throws AuthenticationException if authentication fails
     */
    private suspend fun authWithLive(token: String): Pair<String, String> {
        val responseBody = XblAuthRequest(
            Properties = Properties(
                AuthMethod = "RPS",
                SiteName = "user.auth.xboxlive.com",
                RpsTicket = "d=$token"
            ),
            RelyingParty = "http://auth.xboxlive.com",
            TokenType = "JWT"
        )

        return try {
            val response: HttpResponse = httpClient.post(XBL_AUTH_URL) {
                contentType(ContentType.Application.Json)
                setBody(responseBody)
            }

            val body = response.bodyAsText()

            if(response.status != HttpStatusCode.OK) {
                authLogger.error("XBL authentication failed with HTTP status {}", response.status.value)
                throw AuthenticationException("XBL authentication failed with HTTP status ${response.status.value}")
            }

            debug("XBL auth response received (status=${response.status.value}, size=${body.length})", debugLogging)
            val xblResponse: XblTokenResponse = try {
                json.decodeFromString(XblTokenResponse.serializer(), body)
            } catch (e: Exception) {
                authLogger.error("Failed to parse XBL auth response", e)
                throw AuthenticationException("XBL auth response parsing failed", e)
            }

            val xblToken = xblResponse.token
            val hash = xblResponse.displayClaims.xui.first().userHash

            Pair(xblToken, hash)
        } catch (e: Exception) {
            authLogger.error("Error in authWithLive", e)
            throw AuthenticationException("XBL authentication failed", e)
        }
    }

    /**
     * Authenticates with XSTS using an Xbox Live token.
     * @param token the Xbox Live token
     * @return the XSTS token
     * @throws AuthenticationException if authentication fails
     */
    private suspend fun authWithXSTS(token: String): String {
        val responseBody = XstsAuthRequest(
            Properties = XstsProperties(
                SandboxId = "RETAIL",
                UserTokens = listOf(token)
            ),
            RelyingParty = "rp://api.minecraftservices.com/",
            TokenType = "JWT"
        )

        return try {
            val response: HttpResponse = httpClient.post(XSTS_AUTH_URL) {
                contentType(ContentType.Application.Json)
                setBody(responseBody)
            }

            val body = response.bodyAsText()

            if(response.status != HttpStatusCode.OK) {
                authLogger.error("XSTS authentication failed with HTTP status {}", response.status.value)
                throw AuthenticationException("XSTS authentication failed with HTTP status ${response.status.value}")
            }

            debug("XSTS auth response received (status=${response.status.value}, size=${body.length})", debugLogging)
            val xstsResponse: XstsTokenResponse = try {
                json.decodeFromString(XstsTokenResponse.serializer(), body)
            } catch (e: Exception) {
                authLogger.error("Failed to parse XSTS auth response", e)
                throw AuthenticationException("XSTS auth response parsing failed", e)
            }

            xstsResponse.token
        } catch (e: Exception) {
            authLogger.error("Error in authWithXSTS", e)
            throw AuthenticationException("XSTS authentication failed", e)
        }
    }

    /**
     * Gets a Minecraft token from an XSTS token and user hashes.
     * @param token the XSTS token
     * @param hash the user hash
     * @return the Minecraft token
     * @throws TokenException if token refresh fails
     */
    private suspend fun getMCToken(token: String, hash: String): String {
        val identity = "XBL3.0 x=$hash;$token"
        val body = mapOf("identityToken" to identity)

        return try {
            val response: HttpResponse =
                httpClient.post("https://api.minecraftservices.com/authentication/login_with_xbox") {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }

            val body = response.bodyAsText()

            if (response.status != HttpStatusCode.OK) {
                authLogger.error("MC authentication failed with HTTP status {}", response.status.value)
                throw TokenException("MC authentication failed with HTTP status ${response.status.value}")
            }

            debug("MC auth response received (status=${response.status.value}, size=${body.length})", debugLogging)
            val authResponse: MCAuthResponse = try {
                json.decodeFromString(MCAuthResponse.serializer(), body)
            } catch (e: Exception) {
                authLogger.error("Failed to parse MC auth response", e)
                throw TokenException("MC auth response parsing failed", e)
            }

            authResponse.accessToken
        } catch (e: ConnectTimeoutException) {
            throw TokenException("Could not get MC Token due to time-out from Microsoft", e)
        } catch (e: Exception) {
            throw TokenException("MC token refresh failed", e)
        }
    }

    internal fun attemptAutoSignIn() {
        AppAuthScope.launch {
            var tryCount = 0
            while(isActive && tryCount < 5) {
                try {
                    val accounts = try {
                        MSAL.app.accounts.await().toList()
                    } catch (t: Throwable) {
                        authLogger.warn("Failed to read MSAL accounts from cache", t)
                        emptyList()
                    }

                    if (accounts.isEmpty()) {
                        authLogger.info("No Microsoft accounts available for auto sign-in")
                        break
                    }

                    var restored = false
                    for (acct in accounts) {
                        if (!isActive) break

                        val homeId = acct.homeAccountId()
                        authLogger.info("Attempting silent token acquire for cached account")

                        try {
                            val silentParams = SilentParameters.builder(authScopes, acct).build()
                            val result = try { MSAL.app.acquireTokenSilently(silentParams).await() } catch (e: Exception) {
                                authLogger.info("Silent acquire failed for cached account")
                                null
                            }

                            if (result != null) {
                                account = result.account()
                                val resolvedHomeId = result.account()?.homeAccountId() ?: homeId
                                if (!resolvedHomeId.isNullOrBlank()) {
                                    currentAccountHomeId = resolvedHomeId
                                }

                                val msToken = result.accessToken()
                                try {
                                    if (!resolvedHomeId.isNullOrBlank()) {
                                        val mcToken = getMCToken(msToken)
                                        ProfileMngr.Cache.initForAccount(resolvedHomeId, mcToken)
                                    } else {
                                        val mcToken = getMCToken(msToken)
                                        ProfileMngr.Cache.init(mcToken)
                                    }
                                    authLogger.info("Auto sign-in succeeded for cached account")
                                } catch (pErr: Throwable) {
                                    authLogger.warn("Auto sign-in succeeded but profile init failed", pErr)
                                }

                                restored = true
                                break
                            }
                        } catch (t: Throwable) {
                            authLogger.warn("Error attempting silent account restore", t)
                        }
                    }

                    if (!restored) {
                        authLogger.info("Auto sign-in: no account could be restored silently. Interactive sign-in will be required.")
                    }

                    break
                } catch (e: Exception) {
                    tryCount++
                    mainLogger.error("Auto sign-in attempt #$tryCount failed", e)
                    val delayMs = (1000L * (1 shl (tryCount - 1))).coerceAtMost(60000L)
                    delay(delayMs)
                }
            }
        }
    }

    /**
     * Lists accounts from the MSAL cache.
     */
    suspend fun listAccounts(): List<MSAccountSummary> = withContext(Dispatchers.IO) {
        try {
            val accs = MSAL.app.accounts.await()
            accs.map { a ->
                MSAccountSummary(
                    a.homeAccountId(),
                    a.username()
                )
            }
        } catch (e: Exception) {
            authLogger.error("Failed to list accounts", e)
            emptyList()
        }
    }

    /**
     * Signs out a specific account by home id.
     */
    suspend fun signOutAccount(homeAccountId: String) {
        try {
            val account = MSAL.findAccount(homeAccountId = homeAccountId) ?: return
            MSAL.app.removeAccount(account).await()
        } catch (e: Exception) {
            authLogger.warn("signOutAccount error", e)
        } finally {
            if (currentAccountHomeId == homeAccountId) currentAccountHomeId = null
        }
    }

    /**
     * Signs out every cached account.
     */
    suspend fun signOutAll() {
        try {
            val accounts = MSAL.app.accounts.await()
            for (a in accounts) {
                MSAL.app.removeAccount(a).await()
            }
        } catch (e: Exception) {
            authLogger.warn("signOutAll error", e)
        } finally {
            currentAccountHomeId = null
        }
    }

    /**
     * Signs out the current user.
     */
    suspend fun signOutSuspending() {
        try {
            val accounts = MSAL.app.accounts.await()
            for (a in accounts) MSAL.app.removeAccount(a).await()
        } catch (e: Exception) {
            authLogger.error("Error removing Microsoft accounts", e)
        } finally {
            account = null
            authLogger.info("User signed out.")
        }
    }

    fun signOut() = runBlocking { signOutSuspending() }

    /**
     * Retrieves a list of Minecraft versions.
     */
    suspend fun getMinecraftVersions(releaseTypes: List<MCVersionType> = listOf(MCVersionType.Release)): List<MCVersion> {
        return try {
            val response = httpClient.get("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json")
            val body = response.bodyAsText()

            val manifest: VersionManifest = json.decodeFromString(body)

            val typesSet = releaseTypes.toSet()
            val releases = manifest.versions.filter { it.type in typesSet }
            authLogger.info("Successfully fetched MC version manifest.")
            authLogger.info("Filtered ${releases.size} release versions from the MC version manifest.")
            releases
        } catch (e: Exception) {
            authLogger.error("Error fetching Minecraft versions", e)
            emptyList()
        }
    }

    /**
     * Download and prepare a vanilla Minecraft instance into the target directory.
     */
    suspend fun setupMinecraftInstance(versionId: String, targetDir: VPath): Boolean = withContext(IODispatchers.BulkIO) {
        try {
            authLogger.info("MC setup start version={} dir={}", versionId, targetDir.toString().redactUserPath())
            val manifest = fetchVersionManifest()
            val entry = manifest.versions.firstOrNull { it.id == versionId }
                ?: run {
                    authLogger.error("Minecraft version not found: {}", versionId)
                    return@withContext false
                }

            val versionJsonText = httpClient.get(entry.url).bodyAsText()
            val versionInfo = json.decodeFromString(VersionInfo.serializer(), versionJsonText)
            authLogger.info("MC setup fetched version json for {}", versionId)

            val versionsDir = targetDir.resolve(".tr").resolve("versions").resolve(versionId)
            versionsDir.mkdirs()
            versionsDir.resolve("$versionId.json").writeBytesAtomic(versionJsonText.toByteArray())

            val client = versionInfo.downloads.client
            val clientJar = versionsDir.resolve("$versionId.jar")
            if(!clientJar.exists()) {
                authLogger.info("Downloading client jar {}", client.url)
                val bytes = downloadBytesChecked(client.url, timeoutMs = 60_000L)
                validateJarBytesOrThrow("$versionId.jar", bytes)
                clientJar.writeBytesAtomic(bytes)
            }

            val libsDir = targetDir.resolve(".tr").resolve("libraries")
            val nativesDir = targetDir.resolve(".tr").resolve("natives").resolve(versionId)
            nativesDir.mkdirs()

            supervisorScope {
                val libsJob = async {
                    authLogger.info("Downloading libraries + natives ({} libs)", versionInfo.libraries.size)
                    downloadLibraries(versionInfo.libraries, libsDir, nativesDir)
                    authLogger.info("Libraries complete")
                }

                val assetsJob = async {
                    authLogger.info("Downloading assets {}", versionInfo.assetIndex.id)
                    downloadAssets(versionInfo.assetIndex, targetDir)
                    authLogger.info("Assets complete")
                }

                val logJob = async {
                    versionInfo.logging?.client?.let { logCfg ->
                        val logDir = targetDir.resolve(".tr").resolve("log_configs")
                        logDir.mkdirs()
                        val dest = logDir.resolve(logCfg.file.id)
                        if(!dest.exists()) {
                            val bytes = downloadBytesChecked(logCfg.file.url, timeoutMs = 30_000L)
                            dest.writeBytesAtomic(bytes)
                        }
                    }
                }

                libsJob.await()
                assetsJob.await()
                logJob.await()
            }

            authLogger.info("MC setup finished for {}", versionId)
            true
        } catch (e: Exception) {
            authLogger.error("Failed to setup Minecraft instance {}", versionId, e)
            false
        }
    }

    /**
     * Write a merged version JSON for a loader instance.
     *
     * This merges the base Minecraft version JSON with a loader-provided patch
     * located at `.tr/loader/<loaderId>/version_patch.json` in the instance.
     */
    suspend fun writeMergedVersionJson(
        mcVersion: String,
        loaderId: String,
        loaderVersion: String,
        targetDir: VPath
    ): VPath? = withContext(IODispatchers.FileIO) {
        val baseDir = targetDir.resolve(".tr").resolve("versions").resolve(mcVersion)
        val baseJsonFile = baseDir.resolve("$mcVersion.json")
        val baseJar = baseDir.resolve("$mcVersion.jar")
        if(!baseJsonFile.exists()) return@withContext null

        val baseText = baseJsonFile.readTextOrNull() ?: return@withContext null
        val baseObj = json.parseToJsonElement(baseText).jsonObject

        val patchFile = targetDir.resolve(".tr").resolve("loader").resolve(loaderId).resolve("version_patch.json")
        val patchText = patchFile.readTextOrNull() ?: return@withContext null
        val patchObj = json.parseToJsonElement(patchText).jsonObject

        val merged = mergeVersionJson(baseObj, patchObj)
        val mergedId = "$mcVersion-$loaderId-$loaderVersion"
        val mergedObj = merged.toMutableMap().apply { put("id", JsonPrimitive(mergedId)) }
        val mergedJson = json.encodeToString(JsonObject(mergedObj))

        val outDir = targetDir.resolve(".tr").resolve("versions").resolve(mergedId)
        outDir.mkdirs()
        val outJson = outDir.resolve("$mergedId.json")
        outJson.writeBytesAtomic(mergedJson.toByteArray())

        if(baseJar.exists()) {
            val outJar = outDir.resolve("$mergedId.jar")
            if(!outJar.exists()) {
                outJar.writeBytesAtomic(baseJar.bytesOrNothing())
            }
        }

        outJson
    }

    private fun mergeVersionJson(base: JsonObject, patch: JsonObject): JsonObject {
        val merged = base.toMutableMap()

        patch["mainClass"]?.let { merged["mainClass"] = it }
        mergeArguments(base, patch)?.let { merged["arguments"] = it }
        patch["minecraftArguments"]?.let { merged["minecraftArguments"] = it }

        val baseLibs = base["libraries"]?.jsonArray ?: JsonArray(emptyList())
        val patchLibs = patch["libraries"]?.jsonArray ?: JsonArray(emptyList())
        if(patchLibs.isNotEmpty()) {
            merged["libraries"] = mergeLibrariesPreferPatch(baseLibs, patchLibs)
        }

        return JsonObject(merged)
    }

    private fun mergeArguments(base: JsonObject, patch: JsonObject): JsonObject? {
        val patchArgs = patch["arguments"] as? JsonObject ?: return base["arguments"] as? JsonObject
        val baseArgs = base["arguments"] as? JsonObject

        val baseGame = baseArgs?.get("game")?.jsonArray
            ?: base["minecraftArguments"]?.jsonPrimitive?.contentOrNull?.split(" ")?.map { JsonPrimitive(it) }?.let { JsonArray(it) }
            ?: JsonArray(emptyList())
        val baseJvm = baseArgs?.get("jvm")?.jsonArray ?: JsonArray(emptyList())

        val patchGame = patchArgs["game"]?.jsonArray ?: JsonArray(emptyList())
        val patchJvm = patchArgs["jvm"]?.jsonArray ?: JsonArray(emptyList())

        val mergedGame = JsonArray(baseGame + patchGame)
        val mergedJvm = JsonArray(baseJvm + patchJvm)

        return buildJsonObject {
            put("game", mergedGame)
            put("jvm", mergedJvm)
        }
    }

    /**
     * Merge libraries giving precedence to loader-supplied entries. This removes older duplicates
     * that can clash on the classpath (e.g., ASM or loader modules from vanilla vs. NeoForge).
     */
    private fun mergeLibrariesPreferPatch(baseLibs: JsonArray, patchLibs: JsonArray): JsonArray {
        if (patchLibs.isEmpty()) return baseLibs
        val merged = LinkedHashMap<String, JsonElement>()

        fun addAll(libs: JsonArray, prefer: Boolean) {
            for (el in libs) {
                val key = libraryIdentityKey(el) ?: libraryKey(el)
                if (key.isBlank()) continue
                if (!merged.containsKey(key) || prefer) {
                    merged[key] = el
                }
            }
        }

        addAll(baseLibs, false)
        addAll(patchLibs, true)
        return JsonArray(merged.values.toList())
    }

    private fun libraryKey(el: JsonElement): String {
        val obj = el as? JsonObject ?: return el.toString()
        val name = obj["name"]?.jsonPrimitive?.contentOrNull
        val path = obj["downloads"]?.jsonObject
            ?.get("artifact")?.jsonObject
            ?.get("path")?.jsonPrimitive?.contentOrNull
        return path ?: name ?: el.toString()
    }

    private fun libraryIdentityKey(el: JsonElement): String? {
        val obj = el as? JsonObject ?: return null
        val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return null
        val parts = name.split(":")
        if (parts.size < 3) return name
        val group = parts[0]
        val artifact = parts[1]
        val classifier = parts.getOrNull(3)
        return if (classifier != null) "$group:$artifact:$classifier" else "$group:$artifact"
    }

    private suspend fun fetchVersionManifest(): VersionManifest {
        val response = httpClient.get("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json")
        val body = response.bodyAsText()
        return json.decodeFromString(VersionManifest.serializer(), body)
    }

    private suspend fun downloadBytesChecked(url: String, timeoutMs: Long = 20_000L): ByteArray = withTimeout(timeoutMs) {
        val response = httpClient.get(url)
        if (!response.status.isSuccess()) {
            throw IllegalStateException("HTTP ${response.status.value} downloading '$url'")
        }
        response.bodyAsBytes()
    }

    private fun validateJarBytesOrThrow(path: String, bytes: ByteArray) {
        if (!path.endsWith(".jar", ignoreCase = true)) return
        if (!isValidJarBytes(bytes)) {
            throw IllegalStateException("Downloaded jar is invalid: $path")
        }
    }

    private fun isUsableLibraryArtifact(path: VPath, expectedSize: Long?, requireJar: Boolean): Boolean {
        if (!path.exists()) return false
        val size = path.sizeOrNull() ?: return false
        if (size <= 0L) return false
        if (expectedSize != null && expectedSize > 0L && size != expectedSize) return false
        if (!requireJar) return true
        return isValidJarFile(path)
    }

    private fun isValidJarFile(path: VPath): Boolean {
        return try {
            JarFile(path.toJFile()).use { true }
        } catch (_: Throwable) {
            false
        }
    }

    private fun isValidJarBytes(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        return try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
                zis.nextEntry != null
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun isLibraryAllowed(rules: List<LibraryRule>?): Boolean {
        if(rules.isNullOrEmpty()) return true
        var allowed = false
        for(rule in rules) {
            if(rule.os != null && !osMatches(rule.os)) continue
            allowed = rule.action == "allow"
        }
        return allowed
    }

    private fun osMatches(os: RuleOS): Boolean {
        val nameOk = when(os.name) {
            null -> true
            "linux" -> Platform.isLinux
            "windows" -> Platform.isWindows
            "osx" -> Platform.isMacOS
            else -> false
        }

        if(!nameOk) return false
        if(os.arch == null) return true

        val arch = System.getProperty("os.arch") ?: ""
        return if(os.arch == "x86") arch.contains("86") && !arch.contains("64") else arch.contains("64")
    }

    private suspend fun downloadLibraries(libraries: List<Library>, libsDir: VPath, nativesDir: VPath) = withContext(IODispatchers.BulkIO) {
        val total = libraries.size
        val concurrency = chooseLibraryConcurrency(total)
        authLogger.info("Libraries: {} to ensure, concurrency={}", total, concurrency)
        val sharedLibsDir = fromTR(TConstants.Dirs.CACHE).resolve("libraries")
        val semaphore = Semaphore(concurrency)
        val startedAt = System.currentTimeMillis()
        var completed = 0

        coroutineScope {
            libraries.withIndex().map { (idx, lib) ->
                launch {
                    if(!isLibraryAllowed(lib.rules)) return@launch
                    semaphore.withPermit {
                        try {
                            var primaryArtifactFile: VPath? = null
                            var primaryArtifactPath: String? = null

                            val artifact = lib.downloads?.artifact
                            if(artifact != null) {
                                val dest = libsDir.resolve(artifact.path)
                                val cache = sharedLibsDir.resolve(artifact.path)
                                val expectedSize = artifact.size
                                if (!isUsableLibraryArtifact(dest, expectedSize, requireJar = true)) {
                                    if (!linkOrCopyFromCache(cache, dest) || !isUsableLibraryArtifact(dest, expectedSize, requireJar = true)) {
                                        if (dest.exists()) dest.delete()
                                        if (cache.exists() && !isUsableLibraryArtifact(cache, expectedSize, requireJar = true)) {
                                            cache.delete()
                                        }
                                    authLogger.debug("lib[{}/{}] artifact {}", idx+1, total, artifact.path)
                                    dest.parent().mkdirs()
                                    val bytes = downloadBytesChecked(artifact.url)
                                    if (expectedSize != null && expectedSize > 0L && bytes.size.toLong() != expectedSize) {
                                        throw IllegalStateException(
                                            "Artifact size mismatch for ${artifact.path}: got ${bytes.size}, expected $expectedSize"
                                        )
                                    }
                                    validateJarBytesOrThrow(artifact.path, bytes)
                                    cache.parent().mkdirs()
                                    cache.writeBytesAtomic(bytes)
                                    if (!linkOrCopyFromCache(cache, dest)) {
                                        dest.writeBytesAtomic(bytes)
                                    }
                                    }
                                }
                                primaryArtifactFile = dest
                                primaryArtifactPath = artifact.path
                            } else if(lib.name != null) {
                                val url = (lib.url ?: "https://libraries.minecraft.net/").trimEnd('/')
                                val path = mavenPath(lib.name)
                                val dest = libsDir.resolve(path)
                                val cache = sharedLibsDir.resolve(path)
                                if (!isUsableLibraryArtifact(dest, expectedSize = null, requireJar = true)) {
                                    if (!linkOrCopyFromCache(cache, dest) || !isUsableLibraryArtifact(dest, expectedSize = null, requireJar = true)) {
                                        if (dest.exists()) dest.delete()
                                        if (cache.exists() && !isUsableLibraryArtifact(cache, expectedSize = null, requireJar = true)) {
                                            cache.delete()
                                        }
                                    authLogger.debug("lib[{}/{}] maven {}", idx+1, total, path)
                                    dest.parent().mkdirs()
                                    val bytes = downloadBytesChecked("$url/$path")
                                    validateJarBytesOrThrow(path, bytes)
                                    cache.parent().mkdirs()
                                    cache.writeBytesAtomic(bytes)
                                    if (!linkOrCopyFromCache(cache, dest)) {
                                        dest.writeBytesAtomic(bytes)
                                    }
                                    }
                                }
                                primaryArtifactFile = dest
                                primaryArtifactPath = path
                            }

                            if (shouldExtractArtifactNatives(lib, primaryArtifactPath)) {
                                primaryArtifactFile?.let { artifactFile ->
                                    extractNatives(artifactFile, nativesDir, lib.extract)
                                }
                            }

                            val nativesKey = lib.natives?.let { pickNativeKey(it) }
                            val classifier = nativesKey?.let { key -> lib.downloads?.classifiers?.get(key) }
                            if(classifier != null) {
                                val dest = libsDir.resolve(classifier.path)
                                val cache = sharedLibsDir.resolve(classifier.path)
                                val expectedSize = classifier.size
                                if (!isUsableLibraryArtifact(dest, expectedSize, requireJar = true)) {
                                    if (!linkOrCopyFromCache(cache, dest) || !isUsableLibraryArtifact(dest, expectedSize, requireJar = true)) {
                                        if (dest.exists()) dest.delete()
                                        if (cache.exists() && !isUsableLibraryArtifact(cache, expectedSize, requireJar = true)) {
                                            cache.delete()
                                        }
                                    dest.parent().mkdirs()
                                    val bytes = downloadBytesChecked(classifier.url)
                                    if (expectedSize != null && expectedSize > 0L && bytes.size.toLong() != expectedSize) {
                                        throw IllegalStateException(
                                            "Native size mismatch for ${classifier.path}: got ${bytes.size}, expected $expectedSize"
                                        )
                                    }
                                    validateJarBytesOrThrow(classifier.path, bytes)
                                    cache.parent().mkdirs()
                                    cache.writeBytesAtomic(bytes)
                                    if (!linkOrCopyFromCache(cache, dest)) {
                                        dest.writeBytesAtomic(bytes)
                                    }
                                    }
                                }
                                extractNatives(dest, nativesDir, lib.extract)
                            }
                        } catch (t: Exception) {
                            authLogger.warn("Library {} failed: {}", lib.name ?: lib.downloads?.artifact?.path ?: "unknown", t.message)
                        } finally {
                            val done = synchronized(this@MicrosoftAuth) { ++completed }
                            if(done % 50 == 0 || done == total) {
                                val elapsed = System.currentTimeMillis() - startedAt
                                authLogger.info("Libraries progress {}/{} ({})", done, total, formatDurationMs(elapsed))
                            }
                        }
                    }
                }
            }.joinAll()
        }

        authLogger.info("Libraries download complete in {}", formatDurationMs(System.currentTimeMillis() - startedAt))
    }

    private fun shouldExtractArtifactNatives(lib: Library, artifactPath: String?): Boolean {
        if (artifactPath.isNullOrBlank()) return false
        if (!artifactPath.endsWith(".jar", ignoreCase = true)) return false

        val normalizedPath = artifactPath.lowercase()
        if (normalizedPath.contains("-natives-") || normalizedPath.endsWith("-natives.jar")) {
            return true
        }

        val coordinate = lib.name ?: return false
        val classifier = coordinate.split(':').getOrNull(3)?.lowercase() ?: return false
        return classifier.startsWith("natives-")
    }

    private fun pickNativeKey(natives: Map<String, String>): String? {
        val osKey = when {
            Platform.isWindows -> "windows"
            Platform.isMacOS -> "osx"
            Platform.isLinux -> "linux"
            else -> return null
        }
        val raw = natives[osKey] ?: return null
        if(!raw.contains("\${arch}")) return raw
        val arch = System.getProperty("os.arch") ?: ""
        val token = if(arch.contains("64")) "64" else "32"
        return raw.replace("\${arch}", token)
    }

    private fun extractNatives(jar: VPath, nativesDir: VPath, extract: LibraryExtract?) {
        ZipInputStream(jar.toJFile().inputStream()).use { zis ->
            var entry = zis.nextEntry
            while(entry != null) {
                val name = entry.name
                if(entry.isDirectory) { entry = zis.nextEntry; continue }
                if(extract?.exclude?.any { name.startsWith(it) } == true) {
                    entry = zis.nextEntry; continue
                }
                val out = nativesDir.resolve(name)
                out.parent().mkdirs()
                out.writeBytes(zis.readBytes())
                entry = zis.nextEntry
            }
        }
    }

    private suspend fun downloadAssets(assetIndex: AssetIndex, targetDir: VPath) = withContext(IODispatchers.BulkIO) {
        data class PendingAsset(
            val hash: String,
            val size: Long,
            val path: VPath,
            val url: String
        )

        val sharedAssetsDir = fromTR(TConstants.Dirs.ASSETS)
        sharedAssetsDir.mkdirs()

        val instanceAssetsDir = targetDir.resolve(".tr").resolve("assets")
        val assetsDir = tryCreateSharedAssetsLink(instanceAssetsDir, sharedAssetsDir)

        val indexesDir = assetsDir.resolve("indexes")
        val objectsDir = assetsDir.resolve("objects")
        indexesDir.mkdirs()
        objectsDir.mkdirs()

        val indexFile = indexesDir.resolve("${assetIndex.id}.json")
        val index = loadAssetIndex(indexFile, assetIndex)
        val total = index.objects.size
        val concurrency = chooseAssetConcurrency(total)
        authLogger.info("Assets: {} objects to ensure (id={}), concurrency={}", total, assetIndex.id, concurrency)

        val startedAt = System.currentTimeMillis()
        val semaphore = Semaphore(concurrency) // limit concurrent asset downloads
        var completed = 0
        val failed = ConcurrentLinkedQueue<PendingAsset>()

        coroutineScope {
            index.objects.entries.map { entry ->
                launch {
                    val obj = entry.value
                    val hash = obj.hash
                    val prefix = hash.substring(0, 2)
                    val objPath = objectsDir.resolve(prefix).resolve(hash)
                    val existingSize = objPath.sizeOrNull()
                    if(existingSize != null && existingSize == obj.size) {
                        return@launch
                    }
                    semaphore.withPermit {
                        val sizeAfterPermit = objPath.sizeOrNull()
                        if (sizeAfterPermit != null && sizeAfterPermit == obj.size) {
                            return@withPermit
                        }
                        objPath.parent().mkdirs()
                        val url = "https://resources.download.minecraft.net/$prefix/$hash"
                        try {
                            if (objPath.exists()) {
                                authLogger.warn(
                                    "Asset {} has invalid cached size (got={}, expected={}), re-downloading",
                                    hash,
                                    sizeAfterPermit ?: -1L,
                                    obj.size
                                )
                                objPath.delete()
                            }
                            val bytes = downloadBytesChecked(url)
                            val actualSize = bytes.size.toLong()
                            if (actualSize != obj.size) {
                                throw IllegalStateException(
                                    "Asset size mismatch for $hash: got $actualSize, expected ${obj.size}"
                                )
                            }
                            val actualHash = sha1Hex(bytes)
                            if (!actualHash.equals(hash, ignoreCase = true)) {
                                throw IllegalStateException(
                                    "Asset hash mismatch for $hash: got $actualHash"
                                )
                            }
                            objPath.writeBytes(bytes)
                        } catch (t: Exception) {
                            failed.add(PendingAsset(hash = hash, size = obj.size, path = objPath, url = url))
                            authLogger.warn("Asset {} failed ({}/{}): {}", hash, completed + 1, total, t.message)
                            return@withPermit
                        } finally {
                            val done = synchronized(this@MicrosoftAuth) { ++completed }
                            if(done % 200 == 0 || done == total) {
                                val elapsed = System.currentTimeMillis() - startedAt
                                authLogger.info("Assets progress {}/{} ({})", done, total, formatDurationMs(elapsed))
                            }
                        }
                    }
                }
            }.joinAll()
        }

        if (failed.isNotEmpty()) {
            val firstPassFailed = failed.toList().distinctBy { it.hash }
            val retryConcurrency = minOf(8, concurrency.coerceAtLeast(1))
            authLogger.warn(
                "Assets: retrying {} failed downloads with lower concurrency={}",
                firstPassFailed.size,
                retryConcurrency
            )
            val stillFailed = ConcurrentLinkedQueue<PendingAsset>()
            val retrySemaphore = Semaphore(retryConcurrency)

            coroutineScope {
                firstPassFailed.map { pending ->
                    launch {
                        retrySemaphore.withPermit {
                            try {
                                val existingSize = pending.path.sizeOrNull()
                                if (existingSize != null && existingSize == pending.size) return@withPermit
                                if (pending.path.exists()) pending.path.delete()
                                pending.path.parent().mkdirs()

                                val bytes = downloadBytesChecked(pending.url, timeoutMs = 45_000L)
                                val actualSize = bytes.size.toLong()
                                if (actualSize != pending.size) {
                                    throw IllegalStateException(
                                        "Asset size mismatch for ${pending.hash}: got $actualSize, expected ${pending.size}"
                                    )
                                }
                                val actualHash = sha1Hex(bytes)
                                if (!actualHash.equals(pending.hash, ignoreCase = true)) {
                                    throw IllegalStateException(
                                        "Asset hash mismatch for ${pending.hash}: got $actualHash"
                                    )
                                }
                                pending.path.writeBytes(bytes)
                            } catch (_: Exception) {
                                stillFailed.add(pending)
                            }
                        }
                    }
                }.joinAll()
            }

            if (stillFailed.isNotEmpty()) {
                val remaining = stillFailed.toList().distinctBy { it.hash }
                val sample = remaining.take(8).joinToString(", ") { it.hash }
                throw IllegalStateException(
                    "Failed to download ${remaining.size} assets after retries (sample hashes: $sample)"
                )
            }
        }

        authLogger.info("Assets download complete in {}", formatDurationMs(System.currentTimeMillis() - startedAt))
    }

    private suspend fun loadAssetIndex(indexFile: VPath, assetIndex: AssetIndex): AssetIndexFile {
        val cachedBytes = if (indexFile.exists()) indexFile.bytesOrNull() else null
        if (cachedBytes != null) {
            val cachedValid = validateAssetIndexBytes(cachedBytes, assetIndex)
            if (cachedValid) {
                val cachedParsed = parseAssetIndexBytes(cachedBytes, assetIndex.id, "cache")
                if (cachedParsed != null) {
                    return cachedParsed
                }
                authLogger.warn("Assets: cached index {} is invalid JSON, refreshing", assetIndex.id)
            } else {
                authLogger.warn("Assets: cached index {} failed integrity validation, refreshing", assetIndex.id)
            }
        }

        authLogger.info("Assets: downloading index {}", assetIndex.id)
        val downloaded = downloadBytesChecked(assetIndex.url, timeoutMs = 30_000L)
        if (!validateAssetIndexBytes(downloaded, assetIndex)) {
            throw IllegalStateException("Downloaded asset index ${assetIndex.id} failed integrity validation")
        }
        val parsed = parseAssetIndexBytes(downloaded, assetIndex.id, "download")
            ?: throw IllegalStateException("Downloaded asset index ${assetIndex.id} is invalid JSON")
        indexFile.writeBytes(downloaded)
        return parsed
    }

    /**
     * Low-priority shared runtime cache maintenance.
     * - Scrub a small sample of cached assets/libs for corruption
     * - Garbage collect unreferenced asset indexes and objects based on local projects
     */
    suspend fun runSharedCacheMaintenanceIfDue() = withContext(IODispatchers.FileIO) {
        if (!markCacheMaintenanceIfDue(System.currentTimeMillis())) {
            return@withContext
        }

        val assetsRoot = fromTR(TConstants.Dirs.ASSETS)
        val cacheRoot = fromTR(TConstants.Dirs.CACHE)
        val assetScrub = scrubSampledAssetObjects(assetsRoot.resolve("objects"), CACHE_SCRUB_ASSET_SAMPLE_SIZE)
        val libScrub = scrubSampledLibraries(cacheRoot.resolve("libraries"), CACHE_SCRUB_LIBRARY_SAMPLE_SIZE)
        val gcStats = gcAssetCacheByReachableIndexes(assetsRoot)

        authLogger.info(
            "Cache maintenance complete: assetScrub(scanned={}, evicted={}), libScrub(scanned={}, evicted={}), assetGc(indexesRemoved={}, indexesKept={}, objectsScanned={}, objectsDeleted={}, gcLimited={})",
            assetScrub.scanned,
            assetScrub.evicted,
            libScrub.scanned,
            libScrub.evicted,
            gcStats.indexesRemoved,
            gcStats.indexesKept,
            gcStats.objectsScanned,
            gcStats.objectsDeleted,
            gcStats.hitDeleteLimit
        )
    }

    private data class SampleScrubStats(
        val scanned: Int,
        val evicted: Int
    )

    private data class AssetGcStats(
        val indexesRemoved: Int,
        val indexesKept: Int,
        val objectsScanned: Int,
        val objectsDeleted: Int,
        val hitDeleteLimit: Boolean
    )

    private fun markCacheMaintenanceIfDue(nowMs: Long): Boolean {
        val stampFile = fromTR(TConstants.Dirs.CACHE).resolve(".cache-maintenance.timestamp")
        return try {
            val previous = if (stampFile.exists()) {
                stampFile.readTextOrNull()?.trim()?.toLongOrNull()
            } else {
                null
            }
            if (previous != null && nowMs - previous < CACHE_MAINTENANCE_INTERVAL_MS) {
                false
            } else {
                stampFile.parent().mkdirs()
                stampFile.writeTextAtomic(nowMs.toString())
                true
            }
        } catch (t: Throwable) {
            authLogger.debug("Failed reading cache maintenance timestamp; continuing", t)
            true
        }
    }

    private fun sampleFiles(root: VPath, sampleSize: Int, predicate: (VPath) -> Boolean): List<VPath> {
        if (sampleSize <= 0 || !root.exists() || !root.isDir()) return emptyList()
        val sample = ArrayList<VPath>(sampleSize)
        var seen = 0L
        try {
            Files.walk(root.toJPath()).use { stream ->
                stream.filter { Files.isRegularFile(it) }.forEach { path ->
                    val vPath = VPath.parse(path.toString())
                    if (!predicate(vPath)) return@forEach
                    seen += 1
                    if (sample.size < sampleSize) {
                        sample += vPath
                    } else {
                        val slot = ThreadLocalRandom.current().nextLong(seen)
                        if (slot < sampleSize) {
                            sample[slot.toInt()] = vPath
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            authLogger.debug("Failed sampling files under {}", root.toAbsolute().toString().redactUserPath(), t)
            return emptyList()
        }
        return sample
    }

    private fun scrubSampledAssetObjects(objectsDir: VPath, sampleSize: Int): SampleScrubStats {
        val sample = sampleFiles(objectsDir, sampleSize) { p ->
            sha1FileNameRegex.matches(p.fileName().lowercase())
        }
        var scanned = 0
        var evicted = 0
        for (file in sample) {
            scanned += 1
            val expectedHash = file.fileName().lowercase()
            val bytes = file.bytesOrNull()
            val valid = bytes != null && bytes.isNotEmpty() && sha1Hex(bytes).equals(expectedHash, ignoreCase = true)
            if (!valid) {
                if (file.delete()) {
                    evicted += 1
                }
            }
        }
        return SampleScrubStats(scanned, evicted)
    }

    private fun scrubSampledLibraries(libsRoot: VPath, sampleSize: Int): SampleScrubStats {
        val sample = sampleFiles(libsRoot, sampleSize) { p ->
            p.fileName().endsWith(".jar", ignoreCase = true)
        }
        var scanned = 0
        var evicted = 0
        for (file in sample) {
            scanned += 1
            if (!isUsableLibraryArtifact(file, expectedSize = null, requireJar = true)) {
                if (file.delete()) {
                    evicted += 1
                }
            }
        }
        return SampleScrubStats(scanned, evicted)
    }

    private fun gcAssetCacheByReachableIndexes(assetsRoot: VPath): AssetGcStats {
        val indexesDir = assetsRoot.resolve("indexes")
        val objectsDir = assetsRoot.resolve("objects")
        if (!indexesDir.exists() || !objectsDir.exists()) {
            return AssetGcStats(0, 0, 0, 0, false)
        }

        val reachableIndexIds = collectReachableAssetIndexIds()
        if (reachableIndexIds.isEmpty()) {
            authLogger.info("Assets GC skipped: no reachable project indexes detected")
            return AssetGcStats(0, 0, 0, 0, false)
        }

        val reachableHashes = HashSet<String>()
        var indexesRemoved = 0
        var indexesKept = 0

        val indexFiles = indexesDir.listFiles { it.isFile() && it.fileName().endsWith(".json", ignoreCase = true) }
        for (indexFile in indexFiles) {
            val id = indexFile.fileName().removeSuffix(".json")
            if (!reachableIndexIds.contains(id)) {
                if (indexFile.delete()) {
                    indexesRemoved += 1
                }
                continue
            }

            val bytes = indexFile.bytesOrNull()
            if (bytes == null || bytes.isEmpty()) continue
            val parsed = parseAssetIndexBytes(bytes, id, "gc")
            if (parsed != null) {
                indexesKept += 1
                parsed.objects.values.forEach { obj -> reachableHashes += obj.hash.lowercase() }
            }
        }

        if (reachableHashes.isEmpty()) {
            return AssetGcStats(indexesRemoved, indexesKept, 0, 0, false)
        }

        var objectsScanned = 0
        var objectsDeleted = 0
        var hitDeleteLimit = false

        try {
            Files.walk(objectsDir.toJPath()).use { stream ->
                val iter = stream.iterator()
                while (iter.hasNext()) {
                    val path = iter.next()
                    if (!Files.isRegularFile(path)) continue
                    objectsScanned += 1
                    val fileName = path.fileName?.toString()?.lowercase() ?: continue
                    val shouldDelete = !sha1FileNameRegex.matches(fileName) || !reachableHashes.contains(fileName)
                    if (!shouldDelete) continue
                    if (objectsDeleted >= CACHE_GC_MAX_DELETE_PER_RUN) {
                        hitDeleteLimit = true
                        break
                    }
                    if (runCatching { Files.deleteIfExists(path) }.getOrDefault(false)) {
                        objectsDeleted += 1
                    }
                }
            }
        } catch (t: Throwable) {
            authLogger.warn("Assets GC scan failed", t)
        }

        pruneEmptyObjectDirs(objectsDir)
        return AssetGcStats(indexesRemoved, indexesKept, objectsScanned, objectsDeleted, hitDeleteLimit)
    }

    private fun collectReachableAssetIndexIds(): Set<String> {
        val ids = LinkedHashSet<String>()
        val projectsRoot = fromTR(TConstants.Dirs.PROJECTS)
        if (!projectsRoot.exists() || !projectsRoot.isDir()) {
            return ids
        }

        val projects = projectsRoot.listFiles { it.isDir() }
        for (projectDir in projects) {
            val versionsRoot = projectDir.resolve(".tr").resolve("versions")
            if (!versionsRoot.exists() || !versionsRoot.isDir()) continue
            val versionDirs = versionsRoot.listFiles { it.isDir() }
            for (versionDir in versionDirs) {
                val jsonFiles = versionDir.listFiles { it.isFile() && it.fileName().endsWith(".json", ignoreCase = true) }
                for (jsonFile in jsonFiles) {
                    val text = jsonFile.readTextOrNull() ?: continue
                    val versionObj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: continue
                    val id = versionObj["assetIndex"]?.jsonObject
                        ?.get("id")?.jsonPrimitive?.contentOrNull
                    if (!id.isNullOrBlank()) {
                        ids += id
                    }
                }
            }
        }
        return ids
    }

    private fun pruneEmptyObjectDirs(objectsDir: VPath) {
        if (!objectsDir.exists() || !objectsDir.isDir()) return
        val dirs = mutableListOf<java.nio.file.Path>()
        try {
            Files.walk(objectsDir.toJPath()).use { stream ->
                stream.filter { Files.isDirectory(it) }.forEach { dirs.add(it) }
            }
            dirs.sortedByDescending { it.nameCount }.forEach { dir ->
                if (dir == objectsDir.toJPath()) return@forEach
                runCatching { Files.deleteIfExists(dir) }
            }
        } catch (_: Throwable) {
        }
    }

    private fun validateAssetIndexBytes(bytes: ByteArray, assetIndex: AssetIndex): Boolean {
        if (bytes.isEmpty()) return false

        val expectedSize = assetIndex.size
        if (expectedSize != null && expectedSize > 0L && bytes.size.toLong() != expectedSize) {
            return false
        }

        val expectedSha1 = assetIndex.sha1?.trim()?.lowercase()
        if (!expectedSha1.isNullOrBlank()) {
            val actualSha1 = sha1Hex(bytes)
            if (actualSha1 != expectedSha1) {
                return false
            }
        }

        return true
    }

    private fun parseAssetIndexBytes(bytes: ByteArray, id: String, source: String): AssetIndexFile? {
        return runCatching {
            json.decodeFromString(AssetIndexFile.serializer(), bytes.toString(Charsets.UTF_8))
        }.onFailure { t ->
            authLogger.warn("Assets: failed to parse {} index {}", source, id, t)
        }.getOrNull()
    }

    private fun sha1Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(bytes)
        val hex = CharArray(digest.size * 2)
        val digits = "0123456789abcdef"
        var i = 0
        for (b in digest) {
            val v = b.toInt() and 0xFF
            hex[i++] = digits[v ushr 4]
            hex[i++] = digits[v and 0x0F]
        }
        return String(hex)
    }

    private fun chooseAssetConcurrency(total: Int): Int {
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(2)
        val target = when {
            total >= 10_000 -> 64
            total >= 6_000 -> 56
            total >= 3_000 -> 48
            else -> 32
        }
        return (cores * 5).coerceIn(ASSET_CONCURRENCY_MIN, minOf(target, ASSET_CONCURRENCY_MAX))
    }

    private fun chooseLibraryConcurrency(total: Int): Int {
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(2)
        val target = when {
            total >= 1_200 -> 24
            total >= 600 -> 16
            else -> 14
        }
        return (cores * 4).coerceIn(LIBS_CONCURRENCY_MIN, minOf(target, LIBS_CONCURRENCY_MAX))
    }

    private fun tryCreateSharedAssetsLink(instanceDir: VPath, sharedDir: VPath): VPath {
        return try {
            if(!instanceDir.exists()) {
                instanceDir.parent().mkdirs()
                Files.createSymbolicLink(instanceDir.toJPath(), sharedDir.toJPath())
                authLogger.info("Assets: linked instance cache to shared {}", sharedDir.toAbsolute().toString().redactUserPath())
            } else {
                val isSymlink = Files.isSymbolicLink(instanceDir.toJPath())
                if(isSymlink) {
                    authLogger.info("Assets: instance cache already linked to shared {}", sharedDir.toAbsolute().toString().redactUserPath())
                } else {
                    authLogger.info("Assets: instance cache exists; using per-instance assets at {}", instanceDir.toAbsolute().toString().redactUserPath())
                    return instanceDir
                }
            }
            sharedDir
        } catch (t: Throwable) {
            authLogger.warn("Assets: failed to link shared cache; using instance assets", t)
            try { instanceDir.mkdirs() } catch (_: Throwable) {}
            instanceDir
        }
    }

    private fun mavenPath(name: String): String {
        val parts = name.split(":")
        if(parts.size < 3) return name
        val group = parts[0].replace('.', '/')
        val artifact = parts[1]
        val version = parts[2]
        val classifier = parts.getOrNull(3)
        val file = if(classifier != null) "$artifact-$version-$classifier.jar" else "$artifact-$version.jar"
        return "$group/$artifact/$version/$file"
    }
}

private fun debug(msg: String, condition: Boolean = false) { if(condition) authLogger.debug(msg) }

/**
 * Summary of a Microsoft account returned by MSAL.
 */
data class MSAccountSummary(
    val homeAccountId: String,
    val username: String?
)

/**
 * Custom exception for authentication-related errors
 */
class AuthenticationException(message: String?, cause: Throwable? = null) : Exception(message, cause)

/**
 * Custom exception for token-related errors
 */
class TokenException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Response from XBL token endpoint. */
@Serializable
private data class XblTokenResponse(
    @SerialName("Token") val token: String,
    @SerialName("DisplayClaims") val displayClaims: DisplayClaims
)

/** Request payload for XBL authentication. */
@Serializable
private data class XblAuthRequest(
    val Properties: Properties,
    val RelyingParty: String,
    val TokenType: String
)

/** Request payload for XSTS authentication. */
@Serializable
private data class XstsAuthRequest(
    val Properties: XstsProperties,
    val RelyingParty: String,
    val TokenType: String
)

/** XBL auth properties. */
@Serializable
private data class Properties(
    val AuthMethod: String,
    val SiteName: String,
    val RpsTicket: String
)

/** XSTS auth properties. */
@Serializable
private data class XstsProperties(
    val SandboxId: String,
    val UserTokens: List<String>
)

/** Response from XSTS token endpoint. */
@Serializable
private data class XstsTokenResponse(
    @SerialName("Token") val token: String,
    @SerialName("DisplayClaims") val displayClaims: DisplayClaims
)

/** Display claims container for XBL/XSTS responses. */
@Serializable
private data class DisplayClaims(
    @SerialName("xui") val xui: List<Xui>
)

/** User hash entry in XBL/XSTS responses. */
@Serializable
private data class Xui(
    @SerialName("uhs") val userHash: String
)

/** Response from Minecraft auth endpoint. */
@Serializable
private data class MCAuthResponse(
    @SerialName("username") val uuid: String, // this is not the player UUID.
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String,
    @SerialName("expires_in") val expiresIn: Int
)

/** Version manifest root. */
@Serializable
private data class VersionManifest(
    val versions: List<MCVersion>
)

/** Minecraft version entry from the manifest. */
@Serializable
data class MCVersion(
    val id: String,
    val type: MCVersionType,
    val url: String
)

/** Detailed version JSON for a specific Minecraft version. */
@Serializable
private data class VersionInfo(
    val id: String,
    val downloads: VersionDownloads,
    val libraries: List<Library> = emptyList(),
    val assetIndex: AssetIndex,
    val mainClass: String? = null,
    val arguments: JsonObject? = null,
    val minecraftArguments: String? = null,
    val javaVersion: JavaVersion? = null,
    val logging: Logging? = null
)

/** Version downloads root. */
@Serializable
private data class VersionDownloads(
    val client: DownloadEntry
)

/** Download entry for a file in the version JSON. */
@Serializable
private data class DownloadEntry(
    val url: String,
    val sha1: String? = null,
    val size: Long? = null
)

/** Library definition in a version JSON. */
@Serializable
private data class Library(
    val name: String? = null,
    val url: String? = null,
    val rules: List<LibraryRule>? = null,
    val natives: Map<String, String>? = null,
    val downloads: LibraryDownloads? = null,
    val extract: LibraryExtract? = null
)

/** Library downloads container. */
@Serializable
private data class LibraryDownloads(
    val artifact: LibraryArtifact? = null,
    val classifiers: Map<String, LibraryArtifact>? = null
)

/** Library artifact entry. */
@Serializable
private data class LibraryArtifact(
    val path: String,
    val url: String,
    val sha1: String? = null,
    val size: Long? = null
)

/** Library extract rules for natives. */
@Serializable
private data class LibraryExtract(
    val exclude: List<String> = emptyList()
)

/** Rule that filters a library by OS. */
@Serializable
private data class LibraryRule(
    val action: String,
    val os: RuleOS? = null
)

/** OS rule descriptor. */
@Serializable
private data class RuleOS(
    val name: String? = null,
    val arch: String? = null
)

/** Asset index descriptor. */
@Serializable
private data class AssetIndex(
    val id: String,
    val url: String,
    val sha1: String? = null,
    val size: Long? = null
)

/** Asset index file contents. */
@Serializable
private data class AssetIndexFile(
    val objects: Map<String, AssetObject>
)

/** Asset object entry. */
@Serializable
private data class AssetObject(
    val hash: String,
    val size: Long
)

/** Argument entry with optional rules. */
@Serializable
private data class Argument(
    val rules: List<LibraryRule>? = null,
    val value: JsonElement
)

/** Java version requirement in version JSON. */
@Serializable
private data class JavaVersion(
    val majorVersion: Int
)

/** Logging descriptor in version JSON. */
@Serializable
private data class Logging(
    val client: LoggingConfig? = null
)

/** Logging config for client. */
@Serializable
private data class LoggingConfig(
    val file: LoggingFile
)

/** Logging file descriptor. */
@Serializable
private data class LoggingFile(
    val id: String,
    val url: String
)

/** Minecraft version type. */
@Serializable
enum class MCVersionType {
    @SerialName("release") Release,
    @SerialName("snapshot") Snapshot,
    @SerialName("old_beta") OldBeta,
    @SerialName("old_alpha") OldAlpha
    ;
}
