package com.nuvio.app.features.discord

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

private const val API_BASE = "https://discord.com/api/v10"
private const val GATEWAY_URL = "wss://gateway.discord.gg/?v=10&encoding=json"

internal actual object DiscordPlatform {
    actual val isSupported: Boolean = true

    private val log = Logger.withTag("DiscordGateway")
    private val json = Json { ignoreUnknownKeys = true }
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    actual suspend fun fetchAccount(token: String): DiscordAccount? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$API_BASE/users/@me")
            .header("Authorization", token)
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext null
            val user = json.parseToJsonElement(response.body.string()).jsonObject
            DiscordAccount(
                id = user.string("id") ?: return@withContext null,
                username = user.string("username") ?: return@withContext null,
                globalName = user.string("global_name"),
                avatarHash = user.string("avatar"),
            )
        }
    }

    actual suspend fun resolveImage(token: String, url: String): String? = withContext(Dispatchers.IO) {
        val body = buildJsonObject { putJsonArray("urls") { add(JsonPrimitive(url)) } }.toString()
        val request = Request.Builder()
            .url("$API_BASE/applications/$DISCORD_APPLICATION_ID/external-assets")
            .header("Authorization", token)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        runCatching {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    log.w { "external-assets HTTP ${response.code} for $url" }
                    return@use null
                }
                json.parseToJsonElement(response.body.string()).jsonArray
                    .firstOrNull()?.jsonObject?.string("external_asset_path")
                    ?.let { "mp:$it" }
            }
        }.getOrNull()
    }

    actual suspend fun betterPosterExists(url: String): Boolean = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).head().build()
        runCatching {
            http.newCall(request).execute().use { response ->
                response.isSuccessful && response.header("Content-Type").orEmpty().startsWith("image/")
            }
        }.getOrDefault(false)
    }

    actual fun setActivity(token: String, status: DiscordStatus, activity: JsonObject?) {
        DiscordGateway.setPresence(token, status, activity)
    }

    actual fun disconnect() {
        DiscordGateway.close()
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull
}

/**
 * A minimal Discord gateway session that only exists to carry presence. It identifies as a
 * desktop client (mobile sessions don't show rich presence) and reports itself AFK so Discord
 * keeps delivering push notifications to the phone app.
 */
@OptIn(ExperimentalCoroutinesApi::class)
private object DiscordGateway {
    private val log = Logger.withTag("DiscordGateway")
    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    // All gateway state is touched from this one sequential dispatcher.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    private var token: String? = null
    private var status = DiscordStatus.ONLINE
    private var activity: JsonObject? = null

    private var socket: WebSocket? = null
    private var ready = false
    private var sequence: Long? = null
    private var sessionId: String? = null
    private var resumeUrl: String? = null
    private var heartbeatJob: Job? = null
    private var idleCloseJob: Job? = null
    private var reconnectJob: Job? = null
    private var reconnectAttempts = 0

    fun setPresence(token: String, status: DiscordStatus, activity: JsonObject?) {
        scope.launch {
            if (this@DiscordGateway.token != token) {
                closeSocket()
                sessionId = null
            }
            this@DiscordGateway.token = token
            this@DiscordGateway.status = status
            this@DiscordGateway.activity = activity

            idleCloseJob?.cancel()
            if (activity == null) {
                if (ready) sendPresence()
                // Nothing to show: drop the extra session after a while.
                idleCloseJob = scope.launch {
                    delay(60_000L)
                    closeSocket()
                    DiscordPresenceRepository.onConnectionStateChanged(DiscordConnectionState.DISCONNECTED)
                }
                return@launch
            }
            if (socket == null) connect() else if (ready) sendPresence()
        }
    }

    fun close() {
        scope.launch {
            token = null
            activity = null
            sessionId = null
            closeSocket()
        }
    }

    private fun connect() {
        reconnectJob?.cancel()
        val url = resumeUrl?.takeIf { sessionId != null }?.let { "$it/?v=10&encoding=json" } ?: GATEWAY_URL
        DiscordPresenceRepository.onConnectionStateChanged(DiscordConnectionState.CONNECTING)
        val request = Request.Builder().url(url).build()
        socket = client.newWebSocket(request, Listener())
    }

    private fun closeSocket() {
        heartbeatJob?.cancel()
        reconnectJob?.cancel()
        ready = false
        socket?.close(1000, null)
        socket = null
    }

