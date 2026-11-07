package com.music.bitchord

import com.music.bitchord.playback.buildOrRelease
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BuildOrReleaseTest {

    private class Deck {
        var closed = false
    }

    @Test(timeout = 10_000)
    fun `a build that finishes after its caller was cancelled is released`() = runBlocking {
        val started = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val built = Deck()
        val released = mutableListOf<Deck>()

        val job = launch(Dispatchers.Default) {
            buildOrRelease(Dispatchers.IO, release = { deck: Deck -> deck.closed = true; released += deck }) {
                started.countDown()
                // Decoding: blocking, not cancellable part-way.
                finish.await(5, TimeUnit.SECONDS)
                built
            }
        }
        started.await()
        // The listener skips while the decks are still decoding.
        job.cancel()
        finish.countDown()
        job.join()

        assertTrue(built.closed)
        assertEquals(listOf(built), released)
    }

    @Test
    fun `a build the caller receives is handed back, not released`() = runBlocking {
        val deck = Deck()
        val result = buildOrRelease(Dispatchers.Default, release = { it: Deck -> it.closed = true }) { deck }
        assertSame(deck, result)
        assertFalse(deck.closed)
    }

    @Test
    fun `a build that makes nothing has nothing to release`() = runBlocking {
        var releases = 0
        val result = buildOrRelease<Deck>(Dispatchers.Default, release = { releases++ }) { null }
        assertEquals(null, result)
        assertEquals(0, releases)
    }
}
