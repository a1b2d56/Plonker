package com.music.bitchord.playback

import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * With no album or playlist playing as a bounded window, the controller must
 * stay out of the way: the service's flat paths run as though it weren't there.
 */
class BoundedContextControllerTest {

    /** A player that fails the test if anything so much as reads it. */
    private val untouchable: ExoPlayer = Proxy.newProxyInstance(
        ExoPlayer::class.java.classLoader,
        arrayOf(ExoPlayer::class.java),
    ) { proxy, method, args ->
        when (method.name) {
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args!![0]
            "toString" -> "untouchable"
            else -> throw AssertionError("player.${method.name} called with no bounded context")
        }
    } as ExoPlayer

    private var refreshes = 0
    private var hooks = 0

    private fun idle() = BoundedContextController(
        scope = CoroutineScope(Dispatchers.Unconfined),
        player = { untouchable },
        transitioning = { false },
        casting = { false },
        refreshButtons = { refreshes++ },
        onContextStarting = { hooks++ },
        onRestoring = { hooks++ },
    )

    @Test
    fun `nothing is active and nothing is waiting`() {
        val bounded = idle()
        assertFalse(bounded.active)
        assertFalse(bounded.hasPendingEdits)
    }

    @Test
    fun `shuffle falls through to the flat queue's own path`() {
        assertFalse(idle().requestShuffleToggle())
        assertEquals(0, refreshes)
    }

    @Test
    fun `AutoPlay is not held back, and the player is not read to decide it`() {
        assertTrue(idle().boundedAutoplayEligible(untouchable))
    }

    @Test
    fun `the queue hooks and the player are left alone`() {
        val bounded = idle()
        bounded.applyDeferredBoundedQueueEdit()
        bounded.scheduleBoundedContextTopUp()
        bounded.syncBoundedContextCursor(untouchable, null)
        bounded.saveBoundedContextSnapshot(untouchable)
        bounded.rebuildBoundedContextAfterParty()
        bounded.forget()
        assertFalse(bounded.active)
        assertEquals(0, hooks)
        assertEquals(0, refreshes)
    }
}
