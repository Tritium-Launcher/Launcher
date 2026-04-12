package io.github.tritium_launcher.launcher.platform

import io.github.tritium_launcher.launcher.extension.core.CoreSettingValues
import io.github.tritium_launcher.launcher.logger
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.websocket.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import java.util.*

/**
 * Bridge response payload.
 *
 * @property ok Indicates whether the action succeeded.
 * @property message Human-readable status or error message.
 * @property data Optional action-specific data payload.
 * @property id Request/response correlation id when provided by the bridge.
 */
data class CompanionBridgeResponse(
    val ok: Boolean,
    val message: String,
    val data: JsonObject = buildJsonObject { },
    val id: String? = null
)

/**
 * Websocket client for bridge requests.
 *
 * Communication model:
 * - Opens a short-lived websocket per request.
 * - Sends a single JSON request frame.
 * - Reads one JSON response frame and closes.
 */
object CompanionBridge {
    private val logger = logger()
    private val json = Json { ignoreUnknownKeys = true }
    private val httpClient = HttpClient(CIO) {
        install(WebSockets)
    }
    @Volatile
    private var sessionToken: String? = null

    private const val DEFAULT_REQUEST_TIMEOUT_MS = 30_000L
    private const val PING_TIMEOUT_MS = 5_000L
    private const val COMMAND_TIMEOUT_MS = 60_000L
    private const val RELOAD_TIMEOUT_MS = 10 * 60 * 1_000L
    private const val CLOSE_GAME_TIMEOUT_MS = 30_000L
    private const val MIN_REQUEST_TIMEOUT_MS = 1_000L
    private const val MAX_REQUEST_TIMEOUT_MS = 15 * 60 * 1_000L
    private const val AUTH_HEADER = "X-Tritium-Token"

    /** Active websocket endpoint. */
    fun endpoint(): String = "ws://${CoreSettingValues.companionWsHost()}:${CoreSettingValues.companionWsPort()}/tritium"

    /**
     * Sets the per-session auth token used for websocket handshakes.
     */
    fun setSessionToken(token: String?) {
        sessionToken = token?.trim()?.takeIf { it.isNotEmpty() }
    }

    /**
     * Clears the active per-session auth token.
     */
    fun clearSessionToken() {
        sessionToken = null
    }

    /** Sends a `ping` request. */
    suspend fun ping(timeoutMs: Long = PING_TIMEOUT_MS): CompanionBridgeResponse =
        request("ping", timeoutMs = timeoutMs)

    /**
     * Sends a `reload_server` request.
     *
     * The timeout is applied both client-side and in the payload sent to the bridge.
     */
    suspend fun reloadServer(timeoutMs: Long = RELOAD_TIMEOUT_MS): CompanionBridgeResponse {
        val effectiveTimeout = sanitizeTimeoutMs(timeoutMs)
        return request(
            action = "reload_server",
            payload = buildJsonObject {
                put("timeoutMs", effectiveTimeout.toInt())
            },
            timeoutMs = effectiveTimeout
        )
    }

    /**
     * Sends an `execute_command` request.
     *
     * The timeout is applied both client-side and in the payload sent to the bridge.
     */
    suspend fun sendCommand(command: String, timeoutMs: Long = COMMAND_TIMEOUT_MS): CompanionBridgeResponse {
        val effectiveTimeout = sanitizeTimeoutMs(timeoutMs)
        return request(
            action = "execute_command",
            payload = buildJsonObject {
                put("command", command)
                put("timeoutMs", effectiveTimeout.toInt())
            },
            timeoutMs = effectiveTimeout
        )
    }

    /**
     * Sends a `close_game` request.
     */
    suspend fun closeGame(timeoutMs: Long = CLOSE_GAME_TIMEOUT_MS): CompanionBridgeResponse {
        val effectiveTimeout = sanitizeTimeoutMs(timeoutMs)
        return request(
            action = "close_game",
            payload = buildJsonObject {
                put("timeoutMs", effectiveTimeout.toInt())
            },
            timeoutMs = effectiveTimeout
        )
    }

    /**
     * Sends a websocket request asynchronously.
     */
    suspend fun request(
        action: String,
        payload: JsonObject = buildJsonObject { },
        timeoutMs: Long = DEFAULT_REQUEST_TIMEOUT_MS
    ): CompanionBridgeResponse =
        withContext(Dispatchers.IO) {
            requestInternal(action, payload, timeoutMs)
        }

