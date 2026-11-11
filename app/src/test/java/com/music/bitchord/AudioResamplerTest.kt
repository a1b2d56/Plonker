package com.music.bitchord

import com.music.bitchord.recognition.AudioResampler
import com.music.bitchord.recognition.DecodedAudio
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioResamplerTest {
    @Test
    fun testResample44100To16000() = runBlocking {
        val sampleRate = 44100
        val targetRate = 16000
        val durationSec = 1.0
        val sampleCount = (sampleRate * durationSec).toInt()
        val buffer = ByteBuffer.allocate(sampleCount * 2).order(ByteOrder.LITTLE_ENDIAN)

        // Generate a 440Hz sine wave
        for (i in 0 until sampleCount) {
            val sample = (Math.sin(2.0 * Math.PI * 440.0 * i / sampleRate) * 16000).toInt().toShort()
            buffer.putShort(sample)
        }

        val input = DecodedAudio(
            data = buffer.array(),
            channelCount = 1,
            sampleRate = sampleRate,
            pcmEncoding = 2,
        )

        val result = AudioResampler.resample(input, targetRate)
        assertTrue(result.isSuccess)

        val output = result.getOrThrow()
        assertEquals(targetRate, output.sampleRate)
        assertEquals(1, output.channelCount)

        val expectedSamples = (sampleCount * (targetRate.toDouble() / sampleRate)).toInt()
        val actualSamples = output.data.size / 2
        assertEquals(expectedSamples, actualSamples)
    }

    @Test
    fun testStereoToMonoResample() = runBlocking {
        val sampleRate = 48000
        val targetRate = 16000
        val sampleCount = 4800 // 100ms
        val buffer = ByteBuffer.allocate(sampleCount * 2 * 2).order(ByteOrder.LITTLE_ENDIAN)

        for (i in 0 until sampleCount) {
            buffer.putShort(1000.toShort()) // Left
            buffer.putShort(3000.toShort()) // Right
        }

        val input = DecodedAudio(
            data = buffer.array(),
            channelCount = 2,
            sampleRate = sampleRate,
            pcmEncoding = 2,
        )

        val result = AudioResampler.resample(input, targetRate)
        assertTrue(result.isSuccess)

        val output = result.getOrThrow()
        assertEquals(targetRate, output.sampleRate)
        assertEquals(1, output.channelCount)

        // Downmixed value should be ~2000
        val outBuffer = ByteBuffer.wrap(output.data).order(ByteOrder.LITTLE_ENDIAN)
        val firstSample = outBuffer.short
        assertEquals(2000.toShort(), firstSample)
    }
}
