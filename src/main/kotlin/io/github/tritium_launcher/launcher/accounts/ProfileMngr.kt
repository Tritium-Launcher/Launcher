package io.github.tritium_launcher.launcher.accounts

import io.github.tritium_launcher.launcher.TConstants
import io.github.tritium_launcher.launcher.fromTR
import io.github.tritium_launcher.launcher.io.VPath
import io.github.tritium_launcher.launcher.io.atomicWrite
import io.github.tritium_launcher.launcher.logger
import io.github.tritium_launcher.launcher.platform.ClientIdentity
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.ConcurrentHashMap

/**
 * Holds methods for profile management, and the profile cache.
 */
object ProfileMngr {
    private const val MC_API_BASE = "https://api.minecraftservices.com"
    private const val PROFILE_URL = "$MC_API_BASE/minecraft/profile"

    private const val SKIN_CHANGE_URL = "$MC_API_BASE/minecraft/profile/skins"
    private const val CAPE_CHANGE_URL = "$MC_API_BASE/minecraft/profile/capes/active"

    private val listeners = mutableListOf<(MCProfile?) -> Unit>()
    private val progressListeners = mutableListOf<(Double) -> Unit>()

    private val json = Json { ignoreUnknownKeys = true }

    internal var httpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(json)
        }

        defaultRequest {
            header("User-Agent", ClientIdentity.userAgent)
            header("X-Client-Info", ClientIdentity.clientInfoHeader)
        }
    }

    /** Adds a listener for profile changes. */
    fun addListener(listener: (MCProfile?) -> Unit) {
        listeners.add(listener)
    }

    /** Adds a listener for profile fetch progress. */
    fun addProgressListener(listener: (Double) -> Unit) {
        progressListeners.add(listener)
    }

    private fun notifyProgress(progress: Double) {
        progressListeners.forEach { it(progress) }
    }

    private fun notifyProfileChanged(profile: MCProfile?) {
        listeners.forEach { it(profile) }
    }

    private val profilesDir = fromTR(TConstants.Dirs.PROFILES).also { it.mkdirs() }

    private val logger = logger()

    @OptIn(DelicateCoroutinesApi::class)
    object Cache {
        private val profiles = ConcurrentHashMap<String, MCProfile>()
        private val tokens = ConcurrentHashMap<String, String>()
        private val mutex = Mutex()

        @Volatile
        private var defaultHomeId: String? = null

        @JvmStatic
        val isEmpty: Boolean
            get() = profiles.isEmpty()

        init {
            GlobalScope.launch(Dispatchers.IO) {
                try {
                    profilesDir.listFiles { it.isFile() && it.fileName().endsWith(".json") }.forEach { f ->
                        try {
                            val contents = f.readTextOrNull() ?: return@forEach
                            val profile = json.decodeFromString(MCProfile.serializer(), contents)
                            val homeId = f.fileName().removeSuffix(".json")
                            profiles[homeId] = profile
                            logger.info("Loaded cached MC profile for account $homeId from disk")
                        } catch (t: Throwable) {
                            logger.warn("Failed to load cached profile file ${f.toAbsolute()}", t)
                        }
                    }
                } catch (t: Throwable) {
                    logger.warn("Could not pre-load profiles", t)
                }
            }
        }

        /** Fetches and caches the profile for a specific account id. */
        suspend fun initForAccount(homeAccountId: String, token: String) = withContext(Dispatchers.IO) {
            try {
                notifyProgress(0.2) // Started token validation
                val profile = fetch(token)
                notifyProgress(0.6) // Profile fetched
                if (profile != null) {
                    profiles[homeAccountId] = profile
                    tokens[homeAccountId] = token
                    saveProfileToDisk(homeAccountId, profile)
                    logger.info("Cached MC profile for account (${profile.name})")
                } else logger.error("Failed to fetch MC profile for account $homeAccountId during init")
            } catch (e: Exception) {
                logger.error("Exception while fetching MC profile for account $homeAccountId", e)
            }
            notifyProgress(1.0) // Process complete
            val current = MicrosoftAuth.currentAccountHomeId
            if(current != null && current == homeAccountId) {
                notifyProfileChanged(profiles[homeAccountId])
            }
        }

        /** Fetches and caches the profile for the current account. */
        suspend fun init(token: String) {
            val home = MicrosoftAuth.currentAccountHomeId
                ?: throw IllegalStateException("Cannot infer homeAccountId, no current Microsoft account selected")
            initForAccount(home, token)
            defaultHomeId = home
        }

        /** Returns the cached profile for a specific account id. */
        fun getForAccount(homeAccountId: String): MCProfile? = profiles[homeAccountId]

        /** Returns the cached profile for the current account. */
        fun get(): MCProfile? {
            val home = MicrosoftAuth.currentAccountHomeId ?: defaultHomeId
            return if(home != null) profiles[home] else null
        }

        /** Returns the cached username for the current account. */
        fun getUsername(): String? = runCatching { profiles[MicrosoftAuth.currentAccountHomeId ?: defaultHomeId]?.name }.getOrNull()
        /** Returns the cached UUID for the current account. */
        fun getUUID(): String? = runCatching { profiles[MicrosoftAuth.currentAccountHomeId ?: defaultHomeId]?.id }.getOrNull()
        /** Returns the cached skins for the current account. */
        fun getSkins(): List<MCSkin>? = runCatching { profiles[MicrosoftAuth.currentAccountHomeId ?: defaultHomeId]?.skins }.getOrNull()
        /** Returns the cached capes for the current account. */
        fun getCapes(): List<MCCape>? = runCatching { profiles[MicrosoftAuth.currentAccountHomeId ?: defaultHomeId]?.capes }.getOrNull()
        internal fun getToken(): String? = runCatching { tokens[MicrosoftAuth.currentAccountHomeId ?: defaultHomeId] }.getOrNull()

        /** Clears cache entries for a specific account id. */
        fun clearForAccount(homeAccountId: String) {
            profiles.remove(homeAccountId)
            tokens.remove(homeAccountId)
            val f = profilesDir.resolve("$homeAccountId.json")
            try {
                if(f.exists()) f.delete()
            } catch (t: Throwable) {
                logger.warn("Failed to delete cached profile file ${f.toAbsolute()}", t)
            }

            val current = MicrosoftAuth.currentAccountHomeId
            if(current == homeAccountId) notifyProfileChanged(null)
        }

        /** Clears all cached profiles and tokens. */
        fun clearAll() {
            profiles.clear()
            tokens.clear()
            profilesDir.listFiles { it.isFile() }.forEach { try { it.delete() } catch (_: Throwable) {} }
            notifyProfileChanged(null)
        }

        private suspend fun saveProfileToDisk(homeAccountId: String, profile: MCProfile) = withContext(Dispatchers.IO) {
            mutex.withLock {
                val f = profilesDir.resolve("$homeAccountId.json")
                try {
                    f.parent().mkdirs()
                    atomicWrite(
                        f,
                        json.encodeToString(MCProfile.serializer(), profile).toByteArray(Charsets.UTF_8),
                        durable = true
                    )
                } catch (t: Throwable) {
                    logger.warn("Failed to persist profile for $homeAccountId", t)
                }
            }
        }
    }

    /** Fetches a profile for the given access token. */
    suspend fun fetch(token: String): MCProfile? {
        return try {
            val response: HttpResponse = httpClient.get(PROFILE_URL) {
                header("Authorization", "Bearer $token")
            }
            val body = response.bodyAsText()
            if (response.status != HttpStatusCode.OK) {
                logger.error("Failed to fetch profile: HTTP {}", response.status.value)
                null
            } else {
                json.decodeFromString(MCProfile.serializer(), body)
            }
        } catch (e: Exception) {
            logger.error("Error fetching MC profile", e)
            null
        }
    }

    /** Attempts to change the profile name. */
    suspend fun changeName(token: String, newName: String): Boolean {
        val url = "$PROFILE_URL/name/$newName"
        return withContext(Dispatchers.IO) {
            try {
                val response = httpClient.put(url) {
                    headers {
                        append("Authorization", "Bearer $token")
                        append("Content-Length", "0")
                    }
                }
                when (response.status) {
                    HttpStatusCode.NoContent -> true
                    HttpStatusCode.BadRequest -> {
                        logger.error("Invalid profile name: $newName")
                        false
                    }

                    HttpStatusCode.Forbidden -> {
                        logger.error("Could not change name for profile")
                        false
                    }

                    HttpStatusCode.TooManyRequests -> {
                        logger.error("Too many requests sent")
                        false
                    }

                    else -> {
                        logger.error("Error changing username: ${response.status.value}")
                        false
                    }
                }
            } catch (e: Exception) {
                logger.error("Error changing username", e)
                false
            }
        }
    }

    /** Switches the active skin to an existing skin id. */
    suspend fun changeSkin(token: String, skinId: String, variant: String = "classic"): Boolean {
        return try {
            val response = httpClient.put("$SKIN_CHANGE_URL/$skinId") {
                headers { append("Authorization", "Bearer $token") }
                contentType(ContentType.Application.Json)
                setBody(JsonObject(mapOf("variant" to JsonPrimitive(variant))))
            }
            if (response.status == HttpStatusCode.NoContent) {
                logger.info("Skin changed successfully to skin id $skinId")
                true
            } else {
                logger.error("Failed to change skin: HTTP ${response.status.value}")
                false
            }
        } catch (e: Exception) {
            logger.error("Error changing skin", e)
            false
        }
    }

    /** Uploads a new skin file. */
    suspend fun uploadSkin(token: String, file: VPath, variant: String = "classic"): Boolean {
        return try {
            val bytes = file.bytesOrNull() ?: return false
            val fileName = file.fileName()
            val response = httpClient.post(SKIN_CHANGE_URL) {
                header("Authorization", "Bearer $token")
                contentType(ContentType.MultiPart.FormData)
                setBody(MultiPartFormDataContent(formData {
                    append("variant", variant)
                    append("file", bytes, Headers.build {
                        append(HttpHeaders.ContentType, "image/png")
                        append(HttpHeaders.ContentDisposition, "form-data; name=\"file\"; filename=\"$fileName\"")
                    })
                }))
            }
            if (response.status == HttpStatusCode.NoContent) {
                logger.info("Skin uploaded successfully: {}", fileName)
                true
            } else {
                logger.error("Failed to upload skin: HTTP ${response.status.value}")
                false
            }
        } catch (e: Exception) {
            logger.error("Error uploading skin", e)
            false
        }
    }

    /** Switches the active cape to an existing cape id. */
    suspend fun changeCape(token: String, capeId: String): Boolean {
        return try {
            val response = httpClient.put("$CAPE_CHANGE_URL/$capeId") {
                header("Authorization", "Bearer $token")
            }
            if (response.status == HttpStatusCode.NoContent) {
                logger.info("Cape changed successfully to cape id $capeId")
                true
            } else {
                logger.error("Failed to change cape: HTTP ${response.status.value}")
                false
            }
        } catch (e: Exception) {
            logger.error("Error changing cape", e)
            false
        }
    }


    /** Minecraft profile returned by the services API. */
    @Serializable
    data class MCProfile(
        @SerialName("id") val id: String,
        @SerialName("name") val name: String,
        @SerialName("skins") val skins: List<MCSkin>,
        @SerialName("capes") val capes: List<MCCape>
    )
    /** Skin entry for a profile. */
    @Serializable
    data class MCSkin(
        val id: String,
        val state: String,
        val url: String,
        val variant: String
    )
    /** Cape entry for a profile. */
    @Serializable
    data class MCCape(
        val id: String,
        val state: String,
        val url: String,
        val alias: String
    )

}
