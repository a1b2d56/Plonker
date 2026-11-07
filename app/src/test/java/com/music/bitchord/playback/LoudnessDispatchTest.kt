package com.music.bitchord.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class LoudnessDispatchTest {

    // ---- LoudnessRules ----

    @Test
    fun `a loud master is turned down by what it measures over the reference`() {
        assertEquals(-650, LoudnessRules.gainMb(enabled = true, offForSpeaker = false, loudnessDb = 6.5))
    }

    @Test
    fun `a quiet master is lifted, but never by more than 3 dB`() {
        assertEquals(200, LoudnessRules.gainMb(enabled = true, offForSpeaker = false, loudnessDb = -2.0))
        assertEquals(LoudnessRules.MAX_GAIN_MB, LoudnessRules.gainMb(enabled = true, offForSpeaker = false, loudnessDb = -9.0))
    }

    @Test
    fun `and never cut by more than 15 dB`() {
        assertEquals(LoudnessRules.MIN_GAIN_MB, LoudnessRules.gainMb(enabled = true, offForSpeaker = false, loudnessDb = 30.0))
    }

    @Test
    fun `no gain when off, off for the speaker, or not yet measured`() {
        assertNull(LoudnessRules.gainMb(enabled = false, offForSpeaker = false, loudnessDb = 6.0))
        assertNull(LoudnessRules.gainMb(enabled = true, offForSpeaker = true, loudnessDb = 6.0))
        assertNull(LoudnessRules.gainMb(enabled = true, offForSpeaker = false, loudnessDb = null))
    }

    @Test
    fun `millibels become the factor a sample is multiplied by`() {
        assertEquals(1f, LoudnessRules.linear(null), 0f)
        assertEquals(1f, LoudnessRules.linear(0), 0f)
        // -6.02 dB halves the amplitude.
        assertEquals(0.5f, LoudnessRules.linear(-602), 0.001f)
    }

    // ---- LoudnessDispatch ----

    private var aActive = true
    private val published = mutableListOf<Pair<Float?, Float?>>()

    private fun dispatch(figures: Map<String, Double>) = LoudnessDispatch(
        scope = CoroutineScope(Dispatchers.Unconfined),
        aIsActive = { aActive },
        player = { null },
        loudnessDbFor = { figures[it] },
        enabled = { true },
        offForSpeaker = { false },
        publish = { gain, lufs -> published += gain to lufs },
    )

    @Test
    fun `the session's stage follows the sink role`() {
        val loudness = dispatch(emptyMap())
        assertSame(loudness.a, loudness.active())
        assertSame(loudness.b, loudness.spare())
        aActive = false
        assertSame(loudness.b, loudness.active())
        assertSame(loudness.a, loudness.spare())
    }

    @Test
    fun `both stages read the same per-track gain`() {
        val loudness = dispatch(mapOf("loud" to 6.02))
        assertEquals(0.5f, loudness.a.gainFor("loud"), 0.001f)
        assertEquals(0.5f, loudness.b.gainFor("loud"), 0.001f)
        assertEquals(1f, loudness.a.gainFor("unmeasured"), 0f)
    }

    @Test
    fun `the readout shows the gain and the figure, or nothing`() {
        val loudness = dispatch(mapOf("loud" to 6.5))
        loudness.publishFor("loud")
        loudness.publishFor("unmeasured")
        loudness.publishFor(" ")
        assertEquals(listOf(-6.5f to 6.5f, null to null, null to null), published)
    }
}
