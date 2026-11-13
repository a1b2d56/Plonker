/**
 * vivimusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */
package com.music.bitchord.recognition

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Data class representing decoded audio data with its properties.
 */
data class DecodedAudio(
    val data: ByteArray,
    val channelCount: Int,
    val sampleRate: Int,
    val pcmEncoding: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as DecodedAudio
        return data.contentEquals(other.data) &&
                channelCount == other.channelCount &&
                sampleRate == other.sampleRate &&
                pcmEncoding == other.pcmEncoding
    }

    override fun hashCode(): Int {
        var result = data.contentHashCode()
        result = 31 * result + channelCount
        result = 31 * result + sampleRate
        result = 31 * result + pcmEncoding
        return result
    }
}

/**
 * High-performance, pure Kotlin audio resampler and channel converter.
 * Converts 16-bit PCM audio (mono/stereo) to 16kHz mono audio for Shazam fingerprinting.
 */
object AudioResampler {

    suspend fun resample(
        decodedAudio: DecodedAudio,
        outputSampleRate: Int
    ): Result<DecodedAudio> = withContext(Dispatchers.Default) {
        if (decodedAudio.sampleRate == outputSampleRate && decodedAudio.channelCount == 1) {
            return@withContext Result.success(decodedAudio)
        }

        runCatching {
            val srcRate = decodedAudio.sampleRate
            val channels = decodedAudio.channelCount.coerceAtLeast(1)
            val bytes = decodedAudio.data

            // Convert raw bytes to 16-bit mono short samples
            val shortCount = bytes.size / 2
            val frameCount = shortCount / channels
            if (frameCount == 0) {
                return@runCatching DecodedAudio(
                    data = ByteArray(0),
                    channelCount = 1,
                    sampleRate = outputSampleRate,
                    pcmEncoding = decodedAudio.pcmEncoding
                )
            }

            val monoSamples = ShortArray(frameCount)
            val byteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

            if (channels == 1) {
                for (i in 0 until frameCount) {
                    monoSamples[i] = byteBuffer.short
                }
            } else {
                for (i in 0 until frameCount) {
                    var sum = 0
                    for (c in 0 until channels) {
                        sum += byteBuffer.short.toInt()
                    }
                    monoSamples[i] = (sum / channels).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                }
            }

            if (srcRate == outputSampleRate) {
                val outBuffer = ByteBuffer.allocate(monoSamples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                for (sample in monoSamples) {
                    outBuffer.putShort(sample)
                }
                return@runCatching DecodedAudio(
                    data = outBuffer.array(),
                    channelCount = 1,
                    sampleRate = outputSampleRate,
                    pcmEncoding = decodedAudio.pcmEncoding
                )
            }

            val resampled = resampleMono(monoSamples, srcRate, outputSampleRate)
            val outBytes = ByteArray(resampled.size * 2)
            val outBuffer = ByteBuffer.wrap(outBytes).order(ByteOrder.LITTLE_ENDIAN)
            for (sample in resampled) outBuffer.putShort(sample)

            DecodedAudio(
                data = outBytes,
                channelCount = 1,
                sampleRate = outputSampleRate,
                pcmEncoding = decodedAudio.pcmEncoding
            )
        }
    }

    /**
     * Where the low-pass turns over, as a fraction of the output rate: 7.5 kHz
     * for the 16 kHz Shazam wants, just under its 8 kHz limit.
     */
    private const val CUTOFF_OF_OUTPUT_RATE = 7_500.0 / 16_000.0

    /** How far the filter reaches each side of an output sample, in zero crossings of its sinc. */
    private const val ZERO_CROSSINGS = 16

    /** Filter table entries per source sample. */
    private const val TABLE_STEPS = 64

    /**
     * Converts mono [samples] from [srcRate] to [outRate] through a
     * windowed-sinc low-pass, evaluated at each output sample's own position
     * so filtering and interpolation are one pass.
     *
     * The filter is the point. Straight interpolation keeps everything the
     * recording holds above half the output rate and folds it back down: at
     * 16 kHz out, a cymbal at 10 kHz lands at 6 kHz, inside the band the
     * fingerprint is taken from, as a tone the song never had. Bright
     * recordings matched worst for exactly that reason.
     */
    internal fun resampleMono(samples: ShortArray, srcRate: Int, outRate: Int): ShortArray {
        val frameCount = samples.size
        if (frameCount == 0 || srcRate <= 0 || outRate <= 0) return ShortArray(0)
        val ratio = srcRate.toDouble() / outRate.toDouble()
        val outFrameCount = (frameCount / ratio).toInt()

        // Cutoff in cycles per source sample. Going up in rate there is
        // nothing to remove, so it sits just under the source's own limit.
        val cutoff = minOf(outRate * CUTOFF_OF_OUTPUT_RATE, srcRate * 0.5 * 0.9375) / srcRate
        val halfWidth = ZERO_CROSSINGS / (2.0 * cutoff)

        // The filter's shape from its centre outwards: sinc under a Hann
        // window. Looked up rather than computed per tap, which is what keeps
        // twelve seconds of audio at a few million multiplies.
        val table = DoubleArray((halfWidth * TABLE_STEPS).toInt() + 2)
        for (n in table.indices) {
            val t = n.toDouble() / TABLE_STEPS
            if (t >= halfWidth) continue
            val x = Math.PI * 2.0 * cutoff * t
            val sinc = if (x < 1e-9) 1.0 else Math.sin(x) / x
            table[n] = sinc * (0.5 + 0.5 * Math.cos(Math.PI * t / halfWidth))
        }

        val out = ShortArray(outFrameCount)
        val last = frameCount - 1
        for (i in 0 until outFrameCount) {
            val centre = i * ratio
            val from = Math.ceil(centre - halfWidth).toInt()
            val to = Math.floor(centre + halfWidth).toInt()
            var sum = 0.0
            var weight = 0.0
            for (k in from..to) {
                val h = table[(Math.abs(k - centre) * TABLE_STEPS + 0.5).toInt()]
                // Past either end the edge sample is held, so a steady level
                // stays that level right up to the first and last sample.
                sum += h * samples[if (k < 0) 0 else if (k > last) last else k]
                weight += h
            }
            out[i] = Math.round(sum / weight)
                .coerceIn(Short.MIN_VALUE.toLong(), Short.MAX_VALUE.toLong())
                .toShort()
        }
        return out
    }
}
