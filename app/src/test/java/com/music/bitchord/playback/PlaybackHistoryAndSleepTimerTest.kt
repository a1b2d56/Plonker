package com.music.bitchord.playback

import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * The sleep timer's fade and "sleep after this song", which must stop on the
 * song finishing and never on a skip.
 */
class PlaybackHistoryAndSleepTimerTest {

    @After
    fun stopTimer() = SleepTimer.cancel()

    // ---- the fade ----

    @Test
    fun `a long timer fades over its last ten seconds`() {
        assertEquals(SleepFade.Plan(waitMs = 50_000L, fadeMs = 10_000L), SleepFade.plan(60_000L))
        assertEquals(SleepFade.Plan(waitMs = 3_590_000L, fadeMs = 10_000L), SleepFade.plan(3_600_000L))
    }

    @Test
    fun `a short one fades over half of what is left`() {
        assertEquals(SleepFade.Plan(waitMs = 4_000L, fadeMs = 4_000L), SleepFade.plan(8_000L))
        assertEquals(SleepFade.Plan(waitMs = 10_000L, fadeMs = 10_000L), SleepFade.plan(20_000L))
    }

    @Test
    fun `a deadline already passed pauses at once`() {
        assertEquals(SleepFade.Plan(waitMs = 0L, fadeMs = 0L), SleepFade.plan(0L))
        val late = SleepFade.plan(-1_500L)
        assertEquals(0L, late.fadeMs)
        assertTrue(late.waitMs <= 0L)
    }

    @Test
    fun `the volume steps down evenly and lands on silence`() {
        val steps = (SleepFade.STEPS - 1 downTo 0).map(SleepFade::volumeAt)
        assertEquals(SleepFade.STEPS, steps.size)
        assertTrue(steps.first() < 1f)
        assertEquals(0f, steps.last())
        steps.zipWithNext().forEach { (a, b) -> assertEquals(1f / SleepFade.STEPS, a - b, 1e-6f) }
    }

    @Test
    fun `a fade runs from full to silence over the time it has`() {
        assertEquals(1f, SleepFade.gain(nowMs = 1_000L, fromMs = 1_000L, endMs = 11_000L))
        assertEquals(0.5f, SleepFade.gain(nowMs = 6_000L, fromMs = 1_000L, endMs = 11_000L), 1e-6f)
        assertEquals(0f, SleepFade.gain(nowMs = 11_000L, fromMs = 1_000L, endMs = 11_000L))
        assertEquals(0f, SleepFade.gain(nowMs = 12_000L, fromMs = 1_000L, endMs = 11_000L))
    }

    @Test
    fun `a fade that waited for a crossfade starts from full, not from where it would have been`() {
        // The crossfade landed with 4 of the 10 seconds left.
        assertEquals(1f, SleepFade.gain(nowMs = 7_000L, fromMs = 7_000L, endMs = 11_000L))
        assertEquals(0.5f, SleepFade.gain(nowMs = 9_000L, fromMs = 7_000L, endMs = 11_000L), 1e-6f)
        // Handed the volume at the deadline itself: nothing left to fade over.
        assertEquals(0f, SleepFade.gain(nowMs = 11_000L, fromMs = 11_000L, endMs = 11_000L))
    }

    // ---- sleep after this song ----

    private val calls = mutableListOf<String>()

    /** A player that only records what is asked of it, and is never playing. */
    private val player: ExoPlayer = Proxy.newProxyInstance(
        ExoPlayer::class.java.classLoader,
        arrayOf(ExoPlayer::class.java),
    ) { proxy, method, args ->
        when (method.name) {
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args!![0]
            "toString" -> "recording player"
            "isPlaying" -> false
            else -> {
                calls += method.name
                null
            }
        }
    } as ExoPlayer

    private fun history() = PlaybackHistoryAndSleepTimer(CoroutineScope(Dispatchers.Unconfined), player = { player })

    @Test
    fun `the song finishing ends a sleep-after-this-song timer`() {
        SleepTimer.startAfterTrack()
        history().afterTrackChanged(previousEnded = true, exoPlayer = player)
        assertEquals(listOf("pause"), calls)
        assertFalse(SleepTimer.isRunning)
    }

    @Test
    fun `a skip does not`() {
        SleepTimer.startAfterTrack()
        history().afterTrackChanged(previousEnded = false, exoPlayer = player)
        assertEquals(emptyList<String>(), calls)
        assertTrue(SleepTimer.afterTrack.value)
    }

    @Test
    fun `with no timer the song finishing just moves on`() {
        history().afterTrackChanged(previousEnded = true, exoPlayer = player)
        assertEquals(emptyList<String>(), calls)
    }
}
