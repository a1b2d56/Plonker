package com.music.bitchord.playback

import android.content.ContextWrapper
import android.media.AudioDeviceInfo
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import com.music.bitchord.data.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pipeline's bookkeeping between the two players' DSP stages, and the
 * format reading the output negotiation leans on.
 */
class AudioRoutingAndDspPipelineTest {

    private val spatialBefore = AppSettings.spatialAudio.value
    private val rebuilds = mutableListOf<Boolean>()

    private fun pipeline() = AudioRoutingAndDspPipeline(
        context = ContextWrapper(null),
        scope = CoroutineScope(Dispatchers.Unconfined),
        players = { emptyList() },
        transitioning = { false },
        rebuildPlayers = { rebuilds += it },
    )

    @After
    fun restore() {
        AppSettings.spatialAudio.value = spatialBefore
    }

    // ---- the decks and their roles ----

    @Test
    fun `deck A starts audible`() {
        val p = pipeline()
        assertTrue(p.aIsActive)
        assertSame(p.a.filter, p.activeFilter)
        assertSame(p.b.filter, p.spareFilter)
    }

    @Test
    fun `the filter roles trade places with the players and back`() {
        val p = pipeline()
        p.swapFilterRoles()
        assertFalse(p.aIsActive)
        assertSame(p.b.filter, p.activeFilter)
        assertSame(p.a.filter, p.spareFilter)
        p.swapFilterRoles()
        assertTrue(p.aIsActive)
    }

    @Test
    fun `a rebuild puts deck A back in front whichever was audible`() {
        val p = pipeline()
        p.swapFilterRoles()
        p.resetFilterRoles()
        assertTrue(p.aIsActive)
        assertSame(p.b.filter, p.spareFilter)
    }

    @Test
    fun `each deck has processors of its own`() {
        val p = pipeline()
        assertTrue(p.a.spatial !== p.b.spatial)
        assertTrue(p.a.equalizer !== p.b.equalizer)
        assertTrue(p.a.filter !== p.b.filter)
    }

    // ---- spatial audio ----

    @Test
    fun `spatial widening follows the setting on both decks`() {
        val p = pipeline()
        AppSettings.spatialAudio.value = true
        p.applySpatialAudioEnabled()
        assertTrue(p.a.spatial.enabled && p.b.spatial.enabled)
        AppSettings.spatialAudio.value = false
        p.applySpatialAudioEnabled()
        assertFalse(p.a.spatial.enabled || p.b.spatial.enabled)
    }

    @Test
    fun `a Dolby Atmos track turns widening off on both decks`() {
        val p = pipeline()
        AppSettings.spatialAudio.value = true
        p.activeTrackIsDolbyAtmos = true
        p.applySpatialAudioEnabled()
        assertFalse(p.a.spatial.enabled || p.b.spatial.enabled)
        p.activeTrackIsDolbyAtmos = false
        p.applySpatialAudioEnabled()
        assertTrue(p.a.spatial.enabled && p.b.spatial.enabled)
        assertEquals(emptyList<Boolean>(), rebuilds)
    }

    // ---- USB DAC detection ----

    @Test
    fun `USB devices, headsets and accessories are USB outputs`() {
        assertTrue(AudioRoutingAndDspPipeline.isUsbOutput(AudioDeviceInfo.TYPE_USB_DEVICE))
        assertTrue(AudioRoutingAndDspPipeline.isUsbOutput(AudioDeviceInfo.TYPE_USB_HEADSET))
        assertTrue(AudioRoutingAndDspPipeline.isUsbOutput(AudioDeviceInfo.TYPE_USB_ACCESSORY))
    }

    @Test
    fun `speakers, Bluetooth and wired headphones are not`() {
        assertFalse(AudioRoutingAndDspPipeline.isUsbOutput(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
        assertFalse(AudioRoutingAndDspPipeline.isUsbOutput(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))
        assertFalse(AudioRoutingAndDspPipeline.isUsbOutput(AudioDeviceInfo.TYPE_WIRED_HEADPHONES))
    }

    // ---- reading a format ----

    /** A STREAMINFO block behind a `fLaC` marker, as Media3 hands it over. */
    private fun flacInit(sampleRateByte10: Int, byte11: Int, byte12: Int, byte13: Int): ByteArray {
        val body = ByteArray(34)
        body[10] = sampleRateByte10.toByte()
        body[11] = byte11.toByte()
        body[12] = byte12.toByte()
        body[13] = byte13.toByte()
        return "fLaC".toByteArray(Charsets.US_ASCII) + byteArrayOf(0, 0, 0, 34) + body
    }

    private fun flac(init: ByteArray, pcmEncoding: Int = Format.NO_VALUE) = Format.Builder()
        .setSampleMimeType(MimeTypes.AUDIO_FLAC)
        .setInitializationData(listOf(init))
        .setPcmEncoding(pcmEncoding)
        .build()

    @Test
    fun `a FLAC in MP4 with no rate on its sample entry is read from STREAMINFO`() {
        // 44.1 kHz, stereo, 16-bit.
        val measured = flac(flacInit(0x0A, 0xC4, 0x42, 0xF0)).measure()
        assertEquals(44_100, measured.sampleRateHz)
        assertEquals(2, measured.channels)
        assertEquals(16, measured.bitDepth)
        assertEquals(1411, measured.pcmBitrateKbps)
    }

    @Test
    fun `a hi-res FLAC reads as hi-res`() {
        // 96 kHz, stereo, 24-bit.
        val measured = flac(flacInit(0x17, 0x70, 0x03, 0x70)).measure()
        assertEquals(96_000, measured.sampleRateHz)
        assertEquals(24, measured.bitDepth)
        assertEquals(4608, measured.pcmBitrateKbps)
    }

    @Test
    fun `the renderer's PCM depth wins, except a float carrier`() {
        val init = flacInit(0x0A, 0xC4, 0x42, 0xF0)
        assertEquals(24, flac(init, C.ENCODING_PCM_24BIT).measure().bitDepth)
        // Float says nothing about the bits poured into it: the file's own depth stands.
        assertEquals(16, flac(init, C.ENCODING_PCM_FLOAT).measure().bitDepth)
    }

    @Test
    fun `a lossy stream with no figures stays unknown rather than guessed`() {
        val opus = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_OPUS).build().measure()
        assertNull(opus.sampleRateHz)
        assertNull(opus.bitDepth)
        assertNull(opus.pcmBitrateKbps)
    }

    @Test
    fun `PCM encodings map to their depths`() {
        assertEquals(16, bitDepthOf(C.ENCODING_PCM_16BIT))
        assertEquals(24, bitDepthOf(C.ENCODING_PCM_24BIT))
        assertEquals(32, bitDepthOf(C.ENCODING_PCM_FLOAT))
        assertNull(bitDepthOf(C.ENCODING_INVALID))
    }
}
