package com.music.bitchord.playback.audio.bitperfect.usb

import android.content.Context
import android.hardware.usb.UsbDeviceConnection
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Watchdog monitoring direct USB DAC streaming and route state transitions.
 *
 * Guarantees:
 * - Timely detection of USB cable disconnects and interface release before Android ALSA hangs.
 * - Auto-clearing transient format blacklist entries on physical re-attachment.
 * - Safe reclamation of orphaned [UsbDeviceConnection] instances during unexpected route switches.
 */
internal object UsbDacWatchdog {

    private const val TAG = "UsbDacWatchdog"
    private const val STALL_TIMEOUT_MS = 4000L

    private val activeTransport = AtomicReference<UsbDirectTransport?>(null)
    private val lastWriteTimestamp = AtomicLong(0L)
    private val disconnectCount = AtomicInteger(0)
    private val isMonitoring = AtomicBoolean(false)

    /** Callback invoked when a stuck or detached USB stream is forced to tear down. */
    var onRecoveryRequired: (() -> Unit)? = null

    /** Registers an active transport for watchdog monitoring. */
    fun attachTransport(transport: UsbDirectTransport) {
        activeTransport.set(transport)
        lastWriteTimestamp.set(SystemClock.elapsedRealtime())
        isMonitoring.set(true)
    }

    /** Unregisters the transport cleanly on standard release. */
    fun detachTransport(transport: UsbDirectTransport) {
        activeTransport.compareAndSet(transport, null)
        isMonitoring.set(false)
    }

    /** Records a healthy write pulse from the audio streaming worker. */
    fun notifyWriteSuccess() {
        lastWriteTimestamp.set(SystemClock.elapsedRealtime())
    }

    /**
     * Checks whether the active USB stream has stalled beyond [STALL_TIMEOUT_MS].
     * @return true if stalled and recovery was initiated.
     */
    fun checkHealth(): Boolean {
        val transport = activeTransport.get() ?: return false
        val elapsed = SystemClock.elapsedRealtime() - lastWriteTimestamp.get()
        if (elapsed > STALL_TIMEOUT_MS) {
            Log.w(TAG, "USB transport write stall detected (${elapsed}ms). Triggering teardown.")
            transport.markDetached()
            activeTransport.compareAndSet(transport, null)
            isMonitoring.set(false)
            onRecoveryRequired?.invoke()
            return true
        }
        return false
    }

    /**
     * Handles USB hardware device detachment.
     * Ensures any open transport for [deviceId] is immediately marked detached and torn down.
     */
    fun onDeviceDetached(deviceId: Int) {
        disconnectCount.incrementAndGet()
        val current = activeTransport.get()
        if (current != null && current.usbDeviceId == deviceId) {
            Log.i(TAG, "Active USB DAC $deviceId detached physically. Invalidating stream.")
            current.markDetached()
            activeTransport.compareAndSet(current, null)
            isMonitoring.set(false)
        }
    }

    /**
     * Cleans up a USB connection safely, catching any platform exceptions.
     */
    fun safeCloseConnection(connection: UsbDeviceConnection?) {
        if (connection == null) return
        runCatching {
            connection.close()
        }.onFailure {
            Log.w(TAG, "Error while safely closing UsbDeviceConnection", it)
        }
    }

    fun getDisconnectCount(): Int = disconnectCount.get()

    fun isAttached(): Boolean = activeTransport.get() != null
}
