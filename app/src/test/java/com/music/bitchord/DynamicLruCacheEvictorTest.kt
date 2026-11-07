package com.music.bitchord

import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheSpan
import com.music.bitchord.playback.DynamicLruCacheEvictor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class DynamicLruCacheEvictorTest {

    /**
     * Stands in for SimpleCache where the evictor can see it: holds the spans
     * it has, and on removeSpan drops one and reports it back, the way
     * SimpleCache calls `onSpanRemoved` from inside its own lock.
     */
    private class FakeCache {
        val live = HashSet<CacheSpan>()
        lateinit var evictor: DynamicLruCacheEvictor
        val cache: Cache = Proxy.newProxyInstance(
            Cache::class.java.classLoader,
            arrayOf(Cache::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "removeSpan" -> synchronized(proxy) {
                    val span = args!![0] as CacheSpan
                    if (live.remove(span)) evictor.onSpanRemoved(proxy as Cache, span)
                    null
                }
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args!![0]
                "toString" -> "FakeCache"
                else -> throw UnsupportedOperationException(method.name)
            }
        } as Cache

        /** A write committing a span, under the cache's lock as SimpleCache does it. */
        fun add(span: CacheSpan) = synchronized(cache) {
            live += span
            evictor.onSpanAdded(cache, span)
        }
    }

    private fun span(key: String, position: Long, length: Long, touched: Long) =
        CacheSpan(key, position, length, touched, null)

    @Test
    fun `a lowered limit evicts the oldest non-head spans first`() {
        val fake = FakeCache()
        val evictor = DynamicLruCacheEvictor(maxBytes = Long.MAX_VALUE, headBytes = 10, headBudgetBytes = 0)
        fake.evictor = evictor
        val old = span("a", 100, 50, touched = 1)
        val newer = span("b", 100, 50, touched = 2)
        fake.add(old)
        fake.add(newer)

        evictor.maxBytes = 60
        evictor.applyNow(fake.cache)

        assertEquals(setOf(newer), fake.live)
    }

    @Test
    fun `heads are held back until nothing else is left`() {
        val fake = FakeCache()
        val evictor = DynamicLruCacheEvictor(maxBytes = Long.MAX_VALUE, headBytes = 10, headBudgetBytes = 1_000)
        fake.evictor = evictor
        val head = span("a", 0, 50, touched = 1)
        val tail = span("a", 100, 50, touched = 2)
        fake.add(head)
        fake.add(tail)

        evictor.maxBytes = 60
        evictor.applyNow(fake.cache)
        assertEquals(setOf(head), fake.live)

        evictor.maxBytes = 0
        evictor.applyNow(fake.cache)
        assertTrue(fake.live.isEmpty())
    }

    @Test(timeout = 30_000)
    fun `a limit change while a stream is writing keeps the evictor consistent`() {
        val fake = FakeCache()
        val evictor = DynamicLruCacheEvictor(maxBytes = 4_000, headBytes = 10, headBudgetBytes = 2_000)
        fake.evictor = evictor
        val failure = AtomicReference<Throwable?>(null)
        val start = CountDownLatch(1)

        val writer = Thread {
            start.await()
            runCatching {
                for (i in 0 until 20_000) {
                    // Every fourth span opens a track, so the head budget is
                    // exercised as well as plain LRU.
                    fake.add(span("k$i", if (i % 4 == 0) 0 else 100, 25, touched = i.toLong()))
                }
            }.onFailure { failure.compareAndSet(null, it) }
        }
        val settings = Thread {
            start.await()
            runCatching {
                repeat(20_000) { i ->
                    evictor.maxBytes = if (i % 2 == 0) 500 else 4_000
                    evictor.applyNow(fake.cache)
                }
            }.onFailure { failure.compareAndSet(null, it) }
        }
        writer.start()
        settings.start()
        start.countDown()
        writer.join(TimeUnit.SECONDS.toMillis(25))
        settings.join(TimeUnit.SECONDS.toMillis(25))

        failure.get()?.let { throw AssertionError("evictor raced its own cache", it) }
        // Whatever survived, the evictor still accounts for every byte of it:
        // asked to empty the cache, it removes exactly what is there and stops.
        evictor.maxBytes = 0
        evictor.applyNow(fake.cache)
        assertTrue("left behind: ${fake.live.size} spans", fake.live.isEmpty())
    }
}
