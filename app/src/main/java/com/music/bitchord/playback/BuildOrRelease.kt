package com.music.bitchord.playback

import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

/**
 * Runs [build] on [context] and returns what it built, the way [withContext]
 * would, except that a result the caller can no longer receive is released
 * rather than dropped.
 *
 * [withContext] checks for cancellation as it hands the result back. A build
 * that cannot be interrupted part-way (decoding, a native allocation) runs to
 * completion after its caller was cancelled, and then the result is thrown
 * away unseen. That's harmless for plain data. For anything holding native
 * memory or a codec it is a leak per cancellation, and cancellations come in
 * bursts: every skip in a run of skips is one. Here [release] gets that result
 * instead, before the cancellation carries on.
 */
suspend fun <T : Any> buildOrRelease(
    context: CoroutineContext,
    release: (T) -> Unit,
    build: () -> T?,
): T? {
    val built = AtomicReference<T?>(null)
    try {
        return withContext(context) { build().also { built.set(it) } }
    } catch (cancelled: CancellationException) {
        built.getAndSet(null)?.let(release)
        throw cancelled
    }
}
