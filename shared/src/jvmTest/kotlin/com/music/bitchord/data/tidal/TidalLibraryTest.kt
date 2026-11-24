package com.music.bitchord.data.tidal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TidalLibraryTest {

    @Test
    fun constantsAndPrefixesMatchExpectedRoutes() {
        assertEquals("tidal:playlist:", TIDAL_PAGE_PREFIX)
        assertEquals("tidal:favorites", TidalLibrary.LIKED_ID)
        assertTrue(TIDAL_PENDING_PREFIX.startsWith("tidal"))
        assertTrue(TIDAL_MISSING_PREFIX.startsWith("tidal"))
    }

    @Test
    fun tidalPlaylistInstantiation() {
        val playlist = TidalPlaylist(
            id = "uuid-1234",
            name = "My Chill Mix",
            owner = "Tester",
            imageUrl = "https://resources.tidal.com/images/123/640x640.jpg",
            trackCount = 42,
            isLiked = false,
        )
        assertEquals("uuid-1234", playlist.id)
        assertEquals("My Chill Mix", playlist.name)
        assertEquals(42, playlist.trackCount)
    }

    @Test
    fun tidalTrackInstantiation() {
        val track = TidalTrack(
            id = "t-999",
            title = "Midnight Drive",
            artist = "Synthesizer",
            album = "Retrowave",
            durationMs = 210000,
            imageUrl = "https://resources.tidal.com/images/cover/640x640.jpg",
        )
        assertEquals("t-999", track.id)
        assertEquals("Midnight Drive", track.title)
        assertEquals(210000, track.durationMs)
    }
}
