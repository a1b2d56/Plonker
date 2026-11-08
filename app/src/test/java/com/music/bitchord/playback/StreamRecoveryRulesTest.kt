package com.music.bitchord.playback

import com.music.bitchord.data.innertube.StreamResolver
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What [StreamRecoveryCoordinator] decides about a track whose stream failed:
 * whether a higher-quality source goes back to YouTube, when it stops
 * retrying, and whether the queue moves past it.
 */
class StreamRecoveryRulesTest {

    // ---- falling back to YouTube ----

    @Test
    fun `a failed lossless upgrade falls back to YouTube`() {
        assertTrue(StreamRecoveryRules.fallsBackToYouTube("bitchord://watch?v=5IX-nUxDtJI&q=hifi", substituted = false))
        assertTrue(StreamRecoveryRules.fallsBackToYouTube("bitchord://watch?v=5IX-nUxDtJI&q=hifi-2", substituted = false))
    }

    @Test
    fun `a failed substitute falls back to YouTube`() {
        assertTrue(StreamRecoveryRules.fallsBackToYouTube("bitchord://watch?v=5IX-nUxDtJI&n=Bin%20Tere", substituted = true))
    }

    @Test
    fun `plain YouTube audio is retried, not fallen back`() {
        assertFalse(StreamRecoveryRules.fallsBackToYouTube("bitchord://watch?v=5IX-nUxDtJI", substituted = false))
    }

    @Test
    fun `a direct YouTube fallback never falls back again`() {
        assertFalse(
            StreamRecoveryRules.fallsBackToYouTube(
                "bitchord://watch?v=5IX-nUxDtJI&direct_youtube=1&q=original",
                substituted = true,
            ),
        )
    }

    @Test
    fun `a track picked from an addon stays the addon's`() {
        // PlaybackFallback counts these as alternatives; recovery must not swap
        // them for a YouTube recording the listener did not ask for.
        val addon = "bitchord://source?s=addon-1&t=3919244&n=Bin%20Tere"
        assertTrue(PlaybackFallback.isAlternative(addon, substitutedYouTube = false))
        assertFalse(StreamRecoveryRules.fallsBackToYouTube(addon, substituted = false))
        assertFalse(StreamRecoveryRules.fallsBackToYouTube("bitchord://source/x?q=hifi", substituted = true))
    }

    @Test
    fun `a local file is never sent to YouTube by this path`() {
        assertFalse(StreamRecoveryRules.fallsBackToYouTube("file:///sdcard/Music/a.m4a", substituted = false))
    }

    // ---- verdicts ----

    @Test
    fun `a resolver verdict is found under Media3's wrapping`() {
        val verdict = StreamResolver.PermanentlyUnplayableException("age restricted")
        val wrapped = IOException("loader", RuntimeException("unexpected", verdict))
        assertEquals("age restricted", StreamRecoveryRules.permanentReason(wrapped))
    }

    @Test
    fun `an ordinary failure is not a verdict`() {
        assertNull(StreamRecoveryRules.permanentReason(IOException("connection reset")))
        assertNull(StreamRecoveryRules.permanentReason(null))
    }

    @Test
    fun `a verdict buried too deep is not looked for`() {
        var error: Throwable = StreamResolver.PermanentlyUnplayableException("gone")
        repeat(StreamRecoveryRules.PERMANENT_CAUSE_DEPTH) { error = IOException("wrap $it", error) }
        assertNull(StreamRecoveryRules.permanentReason(error))
    }

    @Test
    fun `a cause that is its own cause does not loop`() {
        val selfish = object : IOException("loop") {
            override val cause: Throwable get() = this
        }
        assertNull(StreamRecoveryRules.permanentReason(selfish))
    }

    // ---- the attempt budget ----

    @Test
    fun `a verdict gives up at once, anything else after the budget`() {
        assertTrue(StreamRecoveryRules.givesUp("region locked", attempts = 1))
        assertFalse(StreamRecoveryRules.givesUp(null, attempts = StreamRecoveryRules.MAX_RECOVERIES))
        assertTrue(StreamRecoveryRules.givesUp(null, attempts = StreamRecoveryRules.MAX_RECOVERIES + 1))
    }

    // ---- moving past the track ----

    @Test
    fun `a ruled-out track is skipped whatever the run`() {
        assertEquals(
            StreamRecoveryRules.Skip.Ruled("gone"),
            StreamRecoveryRules.skip("gone", neverStarted = false, consecutiveSkips = 99),
        )
    }

    @Test
    fun `a song that died mid-play is not taken away`() {
        assertEquals(StreamRecoveryRules.Skip.Stay, StreamRecoveryRules.skip(null, neverStarted = false, consecutiveSkips = 0))
    }

    @Test
    fun `a track that never started is skipped until the run is too long`() {
        assertEquals(
            StreamRecoveryRules.Skip.FailedToStart,
            StreamRecoveryRules.skip(null, neverStarted = true, consecutiveSkips = StreamRecoveryRules.MAX_CONSECUTIVE_SKIPS - 1),
        )
        assertEquals(
            StreamRecoveryRules.Skip.TooManyInARow,
            StreamRecoveryRules.skip(null, neverStarted = true, consecutiveSkips = StreamRecoveryRules.MAX_CONSECUTIVE_SKIPS),
        )
    }
}
