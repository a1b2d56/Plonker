package com.music.bitchord.data

import com.music.bitchord.data.settings.Reachability
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When a connected network counts as one the app can stream over. */
class ReachabilityTest {

    @Test
    fun `a network the system has checked is online without being asked`() {
        assertTrue(Reachability.online(internet = true, validated = true, captivePortal = false, reachable = null))
        assertFalse(Reachability.needsProbe(internet = true, validated = true, captivePortal = false))
    }

    @Test
    fun `a network whose Google check is blocked is online once the host answers`() {
        assertTrue(Reachability.needsProbe(internet = true, validated = false, captivePortal = false))
        assertTrue(Reachability.online(internet = true, validated = false, captivePortal = false, reachable = true))
    }

    @Test
    fun `it is not online before the answer, or when the answer is no`() {
        assertFalse(Reachability.online(internet = true, validated = false, captivePortal = false, reachable = null))
        assertFalse(Reachability.online(internet = true, validated = false, captivePortal = false, reachable = false))
    }

    @Test
    fun `a sign-in page is offline whatever answers behind it`() {
        assertFalse(Reachability.online(internet = true, validated = false, captivePortal = true, reachable = true))
        assertFalse(Reachability.needsProbe(internet = true, validated = false, captivePortal = true))
    }

    @Test
    fun `a network that offers no internet is never asked`() {
        assertFalse(Reachability.online(internet = false, validated = false, captivePortal = false, reachable = true))
        assertFalse(Reachability.needsProbe(internet = false, validated = false, captivePortal = false))
    }
}
