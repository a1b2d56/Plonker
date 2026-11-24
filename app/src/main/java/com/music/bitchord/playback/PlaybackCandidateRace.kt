package com.music.bitchord.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.selects.select

/**
 * Races two candidate stream resolution paths concurrently.
 *
 * An empty (null) result is a miss; only a non-null playable candidate wins
 * and cancels the other path. The loser is cancelled in `finally` but not awaited:
 * a slow or blocking stream fetch that ignores cancellation will never hold a
 * finished winner back from starting playback immediately.
 */
internal suspend fun <T> racePlaybackCandidates(
    first: suspend () -> T?,
    second: suspend () -> T?,
): T? {
    val candidates = CoroutineScope(currentCoroutineContext().minusKey(Job) + SupervisorJob())
    val a = candidates.async { first() }
    val b = candidates.async { second() }
    return try {
        select {
            a.onAwait { it ?: b.await() }
            b.onAwait { it ?: a.await() }
        }
    } finally {
        candidates.cancel()
    }
}
