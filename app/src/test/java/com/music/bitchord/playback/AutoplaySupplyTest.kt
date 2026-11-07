package com.music.bitchord.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * With no player yet (the service before its first track, or after it has
 * let go), every AutoPlay entry point has to be a quiet no-op: these are
 * called from the player's own listeners and the party's state collector,
 * which can fire around that edge.
 */
class AutoplaySupplyTest {

    private var refreshes = 0

    private fun supply(): AutoplaySupply {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val bounded = BoundedContextController(
            scope = scope,
            player = { null },
            transitioning = { false },
            casting = { false },
            refreshButtons = {},
            onContextStarting = {},
            onRestoring = {},
        )
        return AutoplaySupply(scope = scope, player = { null }, bounded = bounded, refreshButtons = { refreshes++ })
    }

    @Test
    fun `with no player every entry point does nothing`() {
        val autoplay = supply()
        autoplay.load()
        autoplay.reload()
        autoplay.refreshIfQueueEmpty()
        autoplay.stash()
        autoplay.restore()
        autoplay.clearStash()
        autoplay.resetForNewQueue()
        assertEquals(0, refreshes)
    }
}
