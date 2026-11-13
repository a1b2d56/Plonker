package com.music.bitchord.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/** What reaches the fingerprint: the band below 8 kHz, and nothing folded into it from above. */
class AudioResamplerFilterTest {

    private fun tone(hz: Double, rate: Int, seconds: Double = 0.5, amplitude: Double = 16_000.0) =
        ShortArray((rate * seconds).toInt()) { (sin(2.0 * PI * hz * it / rate) * amplitude).toInt().toShort() }

    /** Level of the middle of [samples], clear of the filter's run-in at either end. */
    private fun rms(samples: ShortArray): Double {
        val body = samples.slice(samples.size / 4 until samples.size * 3 / 4)
        return sqrt(body.sumOf { it.toDouble() * it } / body.size)
    }

    private val full = 16_000.0 / sqrt(2.0)

    @Test
    fun `a tone above the output's range is removed, not folded down into it`() {
        // 10 kHz at 44.1 kHz would come out as a 6 kHz tone at full level.
        val folded = rms(AudioResampler.resampleMono(tone(10_000.0, 44_100), 44_100, 16_000))
        assertTrue("10 kHz came through at $folded", folded < full * 0.01)
        // 9 kHz at 48 kHz would come out at 7 kHz.
        val closer = rms(AudioResampler.resampleMono(tone(9_000.0, 48_000), 48_000, 16_000))
        assertTrue("9 kHz came through at $closer", closer < full * 0.02)
    }

    @Test
    fun `what the fingerprint listens to comes through at its own level`() {
        for (hz in listOf(440.0, 1_000.0, 3_000.0, 6_000.0)) {
            for (rate in listOf(44_100, 48_000)) {
                val kept = rms(AudioResampler.resampleMono(tone(hz, rate), rate, 16_000))
                assertEquals("$hz Hz from $rate", full, kept, full * 0.02)
            }
        }
    }

    @Test
    fun `a steady level stays that level from the first sample to the last`() {
        val out = AudioResampler.resampleMono(ShortArray(4_800) { 2_000 }, 48_000, 16_000)
        assertEquals(1_600, out.size)
        assertTrue(out.all { it == 2_000.toShort() })
    }

    @Test
    fun `going up in rate keeps the tone`() {
        val kept = rms(AudioResampler.resampleMono(tone(1_000.0, 8_000), 8_000, 16_000))
        assertEquals(full, kept, full * 0.02)
    }

    @Test
    fun `nothing in gives nothing out`() {
        assertEquals(0, AudioResampler.resampleMono(ShortArray(0), 44_100, 16_000).size)
    }
}
