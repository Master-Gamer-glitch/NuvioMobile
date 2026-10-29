package com.nuvio.app.features.discord

import co.touchlab.kermit.Logger
import com.nuvio.app.core.time.EpisodeReleaseDatePlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.math.abs

/**
 * Mirrors the player to the Discord card. The player reports every snapshot; this decides
 * when something visible changed and keeps under Discord's presence rate limit.
 */
object DiscordPresence {
    private val log = Logger.withTag("DiscordPresence")
    // Main: the player calls in from the main thread, so state needs no locking.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val wake = Channel<Unit>(Channel.CONFLATED)

    // Discord allows 5 presence updates per 20 s per session.
    private const val MIN_SEND_INTERVAL_MS = 4_500L
    // Re-send when the real position drifts this far from what the card's timer shows (seeks).
    private const val DRIFT_TOLERANCE_MS = 3_000L

    private var playback: DiscordPlayback? = null
    private var pausedSinceMs: Long? = null
    private val imageAssets = mutableMapOf<String, String>()
    private val betterPosters = mutableMapOf<String, Boolean>()

    private var lastSentKey: String? = null
    private var lastSentStartMs: Long? = null
    private var lastSentAtMs = 0L
    private var sentSomething = false

    init {
        scope.launch { publishLoop() }
    }

    /** Latest player state; cheap to call on every snapshot. */
    fun update(next: DiscordPlayback) {
        val previous = playback
        playback = next
        pausedSinceMs = when {
            next.isPlaying -> null
            previous?.isPlaying != false || pausedSinceMs == null -> now()
            else -> pausedSinceMs
        }
        wake.trySend(Unit)
    }

    /** The player closed. */
    fun clear() {
        playback = null
        pausedSinceMs = null
        wake.trySend(Unit)
    }

    /** Settings changed: rebuild the card now. */
    fun republish() {
        lastSentKey = null
        wake.trySend(Unit)
    }

    private fun now() = EpisodeReleaseDatePlatform.nowEpochMs()

    private suspend fun publishLoop() {
        while (true) {
            // Wake on changes, and every 15 s so the pause timeout can expire without player events.
            withTimeoutOrNull(15_000L) { wake.receive() }
            runCatching { publishIfNeeded() }.onFailure { log.w(it) { "Discord presence update failed" } }
        }
    }

    private suspend fun publishIfNeeded() {
        DiscordPresenceRepository.ensureLoaded()
        val state = DiscordPresenceRepository.uiState.value
        val token = DiscordPresenceRepository.token ?: return
        val settings = state.settings
        val current = playback?.takeIf { settings.enabled && shouldShowPaused(it, settings) }

        if (current == null) {
            if (sentSomething) {
                DiscordPlatform.setActivity(token, settings.status, null)
                sentSomething = false
                lastSentKey = null
            }
            return
        }

        current.imdbId?.takeIf { it.startsWith("tt") && it !in betterPosters && settings.artwork == DiscordArtwork.BETTER_POSTERS }
            ?.let { id -> betterPosters[id] = betterPosterUrl(id)?.let { DiscordPlatform.betterPosterExists(it) } ?: false }

        val nowMs = now()
        val card = buildDiscordCard(
            playback = current,
            settings = settings,
            nowMs = nowMs,
            betterPosterAvailable = current.imdbId?.let { betterPosters[it] } == true,
        )
        val key = card.copy(startMs = null, endMs = null).toString() + settings.status
        val drifted = card.startMs != lastSentStartMs &&
            (card.startMs == null || lastSentStartMs == null || abs(card.startMs - lastSentStartMs!!) > DRIFT_TOLERANCE_MS)
        if (key == lastSentKey && !drifted) return

        val wait = MIN_SEND_INTERVAL_MS - (nowMs - lastSentAtMs)
        if (wait > 0) {
            delay(wait)
            wake.trySend(Unit) // re-evaluate with fresh state after the wait
            return
        }

        val activity = card.toActivity(token)
        DiscordPlatform.setActivity(token, settings.status, activity)
        lastSentKey = key
        lastSentStartMs = card.startMs
        lastSentAtMs = now()
        sentSomething = true
    }

    private fun shouldShowPaused(playback: DiscordPlayback, settings: DiscordPresenceSettings): Boolean {
        if (playback.isPlaying) return true
        val since = pausedSinceMs ?: return true
        return when {
            settings.pauseMinutes < 0 -> true
            settings.pauseMinutes == 0 -> false
            else -> now() - since < settings.pauseMinutes * 60_000L
        }
    }

    private suspend fun asset(token: String, url: String?): String? {
        url ?: return null
        imageAssets[url]?.let { return it }
        val resolved = DiscordPlatform.resolveImage(token, url) ?: return null
        imageAssets[url] = resolved
        return resolved
    }

    private suspend fun DiscordCard.toActivity(token: String): JsonObject {
        val large = asset(token, largeImageUrl)
        val small = asset(token, smallImageUrl)
        return buildJsonObject {
            put("name", name)
            put("type", 3) // Watching
            put("application_id", DISCORD_APPLICATION_ID)
            details?.let { put("details", it) }
            state?.let { put("state", it) }
            put(
                "status_display_type",
                when (memberListText) {
                    DiscordMemberListText.NAME -> 0
                    DiscordMemberListText.STATE -> 1
                    DiscordMemberListText.DETAILS -> 2
                },
            )
            if (startMs != null) {
                putJsonObject("timestamps") {
                    put("start", startMs)
                    endMs?.let { put("end", it) }
                }
            }
            if (large != null) {
                putJsonObject("assets") {
                    put("large_image", large)
                    largeText?.let { put("large_text", it) }
                    if (small != null) {
                        put("small_image", small)
                        smallText?.let { put("small_text", it) }
                    }
                }
            }
            if (buttons.isNotEmpty()) {
                putJsonArray("buttons") { buttons.forEach { add(it.first) } }
                putJsonObject("metadata") {
                    putJsonArray("button_urls") { buttons.forEach { add(it.second) } }
                }
            }
        }
    }
}

/**
 * Discord application the presence is attributed to; Discord also needs it to proxy external
 * images (posters, icons). Maintainers would swap in an application registered for Nuvio.
 */
internal const val DISCORD_APPLICATION_ID = "1565313703350370356"
