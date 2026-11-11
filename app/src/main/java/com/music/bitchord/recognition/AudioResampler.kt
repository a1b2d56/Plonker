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

            // High-quality linear interpolation resampling
            val ratio = srcRate.toDouble() / outputSampleRate.toDouble()
            val outFrameCount = (frameCount / ratio).toInt()
            val outBytes = ByteArray(outFrameCount * 2)
            val outBuffer = ByteBuffer.wrap(outBytes).order(ByteOrder.LITTLE_ENDIAN)

            for (i in 0 until outFrameCount) {
                val srcPos = i * ratio
                val srcIndex = srcPos.toInt()
                val frac = srcPos - srcIndex

                val s1 = monoSamples[srcIndex.coerceIn(0, frameCount - 1)].toDouble()
                val s2 = monoSamples[(srcIndex + 1).coerceIn(0, frameCount - 1)].toDouble()
                val interpolated = (s1 + frac * (s2 - s1)).toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    .toShort()

                outBuffer.putShort(interpolated)
            }

            DecodedAudio(
                data = outBytes,
                channelCount = 1,
                sampleRate = outputSampleRate,
                pcmEncoding = decodedAudio.pcmEncoding
            )
        }
    }
}
