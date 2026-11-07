package com.music.bitchord

import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.VersionState
import com.music.bitchord.playback.VersionState.Toggle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionStateTest {

    private fun video(id: String) = Song(videoId = id, title = id, artist = "", thumbnailUrl = null, isVideo = true)
    private fun audio(id: String) = Song(videoId = id, title = id, artist = "", thumbnailUrl = null)

    private val audioCache = mutableMapOf<String, Song>()
    private val videoCache = mutableMapOf<String, Song>()
    private val knownAlignments = mutableSetOf<Pair<String, String>>()
    private var smart = false

    private fun state() = VersionState(
        cachedAudioVersion = { audioCache[it] },
        cachedVideoVersion = { videoCache[it] },
        alignmentKnown = { from, to -> (from to to) in knownAlignments },
        smartAlignment = { smart },
    )

    // ---- resolving a switch ----

    @Test
    fun `a found catalogue cut is recorded and carries the video's queue place`() {
        val state = state()
        val v = video("v").copy(queueEntryId = "entry-7", radioName = "Station")
        val target = state.audioFound(v, audio("a"), holdUntilAligned = false)

        assertEquals("a", target.videoId)
        assertTrue(target.isVideoOrigin)
        assertEquals("entry-7", target.queueEntryId)
        assertEquals("Station", target.radioName)
        assertSame(v, state.convertedFromVideo)
        assertEquals("a", state.convertedAudioId)
        assertEquals(target, state.optimistic)
    }

    @Test
    fun `an alignment still owed keeps the UI on the version actually playing`() {
        val state = state()
        state.videoFound(audio("a"), video("v"), holdUntilAligned = true)
        assertNull(state.optimistic)
        assertEquals("v", state.convertedVideoId)

        knownAlignments += "a" to "v"
        state.videoFound(audio("a"), video("v"), holdUntilAligned = true)
        assertEquals("v", state.optimistic?.videoId)
    }

    @Test
    fun `asking for the music-only cut forgets an earlier revert`() {
        val state = state()
        state.audioFound(video("v"), audio("a"), holdUntilAligned = false)
        state.prepareToggle(audio("a"))
        assertEquals("v", state.keepVideoId)

        state.beginSwitch(towardAudio = false)
        assertEquals("v", state.keepVideoId)
        assertTrue(state.switching)
        state.beginSwitch(towardAudio = true)
        assertNull(state.keepVideoId)
        state.endSwitch()
        assertFalse(state.switching)
    }

    @Test
    fun `a track with no other cut is noted once`() {
        val state = state()
        state.markNotFound("v")
        state.markNotFound("v")
        assertEquals(listOf("v"), state.notFound.toList())
    }

    // ---- the menu's toggle ----

    @Test
    fun `toggling a converted track reverts to the exact original row`() {
        val state = state()
        val original = video("v").copy(title = "Official Video")
        state.audioFound(original, audio("a"), holdUntilAligned = false)

        val toggle = state.prepareToggle(audio("a"))

        assertEquals(Toggle.Revert(original), toggle)
        assertEquals("v", state.keepVideoId)
        assertNull(state.convertedFromVideo)
        assertNull(state.convertedAudioId)
        assertSame(original, state.optimistic)
    }

    @Test
    fun `reverting a video switch goes back to the audio row`() {
        val state = state()
        val original = audio("a")
        state.videoFound(original, video("v"), holdUntilAligned = false)

        assertEquals(Toggle.Revert(original), state.prepareToggle(video("v")))
        assertNull(state.convertedFromAudio)
        assertNull(state.convertedVideoId)
    }

    @Test
    fun `a revert whose alignment is unmeasured leaves the optimistic row alone`() {
        smart = true
        val state = state()
        state.audioFound(video("v"), audio("a"), holdUntilAligned = false)
        val before = state.optimistic

        state.prepareToggle(audio("a"))
        assertSame(before, state.optimistic)
    }

    @Test
    fun `a video with a known catalogue cut switches to it optimistically`() {
        audioCache["v"] = audio("a")
        val state = state()
        val v = video("v").copy(queueEntryId = "e1")

        assertEquals(Toggle.ToAudio, state.prepareToggle(v))
        assertEquals("a", state.optimistic?.videoId)
        assertTrue(state.optimistic!!.isVideoOrigin)
        assertEquals("e1", state.optimistic?.queueEntryId)
    }

    @Test
    fun `an audio row looks for its video and claims nothing it hasn't found`() {
        val state = state()
        assertEquals(Toggle.ToVideo, state.prepareToggle(audio("a")))
        assertNull(state.optimistic)

        videoCache["a"] = video("v")
        assertEquals(Toggle.ToVideo, state.prepareToggle(audio("a")))
        assertEquals("v", state.optimistic?.videoId)
    }

    // ---- what the UI shows ----

    @Test
    fun `the in-flight version is what the player describes`() {
        val state = state()
        val v = video("v")
        assertSame(v, state.effectiveSong(v))

        state.audioFound(v, audio("a"), holdUntilAligned = false)
        assertEquals("a", state.effectiveSong(v).videoId)
    }

    @Test
    fun `the menu labels the cut the switch is heading for`() {
        val state = state()
        assertTrue(state.isAudioVersion(audio("a")))
        assertFalse(state.isAudioVersion(video("v")))

        state.audioFound(video("v"), audio("a"), holdUntilAligned = false)
        assertTrue(state.isAudioVersion(video("v")))
    }

    @Test
    fun `an audio row converted to video reads as video before the optimistic row lands`() {
        val state = state()
        state.videoFound(audio("a"), video("v"), holdUntilAligned = true)
        assertNull(state.optimistic)
        assertFalse(state.isAudioVersion(audio("a").copy(videoId = "v")))
    }

    // ---- track changes ----

    @Test
    fun `arriving on the switched-to track keeps the conversion and clears the optimistic row`() {
        val state = state()
        state.audioFound(video("v"), audio("a"), holdUntilAligned = false)
        state.beginSwitch(towardAudio = true)

        state.onTrackChanged("a")

        assertNull(state.optimistic)
        assertEquals("a", state.convertedAudioId)
        assertFalse(state.switching)
    }

    @Test
    fun `moving to an unrelated track forgets the whole conversion`() {
        val state = state()
        state.audioFound(video("v"), audio("a"), holdUntilAligned = false)
        state.prepareToggle(audio("a"))

        state.onTrackChanged("other")

        assertNull(state.optimistic)
        assertNull(state.keepVideoId)
        assertNull(state.convertedFromVideo)
        assertNull(state.convertedAudioId)
    }

    @Test
    fun `landing back on the reverted video keeps the revert so it isn't converted again`() {
        val state = state()
        state.audioFound(video("v"), audio("a"), holdUntilAligned = false)
        state.prepareToggle(audio("a"))

        state.onTrackChanged("v")

        assertNull(state.optimistic)
        assertEquals("v", state.keepVideoId)
    }
}
