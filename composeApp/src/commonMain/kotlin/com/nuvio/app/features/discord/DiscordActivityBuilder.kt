package com.nuvio.app.features.discord

import kotlin.math.roundToLong

internal const val DISCORD_APP_NAME = "Nuvio"
internal const val PAUSE_ICON_URL = "https://cdn.jsdelivr.net/gh/jdecked/twemoji@15.1.0/assets/72x72/23f8.png"
// The full app icon (logo inset on black) so it sits cleanly in Discord's round badge;
// the bare transparent logo poked out of the circle.
internal const val NUVIO_ICON_URL =
    "https://raw.githubusercontent.com/NuvioMedia/NuvioMobile/cmp-rewrite/iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/app-icon-1024.png"

internal data class DiscordQuality(
    val resolution: String?,
    val hdr: String?,
    val audio: String?,
    val isPremium: Boolean,
) {
    val line: String
        get() = listOfNotNull(resolution, hdr, audio).joinToString(" · ")
}

internal fun DiscordPlayback.quality(): DiscordQuality {
    val width = videoWidth ?: 0
    val height = videoHeight ?: 0
    val resolution = when {
        width >= 3800 || height >= 2100 -> "4K"
        width >= 1900 || height >= 1000 -> "1080p"
        width >= 1260 || height >= 700 -> "720p"
        width > 0 -> "SD"
        else -> null
    }
    val hdrLabel = when {
        "DV" in hdr -> "Dolby Vision"
        "HDR10+" in hdr -> "HDR10+"
        "HDR10" in hdr -> "HDR10"
        "HLG" in hdr -> "HLG"
        else -> null
    }
    val profile = audioProfile.orEmpty().lowercase()
    val codec = audioCodec.orEmpty().uppercase()
    val atmos = "atmos" in profile || "joc" in profile
    val audioName = when {
        codec == "TRUEHD" && atmos -> "Dolby Atmos (TrueHD)"
        codec == "E-AC-3" && atmos -> "Dolby Atmos"
        codec == "TRUEHD" -> "Dolby TrueHD"
        codec == "E-AC-3" || codec == "EAC3" -> "Dolby Digital+"
        codec == "AC-3" || codec == "AC3" -> "Dolby Digital"
        codec == "DTS" && ("dts:x" in profile || "dts-x" in profile) -> "DTS:X"
        codec == "DTS" && "ma" in profile -> "DTS-HD MA"
        codec == "DTS" && "hra" in profile -> "DTS-HD HRA"
        codec.isNotEmpty() -> audioCodec
        else -> null
    }
    val channels = audioChannels ?: 0
    val layout = when {
        channels <= 0 -> null
        channels == 1 -> "Mono"
        channels == 2 -> "Stereo"
        else -> "${channels - 1}.1"
    }
    val audio = when {
        audioName == null -> null
        audioName.startsWith("Dolby Atmos (") -> listOfNotNull("Dolby Atmos", layout, "(TrueHD)").joinToString(" ")
        else -> listOfNotNull(audioName, layout).joinToString(" ")
    }
    val premiumAudio = codec == "TRUEHD" || atmos || (codec == "DTS" && profile.isNotEmpty()) || channels >= 6
    return DiscordQuality(
        resolution = resolution,
        hdr = hdrLabel,
        audio = audio,
        isPremium = resolution == "4K" || hdrLabel != null || premiumAudio,
    )
}

/** Video bitrate from the player, or the overall rate estimated from size and duration. */
internal fun DiscordPlayback.bitsPerSecond(): Double? {
    videoBitrate?.takeIf { it > 0 }?.let { return it.toDouble() }
    val bytes = fileSizeBytes?.takeIf { it > 0 } ?: return null
    val seconds = durationMs.takeIf { it > 0 }?.div(1000.0) ?: return null
    return bytes * 8.0 / seconds
}

// Whole numbers: Discord caps button labels at 32 characters.
internal fun formatBitrateShort(bps: Double): String =
    if (bps >= 1e6) "${(bps / 1e6).roundToLong()} Mbps" else "${(bps / 1e3).roundToLong()} kbps"

internal fun formatSizeShort(bytes: Long): String =
    if (bytes >= 1_000_000_000L) "${(bytes / 1e9).roundToLong()} GB" else "${(bytes / 1e6).roundToLong()} MB"

internal fun DiscordPlayback.seasonEpisode(): String? {
    val season = seasonNumber ?: return null
    val episode = episodeNumber ?: return null
    return "S${season.toString().padStart(2, '0')}E${episode.toString().padStart(2, '0')}"
}

/** "Episode 4" adds nothing next to S01E04. */
internal fun DiscordPlayback.meaningfulEpisodeTitle(): String? =
    episodeTitle?.trim()?.takeIf { it.isNotEmpty() && !Regex("^Episode \\d+$").matches(it) }

// A movie's year rides on the title line ("Verity · 2026"): on its own under a
// "Watching Verity" header, a bare year became the card's bold line.
internal fun DiscordPlayback.titleLine(): String =
    if (isEpisode || year.isNullOrBlank()) title else "$title · $year"

internal fun DiscordPlayback.secondLine(): String? =
    if (isEpisode) {
        listOfNotNull(seasonEpisode(), meaningfulEpisodeTitle()).joinToString(" · ").ifEmpty { null }
    } else {
        null
    }

internal fun DiscordPlayback.imdbUrl(): String? =
    imdbId?.takeIf { it.startsWith("tt") }?.let { "https://www.imdb.com/title/$it/" }

