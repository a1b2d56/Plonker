package com.music.bitchord

import com.music.bitchord.data.model.SPOTIFY_MISSING_PREFIX
import com.music.bitchord.data.model.SPOTIFY_PENDING_PREFIX
import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.BoundedQueuePlanner
import com.music.bitchord.playback.PlayFromRules
import com.music.bitchord.playback.PlayRequests
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueControllerRulesTest {

    private fun song(id: String) = Song(videoId = id, title = id, artist = "", thumbnailUrl = null)

    // ---- PlayFromRules.playable ----

    @Test
    fun `tapping an unresolved Spotify row plays nothing`() {
        val songs = listOf(song("a"), song(SPOTIFY_PENDING_PREFIX + "x"), song("b"))
        assertNull(PlayFromRules.playable(songs, 1))
        val missing = listOf(song(SPOTIFY_MISSING_PREFIX + "y"), song("a"))
        assertNull(PlayFromRules.playable(missing, 0))
    }

    @Test
    fun `unresolved rows are dropped and the tapped song keeps its place among the rest`() {
        val songs = listOf(
            song(SPOTIFY_PENDING_PREFIX + "x"),
            song("a"),
            song(SPOTIFY_MISSING_PREFIX + "y"),
            song("b"),
            song("c"),
        )
        val (playable, index) = PlayFromRules.playable(songs, 3)!!
        assertEquals(listOf("a", "b", "c"), playable.map { it.videoId })
        assertEquals(1, index)
    }

    @Test
    fun `an index past the list starts from the top rather than failing`() {
        val (playable, index) = PlayFromRules.playable(listOf(song("a"), song("b")), 7)!!
        assertEquals(2, playable.size)
        assertEquals(0, index)
    }

    // ---- PlayFromRules.useBoundedContext ----

    @Test
    fun `a short list goes bounded only when asked`() {
        assertFalse(PlayFromRules.useBoundedContext(requested = false, size = 10, casting = false))
        assertTrue(PlayFromRules.useBoundedContext(requested = true, size = 10, casting = false))
    }

    @Test
    fun `a list longer than the physical window goes bounded whatever page it came from`() {
        val tooLong = BoundedQueuePlanner.MAX_PHYSICAL_CONTEXT + 1
        assertTrue(PlayFromRules.useBoundedContext(requested = false, size = tooLong, casting = false))
        assertFalse(
            PlayFromRules.useBoundedContext(
                requested = false,
                size = BoundedQueuePlanner.MAX_PHYSICAL_CONTEXT,
                casting = false,
            ),
        )
    }

    @Test
    fun `a Cast session never takes the bounded path`() {
        val tooLong = BoundedQueuePlanner.MAX_PHYSICAL_CONTEXT + 1
        assertFalse(PlayFromRules.useBoundedContext(requested = true, size = tooLong, casting = true))
    }

    // ---- PlayRequests ----

    @Test
    fun `a later request supersedes an earlier one still in flight`() {
        val requests = PlayRequests()
        val station = requests.begin()
        assertTrue(requests.isCurrent(station))
        requests.beginReplacing()
        assertFalse(requests.isCurrent(station))
    }

    @Test
    fun `a replacing request clears the station label, a pending one leaves it`() {
        val requests = PlayRequests()
        requests.radioSeed = "a" to "Station"
        requests.begin()
        assertEquals("a" to "Station", requests.radioSeed)
        requests.beginReplacing()
        assertNull(requests.radioSeed)
    }

    @Test
    fun `the station label only survives on the track it was set for`() {
        val requests = PlayRequests()
        requests.radioSeed = "a" to "Station"
        requests.onTrackChanged("a")
        assertEquals("a" to "Station", requests.radioSeed)
        requests.onTrackChanged("b")
        assertNull(requests.radioSeed)
        requests.radioSeed = "c" to "Other"
        requests.onTrackChanged(null)
        assertNull(requests.radioSeed)
    }
}
