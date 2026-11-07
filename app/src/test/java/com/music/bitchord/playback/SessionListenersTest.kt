package com.music.bitchord.playback

import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class SessionListenersTest {

    /** An ExoPlayer that only records which listeners it holds. */
    private class FakePlayer(val name: String) {
        val events = mutableListOf<Player.Listener>()
        val analytics = mutableListOf<AnalyticsListener>()
        val player: ExoPlayer = Proxy.newProxyInstance(
            ExoPlayer::class.java.classLoader,
            arrayOf(ExoPlayer::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "addListener" -> { events += args!![0] as Player.Listener; null }
                "removeListener" -> { events -= args!![0] as Player.Listener; null }
                "addAnalyticsListener" -> { analytics += args!![0] as AnalyticsListener; null }
                "removeAnalyticsListener" -> { analytics -= args!![0] as AnalyticsListener; null }
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args!![0]
                "toString" -> name
                else -> throw UnsupportedOperationException(method.name)
            }
        } as ExoPlayer
    }

    private val events = object : Player.Listener {}
    private val analytics = object : AnalyticsListener {}

    @Test
    fun `the pair goes on together`() {
        val a = FakePlayer("a")
        val bindings = SessionListeners(events, analytics)
        bindings.attach(a.player)
        assertEquals(listOf(events), a.events)
        assertEquals(listOf(analytics), a.analytics)
        assertSame(a.player, bindings.bound)
    }

    @Test
    fun `a handoff leaves nothing on the outgoing player`() {
        val a = FakePlayer("a")
        val b = FakePlayer("b")
        val bindings = SessionListeners(events, analytics)
        bindings.attach(a.player)

        // As a crossfade does it: off the outgoing player, fields moved, onto the incoming.
        bindings.detach()
        assertNull(bindings.bound)
        bindings.attach(b.player)

        assertTrue(a.events.isEmpty() && a.analytics.isEmpty())
        assertEquals(listOf(events), b.events)
        assertEquals(listOf(analytics), b.analytics)
    }

    @Test
    fun `attaching elsewhere without a detach still moves the pair, not copies it`() {
        val a = FakePlayer("a")
        val b = FakePlayer("b")
        val bindings = SessionListeners(events, analytics)
        bindings.attach(a.player)
        bindings.attach(b.player)
        assertTrue(a.events.isEmpty() && a.analytics.isEmpty())
        assertEquals(1, b.events.size)
        assertEquals(1, b.analytics.size)
    }

    @Test
    fun `attaching twice to the same player registers once`() {
        val a = FakePlayer("a")
        val bindings = SessionListeners(events, analytics)
        bindings.attach(a.player)
        bindings.attach(a.player)
        assertEquals(1, a.events.size)
        assertEquals(1, a.analytics.size)
    }

    @Test
    fun `detaching with nothing bound does nothing`() {
        SessionListeners(events, analytics).detach()
    }
}