    private fun scheduleReconnect() {
        if (token == null || activity == null) return
        reconnectJob?.cancel()
        val delayMs = (5_000L shl reconnectAttempts.coerceAtMost(4)).coerceAtMost(60_000L)
        reconnectAttempts++
        reconnectJob = scope.launch {
            delay(delayMs)
            if (socket == null) connect()
        }
    }

    private fun send(payload: JsonObject) {
        socket?.send(payload.toString())
    }

    private fun presencePayload(): JsonObject = buildJsonObject {
        put("since", 0)
        putJsonArray("activities") { activity?.let { add(it) } }
        put("status", status.wireValue)
        put("afk", true)
    }

    private fun sendPresence() {
        send(buildJsonObject {
            put("op", 3)
            put("d", presencePayload())
        })
    }

    private fun identify() {
        val currentToken = token ?: return
        val session = sessionId
        if (session != null) {
            send(buildJsonObject {
                put("op", 6)
                putJsonObject("d") {
                    put("token", currentToken)
                    put("session_id", session)
                    put("seq", sequence ?: 0L)
                }
            })
            return
        }
        send(buildJsonObject {
            put("op", 2)
            putJsonObject("d") {
                put("token", currentToken)
                put("capabilities", 0)
                putJsonObject("properties") {
                    put("os", "Windows")
                    put("browser", "Discord Client")
                    put("device", "Nuvio")
                }
                put("presence", presencePayload())
                put("compress", false)
            }
        })
    }

    private fun startHeartbeat(intervalMs: Long) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            delay((intervalMs * Math.random()).toLong())
            while (isActive) {
                send(buildJsonObject {
                    put("op", 1)
                    put("d", sequence?.let(::JsonPrimitive) ?: JsonNull)
                })
                delay(intervalMs)
            }
        }
    }

    private fun handle(socket: WebSocket, text: String) {
        if (socket !== this.socket) return
        val message = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        (message["s"] as? JsonPrimitive)?.longOrNull?.let { sequence = it }
        val data: JsonElement? = message["d"]
        when (message["op"]?.jsonPrimitive?.intOrNull) {
            10 -> {
                val interval = data?.jsonObject?.get("heartbeat_interval")?.jsonPrimitive?.longOrNull ?: 41_250L
                startHeartbeat(interval)
                identify()
            }
            1 -> send(buildJsonObject {
                put("op", 1)
                put("d", sequence?.let(::JsonPrimitive) ?: JsonNull)
            })
            0 -> when (message["t"]?.jsonPrimitive?.contentOrNull) {
                "READY" -> {
                    val ready = data?.jsonObject
                    sessionId = ready?.get("session_id")?.jsonPrimitive?.contentOrNull
                    resumeUrl = ready?.get("resume_gateway_url")?.jsonPrimitive?.contentOrNull
                    onReady()
                }
                "RESUMED" -> onReady()
            }
            7 -> { // Discord asks us to reconnect (and resume)
                closeSocket()
                connect()
            }
            9 -> { // Invalid session
                if (data?.jsonPrimitive?.booleanOrNull != true) {
                    sessionId = null
                    sequence = null
                }
                closeSocket()
                scheduleReconnect()
            }
        }
    }

    private fun onReady() {
        ready = true
        reconnectAttempts = 0
        DiscordPresenceRepository.onConnectionStateChanged(DiscordConnectionState.CONNECTED)
        sendPresence()
    }

    private fun onClosed(socket: WebSocket, code: Int, reason: String?) {
        if (socket !== this.socket) return
        this.socket = null
        ready = false
        heartbeatJob?.cancel()
        log.i { "Gateway closed: $code ${reason.orEmpty()}" }
        when (code) {
            4004 -> { // Authentication failed: the token is no longer valid
                token = null
                activity = null
                sessionId = null
                DiscordPresenceRepository.onTokenRejected()
            }
            4007, 4009 -> { // Bad sequence / session timed out: start a fresh session
                sessionId = null
                sequence = null
                scheduleReconnect()
            }
            else -> {
                DiscordPresenceRepository.onConnectionStateChanged(
                    if (activity == null) DiscordConnectionState.DISCONNECTED else DiscordConnectionState.ERROR,
                    reason?.takeIf(String::isNotBlank),
                )
                scheduleReconnect()
            }
        }
    }

    private class Listener : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            scope.launch { handle(webSocket, text) }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, null)
            scope.launch { onClosed(webSocket, code, reason) }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            log.w(t) { "Gateway connection failed" }
            scope.launch { onClosed(webSocket, -1, t.message) }
        }
    }
}