    /**
     * Sends a websocket request and blocks the caller until completion.
     */
    fun requestBlocking(
        action: String,
        payload: JsonObject = buildJsonObject { },
        timeoutMs: Long = DEFAULT_REQUEST_TIMEOUT_MS
    ): CompanionBridgeResponse =
        runBlocking(Dispatchers.IO) {
            requestInternal(action, payload, timeoutMs)
        }

    /**
     * Performs one request/response cycle against the Companion websocket endpoint.
     */
    private suspend fun requestInternal(action: String, payload: JsonObject, timeoutMs: Long): CompanionBridgeResponse {
        val normalizedAction = action.trim().lowercase()
        if (normalizedAction.isBlank()) {
            return CompanionBridgeResponse(ok = false, message = "Action cannot be blank.")
        }
        val effectiveTimeoutMs = sanitizeTimeoutMs(timeoutMs)

        val requestId = UUID.randomUUID().toString()
        val requestPayload = buildJsonObject {
            put("id", requestId)
            put("action", normalizedAction)
            put("payload", payload)
        }

        var session: DefaultClientWebSocketSession? = null

        return try {
            session = withTimeout(effectiveTimeoutMs) {
                httpClient.webSocketSession {
                    url(endpoint())
                    sessionToken?.let { token ->
                        header(AUTH_HEADER, token)
                    }
                }
            }

            withTimeout(effectiveTimeoutMs) {
                session.send(Frame.Text(requestPayload.toString()))
            }
            val rawResponse = withTimeout(effectiveTimeoutMs) {
                readSingleTextResponse(session)
            }
            parseResponse(rawResponse, requestId)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            CompanionBridgeResponse(
                ok = false,
                message = "Request interrupted while talking to Companion websocket: ${e.message}."
            )
        } catch (t: Throwable) {
            CompanionBridgeResponse(
                ok = false,
                message = "Failed to reach Companion websocket at ${endpoint()}: ${t.message ?: t::class.simpleName.orEmpty()}"
            )
        } finally {
            if (session != null) {
                runCatching {
                    session.close(CloseReason(CloseReason.Codes.NORMAL, "done"))
                }
            }
        }
    }

    /**
     * Reads frames until a single text response is received or the socket closes.
     */
    private suspend fun readSingleTextResponse(session: DefaultClientWebSocketSession): String {
        while (true) {
            when (val frame = session.incoming.receive()) {
                is Frame.Text -> return frame.readText()
                is Frame.Close -> {
                    val reason = frame.readReason()
                    throw IllegalStateException(
                        "Companion websocket closed unexpectedly (${reason?.code?.toInt() ?: -1}): ${reason?.message.orEmpty()}"
                    )
                }
                else -> {
                }
            }
        }
    }

    /**
     * Parses a raw websocket response into [CompanionBridgeResponse].
     */
    private fun parseResponse(rawResponse: String, expectedRequestId: String): CompanionBridgeResponse {
        val root = runCatching { json.parseToJsonElement(rawResponse) as? JsonObject }.getOrNull()
            ?: return CompanionBridgeResponse(
                ok = false,
                message = "Invalid JSON response from Companion websocket."
            )

        val responseId = root["id"]?.jsonPrimitive?.contentOrNull
        if (!responseId.isNullOrBlank() && responseId != expectedRequestId) {
            logger.debug("Companion websocket response id mismatch: expected {}, received {}", expectedRequestId, responseId)
        }

        val ok = root["ok"]?.jsonPrimitive?.booleanOrNull ?: false
        val message = root["message"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?: if (ok) "ok" else "Request failed."
        val data = root["data"] as? JsonObject ?: buildJsonObject { }

        return CompanionBridgeResponse(
            ok = ok,
            message = message,
            data = data,
            id = responseId
        )
    }

    /**
     * Clamps request timeout to the supported range.
     */
    private fun sanitizeTimeoutMs(timeoutMs: Long): Long {
        if (timeoutMs <= 0L) return DEFAULT_REQUEST_TIMEOUT_MS
        return timeoutMs.coerceIn(MIN_REQUEST_TIMEOUT_MS, MAX_REQUEST_TIMEOUT_MS)
    }
}
