package com.nuvio.app.features.discord

import kotlinx.serialization.Serializable

@Serializable
enum class DiscordQualityLine { ALWAYS, PREMIUM, NEVER }

@Serializable
enum class DiscordArtwork { BETTER_POSTERS, POSTER, BACKDROP }

@Serializable
enum class DiscordProgress { BAR, ELAPSED, NONE }

@Serializable
enum class DiscordCardHeader { APP, TITLE }

/** Which card line Discord shows next to your name in the member list. */
@Serializable
enum class DiscordMemberListText { NAME, DETAILS, STATE }

@Serializable
enum class DiscordStatus(val wireValue: String) {
    ONLINE("online"),
    IDLE("idle"),
    DND("dnd"),
}

@Serializable
data class DiscordPresenceSettings(
    val enabled: Boolean = true,
    val showTitleLine: Boolean = true,
    val showSecondLine: Boolean = true,
    val qualityLine: DiscordQualityLine = DiscordQualityLine.PREMIUM,
    val artwork: DiscordArtwork = DiscordArtwork.BETTER_POSTERS,
    val progress: DiscordProgress = DiscordProgress.BAR,
    /** 0 hides the card on pause, -1 keeps it until playback resumes or the player closes. */
    val pauseMinutes: Int = -1,
    val header: DiscordCardHeader = DiscordCardHeader.APP,
    val memberListText: DiscordMemberListText = DiscordMemberListText.NAME,
    val status: DiscordStatus = DiscordStatus.ONLINE,
    val bitrateButton: Boolean = true,
    val imdbButton: Boolean = true,
)

@Serializable
data class DiscordAccount(
    val id: String,
    val username: String,
    val globalName: String? = null,
    val avatarHash: String? = null,
) {
    val displayName: String
        get() = globalName?.takeIf(String::isNotBlank) ?: username

    val avatarUrl: String
        get() = avatarHash
            ?.let { "https://cdn.discordapp.com/avatars/$id/$it.png?size=128" }
            ?: "https://cdn.discordapp.com/embed/avatars/${(id.toLongOrNull() ?: 0L).shr(22).mod(6)}.png"
}

enum class DiscordConnectionState { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

/** What the player is doing right now, as far as the presence card cares. */
data class DiscordPlayback(
    val title: String,
    val isEpisode: Boolean,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val episodeTitle: String? = null,
    val year: String? = null,
    val imdbId: String? = null,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val isPlaying: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val videoWidth: Int? = null,
    val videoHeight: Int? = null,
    val hdr: List<String> = emptyList(),
    val audioCodec: String? = null,
    val audioProfile: String? = null,
    val audioChannels: Int? = null,
    val videoBitrate: Long? = null,
    val fileSizeBytes: Long? = null,
)
