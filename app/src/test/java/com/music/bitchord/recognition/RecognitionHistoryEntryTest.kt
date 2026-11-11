package com.music.bitchord.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What an identification is saved as, and what makes two of them the same song. */
class RecognitionHistoryEntryTest {

    private fun result(
        trackId: String = "5551234",
        title: String = "Paranoid Android",
        artist: String = "Radiohead",
        album: String? = "OK Computer",
        cover: String? = "https://img/low.jpg",
        coverHq: String? = "https://img/high.jpg",
        videoId: String? = "fHiGbolFFGw",
    ) = RecognitionResult(
        trackId = trackId,
        title = title,
        artist = artist,
        album = album,
        coverArtUrl = cover,
        coverArtHqUrl = coverHq,
        genre = null,
        releaseDate = null,
        label = null,
        lyrics = null,
        shazamUrl = null,
        appleMusicUrl = null,
        spotifyUrl = null,
        isrc = null,
        youtubeVideoId = videoId,
    )

    @Test
    fun `a result is saved with its title, artist, time, artwork and video id`() {
        val entry = RecognitionHistoryEntry.from(result(), nowMs = 1_700_000_000_000L)
        assertEquals("Paranoid Android", entry.title)
        assertEquals("Radiohead", entry.artist)
        assertEquals("OK Computer", entry.album)
        assertEquals(1_700_000_000_000L, entry.recognizedAt)
        assertEquals("https://img/high.jpg", entry.artworkUrl)
        assertEquals("fHiGbolFFGw", entry.videoId)
        assertNull(entry.browseId)
        assertEquals(0L, entry.id)
    }

    @Test
    fun `the small cover stands in when there is no large one`() {
        assertEquals("https://img/low.jpg", RecognitionHistoryEntry.from(result(coverHq = null), 0).artworkUrl)
        assertNull(RecognitionHistoryEntry.from(result(cover = null, coverHq = null), 0).artworkUrl)
    }

    @Test
    fun `blank optional fields are saved as absent`() {
        val entry = RecognitionHistoryEntry.from(result(album = " ", cover = "", coverHq = null, videoId = ""), 0)
        assertNull(entry.album)
        assertNull(entry.artworkUrl)
        assertNull(entry.videoId)
    }

    @Test
    fun `the same Shazam track is the same song whatever its spelling`() {
        val first = RecognitionHistoryEntry.from(result(), 1)
        val again = RecognitionHistoryEntry.from(result(title = "Paranoid Android (Remastered)"), 2)
        assertEquals(first.trackKey, again.trackKey)
        assertEquals("shazam:5551234", first.trackKey)
    }

    @Test
    fun `without a track id the title and artist decide, ignoring case and padding`() {
        val a = RecognitionHistoryEntry.trackKeyOf("", "Paranoid Android", "Radiohead")
        val b = RecognitionHistoryEntry.trackKeyOf("  ", " paranoid android ", "RADIOHEAD")
        assertEquals(a, b)
        assertNotEquals(a, RecognitionHistoryEntry.trackKeyOf("", "Paranoid Android", "Brad Mehldau"))
        assertNotEquals(a, RecognitionHistoryEntry.trackKeyOf("5551234", "Paranoid Android", "Radiohead"))
    }
}
