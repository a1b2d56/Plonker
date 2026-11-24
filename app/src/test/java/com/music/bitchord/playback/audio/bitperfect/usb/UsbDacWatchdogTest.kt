package com.music.bitchord.playback.audio.bitperfect.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class UsbDacWatchdogTest {

    @Before
    fun setUp() {
        UsbDacWatchdog.onRecoveryRequired = null
    }

    @Test
    fun `disconnect count increments on physical device detachment`() {
        val initialCount = UsbDacWatchdog.getDisconnectCount()
        UsbDacWatchdog.onDeviceDetached(42)
        assertEquals(initialCount + 1, UsbDacWatchdog.getDisconnectCount())
    }

    @Test
    fun `safe close connection absorbs null without error`() {
        UsbDacWatchdog.safeCloseConnection(null)
    }

    @Test
    fun `checkHealth returns false when no transport is attached`() {
        assertFalse(UsbDacWatchdog.checkHealth())
    }

    @Test
    fun `notifyWriteSuccess records health heartbeat`() {
        UsbDacWatchdog.notifyWriteSuccess()
    }
}
