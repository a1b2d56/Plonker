package com.music.bitchord.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The wrong-length check behind an addon copy, and Direct Source Stream's countdown. */
class ReadAheadRulesTest {

    private val listed = 200_000L
    private val tolerance = 3_000L

    @Test
    fun `an addon copy inside the tolerance plays on`() {
        assertFalse(ReadAheadRules.wrongLength(listed + 3_900L, listed, substituted = true, unattributed = false, toleranceMs = tolerance))
        assertFalse(ReadAheadRules.wrongLength(listed - 4_000L, listed, substituted = true, unattributed = false, toleranceMs = tolerance))
    }

    @Test
    fun `an addon preview or a different cut goes back to YouTube`() {
        assertTrue(ReadAheadRules.wrongLength(30_000L, listed, substituted = true, unattributed = false, toleranceMs = tolerance))
        assertTrue(ReadAheadRules.wrongLength(listed + 4_100L, listed, substituted = true, unattributed = false, toleranceMs = tolerance))
    }

    @Test
    fun `bytes of unknown origin are refused only when they are a preview`() {
        assertFalse(ReadAheadRules.wrongLength(listed - 8_000L, listed, substituted = false, unattributed = true, toleranceMs = tolerance))
        assertTrue(ReadAheadRules.wrongLength(listed / 2 - 1, listed, substituted = false, unattributed = true, toleranceMs = tolerance))
    }

    @Test
    fun `YouTube's own copy is never measured against the listing`() {
        assertFalse(ReadAheadRules.wrongLength(30_000L, listed, substituted = false, unattributed = false, toleranceMs = tolerance))
    }

    @Test
    fun `the countdown runs in real time at the playback speed`() {
        assertEquals(10_000L, ReadAheadRules.msToTransition(60_000L, 50_000L, 1f))
        assertEquals(5_000L, ReadAheadRules.msToTransition(60_000L, 50_000L, 2f))
        assertEquals(20_000L, ReadAheadRules.msToTransition(60_000L, 50_000L, 0.5f))
    }

    @Test
    fun `a near-zero speed cannot stretch the window forever`() {
        assertEquals(40_000L, ReadAheadRules.msToTransition(60_000L, 50_000L, 0.01f))
        assertEquals(40_000L, ReadAheadRules.msToTransition(60_000L, 50_000L, 0f))
    }
}
