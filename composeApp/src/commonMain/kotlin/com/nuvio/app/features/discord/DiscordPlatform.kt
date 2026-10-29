package com.nuvio.app.features.discord

import androidx.compose.runtime.Composable
import kotlinx.serialization.json.JsonObject

/**
 * Discord has no local RPC on mobile, so presence goes over the user's own gateway
 * session. Only Android implements it.
 */
internal expect object DiscordPlatform {
    val isSupported: Boolean

    /** Looks up the account behind [token]; null when the token is rejected. */
    suspend fun fetchAccount(token: String): DiscordAccount?

    /** Turns a public image URL into an `mp:external/...` asset Discord will render. */
    suspend fun resolveImage(token: String, url: String): String?

    /** True when BetterPosters has a poster for this IMDb ID. */
    suspend fun betterPosterExists(url: String): Boolean

    /** Sends [activity] (or clears the card when null) and keeps the gateway session alive. */
    fun setActivity(token: String, status: DiscordStatus, activity: JsonObject?)

    fun disconnect()
}

/** Shows Discord's own login page and hands back the account token once signed in. */
@Composable
internal expect fun DiscordLoginDialog(
    onToken: (String) -> Unit,
    onDismiss: () -> Unit,
)
