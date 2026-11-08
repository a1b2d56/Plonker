package com.music.bitchord.playback

import android.os.Bundle
import androidx.media3.common.MediaItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The version swap's fade must stay the equal-power, half-second cross it was
 * tuned as, and the synchronizer must do nothing at all with no player.
 */
class VersionAudioSynchronizerTest {

    private fun near(expected: Float, actual: Float, within: Float = 1e-4f) =
        assertTrue("expected $expected, was $actual", abs(expected - actual) <= within)

    @Test
    fun `the fade runs over 550ms and holds at its ends`() {
        assertEquals(550L, VersionSwapFade.FADE_MS)
        near(0f, VersionSwapFade.progress(0))
        near(0.5f, VersionSwapFade.progress(275))
        near(1f, VersionSwapFade.progress(550))
        near(1f, VersionSwapFade.progress(5_000))
        near(0f, VersionSwapFade.progress(-20))
    }

    @Test
    fun `the cut being left starts alone and the new one ends alone`() {
        val start = VersionSwapFade.gains(0f)
        near(0f, start.incoming)
        near(1f, start.outgoing)
        near(1f, start.trim)
        val end = VersionSwapFade.gains(1f)
        near(1f, end.incoming)
        near(0f, end.outgoing)
        near(1f, end.trim)
    }

    @Test
    fun `power is constant across the whole cross`() {
        for (step in 0..20) {
            val g = VersionSwapFade.gains(step / 20f)
            near(1f, g.incoming * g.incoming + g.outgoing * g.outgoing)
        }
    }

    @Test
    fun `the middle of the cross gets the blend headroom`() {
        val mid = VersionSwapFade.gains(0.5f)
        // Two players at -3dB each sum to sqrt(2) in amplitude for matched masters.
        near(1f / sqrt(sqrt(2f)), mid.trim, within = 1e-3f)
        assertTrue(mid.trim < 1f)
    }

    @Test
    fun `a measured offset never shifts before the start`() {
        assertEquals(12_400L, VersionSwapFade.shifted(10_000L, 2_400L))
        assertEquals(7_600L, VersionSwapFade.shifted(10_000L, -2_400L))
        assertEquals(0L, VersionSwapFade.shifted(1_000L, -2_400L))
    }

    @Test
    fun `with no player nothing is swapped, marked or handed over`() {
        val calls = mutableListOf<String>()
        val sync = VersionAudioSynchronizer(
            context = android.content.ContextWrapper(null),
            scope = CoroutineScope(Dispatchers.Unconfined),
            player = { null },
            spare = { null },
            transitioning = { false },
            loudness = LoudnessDispatch(
                scope = CoroutineScope(Dispatchers.Unconfined),
                aIsActive = { true },
                player = { null },
                loudnessDbFor = { null },
                enabled = { false },
                offForSpeaker = { false },
                publish = { _, _ -> },
            ),
            markSwapping = { calls += "mark $it" },
            refreshButtons = { calls += "refresh" },
            handOverDecks = { _, _ -> calls += "handover" },
            onBecameCurrent = { calls += "current" },
        )
        sync.smoothSwapCurrentTrackVersion(MediaItem.EMPTY)
        sync.revertCurrentToOriginal()
        sync.swapCurrentToVersion(Bundle())
        assertFalse(sync.active)
        assertEquals(emptyList<String>(), calls)
    }
}
