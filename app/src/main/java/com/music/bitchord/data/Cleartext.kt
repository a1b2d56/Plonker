package com.music.bitchord.data

import android.security.NetworkSecurityPolicy
import java.net.URI

/**
 * Whether this build will refuse to talk to an address.
 *
 * Release builds turn cleartext traffic off, so an `http://` server a
 * listener types in can never be reached: every request dies inside the
 * platform with an error that names no cause. Asked before the address is
 * saved, so the listener is told the one thing that fixes it.
 */
object Cleartext {
    fun blocked(url: String): Boolean {
        if (!url.trim().startsWith("http://", ignoreCase = true)) return false
        return runCatching {
            val host = URI(url.trim()).host ?: return false
            !NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted(host)
        }.getOrDefault(false)
    }

    const val MESSAGE = "This build only connects over https://. Use an https:// address."
}
