package com.music.bitchord.playback.audio

import com.music.bitchord.playback.PeakLimiter
import com.music.bitchord.playback.SpatialAudioProcessor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * A gapless track boundary reaches the DSP as a `configure` with the audio
 * carrying straight on. Nothing a stage holds across samples should be
 * thrown away there, or the join is a step in the waveform. A real break,
 * a seek, comes through `flush` and still clears it.
 */
class GaplessBoundaryTest {

    private val sampleRate = 44_100

    private fun stereoSine(frames: Int, startFrame: Int): AudioBlock {
        val block = AudioBlock(channelCount = 2, capacityFrames = frames)
        for (f in 0 until frames) {
            val t = (startFrame + f).toDouble() / sampleRate
            block.samples[f * 2] = (0.6 * sin(2.0 * PI * 440.0 * t)).toFloat()
            block.samples[f * 2 + 1] = (0.4 * sin(2.0 * PI * 330.0 * t)).toFloat()
        }
        block.reset(frames)
        return block
    }

    private fun spatial(): SpatialAudioProcessor =
        SpatialAudioProcessor().apply {
            configure(sampleRate, 2)
            enabled = true
        }

    @Test
    fun `spatial carries its cross-feed across a gapless configure`() {
        val through = spatial()
        val rejoined = spatial()
        // The outgoing track, long enough to fill the 15ms delay line.
        through.process(stereoSine(2_000, 0))
        rejoined.process(stereoSine(2_000, 0))

        // The next track starts gaplessly in the same format.
        rejoined.configure(sampleRate, 2)

        val expected = stereoSine(512, 2_000).also(through::process)
        val actual = stereoSine(512, 2_000).also(rejoined::process)
        assertArrayEquals(expected.samples, actual.samples, 0f)
    }

    @Test
    fun `spatial still starts clean after a seek`() {
        val played = spatial()
        played.process(stereoSine(2_000, 0))
        played.flush()

        val fresh = spatial()
        val expected = stereoSine(512, 9_000).also(fresh::process)
        val actual = stereoSine(512, 9_000).also(played::process)
        assertArrayEquals(expected.samples, actual.samples, 0f)
    }

    @Test
    fun `a new sample rate still gets an empty delay line`() {
        val processor = spatial()
        processor.process(stereoSine(2_000, 0))
        processor.configure(48_000, 2)

        val fresh = SpatialAudioProcessor().apply {
            configure(48_000, 2)
            enabled = true
        }
        val expected = stereoSine(512, 0).also(fresh::process)
        val actual = stereoSine(512, 0).also(processor::process)
        assertArrayEquals(expected.samples, actual.samples, 0f)
    }

    @Test
    fun `the limiter holds its reduction across a gapless configure`() {
        val limiter = PeakLimiter().apply { configure(sampleRate) }
        // A hot passage: a peak well past the ceiling pulls the gain down.
        val held = limiter.gainFor(1.5f)
        assertTrue(held < 0.7f)

        limiter.configure(sampleRate)

        // The next sample is under the ceiling, so the limiter is releasing:
        // it moves a little way back up, not straight to unity.
        val next = limiter.gainFor(0.5f)
        assertTrue("released to $next in one sample", next < 0.7f)
        assertTrue(next >= held)
    }

    @Test
    fun `the limiter still lets go at once on a reset`() {
        val limiter = PeakLimiter().apply { configure(sampleRate) }
        limiter.gainFor(1.5f)
        limiter.reset()
        assertEquals(1f, limiter.gainFor(0.5f), 0f)
    }
}
