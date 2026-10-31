package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.nuvio.app.features.discord.DiscordPlayback
import com.nuvio.app.features.discord.DiscordPresence
import kotlinx.coroutines.delay

/** Mirrors this player to the Discord presence card while it's open. */
@Composable
internal fun PlayerScreenRuntime.BindDiscordPresenceEffects() {
    var mediaInfo by remember(activeSourceUrl) { mutableStateOf(PlayerMediaInfo()) }

    // The player only reads codec details for the stream-info overlay; fetch them once
    // playback of a new source is under way (they settle a moment after the first frame).
    LaunchedEffect(playerController, playerControllerSourceUrl, activeSourceUrl, playbackSnapshot.isLoading) {
        val controller = playerController ?: return@LaunchedEffect
        if (playerControllerSourceUrl != activeSourceUrl || playbackSnapshot.isLoading) return@LaunchedEffect
        delay(2_000L)
        mediaInfo = controller.getMediaInfo()
    }

    LaunchedEffect(Unit) {
        snapshotFlow { discordPlayback(mediaInfo) }.collect { playback ->
            if (playback != null) DiscordPresence.update(playback) else DiscordPresence.clear()
        }
    }

    DisposableEffect(Unit) {
        onDispose { DiscordPresence.clear() }
    }
}

private fun PlayerScreenRuntime.discordPlayback(mediaInfo: PlayerMediaInfo): DiscordPlayback? {
    val snapshot = playbackSnapshot
    if (snapshot.isEnded) return null
    val displayTitle = title.ifBlank { activeStreamTitle }.takeIf(String::isNotBlank) ?: return null
    val meta = playerMeta?.takeIf { it.id == parentMetaId }
    val isEpisode = activeSeasonNumber != null && activeEpisodeNumber != null
    return DiscordPlayback(
        title = displayTitle,
        isEpisode = isEpisode,
        seasonNumber = activeSeasonNumber,
        episodeNumber = activeEpisodeNumber,
        episodeTitle = activeEpisodeTitle,
        year = meta?.releaseInfo?.let { Regex("(19|20)\\d{2}").find(it)?.value },
        imdbId = meta?.imdbId ?: parentMetaId.takeIf { it.startsWith("tt") },
        posterUrl = poster ?: meta?.poster,
        backdropUrl = (if (isEpisode) activeEpisodeThumbnail else null) ?: background ?: meta?.background,
        // Buffering while the user wants playback still counts as watching.
        isPlaying = snapshot.isPlaying || (snapshot.isLoading && shouldPlay),
        positionMs = snapshot.positionMs,
        durationMs = snapshot.durationMs,
        videoWidth = mediaInfo.videoWidth ?: snapshot.videoWidth.takeIf { it > 0 },
        videoHeight = mediaInfo.videoHeight ?: snapshot.videoHeight.takeIf { it > 0 },
        hdr = mediaInfo.hdr,
        audioCodec = mediaInfo.audioCodec,
        audioProfile = mediaInfo.audioProfiles.joinToString(" + ").ifEmpty { null },
        audioChannels = mediaInfo.audioChannels,
        videoBitrate = mediaInfo.videoBitrate?.toLong(),
        fileSizeBytes = activeStream?.behaviorHints?.videoSize,
    )
}
