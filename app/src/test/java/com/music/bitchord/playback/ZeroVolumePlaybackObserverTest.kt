package com.music.bitchord.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZeroVolumePlaybackObserverTest {

    @Test
    fun `auto-pauses when volume drops to 0 while playing`() {
        var paused = false
        var resumed = false
        var playing = true
        var enabled = true

        val observer = ZeroVolumePlaybackObserver(
            isPauseOnZeroVolumeEnabled = { enabled },
            isPlaying = { playing },
            onPausePlayback = { paused = true; playing = false },
            onResumePlayback = { resumed = true; playing = true },
            initialVolume = 10,
        )

        assertEquals(10, observer.lastKnownVolume)
        assertFalse(observer.pausedByZeroVolume)

        // Volume drops to 0
        observer.onVolumeChanged(currentVolume = 0, isMuted = false)

        assertTrue(paused)
        assertTrue(observer.pausedByZeroVolume)
        assertEquals(0, observer.lastKnownVolume)
    }

    @Test
    fun `auto-resumes when volume rises above 0 after zero volume auto-pause`() {
        var paused = false
        var resumed = false
        var playing = true
        var enabled = true

        val observer = ZeroVolumePlaybackObserver(
            isPauseOnZeroVolumeEnabled = { enabled },
            isPlaying = { playing },
            onPausePlayback = { paused = true; playing = false },
            onResumePlayback = { resumed = true; playing = true },
            initialVolume = 8,
        )

        // Volume drops to 0 -> auto-pause
        observer.onVolumeChanged(currentVolume = 0, isMuted = false)
        assertTrue(observer.pausedByZeroVolume)
        assertFalse(playing)

        // Volume rises to 5 -> auto-resume
        observer.onVolumeChanged(currentVolume = 5, isMuted = false)
        assertTrue(resumed)
        assertFalse(observer.pausedByZeroVolume)
        assertTrue(playing)
        assertEquals(5, observer.lastKnownVolume)
    }

    @Test
    fun `does not auto-pause if setting is disabled`() {
        var paused = false
        var resumed = false
        var playing = true
        var enabled = false

        val observer = ZeroVolumePlaybackObserver(
            isPauseOnZeroVolumeEnabled = { enabled },
            isPlaying = { playing },
            onPausePlayback = { paused = true },
            onResumePlayback = { resumed = true },
            initialVolume = 8,
        )

        observer.onVolumeChanged(currentVolume = 0, isMuted = false)
        assertFalse(paused)
        assertFalse(observer.pausedByZeroVolume)
    }

    @Test
    fun `manual action resets pausedByZeroVolume so later volume increases do not auto-resume`() {
        var resumed = false
        var playing = true
        var enabled = true

        val observer = ZeroVolumePlaybackObserver(
            isPauseOnZeroVolumeEnabled = { enabled },
            isPlaying = { playing },
            onPausePlayback = { playing = false },
            onResumePlayback = { resumed = true; playing = true },
            initialVolume = 8,
        )

        // Auto-pause at 0
        observer.onVolumeChanged(currentVolume = 0, isMuted = false)
        assertTrue(observer.pausedByZeroVolume)

        // User explicitly paused or skipped or interacted
        observer.onManualAction()
        assertFalse(observer.pausedByZeroVolume)

        // Volume rises -> should NOT resume because user took a manual action
        observer.onVolumeChanged(currentVolume = 6, isMuted = false)
        assertFalse(resumed)
    }

    @Test
    fun `shouldBlockPlayAction intercepts play when volume is 0 and enabled`() {
        var enabled = true
        val observer = ZeroVolumePlaybackObserver(
            isPauseOnZeroVolumeEnabled = { enabled },
            isPlaying = { false },
            onPausePlayback = {},
            onResumePlayback = {},
            initialVolume = 0,
        )

        assertTrue(observer.shouldBlockPlayAction())

        // Disabled -> should not block
        enabled = false
        assertFalse(observer.shouldBlockPlayAction())
    }

    @Test
    fun `auto-pause maintains pausedByZeroVolume even if onPausePlayback triggers onManualAction`() {
        var playing = true
        var observerRef: ZeroVolumePlaybackObserver? = null

        val observer = ZeroVolumePlaybackObserver(
            isPauseOnZeroVolumeEnabled = { true },
            isPlaying = { playing },
            onPausePlayback = {
                playing = false
                // Simulates SessionPlayer.pause() calling onUserIntent -> onManualAction()
                observerRef?.onManualAction()
            },
            onResumePlayback = { playing = true },
            initialVolume = 10,
        )
        observerRef = observer

        observer.onVolumeChanged(currentVolume = 0, isMuted = false)
        assertTrue(observer.pausedByZeroVolume)
        assertFalse(playing)

        // Resuming volume now still resumes playback
        observer.onVolumeChanged(currentVolume = 5, isMuted = false)
        assertTrue(playing)
        assertFalse(observer.pausedByZeroVolume)
    }
}
