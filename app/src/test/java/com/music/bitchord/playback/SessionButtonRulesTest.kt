package com.music.bitchord.playback

import com.music.bitchord.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionButtonRulesTest {

    private val catalogue = Song(videoId = "abc123", title = "t", artist = "a", thumbnailUrl = null)

    private fun plan(
        song: Song? = catalogue,
        canLike: Boolean = true,
        liked: Boolean = false,
        shuffleOn: Boolean = false,
        pinned: Boolean = false,
    ) = SessionButtonRules.plan(song, canLike, liked, shuffleOn, pinned)

    @Test
    fun `favorite shows the track's state, or is left off when it can't be liked`() {
        assertEquals(false, plan(liked = false).favorite)
        assertEquals(true, plan(liked = true).favorite)
        assertNull(plan(canLike = false, liked = true).favorite)
    }

    @Test
    fun `shuffle mirrors the setting`() {
        assertTrue(plan(shuffleOn = true).shuffleOn)
        assertFalse(plan(shuffleOn = false).shuffleOn)
    }

    @Test
    fun `a station starts off a catalogue track but not a file on the device`() {
        assertTrue(plan().station)
        assertFalse(plan(song = catalogue.copy(videoId = "content://media/1")).station)
        assertFalse(plan(song = catalogue.copy(videoId = "file:///sdcard/a.flac")).station)
        assertFalse(plan(song = catalogue.copy(videoId = "")).station)
        assertFalse(plan(song = null).station)
    }

    @Test
    fun `revert is offered for a catalogue track not already pinned to its original`() {
        assertTrue(plan().revert)
        assertFalse(plan(pinned = true).revert)
    }

    @Test
    fun `nothing to revert for a video, a device file, or nothing playing`() {
        assertFalse(plan(song = catalogue.copy(isVideo = true)).revert)
        assertFalse(plan(song = catalogue.copy(localUri = "content://media/1")).revert)
        assertFalse(plan(song = null).revert)
    }
}