internal fun betterPosterUrl(imdbId: String?): String? =
    imdbId?.takeIf { it.startsWith("tt") }?.let { "https://btttr.cc/poster-r/imdb/poster-default/$it.jpg" }

/**
 * Discord squeezes artwork into a square, so the public wsrv.nl image proxy crops it square
 * (keeping the busiest region) and, while paused, blurs and dims it.
 */
internal fun squareImage(url: String, blurred: Boolean): String {
    val encoded = buildString {
        url.encodeToByteArray().forEach { byte ->
            val c = byte.toInt().toChar()
            if (c.isLetterOrDigit() && byte >= 0) append(c) else append('%').append((byte.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0'))
        }
    }
    val blur = if (blurred) "&blur=12&mod=0.6" else ""
    return "https://wsrv.nl/?url=$encoded&w=512&h=512&fit=cover&a=attention$blur&output=jpg"
}

/** Everything the card shows, before image URLs are turned into Discord assets. */
internal data class DiscordCard(
    val name: String,
    val details: String?,
    val state: String?,
    val memberListText: DiscordMemberListText,
    val startMs: Long?,
    val endMs: Long?,
    val largeImageUrl: String?,
    val largeText: String?,
    val smallImageUrl: String?,
    val smallText: String?,
    val buttons: List<Pair<String, String>>,
)

private fun String.clip(max: Int): String =
    if (length <= max) this else take(max - 1).trimEnd() + "…"

internal fun buildDiscordCard(
    playback: DiscordPlayback,
    settings: DiscordPresenceSettings,
    nowMs: Long,
    betterPosterAvailable: Boolean,
): DiscordCard {
    val paused = !playback.isPlaying
    val quality = playback.quality()
    val titleHeader = settings.header == DiscordCardHeader.TITLE && playback.title.length >= 2

    // A Watching card has two text lines: fill them from title line, second line, quality line,
    // skipping a line that just repeats the header.
    val wantQuality = when (settings.qualityLine) {
        DiscordQualityLine.ALWAYS -> true
        DiscordQualityLine.PREMIUM -> quality.isPremium
        DiscordQualityLine.NEVER -> false
    }
    val qualityLine = quality.line.takeIf { wantQuality && it.length >= 2 }
    val candidates = buildList {
        if (settings.showTitleLine) add(playback.titleLine())
        if (settings.showSecondLine) playback.secondLine()?.let(::add)
        qualityLine?.let(::add)
    }
    val lines = candidates
        .filter { it.length >= 2 }
        .filterNot { titleHeader && it.equals(playback.title, ignoreCase = true) }
        .map { if (it == qualityLine) it.clip(128) else it.clip(34) }
        .toMutableList()
    if (paused && lines.isEmpty()) lines += "Paused"

    val showProgress = !paused && settings.progress != DiscordProgress.NONE
    val start = nowMs - playback.positionMs
    val end = (start + playback.durationMs).takeIf { settings.progress == DiscordProgress.BAR && playback.durationMs > 0 }

    val poster = when (settings.artwork) {
        DiscordArtwork.BACKDROP -> playback.backdropUrl ?: playback.posterUrl
        else -> playback.posterUrl ?: playback.backdropUrl
    }
    val betterPoster = betterPosterUrl(playback.imdbId)
        ?.takeIf { settings.artwork == DiscordArtwork.BETTER_POSTERS && !paused && betterPosterAvailable }
    val largeImage = betterPoster ?: poster?.let { squareImage(it, blurred = paused) }

    val episodeLabel = listOfNotNull(playback.seasonEpisode(), playback.meaningfulEpisodeTitle()).joinToString(" · ")
    var largeText = when {
        playback.isEpisode && episodeLabel.isNotEmpty() -> episodeLabel
        !playback.year.isNullOrBlank() -> "${playback.title} (${playback.year})"
        else -> playback.title
    }
    if (qualityLine != null && qualityLine !in lines) largeText = qualityLine // didn't fit: keep it as the tooltip

    val buttons = buildList {
        // Bitrate gets a button of its own: on the quality line "46 Mbps" reads like internet speed.
        // Discord buttons must link somewhere, so it points at the title's IMDb page.
        val bps = playback.bitsPerSecond()
        if (settings.bitrateButton && bps != null) {
            val size = playback.fileSizeBytes?.takeIf { it > 0 }
            val label = if (size != null) {
                "Bitrate: ${formatBitrateShort(bps)} | Size: ${formatSizeShort(size)}"
            } else {
                "Bitrate: ${formatBitrateShort(bps)}"
            }
            add(label to (playback.imdbUrl() ?: "https://nuvio.tv"))
        }
        if (settings.imdbButton) playback.imdbUrl()?.let { add("View on IMDb" to it) }
    }

    return DiscordCard(
        name = if (titleHeader) playback.title.clip(128) else DISCORD_APP_NAME,
        details = lines.getOrNull(0),
        state = lines.getOrNull(1),
        memberListText = settings.memberListText,
        startMs = start.takeIf { showProgress },
        endMs = end.takeIf { showProgress },
        largeImageUrl = largeImage,
        largeText = largeText.takeIf { it.length >= 2 }?.clip(128),
        smallImageUrl = if (paused) PAUSE_ICON_URL else NUVIO_ICON_URL,
        smallText = if (paused) "Paused" else DISCORD_APP_NAME,
        buttons = buttons,
    )
}
