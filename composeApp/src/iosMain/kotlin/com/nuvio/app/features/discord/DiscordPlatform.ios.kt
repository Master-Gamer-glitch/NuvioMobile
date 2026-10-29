package com.nuvio.app.features.discord

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.serialization.json.JsonObject

// Discord Rich Presence is Android-only for now; the settings page explains that on iOS.
internal actual object DiscordPlatform {
    actual val isSupported: Boolean = false

    actual suspend fun fetchAccount(token: String): DiscordAccount? = null

    actual suspend fun resolveImage(token: String, url: String): String? = null

    actual suspend fun betterPosterExists(url: String): Boolean = false

    actual fun setActivity(token: String, status: DiscordStatus, activity: JsonObject?) = Unit

    actual fun disconnect() = Unit
}

@Composable
internal actual fun DiscordLoginDialog(
    onToken: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    LaunchedEffect(Unit) { onDismiss() }
}
