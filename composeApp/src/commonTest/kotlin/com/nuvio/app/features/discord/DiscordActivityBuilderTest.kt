package com.nuvio.app.features.discord

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiscordActivityBuilderTest {

    private val episode = DiscordPlayback(
        title = "Game of Thrones",
        isEpisode = true,
        seasonNumber = 1,
        episodeNumber = 1,
        episodeTitle = "Winter Is Coming",
        imdbId = "tt0944947",
        posterUrl = "https://image.tmdb.org/t/p/w500/poster.jpg",
        isPlaying = true,
        positionMs = 60_000L,
        durationMs = 3_702_000L,
        videoWidth = 3840,
        videoHeight = 2160,
        hdr = listOf("DV"),
        audioCodec = "TrueHD",
        audioProfile = "Dolby TrueHD + Dolby Atmos",
        audioChannels = 8,
        videoBitrate = 60_836_174L,
        fileSizeBytes = 28_656_526_272L,
    )

    @Test
    fun qualityLineDescribesPremiumStreams() {
        val quality = episode.quality()
        assertEquals("4K · Dolby Vision · Dolby Atmos 7.1 (TrueHD)", quality.line)
        assertTrue(quality.isPremium)
    }

    @Test
    fun linesFillFromTitleThenEpisode() {
        val card = buildDiscordCard(episode, DiscordPresenceSettings(), nowMs = 1_000_000L, betterPosterAvailable = false)
        assertEquals("Game of Thrones", card.details)
        assertEquals("S01E01 · Winter Is Coming", card.state)
        assertEquals(1_000_000L - 60_000L, card.startMs)
        assertEquals(card.startMs!! + 3_702_000L, card.endMs)
    }

    @Test
    fun bitrateButtonFitsDiscordLabelLimit() {
        val card = buildDiscordCard(episode, DiscordPresenceSettings(), nowMs = 0L, betterPosterAvailable = false)
        val (label, url) = card.buttons.first()
        assertEquals("Bitrate: 61 Mbps | Size: 29 GB", label)
        assertTrue(label.length <= 32)
        assertEquals("https://www.imdb.com/title/tt0944947/", url)
        assertEquals("View on IMDb", card.buttons[1].first)
    }

    @Test
    fun bitrateFallsBackToSizeOverDuration() {
        val noBitrate = episode.copy(videoBitrate = null, fileSizeBytes = 1_000_000_000L, durationMs = 1_000_000L)
        assertEquals(8_000_000.0, noBitrate.bitsPerSecond())
    }

    @Test
    fun betterPosterUsedWhilePlayingBlurredArtWhilePaused() {
        val playing = buildDiscordCard(episode, DiscordPresenceSettings(), nowMs = 0L, betterPosterAvailable = true)
        assertEquals("https://btttr.cc/poster-r/imdb/poster-default/tt0944947.jpg", playing.largeImageUrl)

        val paused = buildDiscordCard(episode.copy(isPlaying = false), DiscordPresenceSettings(), nowMs = 0L, betterPosterAvailable = true)
        assertTrue(paused.largeImageUrl!!.startsWith("https://wsrv.nl/?url=https%3A%2F%2Fimage"))
        assertTrue(paused.largeImageUrl!!.contains("&blur=12"))
        assertEquals(PAUSE_ICON_URL, paused.smallImageUrl)
        assertNull(paused.startMs)
    }

    @Test
    fun titleHeaderDropsTheRepeatedTitleLine() {
        val settings = DiscordPresenceSettings(header = DiscordCardHeader.TITLE)
        val card = buildDiscordCard(episode, settings, nowMs = 0L, betterPosterAvailable = false)
        assertEquals("Game of Thrones", card.name)
        assertEquals("S01E01 · Winter Is Coming", card.details)
        assertEquals("4K · Dolby Vision · Dolby Atmos 7.1 (TrueHD)", card.state)
    }

    @Test
    fun movieTitleLineCarriesTheYear() {
        val movie = episode.copy(
            title = "Spider-Man: Brand New Day",
            isEpisode = false,
            seasonNumber = null,
            episodeNumber = null,
            episodeTitle = null,
            year = "2026",
        )
        val settings = DiscordPresenceSettings(header = DiscordCardHeader.TITLE)
        val card = buildDiscordCard(movie, settings, nowMs = 0L, betterPosterAvailable = false)
        assertEquals("Spider-Man: Brand New Day", card.name)
        assertEquals("Spider-Man: Brand New Day · 2026", card.details)
        assertEquals("4K · Dolby Vision · Dolby Atmos 7.1 (TrueHD)", card.state)
    }

    @Test
    fun genericEpisodeNamesAreSkipped() {
        val generic = episode.copy(episodeTitle = "Episode 7", episodeNumber = 7)
        assertEquals("S01E07", generic.secondLine())
    }
}
