package com.music.bitchord.playback

import com.music.bitchord.data.lyrics.LyricLine
import org.junit.Assert.assertEquals
import org.junit.Test

/** What the notification's subtitle shows as a song plays. */
class ServiceLyricsTest {

    private val lines = listOf(
        LyricLine(timeMs = 5_000L, text = "First line"),
        LyricLine(timeMs = 9_000L, text = ""),
        LyricLine(timeMs = 12_000L, text = "Second line"),
        LyricLine(timeMs = 15_000L, text = "   "),
    )

    @Test
    fun `the line being sung, with a note`() {
        assertEquals("♪ First line", lyricSubtitle(lines, 5_000L, "Artist"))
        assertEquals("♪ First line", lyricSubtitle(lines, 8_999L, "Artist"))
        assertEquals("♪ Second line", lyricSubtitle(lines, 12_500L, "Artist"))
    }

    @Test
    fun `the artist before the first line and in a gap`() {
        assertEquals("Artist", lyricSubtitle(lines, 0L, "Artist"))
        assertEquals("Artist", lyricSubtitle(lines, 10_000L, "Artist"))
        assertEquals("Artist", lyricSubtitle(lines, 16_000L, "Artist"))
    }

    @Test
    fun `the artist with no synced lyrics`() {
        assertEquals("Artist", lyricSubtitle(null, 6_000L, "Artist"))
        assertEquals("Artist", lyricSubtitle(emptyList(), 6_000L, "Artist"))
    }
}
