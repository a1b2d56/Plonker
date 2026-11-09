package com.music.bitchord.playback

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.BandwidthMeter
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.music.bitchord.data.NerdStats
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.innertube.StreamResolver
import com.music.bitchord.data.listentogether.ListenTogether
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.data.sources.SourceResolver
import com.music.bitchord.data.sources.SourceStream
import com.music.bitchord.data.sources.StreamFormat
import com.music.bitchord.data.sources.TrackMatcher
import com.music.bitchord.download.Downloads
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * App-side gate for explicit quality retries.
 *
 * Automatic upgrades never pass through this object. Returning the remaining
 * wait rather than a Boolean gives the log enough information to distinguish
 * a deliberate throttle from a search which failed to start.
 */
internal class ManualUpgradeThrottle(private val cooldownMs: Long) {
    private var lastAcceptedAtMs: Long? = null

    @Synchronized
    fun tryAcquire(nowMs: Long): Long {
        val previous = lastAcceptedAtMs
        if (previous != null) {
            val remaining = cooldownMs - (nowMs - previous)
            if (remaining > 0) return remaining
        }
        lastAcceptedAtMs = nowMs
        return 0L
    }
}

/**
 * Media3's retry budget, with one thing added: a load error that cannot
 * succeed on a second attempt does not get one.
 *
 * There was no policy here at all, which meant
 * [DefaultLoadErrorHandlingPolicy] — three tries at a 1s/2s/3s backoff,
 * against *any* IOException. Stacked on top of this service's own
 * [MAX_RECOVERIES] counter and read-ahead's independent resolves, that is
 * where the "infinite loading" came from: the app logged
 * "leaving it alone" after its third attempt, and then Media3 quietly
 * started a fourth on its own schedule — visible in the report as a full
 * resolve walk beginning with no track selection before it, forty seconds
 * after the app had given up. Nothing in the log named it, because nothing
 * in the app had asked for it.
 *
 * Only [StreamResolver.PermanentlyUnplayableException] is refused, and it is
 * refused rather than delayed because the resolver has already established
 * the answer cannot change — it is the type it uses to say exactly that.
 * Everything else keeps the default behaviour, which is right: a shaped
 * response or a dropped connection is worth another go.
 */
internal class PermanentAwareLoadErrorPolicy : DefaultLoadErrorHandlingPolicy() {
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        if (StreamRecoveryRules.permanentReason(loadErrorInfo.exception) != null) return C.TIME_UNSET
        return super.getRetryDelayMsFor(loadErrorInfo)
    }
}

/**
 * The decisions [StreamRecoveryCoordinator] makes about a failed track, kept
 * apart from the player so they can be checked on their own.
 */
internal object StreamRecoveryRules {

    /** What to do with a track that has spent its attempts. */
    sealed interface Skip {
        /** The resolver ruled it out: move on, and it does not count against the run. */
        data class Ruled(val verdict: String) : Skip

        /** It never made a sound: move on, counting it toward [MAX_CONSECUTIVE_SKIPS]. */
        data object FailedToStart : Skip

        /** It died mid-song: stay, so the listener's song is not taken away. */
        data object Stay : Skip

        /** Too many in a row to be the tracks' fault: stop with the error showing. */
        data object TooManyInARow : Skip
    }

    /**
     * Whether a failed stream at [uri] goes back to YouTube's own audio: it was
     * on a higher-quality rendition or a substitute ([substituted]), and the
     * listener did not pick it from an addon in the first place. A track
     * queued from an addon's own results (`bitchord://source`) is that addon's
     * or nothing, since the YouTube copy under it is a recording they did not
     * ask for.
     */
    fun fallsBackToYouTube(uri: String, substituted: Boolean): Boolean {
        val authority = uri.substringAfter("://", missingDelimiterValue = "")
            .substringBefore('/').substringBefore('?').substringBefore('#')
        if (authority == "source") return false
        return PlaybackFallback.isAlternative(uri, substituted)
    }

    /**
     * Why [error] means "never", or null if it only means "not just now".
     *
     * Unwrapped by hand because the classification is the resolver's and the
     * exception has been through Media3's loader by the time it arrives:
     * `ExoPlaybackException` wrapping `Loader.UnexpectedLoaderException` wrapping
     * what the resolver actually threw. A shallow `is` check sees only the
     * outermost of those three.
     */
    fun permanentReason(error: Throwable?): String? {
        var cause = error
        var depth = 0
        while (cause != null && depth++ < PERMANENT_CAUSE_DEPTH) {
            if (cause is StreamResolver.PermanentlyUnplayableException) {
                return cause.message ?: "This track cannot be played"
            }
            cause = cause.cause?.takeIf { it !== cause }
        }
        return null
    }

    /** Whether recovery stops retrying: a verdict is final, otherwise the budget runs out. */
    fun givesUp(verdict: String?, attempts: Int): Boolean = verdict != null || attempts > MAX_RECOVERIES

    /** See [Skip]; [consecutiveSkips] is how many tracks in a row already failed to start. */
    fun skip(verdict: String?, neverStarted: Boolean, consecutiveSkips: Int): Skip = when {
        verdict != null -> Skip.Ruled(verdict)
        !neverStarted -> Skip.Stay
        consecutiveSkips >= MAX_CONSECUTIVE_SKIPS -> Skip.TooManyInARow
        else -> Skip.FailedToStart
    }

    /** How many times one track is picked up off the floor. */
    const val MAX_RECOVERIES = 2

    /**
     * How many tracks in a row may be skipped for a plain playback error
     * before the queue is left alone. Sized to walk a short run of broken
     * tracks without walking an entire queue that is only failing because
     * there is no connection behind it.
     */
    const val MAX_CONSECUTIVE_SKIPS = 4

    /**
     * How far into an exception's causes a resolver verdict is looked for.
     * Media3 wraps twice on this path and the coroutine machinery may add
     * one; nothing nests deeper.
     */
    const val PERMANENT_CAUSE_DEPTH = 8
}

/**
 * What happens when the playing stream lets the listener down, and the hunt
 * for a better one while it is still playing well.
 *
 * Recovery ([recoverFrom]) picks a track that died mid-read back up at the
 * same position: a missing download goes back to streaming, a manifest read as
 * audio is reopened with its type declared, a failed higher-quality source
 * falls back to YouTube's own audio, and anything else is retried a bounded
 * number of times before the queue moves past it.
 *
 * The second look ([lookForBetterCopy], [upgradeQualityNow]) finds a better
 * copy of a track that settled for less, proves it on a silent player, and
 * swaps it in under the music without cutting into a crossfade — see
 * [QualityUpgrade] for the bookkeeping behind it.
 *
 * [currentPlayer] and [crossfade] are read when needed, never captured: the
 * session moves between players at every crossfade. The service reads the
 * swap markers ([swappingMediaId], [swapCutAt], [audioFormatFor],
 * [retryingMediaId]) from its listeners to tell a swap or a retry from the
 * queue moving on.
 */
@UnstableApi
internal class StreamRecoveryCoordinator(
    private val context: Context,
    private val scope: CoroutineScope,
    private val currentPlayer: () -> ExoPlayer?,
    private val crossfade: () -> CrossfadeController?,
    private val mediaSourceFactory: () -> DefaultMediaSourceFactory,
    private val audibleMediaId: () -> String?,
    private val manualUpgradeThrottle: ManualUpgradeThrottle = ManualUpgradeThrottle(MANUAL_UPGRADE_COOLDOWN_MS),
) {
    /**
     * How many tracks in a row have been skipped for a plain playback error,
     * reset the moment anything actually plays — see [StreamRecoveryRules.MAX_CONSECUTIVE_SKIPS].
     */
    var consecutiveErrorSkips = 0

    /** The background hunt for a better copy of whatever is playing. */
    private var upgradeJob: Job? = null

    /** Which track [upgradeJob] is hunting for — see [lookForBetterCopy]. */
    private var upgradeFor: String? = null

    /**
     * Ends [upgradeJob] because the queue has moved on — unless it has already
     * found a stream and is proving it.
     *
     * By the audition everything expensive is spent: the catalogue walk, the
     * stream lookup, and the replacement's first megabytes on disk. [swapIn]
     * knows what to do when the listener skips out from under it — see the
     * `now == null` branch there, which parks the proof with
     * [QualityUpgrade.shelve] so coming back costs nothing — and cancelling the
     * coroutine is what stopped it ever reaching that. Measured on a skip 66ms
     * into an audition: the FLAC was found, the fetch was cancelled mid-flight,
     * and the track played its 320kbps copy for the rest of the session with a
     * plain "High quality" badge over it, because [QualityUpgrade.lookAgain]
     * records a search as answered the moment it has a candidate and only
     * [QualityUpgrade.shelve] takes that back.
     *
     * A job still searching has nothing yet worth keeping, and is still cut
     * short — that is the whole of what this used to do unconditionally.
     */
    fun cancelStaleUpgradeJob() {
        if (QualityUpgrade.isAuditioning(upgradeFor)) return
        upgradeJob?.cancel()
    }

    /**
     * How many times each track has been picked up off the floor, so a stream
     * that fails the same way every time stops rather than loops. Reset when
     * the queue genuinely moves on, not when a track merely re-prepares.
     */
    val recoveries = mutableMapOf<String, Int>()

    /**
     * The track [recoverFrom] is about to seek-and-prepare, so that the
     * transition its own retry may fire can be told from the queue moving on or
     * the listener asking again — see [onTrackBecameCurrent]. Cleared by the
     * transition it describes, the same way [swappingMediaId] is.
     */
    var retryingMediaId: String? = null

    /** Counts every byte a player reads, cache or network, progressive or DASH. */
    private class CountingBandwidthMeter(private val delegate: BandwidthMeter) : BandwidthMeter by delegate {
        val bytes = AtomicLong()

        private val counter = object : TransferListener {
            override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
                delegate.transferListener?.onTransferInitializing(source, dataSpec, isNetwork)
            }

            override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
                delegate.transferListener?.onTransferStart(source, dataSpec, isNetwork)
            }

            override fun onBytesTransferred(
                source: DataSource,
                dataSpec: DataSpec,
                isNetwork: Boolean,
                bytesTransferred: Int,
            ) {
                bytes.addAndGet(bytesTransferred.toLong())
                delegate.transferListener?.onBytesTransferred(source, dataSpec, isNetwork, bytesTransferred)
            }

            override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
                delegate.transferListener?.onTransferEnd(source, dataSpec, isNetwork)
            }
        }

        override fun getTransferListener(): TransferListener = counter
    }

    /**
     * Whether [error] was the connection's fault rather than the bytes': the
     * network failed or timed out, a URL had expired, or there was no
     * connection to begin with. Such a failure says nothing against what is
     * cached, so recovery keeps every complete entry — see [AudioCache.discard].
     */
    private fun isNetworkFault(error: PlaybackException): Boolean =
        error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
            AudioCache.isOffline()

    /**
     * Puts a track that died mid-read back on its feet.
     *
     * Two things get thrown away before trying again, because both have been
     * seen to be the actual fault and neither is visible from the exception:
     *
     *  - The cached bytes. An entry filled from two different files reads
     *    fine until playback reaches the seam and then throws forever, and no
     *    number of retries against the same entry will do anything else.
     *  - The choice of who serves the track. If the source that was picked is
     *    the one handing over something unreadable, resolving again from
     *    scratch is the only way to land anywhere else.
     *
     * The position is kept: this should look like a hiccup, not like the song
     * starting over.
     */
    fun recoverFrom(error: PlaybackException, player: ExoPlayer) {
        val item = player.currentMediaItem ?: return
        val mediaId = item.mediaId
        val uri = item.localConfiguration?.uri
        val position = player.currentPosition.coerceAtLeast(0L)
        val attempts = recoveries.getOrDefault(mediaId, 0) + 1
        recoveries[mediaId] = attempts
        TrackLog.w(
            "BitChord",
            "playback failed for $mediaId at ${position}ms (${error.errorCodeName}), attempt $attempts",
            error,
            about = mediaId,
        )
        // A local file that is not there is the one failure retrying cannot
        // touch, and the only one where the fix is to stop asking for the file.
        //
        // Everything below this point recovers a *stream*: it discards cached
        // bytes, releases the choice of who serves the track, and prepares the
        // same item again. Against `file:///…/Drake - Janice STFU.m4a` that is
        // all wasted — the uri is baked into the item already in the timeline,
        // so `prepare()` reopens the identical dead path and fails identically.
        // Observed as eight `ERROR_CODE_IO_FILE_NOT_FOUND`s in three seconds
        // against a download whose file had been deleted from a file manager,
        // ending in a track that simply refused to play.
        //
        // Rebuilding the item without its local uri is what turns that into a
        // stream, and dropping the record is what stops the next play walking
        // into the same hole. Deliberately ahead of the verdict and the attempt
        // budget below: this is not an attempt spent, it is a different source.
        if (error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND &&
            restreamMissingLocalFile(player, item, uri, position)
        ) {
            return
        }
        // A manifest read as audio is not a broken stream, and everything below
        // this line would treat it as one. Ahead of the verdict and the attempt
        // budget for the same reason as the local-file case above: this is not
        // an attempt spent on the same stream, it is the same stream opened
        // correctly for the first time.
        if (error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED &&
            replayAsManifest(player, item, uri, position)
        ) {
            return
        }
        // An addon/JioSaavn stream gets one chance. If it fails after selection,
        // retrying the ordinary item only lets the same deterministic lookup win
        // again. Rebuild it as an explicit YouTube request instead. This also
        // clears a DASH/HLS MIME left on an upgraded item, which otherwise makes
        // Media3 parse YouTube's WebM bytes as a manifest forever.
        if (fallbackFailedAlternativeToYouTube(item, uri, position, error)) return
        // Offline, and there is no file behind this track: every rung of the
        // ladder below is a network lookup that cannot answer. Skip past it cleanly
        // without exhausting consecutive retry budgets.
        if (AppSettings.meteredConnection.value == null && mediaId !in Downloads.saved.value) {
            TrackLog.d(
                "BitChord",
                "$mediaId has no downloaded copy while offline; moving down the queue",
                about = mediaId,
            )
            scope.launch(TrackLog.about(mediaId)) {
                withContext(Dispatchers.Main) {
                    skipPastUnplayable(mediaId, "offline — not downloaded")
                }
            }
            return
        }
        // Giving up on the *retry*, not on everything below it.
        //
        // A track that has exhausted its attempts is not finished with.
        // [recoveries] is cleared the moment any track becomes current, so the
        // listener who presses play again gets a fresh count — and used to get,
        // along with it, the exact URL that had just failed three times.
        // [StreamChoice] outlives this method by [StreamChoice.TTL_MS], and the
        // resolving factory reads it *before* it resolves anything, so the
        // replay failed instantly and in silence: no resolve logged, no lookup
        // attempted, and none of the refusals recorded below ever consulted,
        // because reaching them means getting as far as resolving. Fifteen
        // minutes of a track that cannot be played and does not even try, which
        // to the listener is a track that is permanently broken. Reported as
        // "sometimes songs don't play even if I've played it before", and the
        // 1.4 log of one shows it exactly — a selection, five seconds, a 404,
        // and not one resolver line in between.
        //
        // So everything from here to the discard runs either way, and only the
        // seek-and-prepare at the end is skipped.
        //
        // A verdict counts as exhausting the attempts immediately. The resolver
        // only throws [StreamResolver.PermanentlyUnplayableException] once it has
        // established that no client, no session and no extraction can serve the
        // track, so the two further attempts this would otherwise spend are two
        // more full walks for an answer already in hand — and in the report they
        // were exactly that, at roughly seventeen youtubei requests each.
        val verdict = StreamRecoveryRules.permanentReason(error)
        val givingUp = StreamRecoveryRules.givesUp(verdict, attempts)
        // A track that has never made a sound has *failed to start*, whatever
        // the error was, and the queue should move past it once the attempts are
        // spent — see [skipReason]. Read here rather than after the delay below,
        // because the seek-and-prepare of an unrelated recovery could move the
        // player on in between.
        val neverStarted = audibleMediaId() != mediaId
        if (verdict != null) {
            TrackLog.w("BitChord", "$mediaId cannot be played: $verdict", about = mediaId)
        } else if (givingUp) {
            TrackLog.w("BitChord", "$mediaId has failed $attempts times; leaving it alone", about = mediaId)
        }
        // The upgraded rendition goes with the cache entry it lived in, so the
        // marker on the URI would otherwise point at nothing.
        QualityUpgrade.forget(mediaId)
        // A track that died on an upgraded URI died on the *upgrade*, and it
        // must not be offered that same swap again the moment it recovers.
        // Left unrecorded, the second look starts over on the retry, finds the
        // same FLAC at the same dead URL, cuts the audio for it again, and
        // fails again — twice more before [StreamRecoveryRules.MAX_RECOVERIES] stops it. Observed
        // on a Tidal URL answering ERROR_CODE_IO_BAD_HTTP_STATUS.
        //
        // This is also the other half of what [StreamChoice.isSubstitute]
        // cannot see below: a track that started on YouTube and swapped to a
        // module mid-song (see [QualityUpgrade]) was remembered by
        // [StreamChoice] as an *un*substituted YouTube choice — the swap
        // never went through [StreamChoice.remember] at all — so a die on
        // this URI is invisible to the `isSubstitute` check even though it is
        // exactly the same failure: a module handed over a URL it cannot
        // actually serve. Left as it was, [resolveWithModulePriority] — the
        // only place that reads [StreamChoice.substitutesRefused] — kept
        // racing the same broken module on every single retry, because
        // nothing had ever told it to stop. Observed on a Tidal DASH manifest
        // that came back malformed 23 times in two minutes, once for every
        // tap of the play button.
        val diedOnModuleStream = uri?.let(QualityUpgrade::cacheTag)
            ?.let { it == "hifi" || it.startsWith("hifi-") } == true
        if (diedOnModuleStream) {
            QualityUpgrade.refuseUpgrades(mediaId)
        }
        // Whatever failed took its claimed format with it. The stream that
        // recovers is a different one and has not promised anything yet, so
        // leaving the old claim behind is how a badge earned by a FLAC ends up
        // sitting over the Opus that replaced it.
        NerdStats.clearDeclared(mediaId)
        // A track that died on a substituted stream died on the *substitution*,
        // and the retry must not be free to make the same one again. The lookup
        // behind it is deterministic and, by the second attempt, cached — so it
        // wins the race against YouTube by the same margin it won it the first
        // time and hands back the identical dead URL, until [StreamRecoveryRules.MAX_RECOVERIES]
        // stops trying. That is a track that never plays at all while a working
        // YouTube URL sits in [StreamResolver]'s cache, resolved and unused.
        // The same reasoning as [QualityUpgrade.refuseUpgrades] above, for the
        // substitution that happens *before* the first note rather than after.
        // Read before the forget below, which is what clears the evidence.
        uri?.getQueryParameter("v")
            ?.takeIf { diedOnModuleStream || StreamChoice.isSubstitute(it) }
            ?.let { videoId ->
                StreamChoice.refuseSubstitutes(videoId)
                TrackLog.w(
                    "BitChord",
                    "$videoId broke on a substituted stream; YouTube serves it for now",
                    about = mediaId,
                )
                // And no swapping back to it mid-song either: the second look asks
                // the same catalogues the same question and would cut the audio that
                // just recovered to land on the same refusal.
                QualityUpgrade.refuseUpgrades(videoId)
            }
        uri?.getQueryParameter("v")?.let(StreamChoice::forget)
        // Read now, not after the delay: it describes the failure, and the
        // connection may be back by then.
        val networkFault = isNetworkFault(error)
        scope.launch(TrackLog.about(mediaId)) {
            // Long enough for the released source to let go of the cache keys
            // about to be removed, short enough to read as a stutter.
            delay(RECOVERY_DELAY_MS)
            uri?.let { withContext(Dispatchers.IO) { AudioCache.discard(it, keepComplete = networkFault) } }
            // The bytes go even when nothing is going to be prepared after
            // them. A half-filled entry whose owner has just been forgotten is
            // the seam this file's [StreamChoice] note is about: the next play
            // resolves freely, lands on a different source, and streams it into
            // the middle of the last one. Releasing the choice without dropping
            // the bytes would trade one stuck track for a corrupt one.
            if (givingUp) {
                // A track nobody can play must not leave the player parked in
                // IDLE on it. Nothing else in this service calls prepare() again,
                // so before this the queue simply stopped: the notification kept
                // showing the song, the play button kept doing nothing, and from
                // the outside that is indistinguishable from a hung app — which
                // is what the report describes and what "it was stuck on my
                // phone too" means. Moving on is the only honest answer.
                skipReason(verdict, error, neverStarted, attempts)?.let { reason ->
                    withContext(Dispatchers.Main) { skipPastUnplayable(mediaId, reason) }
                }
                return@launch
            }
            withContext(Dispatchers.Main) {
                val player = currentPlayer() ?: return@withContext
                if (player.currentMediaItem?.mediaId != mediaId) return@withContext
                TrackLog.d("BitChord", "retrying $mediaId from ${position}ms")
                // Claimed before the seek, so the transition it may fire is
                // recognised as this retry rather than read as a fresh start and
                // handed a fresh attempt budget.
                retryingMediaId = mediaId
                player.seekTo(player.currentMediaItemIndex, position)
                player.prepare()
            }
        }
    }

    /**
     * Replaces a failed addon/JioSaavn/quality-upgrade rendition with YouTube
     * audio and never offers the failed source again during this playback.
     */
    private fun fallbackFailedAlternativeToYouTube(
        item: MediaItem,
        uri: Uri?,
        position: Long,
        error: PlaybackException,
    ): Boolean {
        val playbackUri = uri ?: return false
        // A track the listener picked from an addon is that addon's or nothing:
        // a YouTube Opus copy under it is a different, lesser recording they
        // did not ask for. Let the ordinary retry/skip path deal with it.
        val videoId = playbackUri.getQueryParameter("v")
        val substituted = videoId?.let(StreamChoice::isSubstitute) == true
        if (!StreamRecoveryRules.fallsBackToYouTube(playbackUri.toString(), substituted)) return false
        return replaceWithYouTube(
            item = item,
            playbackUri = playbackUri,
            position = position,
            keepComplete = isNetworkFault(error),
            why = "failed on a higher-quality source",
        )
    }

    /**
     * Puts [item] back on YouTube's own audio at [position], and never offers
     * the source it was on again during this playback. Shared by a stream that
     * failed and one that opened fine and turned out to be the wrong length —
     * see [checkSubstituteLength].
     *
     * @param keepComplete see [AudioCache.discard]: true only when the fault
     *   was the network's, so the bytes on disk are not to blame.
     */
    fun replaceWithYouTube(
        item: MediaItem,
        playbackUri: Uri,
        position: Long,
        keepComplete: Boolean,
        why: String,
    ): Boolean {
        val videoId = playbackUri.getQueryParameter("v")
        val fallback = item.toYouTubeFallbackMediaItem() ?: return false
        val mediaId = item.mediaId

        QualityUpgrade.forget(mediaId)
        QualityUpgrade.refuseUpgrades(mediaId)
        videoId?.let {
            StreamChoice.refuseSubstitutes(it)
            StreamChoice.forget(it)
        }
        NerdStats.clearDeclared(mediaId)
        recoveries.remove(mediaId)
        TrackLog.w(
            "BitChord",
            "$mediaId $why; falling back to YouTube audio",
            about = mediaId,
        )

        scope.launch(TrackLog.about(mediaId)) {
            delay(RECOVERY_DELAY_MS)
            withContext(Dispatchers.IO) { AudioCache.discard(playbackUri, keepComplete = keepComplete) }
            withContext(Dispatchers.Main) {
                val live = currentPlayer() ?: return@withContext
                if (live.currentMediaItem?.mediaId != mediaId) return@withContext
                swappingMediaId = mediaId
                live.replaceMediaItem(live.currentMediaItemIndex, fallback)
                live.seekTo(live.currentMediaItemIndex, position)
                live.prepare()
            }
        }
        return true
    }

    /**
     * Swaps a track whose downloaded file has gone missing back onto a stream,
     * in place and at the same position.
     *
     * The file was downloaded and then deleted from under the app — a file
     * manager, a cleaner, a wiped SD card — leaving [Downloads]' record pointing
     * at nothing. [Song.toMediaItem] checks that record before it builds an
     * item, but only at build time: an item already sitting in the timeline was
     * built when the file was still there can retain that stale URI while it
     * waits in the live queue. This is the other end of that, and the only one
     * that can see the file is gone rather than guess.
     *
     * The record goes first, then the item is rebuilt from its own metadata with
     * the local uri stripped, which sends [Song.toMediaItem] down its streaming
     * branch. Position is kept, so this reads as the hiccup [recoverFrom] is
     * written around rather than the song starting over.
     *
     * @return false when this is not that situation and the caller should carry
     *   on with its ordinary stream recovery — including the case where stripping
     *   the local uri changes nothing, which is a device-library track whose
     *   mediaId *is* the missing file and for which there is no stream to fall
     *   back to. Returning true there would be a swap that fixes nothing, on a
     *   loop.
     */
    private fun restreamMissingLocalFile(
        player: ExoPlayer,
        item: MediaItem,
        uri: Uri?,
        position: Long,
    ): Boolean {
        val scheme = uri?.scheme
        if (scheme != "file" && scheme != "content") return false
        val mediaId = item.mediaId

        Downloads.forgetMissing(mediaId)
        val restreamed = item.toSong().copy(localUri = null, localPath = null).toMediaItem()
        if (restreamed.localConfiguration?.uri == uri) {
            TrackLog.w(
                "BitChord",
                "$mediaId is a local file that is gone and has no stream behind it",
                about = mediaId,
            )
            return false
        }

        TrackLog.w(
            "BitChord",
            "$mediaId was downloaded but $uri is gone; streaming it instead",
            about = mediaId,
        )
        // Not claimed as [retryingMediaId], unlike the seek-and-prepare retry
        // below: that flag exists to stop a retry against the *same* stream
        // refilling its own attempt budget, and this is a different source
        // entirely. Letting the transition clear the count is the right answer
        // here — a stream that has never been tried deserves the full budget.
        recoveries.remove(mediaId)
        player.replaceMediaItem(player.currentMediaItemIndex, restreamed)
        player.seekTo(player.currentMediaItemIndex, position)
        player.prepare()
        return true
    }

    /**
     * Reopens a track whose stream turned out to be a manifest, with the type
     * declared this time.
     *
     * The net under everything else in this file that tries not to reach a
     * manifest through a progressive source — [StreamContainer] has the full
     * account of why one cannot be played that way and how it happens anyway.
     * The live path now declines to substitute one at all, but that is only the
     * `bitchord://watch?v=…` half: a track queued from a module's own search
     * results plays through `bitchord://source?…`, is resolved by that module
     * and by nothing else, and has no YouTube copy to start on. For those there
     * is no choice to make in advance, and this is the only place that can see
     * what actually came back.
     *
     * What makes it safe to act on is that the URL is not in doubt. The
     * resolving data source records what it served ([StreamContainer.served]),
     * so this is not an inference from an error code — the error only says
     * "nothing could read these bytes", and the record says the bytes were an
     * `.mpd`. Declaring the type is then most of the fix: same resolved URL,
     * same position, a `DashMediaSource` instead of a progressive one. The
     * virtual playback URI must also differ, because Media3 otherwise updates
     * the existing progressive source in place and never asks
     * [DefaultMediaSourceFactory] to build the DASH source.
     *
     * The cached bytes go first. They are the manifest, written under the
     * track's ordinary key by the read that failed, and leaving them there
     * invites the segments that follow to be appended to an index — the seam
     * this file's [StreamChoice] note is about, reached from a new direction.
     * [StreamChoice] itself is deliberately left alone: the whole point is to
     * come back to the *same* stream, and forgetting the pin would send the
     * reopen off to race for a different one.
     *
     * @return false when this is not that situation — no record, nothing
     *   manifest-shaped, or an item that already declares its type and so has
     *   failed for some other reason — and the caller should carry on with its
     *   ordinary stream recovery.
     */
    private fun replayAsManifest(
        player: ExoPlayer,
        item: MediaItem,
        uri: Uri?,
        position: Long,
    ): Boolean {
        val mediaId = item.mediaId
        // Already declared, and still unreadable: this is a real failure and
        // re-preparing the identical item would only spend the budget on it.
        if (item.localConfiguration?.mimeType != null) return false
        val mime = StreamContainer.manifestServing(mediaId) ?: return false
        val playbackUri = uri ?: return false
        val reopenedUri = StreamContainer.markedForManifestReopen(playbackUri.toString(), mime)
        if (reopenedUri == playbackUri.toString()) return false

        TrackLog.w(
            "BitChord",
            "$mediaId was served a manifest through a progressive source; reopening it as $mime",
            about = mediaId,
        )
        // Not claimed as [retryingMediaId], on the same reasoning as
        // [restreamMissingLocalFile]: that flag stops a retry against the same
        // stream refilling its own budget, and this is the first attempt this
        // stream has had that could possibly work.
        recoveries.remove(mediaId)
        val declared = item.buildUpon()
            // MIME selects DashMediaSource/HlsMediaSource; the marker makes
            // Media3 actually rebuild rather than update ProgressiveMediaSource.
            .setUri(reopenedUri)
            .setMimeType(mime)
            .build()
        scope.launch(TrackLog.about(mediaId)) {
            withContext(Dispatchers.IO) { AudioCache.discard(playbackUri) }
            withContext(Dispatchers.Main) {
                val live = currentPlayer() ?: return@withContext
                // The queue can move while the discard runs, and replacing the
                // current item then would rewrite whatever the listener skipped
                // to instead.
                if (live.currentMediaItem?.mediaId != mediaId) return@withContext
                live.replaceMediaItem(live.currentMediaItemIndex, declared)
                live.seekTo(live.currentMediaItemIndex, position)
                live.prepare()
            }
        }
        return true
    }

    /**
     * Why a track that has run out of attempts should be left behind, or null
     * to park on it as the queue used to.
     *
     * Two things get past here.
     *
     * A [verdict] always does, unconditionally and exactly as it did before this
     * function existed: the resolver has established the track cannot be served
     * by anyone, and a playlist with a run of age-gated or region-locked tracks
     * in it should walk straight through them.
     *
     * Everything else gets past only when the track never made a sound. The
     * error itself is not consulted — a 403, a dead socket, a codec that will
     * not initialise and a cache entry that will not open are all, from the
     * listener's side, the same event: they pressed play and nothing happened.
     * Waiting for a *classification* of that is what left the queue parked on
     * tracks whose only sin was an error nobody had taught the service to name.
     * What is consulted is [neverStarted], because the one failure that must not
     * move the queue is the one in the middle of a song someone is listening to:
     * the attempts are spent, the recovery has already put them back where they
     * were twice, and jumping to the next track at that point would take the
     * song away rather than rescue it.
     *
     * [StreamRecoveryRules.MAX_CONSECUTIVE_SKIPS] is the floor under the rest. Nothing here can tell
     * a broken track from a broken network, and offline every track in the queue
     * fails identically — so without a limit a dropped connection would quietly
     * walk to the end of the queue, spending a full attempt budget per track,
     * and hand back a queue the listener no longer recognises. Stopping instead
     * leaves the error on the player, which is what puts a message on screen,
     * and leaves the queue where it was. The count only resets when something
     * actually plays, so a working track anywhere in a bad patch restores the
     * full allowance. Verdicts are exempt: they are a statement about one track
     * rather than a guess, and their skipping is behaviour that already works.
     */
    private fun skipReason(
        verdict: String?,
        error: PlaybackException,
        neverStarted: Boolean,
        attempts: Int,
    ): String? = when (val skip = StreamRecoveryRules.skip(verdict, neverStarted, consecutiveErrorSkips)) {
        is StreamRecoveryRules.Skip.Ruled -> skip.verdict
        StreamRecoveryRules.Skip.Stay -> null
        StreamRecoveryRules.Skip.TooManyInARow -> {
            TrackLog.w(
                "BitChord",
                "${StreamRecoveryRules.MAX_CONSECUTIVE_SKIPS} tracks in a row failed to start; " +
                    "this is the queue or the connection, not the track — stopping here",
            )
            null
        }
        StreamRecoveryRules.Skip.FailedToStart -> {
            consecutiveErrorSkips++
            "failed to start after $attempts attempts (${error.errorCodeName})"
        }
    }

    /**
     * Leave a track the resolver has ruled out and carry on down the queue.
     *
     * The item is left in place rather than removed: the listener queued it, and
     * the reason it cannot be played is usually temporary in a way this service
     * cannot see the end of — signing in clears an age gate, travelling clears a
     * region block, and a stream that 403s now resolves again in an hour.
     * Removing it would quietly rewrite a queue on the strength of a ten-minute
     * verdict.
     *
     * Whether a failure has earned this at all is [skipReason]'s decision, not
     * this method's; by here the answer is yes.
     *
     * With nothing after it there is nowhere to go, and stopping is then the
     * correct end state rather than a failure to recover: the error stays on the
     * player, which is what puts a message in front of the listener — see
     * `rememberPlayerState` in
     * [PlayerConnection][com.music.bitchord.playback.PlayerConnection].
     */
    private fun skipPastUnplayable(mediaId: String, reason: String) {
        val exoPlayer = currentPlayer() ?: return
        if (exoPlayer.currentMediaItem?.mediaId != mediaId) return
        if (!exoPlayer.hasNextMediaItem()) {
            TrackLog.w("BitChord", "$reason — and nothing after it in the queue", about = mediaId)
            return
        }
        TrackLog.w("BitChord", "$reason — skipping to the next track", about = mediaId)
        exoPlayer.seekToNextMediaItem()
        // No play() here: an error does not clear playWhenReady, so prepare()
        // resumes exactly as far as the listener had asked for. Calling play()
        // would un-pause a queue they had paused.
        exoPlayer.prepare()
    }

    /**
     * The track whose item this service is about to replace under it, so that
     * [Player.Listener.onMediaItemTransition] can tell a quality swap from the
     * queue actually moving on. Cleared by the transition it describes.
     */
    var swappingMediaId: String? = null

    /**
     * When the audio was last cut for a quality swap, so the analytics listener
     * can say how long it stayed cut. Null except across a swap.
     */
    var swapCutAt: Long? = null

    /**
     * The track [ExoPlayer.getAudioFormat] is currently describing.
     *
     * `audioFormat` is a property of the *renderer*, not of the queue item, and
     * it keeps naming the outgoing track's codec until the renderer has read a
     * sample of the incoming one. Anything that asks "what is playing right
     * now" in the moments after a transition is therefore told about the track
     * before it, and [adoptCachedTrack] is asked exactly there — a queue
     * advance is one of the places [lookForBetterCopy] runs from.
     *
     * Observed: 'Harleys In Hawaii' came up fifteen milliseconds after the
     * queue moved onto it, twenty seconds after the previous track had been
     * upgraded to FLAC. The renderer still said `audio/flac`, so a WebM Opus
     * stream — verified by the `1A 45 DF A3` on its cache entry — was written
     * off as "already lossless from cache" and, because that verdict is
     * recorded once and for good, never offered an upgrade again for the rest
     * of the session.
     */
    var audioFormatFor: String? = null

    /**
     * Starts the second look for the playing track, if it settled for less
     * than was asked for — see [QualityUpgrade].
     *
     * Runs at most once per track: [QualityUpgrade.lookAgain] drops the track
     * from its pending set whatever the answer, so the repeated calls this
     * gets cost nothing after the first. It needs to be called from several
     * places for that reason — a track becomes eligible at a different moment
     * depending on how it was reached. Called only from
     * `onIsPlayingChanged`, it fired for the first track of a session and for
     * nothing after it: the queue advancing while already playing is not a
     * change in `isPlaying`, so every track but the first kept a lookup that
     * had already found its FLAC and was never asked for it.
     *
     * Eligibility has two sources, because being resolved and being played are
     * not the same event. A track the resolver saw is already marked; a track
     * served from the disk cache was never resolved at all and is judged here
     * instead — see [adoptCachedTrack] and [QualityUpgrade.adoptUnresolved].
     */
    fun lookForBetterCopy(player: ExoPlayer) {
        val item = player.currentMediaItem ?: return
        val mediaId = item.mediaId
        val uri = item.localConfiguration?.uri
        val alreadyPending = QualityUpgrade.isPending(mediaId)
        val shelved = QualityUpgrade.shelvedFor(mediaId)
        if (shelved == null && !alreadyPending && !QualityUpgrade.couldStillUpgrade(mediaId, uri)) return
        if (upgradeJob?.isActive == true) {
            // Already hunting for this track. One left over from a track the
            // queue has moved past is a different matter: it can only come
            // back with an answer about a song nobody is listening to, and
            // until it does it holds the slot the current track needs.
            //
            // Unless it is proving a stream it already found, which
            // [cancelStaleUpgradeJob] leaves alone — the fields below are then
            // handed to this track while that one runs on unreferenced, which
            // is all it needs: it carries its own media id and ends itself by
            // shelving what it proved.
            if (upgradeFor == mediaId) return
            cancelStaleUpgradeJob()
        }
        upgradeFor = mediaId
        if (alreadyPending) {
            TrackLog.d("BitChord", "looking again for a better copy of $mediaId", about = mediaId)
        }
        upgradeJob = scope.launch(TrackLog.about(mediaId)) {
            // A previous visit to this track already did all the expensive
            // parts and lost the swap to a skip. Nothing about the answer has
            // gone stale — the stream is still parked and its bytes are still
            // on disk — so this goes straight to the swap and skips the ten
            // seconds of catalogue searching it would otherwise repeat.
            if (shelved != null) {
                TrackLog.d("BitChord", "re-offering the upgrade already proved for $mediaId")
                NerdStats.onLosslessRaceStart(mediaId)
                try {
                    swapIn(mediaId, shelved)
                } finally {
                    NerdStats.onLosslessRaceEnd(mediaId)
                }
                return@launch
            }
            // The runtime the decoder reports is the only measured evidence
            // about what is playing, and everything downstream weighs
            // candidates against it — so it is worth a short wait rather than
            // a null. It is genuinely not known yet at some of the moments
            // this is called from: a queue advance runs its transition before
            // the item it moved onto has finished preparing.
            val playingSeconds = withTimeoutOrNull(DURATION_SETTLE_MS) {
                while (true) {
                    val ms = withContext(Dispatchers.Main) {
                        currentPlayer()
                            ?.takeIf { it.currentMediaItem?.mediaId == mediaId }
                            ?.duration
                            ?: 0L
                    }
                    // Rounded, not truncated: catalogue runtimes are whole
                    // seconds, and truncating a 225.9s stream to 225 spent up to
                    // a second of the length tolerance before any candidate was
                    // weighed.
                    if (ms > 0) return@withTimeoutOrNull ((ms + 500) / 1000).toInt()
                    delay(UPGRADE_PROVE_STEP_MS)
                }
                @Suppress("UNREACHABLE_CODE") null
            }
            // Re-asked rather than carried down from above, because the wait
            // is long enough for the answer to have changed: the resolver runs
            // on the loader thread and marks a track pending as it opens the
            // source, which for a track being fetched is precisely what has to
            // happen before the decoder can report the runtime waited for just
            // above. Reading the flag from before the wait meant a freshly
            // resolved track arrived here looking un-resolved, was handed to
            // the cached-track path, was refused by it for being pending, and
            // lost its upgrade until the next progress sample came round.
            if (!QualityUpgrade.isPending(mediaId) &&
                (uri == null || !adoptCachedTrack(mediaId, uri, playingSeconds))
            ) {
                return@launch
            }
            val better = withContext(Dispatchers.IO) {
                QualityUpgrade.lookAgain(mediaId, playingSeconds)
            } ?: return@launch
            try {
                swapIn(mediaId, better)
            } finally {
                // The badge comes down when the upgrade is done, not when the
                // search that found it was — including the deliberate wait in
                // [swapIn] before the audio is allowed to be cut. See
                // [QualityUpgrade.lookAgain]. In `finally` because a queue
                // that moves on cancels this job, and a cancelled swap has to
                // put the badge out as surely as a completed one.
                NerdStats.onLosslessRaceEnd(mediaId)
            }
        }
    }

    /**
     * Goes looking for a better copy of the playing track because the listener
     * asked for one — the player menu's "Upgrade quality".
     *
     * The way back from a revert, and the only thing that clears one: a track
     * pinned to YouTube's own upload is pinned against the automatic path
     * precisely so a search cannot quietly undo what the listener chose, so the
     * request to search again has to come from the same place the revert did.
     * See [OriginalVersion].
     *
     * Nothing is replaced here, and that is the whole of the design. The
     * obvious version of this rebuilt the reverted item as the ordinary,
     * substitutable entry it would have been queued as, and let the automatic
     * path take it from there. It worked, and it cost two breaks in the audio
     * where the feature only justifies one: the rebuild stopped the track and
     * started it again on the very same YouTube stream, seconds before the
     * upgrade landed and stopped it a second time. The first of those bought
     * nothing — nothing better had even been found yet.
     *
     * So the item is left exactly as it is and the search runs under it. The
     * two things standing in the way of that are handled where they live:
     * [QualityUpgrade.askByHand] exempts a reverted item's rendition marker
     * from the rule that would otherwise read it as "already upgraded", and
     * [QualityUpgrade.upgradedUri] drops `direct_youtube` from the URI it
     * builds, so the swap — when there is something worth swapping to — is
     * served the copy that was found rather than the one being replaced. The
     * listener hears one cut, for the change they asked for.
     */
    fun upgradeQualityNow() {
        val player = currentPlayer() ?: return
        val mediaId = player.currentMediaItem?.mediaId ?: return
        // Do not charge the manual cooldown for a command that merely arrived
        // while this track's automatic or manual upgrade was already running.
        // The UI disables the row too, but the service is the authoritative
        // boundary for controllers which do not use that UI.
        if (mediaId in NerdStats.racingLossless.value ||
            (upgradeJob?.isActive == true && upgradeFor == mediaId)
        ) {
            TrackLog.d("BitChord", "manual upgrade ignored for $mediaId: upgrade already running", about = mediaId)
            return
        }
        val remaining = manualUpgradeThrottle.tryAcquire(SystemClock.elapsedRealtime())
        if (remaining > 0) {
            TrackLog.d(
                "BitChord",
                "manual upgrade ignored for $mediaId: ${remaining}ms cooldown remaining",
                about = mediaId,
            )
            return
        }
        OriginalVersion.unpin(mediaId)
        // A manual retry is a request for the addon's state *now*. In
        // particular, do not replay a successful empty search cached while one
        // of the addon's upstreams was unavailable or reuse an earlier stream
        // URL. Running calls are retained and joined; only completed track
        // answers are discarded. The manifest is intentionally unaffected.
        SourceRegistry.clearCompletedAddonTrackCalls()
        QualityUpgrade.askByHand(mediaId)
        TrackLog.d("BitChord", "upgrade asked for by hand for $mediaId", about = mediaId)
        lookForBetterCopy(player)
    }

    /**
     * Decides whether a track nothing resolved is worth a second look, now that
     * the decoder has settled enough to say what it is playing.
     *
     * Two questions that need the player rather than the queue entry:
     *
     *  - **What codec is actually coming out.** A cache entry can already hold
     *    the FLAC a previous session upgraded to, and hunting a lossless copy
     *    of a track that is already lossless buys a break in the audio for
     *    nothing. An unknown codec is not read as "lossy": it means the
     *    renderer has not been configured yet, so the track is left un-adopted
     *    and the progress sampler asks again a few seconds later. A codec the
     *    renderer is reporting for *some other track* gets the same treatment,
     *    and has to, because it is indistinguishable from an answer — see
     *    [audioFormatFor] for what it cost to read one on trust.
     *  - **Whether the listener owns the file.** A downloaded track resolves to
     *    its own copy on disk — see the resolving data source above, which
     *    answers it before the module race is ever reached, so a download has
     *    never been a candidate for substitution. Reproduced here because this
     *    path skips that resolver entirely; without it the second look would
     *    spend data replacing a file the user deliberately saved.
     *
     * @param durationSec the runtime the decoder reports, waited for by the
     *   caller — needed here to turn the size of the cache entry into a
     *   bitrate. See [cachedFloor].
     */
    private suspend fun adoptCachedTrack(mediaId: String, uri: Uri, durationSec: Int?): Boolean {
        val format = withContext(Dispatchers.Main) {
            currentPlayer()
                ?.takeIf { it.currentMediaItem?.mediaId == mediaId && audioFormatFor == mediaId }
                ?.audioFormat
        } ?: return false
        val mime = format.sampleMimeType ?: return false
        val videoId = uri.getQueryParameter("v") ?: return false
        val downloaded = com.music.bitchord.download.Downloads.savedUri(context, videoId) != null
        if (downloaded) return false
        val target = SourceResolver.targetIn(uri)
        val expectedSec = target.durationSec
        if (TrackMatcher.isSevereMismatch(expectedSec, durationSec)) {
            TrackLog.w(
                "BitChord",
                "cached rendition duration mismatch: playing ${durationSec}s vs catalogue ${expectedSec}s for $videoId; discarding cache rendition",
                about = mediaId,
            )
            withContext(Dispatchers.IO) { AudioCache.discardRendition(uri) }
        }
        return QualityUpgrade.adoptUnresolved(
            mediaId = mediaId,
            uri = uri,
            target = target,
            playingMime = mime,
            playing = withContext(Dispatchers.IO) { cachedFloor(uri, format, durationSec) },
        )
    }

    /**
     * How good the bytes already on disk are, in the only terms a track nothing
     * resolved can be measured in.
     *
     * Two measurements, in order of directness:
     *
     *  - **What the decoder says.** `Format.bitrate` is populated for the
     *    containers that carry the field, which for what BitChord plays means
     *    MP4/AAC — the 320kbps copy a module served last session reports itself
     *    exactly.
     *  - **What the cache entry weighs.** Opus in WebM, which is what YouTube
     *    serves and so what most base entries hold, states no bitrate at all;
     *    but the rendition's full length is recorded in the cache index, and
     *    bytes over seconds *is* a bitrate. Slightly high, because container
     *    overhead counts toward the byte total and not toward the audio — which
     *    errs toward leaving the track alone, the right direction for a figure
     *    that decides whether to cut into playing audio.
     *
     * Null only when neither is available: an entry whose content length was
     * never recorded, or a runtime the decoder never reported. That is the old
     * behaviour of this path, and it is now the exception rather than the rule.
     *
     * The codec is deliberately not filled in. [StreamFormat.isLossless] reads
     * it, and a name carried over from the decoder's mime type would have to be
     * translated to be recognised — where being wrong means claiming a cached
     * stream is already lossless and abandoning the upgrade. Only the bitrate
     * is wanted here; [QualityUpgrade.adoptUnresolved] settles the lossless
     * question separately, from the mime type itself.
     */
    private fun cachedFloor(uri: Uri, format: Format, durationSec: Int?): StreamFormat? {
        format.bitrate.takeIf { it != Format.NO_VALUE && it > 0 }?.let {
            return StreamFormat(kbps = it / 1000)
        }
        val seconds = durationSec?.takeIf { it > 0 } ?: return null
        val bytes = AudioCache.contentLengthOf(uri).takeIf { it > 0 } ?: return null
        return StreamFormat(kbps = (bytes * 8 / seconds / 1000).toInt())
    }

    /** Where the playing track stands, read off the player in one hop. */
    private class SwapPoint(
        val item: MediaItem,
        val uri: String,
        val position: Long,
        val duration: Long,
    )

    /**
     * Replaces the playing track's audio with [stream], keeping the position.
     *
     * The break this causes is the whole cost of the feature, so the guards
     * are worth more than the swap is:
     *
     *  - The track must still be the one the search was started for. A skip
     *    during the lookup makes the answer worthless, not merely late.
     *  - There has to be enough of it left to be worth interrupting. Cutting
     *    the last few seconds of a song to improve the last few seconds of a
     *    song is a straight loss.
     *  - **The replacement has to be ready before anything is taken away.**
     *    See [auditionUpgrade]; this is what the break costs, so it is what
     *    the cut is bought against.
     *
     * The mechanism is [MediaItem.buildUpon] with a marked URI rather than a
     * new item: Media3 only rebuilds a media source when the replacement's
     * playback URI differs, so an item rebuilt identically would be accepted
     * and quietly keep playing the old stream.
     */
    private suspend fun swapIn(mediaId: String, stream: SourceStream) {
        // A search or a shelved proof can outlive the ceiling it was made
        // under — see [onCeilingLowered]. Asked again here, where it decides
        // whether the audio is cut, rather than only where it was found.
        if (!SourceRegistry.permitsForPlayback(stream.sourceConfigId)) {
            TrackLog.d("BitChord", "upgrade dropped: the quality ceiling no longer allows its source", about = mediaId)
            return
        }
        val at = withContext(Dispatchers.Main) { swapPointFor(mediaId) } ?: return
        if (at.duration > 0 && at.duration - at.position < UPGRADE_MIN_REMAINING_MS) {
            TrackLog.d("BitChord", "upgrade abandoned: only ${at.duration - at.position}ms of the track left")
            return
        }

        val upgradedUri = QualityUpgrade.upgradedUri(at.uri)
        // Whether the rendition entry already holds *this* stream's bytes,
        // asked before [force] overwrites the record of what filled it. True
        // only for a shelved upgrade being re-offered, where throwing the entry
        // away would mean paying for the same megabytes twice — and where
        // keeping it is safe for the one reason the discard exists: the file
        // under that key came from this very URL.
        val alreadyFilled = QualityUpgrade.forcedStream(Uri.parse(upgradedUri))?.url == stream.url
        // Parked before the audition rather than at the swap: the silent player
        // reaches its bytes through the same resolving data source the real one
        // does, and that is where a marked URI is turned back into a stream.
        QualityUpgrade.force(mediaId, stream)
        val warmedThrough = auditionUpgrade(mediaId, at, upgradedUri, stream, alreadyFilled)
            // The audition takes seconds, long enough for the ceiling to drop
            // under it; treated as a failed one, so nothing is cut.
            ?.takeIf { SourceRegistry.permitsForPlayback(stream.sourceConfigId) }
        if (warmedThrough == null) {
            // Nothing was cut, so there is nothing to put back: the listener
            // keeps the stream they already had and never learns this
            // happened. Which is the point — this is the failure that used to
            // arrive as a break in the audio followed by the same lossy stream
            // returning a few seconds later. Dropping the parked stream stops
            // [QualityUpgrade.forcedStream] serving a URL that has just failed
            // to prove itself.
            QualityUpgrade.forget(mediaId)
            withContext(Dispatchers.IO) { AudioCache.discardRendition(Uri.parse(upgradedUri)) }
            return
        }

        // There used to be an unconditional five-second hold here, keyed off the
        // track's own position, so that an upgrade arriving with the first note
        // could not cut the song a millisecond in. Its own reasoning said it was
        // "almost always already past by now", and that turned out to be the
        // whole story: by the time this line is reached the search, the stream
        // lookup and the audition have all run, and the audition alone spends
        // seconds on the network. So the guard was not usually deciding to wait
        // — it was adding its five seconds to whatever the swap had already
        // cost, on exactly the tracks that had been quickest to find a better
        // copy. Removed rather than shortened: it is the crossfade grace below
        // that protects the case an upgrade can genuinely spoil, and it does so
        // by asking whether a transition actually happened rather than assuming
        // one might have.
        //
        // Never cut into a crossfade in flight. `replaceMediaItem` tears the
        // session player's source down and rebuilds it — CrossfadeController
        // is either syncing its tail player's position against that same
        // source (arming), riding a ~90ms handoff between the two (lapping),
        // or ramping volume off the incoming track's own position (fading),
        // and all three read a session-player discontinuity as either an
        // unrecognised seek (bail, with an audible ramp-out) or a progress
        // calculation reset to whatever position the new source opens at.
        // Either way the blend breaks rather than merely waits.
        //
        // Bounded so a stuck flag can never leave the upgrade waiting forever;
        // past the timeout this falls through to the same check made again,
        // authoritatively, below — so an unusually long-running crossfade
        // still gets one more look rather than being forced through.
        //
        // This loop is only the coarse wait. [crossfade] is read again inside
        // the `withContext` below with no suspension between that read and
        // `replaceMediaItem` — both run on the same single-threaded Main
        // dispatcher [scope] does — so that second check is the one this
        // logic actually depends on for correctness, not this one.
        var waitedForCrossfade = 0L
        while (withContext(Dispatchers.Main) { crossfade()?.isTransitioning() } == true &&
            waitedForCrossfade < UPGRADE_CROSSFADE_WAIT_TIMEOUT_MS
        ) {
            delay(UPGRADE_CROSSFADE_POLL_MS)
            waitedForCrossfade += UPGRADE_CROSSFADE_POLL_MS
        }

        // Nor the instant one ends. The loop above releases on the tick the
        // blend completes, and a swap made there lands its cut a few hundred
        // milliseconds after the incoming track finally stood alone: the
        // listener hears the mix land and the music stop, in that order, which
        // reads as the transition having broken rather than as a track quietly
        // getting better. This is the one delay on this path, and it is why the
        // blanket one above it could go: a hold measured from the track's own
        // start never covered this case anyway, since an Automix hands over at a
        // cue point that can be well past it.
        //
        // Keyed off when a transition last ended rather than off whether the
        // loop above actually waited, so the same grace covers an upgrade
        // shelved by the check below and re-offered moments later — the same
        // swap, the same few seconds after the same blend, arriving by a
        // different route. And nothing is held back on a track nowhere near a
        // transition: the reading is then already long past the grace.
        withContext(Dispatchers.Main) { crossfade()?.msSinceTransition() }?.let { since ->
            if (since < UPGRADE_AFTER_CROSSFADE_MS) {
                val settle = UPGRADE_AFTER_CROSSFADE_MS - since
                TrackLog.d("BitChord", "upgrade for $mediaId holding ${settle}ms; a transition just ended")
                delay(settle)
            }
        }

        withContext(Dispatchers.Main) {
            val now = swapPointFor(mediaId)
            if (crossfade()?.isTransitioning() == true) {
                // Caught right before the swap that would have broken it —
                // everything spent proving this stream is still worth keeping
                // for next time rather than throwing away, exactly like the
                // "queue moved on" case just below.
                QualityUpgrade.shelve(mediaId, stream)
                TrackLog.d("BitChord", "upgrade for $mediaId shelved: a crossfade was still running")
                return@withContext
            }
            if (now == null) {
                // The queue moved on between the upgrade being proved and the
                // swap being made — a skip, or a track that ran out. Everything
                // this cost is still in hand, so it goes on the shelf rather
                // than in the bin; see [QualityUpgrade.shelve]. Logged because
                // this used to be the one exit here that left no trace at all,
                // and from the logs "found a FLAC, cached it, swapped nothing"
                // was indistinguishable from never having looked.
                QualityUpgrade.shelve(mediaId, stream)
                TrackLog.d("BitChord", "upgrade for $mediaId proved but the queue moved on; shelved")
                return@withContext
            }
            val player = currentPlayer() ?: return@withContext
            if (now.duration > 0 && now.duration - now.position < UPGRADE_MIN_REMAINING_MS) {
                TrackLog.d("BitChord", "upgrade abandoned: only ${now.duration - now.position}ms of the track left")
                QualityUpgrade.forget(mediaId)
                return@withContext
            }
            // The parked stream can be taken away underneath a swap in flight:
            // a playback failure on the *old* stream runs [recoverFrom], which
            // forgets the pending upgrade along with everything else it clears.
            // Swapping onto a marked URI with nothing parked behind it would
            // send the resolver off to find a stream of its own and write it
            // into the rendition entry the audition just filled — two files,
            // one key, which is the corruption the audition exists to avoid.
            if (QualityUpgrade.forcedStream(Uri.parse(upgradedUri)) == null) {
                TrackLog.d("BitChord", "upgrade abandoned: its stream was dropped while it was being proved")
                return@withContext
            }
            // Not fatal, just slower than intended, and worth being able to see
            // in a log: the audition buffers ahead of a moving target and can
            // only lose that race on a connection that is barely keeping up.
            if (now.position > warmedThrough) {
                TrackLog.d(
                    "BitChord",
                    "upgrade landing at ${now.position}ms, past the ${warmedThrough}ms warmed for it",
                )
            }

            // Read before the swap overwrites it — see [watchUpgrade]'s
            // NerdStats cleanup for why the pre-upgrade claim has to be
            // captured here rather than looked up again on revert.
            val previousFormat = NerdStats.declaredFormat(mediaId)
            swappingMediaId = mediaId
            swapCutAt = SystemClock.elapsedRealtime()
            val upgradedMetadata = now.item.mediaMetadata.buildUpon()
                .setExtras(Bundle(now.item.mediaMetadata.extras ?: Bundle()).apply {
                    putBoolean(EXTRA_QUALITY_UPGRADED, true)
                })
                .build()
            player.replaceMediaItem(
                player.currentMediaItemIndex,
                now.item.buildUpon()
                    .setUri(upgradedUri)
                    .setMediaMetadata(upgradedMetadata)
                    .withResolvedStreamType(stream.url)
                    .build(),
            )
            val upgradedSourceName = stream.sourceConfigId?.let { SourceRegistry.config(it)?.displayName }
                ?: "Upgrade"
            NerdStats.onSourceStream(mediaId, stream.format, upgradedSourceName)
            NerdStats.recordSource(mediaId, upgradedSourceName)
            player.seekTo(player.currentMediaItemIndex, now.position)
            player.prepare()
            QualityUpgrade.unshelve(mediaId)
            TrackLog.d("BitChord", "upgraded to ${stream.format.summary} at ${now.position}ms ($upgradedSourceName)")
            watchUpgrade(mediaId, now.uri, now.position, now.duration, previousFormat)
            if (QualityUpgrade.continueAfterLossySwap(mediaId)) {
                // The immediate JioSaavn improvement stays audible while a
                // slower lossless source is checked against its higher-quality
                // floor. Wait for this pass to release `upgradeJob`; otherwise
                // the second pass would see the first one as still active and
                // return without starting its lookup.
                val firstPass = upgradeJob
                scope.launch {
                    firstPass?.join()
                    lookForBetterCopy(player)
                }
            }
            // The opening again, this time sized for Automix rather than for
            // a container header.
            //
            // An upgraded rendition is only ever fetched from the swap point
            // onward, so its first seconds are the one region nothing downloads
            // on its own — [UPGRADE_HEADER_BYTES] covers the header and stops
            // well short of enough *audio* to measure. A megabyte of lossless is
            // four seconds, against the twelve the analyzer needs, so a track
            // that upgrades early could never be analysed from any rendition:
            // the lossless copy had no audio at its head and the copy it
            // replaced was discarded.
            //
            // After the swap and off the main thread, because nothing waits on
            // it — the upgrade is already audible and this only decides whether
            // the *next* transition can be a real mix.
            //
            // Which in a party it cannot be: the transition there is the plain
            // one everybody moves through together, and nothing is analysed for
            // it — see [CrossfadeController] and
            // [com.music.bitchord.playback.smart.TrackAnalyzer]. This is the one
            // fetch Automix makes that neither of those two gates, and a
            // megabyte pulled for an analysis that will not run is a megabyte
            // taken off the connection the party is syncing over.
            if (!ListenTogether.state.value.inParty) {
                launch(Dispatchers.IO) {
                    AudioCache.warmRange(Uri.parse(upgradedUri), 0, ANALYSIS_HEAD_BYTES)
                }
            }
        }
    }

    /** Main thread. Null unless [mediaId] is still current and has another upgrade slot. */
    private fun swapPointFor(mediaId: String): SwapPoint? {
        val player = currentPlayer() ?: return null
        val item = player.currentMediaItem ?: return null
        if (item.mediaId != mediaId) return null
        val uri = item.localConfiguration?.uri?.toString() ?: return null
        // One mid-track lossy improvement (typically Opus → JioSaavn) must not
        // prevent the requested lossless copy from replacing it. Two marked
        // URIs get distinct cache entries through [QualityUpgrade.upgradedUri].
        if (uri.contains("${QualityUpgrade.MARKER}=hifi-")) return null
        // A track the listener is holding on YouTube's own upload is not a
        // candidate for anything, whatever was already in flight for it. The
        // revert can land in the middle of a hunt — that is when the "Upgrading
        // quality" badge makes it most tempting to press — and the search
        // behind it neither knows nor can be told.
        //
        // The pin is the test rather than the item's `direct_youtube`, because
        // those two come apart in exactly the case this must not block: an
        // upgrade asked for by hand leaves the reverted item in place and
        // unpins the track, and swapping against that item is the whole point.
        // See [OriginalVersion] and [QualityUpgrade.askByHand].
        if (OriginalVersion.isPinned(mediaId)) {
            TrackLog.d("BitChord", "no swap for $mediaId: it is held on the original", about = mediaId)
            return null
        }
        return SwapPoint(item, uri, player.currentPosition, player.duration)
    }

    /**
     * Proves the upgraded stream on a second, silent player before a note of
     * the one playing is touched.
     *
     * This is the difference between a swap that is heard and one that is not.
     * `replaceMediaItem` + `prepare` tears the old source down first and builds
     * the new one from nothing: a connection to the CDN, a container header, a
     * range request for wherever the seek lands, a decoder configured, and only
     * then audio. Measured on this device that ran to about a second of silence
     * every time, and the whole of it was spent doing work that had no reason to
     * wait for the music to stop.
     *
     * So it doesn't. A throwaway player opens the same upgraded URI, seeked to
     * where the listener is, and fills the *same on-disk cache entry* the real
     * player will read from — [QualityUpgrade.MARKER] keys that entry apart from
     * the rendition being replaced, which is what makes this safe. When the swap
     * finally happens the bytes are already local, the container is already
     * known to parse, and what is left is a decoder init. The old stream plays
     * through all of it.
     *
     * The second thing it buys is that a failed upgrade stops costing anything.
     * Every way this can go wrong — a dead URL, a 403, a truncated body, a
     * catalogue that matched the wrong cut of the song, a source that promised
     * FLAC and serves Opus — now happens to a player nobody is listening to, and
     * the answer is simply that no swap occurs. Before, all of them were
     * discovered *after* the audio had been cut, and cost a break, several
     * seconds of silence in `STATE_BUFFERING`, and a second break putting the
     * old stream back. See [watchUpgrade], which is now the backstop for this
     * rather than the first line of defence.
     *
     * Silent by construction rather than by volume: with `playWhenReady` false
     * the renderers are enabled and decode — which is all the proof needed —
     * but nothing is started and no second `AudioTrack` is ever opened. It takes
     * no audio focus and backs no session, so nothing else in the app can see it.
     *
     * @return how far into the track the upgrade is buffered and ready, or null
     *   if it never got there.
     */
    private suspend fun auditionUpgrade(
        mediaId: String,
        at: SwapPoint,
        upgradedUri: String,
        stream: SourceStream,
        renditionAlreadyFilled: Boolean,
    ): Long? {
        QualityUpgrade.beginAudition(mediaId)
        val startedAt = SystemClock.elapsedRealtime()
        // Bytes actually read, since bufferedPosition can run far ahead of them on DASH.
        val meter = CountingBandwidthMeter(DefaultBandwidthMeter.getSingletonInstance(context))
        withContext(Dispatchers.IO) {
            // A clean entry first, because `#hifi` names a *slot* and not a
            // file. Every audition is a fresh candidate — a different catalogue,
            // a different master, a different length — and anything left under
            // that key by an earlier attempt at the same track belongs to a
            // different one of those. Media3 will happily read the two as one
            // stream, which is how a whole contiguous 32MB entry ended up
            // decoding to this:
            //
            // ```
            //   Target buffer size reached with less than 500ms of buffered media
            //   IllegalStateException: Playback stuck buffering and not loading
            // ```
            //
            // — a spliced file that cost the swap, the recovery, and seven
            // seconds of silence. The cost of being wrong the other way is one
            // re-download of a track being upgraded twice in a session, which
            // is why a re-offered upgrade is exempt: there the bytes under the
            // key are known to have come from the URL about to be used again.
            if (!renditionAlreadyFilled) AudioCache.discardRendition(Uri.parse(upgradedUri))
            // Then the opening, on its own, because the audition will not cache
            // it: a progressive source parses the container from byte zero and
            // then *seeks away*, leaving behind only the handful of bytes it
            // read before jumping. The real player has to parse the same header
            // from scratch after the swap, and it was reaching the network to do
            // it — the one read nothing can start without. Ahead of the audition
            // rather than beside it, since Media3 locks a cache entry to a
            // single writer.
            AudioCache.warmRange(Uri.parse(upgradedUri), 0, UPGRADE_HEADER_BYTES)
        }
        val audition = withContext(Dispatchers.Main) {
            buildAuditionPlayer(meter).apply {
                setMediaItem(
                    at.item.buildUpon()
                        .setUri(upgradedUri)
                        .withResolvedStreamType(stream.url)
                        .build(),
                )
                seekTo(at.position)
                prepare()
            }
        }
        val warmedThrough: Long?
        try {
            warmedThrough = withTimeoutOrNull(UPGRADE_AUDITION_MS) {
                while (true) {
                    val verdict = withContext(Dispatchers.Main) {
                        auditionVerdict(audition, at.duration, stream, meter.bytes.get())
                    }
                    when (verdict) {
                        is Audition.Ready -> return@withTimeoutOrNull verdict.bufferedTo
                        is Audition.Rejected -> {
                            TrackLog.w("BitChord", "upgrade dropped before it was heard: ${verdict.why}")
                            return@withTimeoutOrNull null
                        }
                        Audition.Waiting -> delay(UPGRADE_PROVE_STEP_MS)
                    }
                }
                @Suppress("UNREACHABLE_CODE") null
            }
        } finally {
            // Not optional and not cancellable: a queue that moves on cancels
            // this job, and a player left behind holds an audio decoder and a
            // write lock on a cache entry for the rest of the session.
            withContext(NonCancellable + Dispatchers.Main) { audition.release() }
            QualityUpgrade.endAudition(mediaId)
        }
        val took = SystemClock.elapsedRealtime() - startedAt
        if (warmedThrough == null) {
            TrackLog.d("BitChord", "upgrade for $mediaId never proved itself in ${took}ms")
            return null
        }
        TrackLog.d(
            "BitChord",
            "upgrade to ${stream.format.summary} proved in ${took}ms, buffered through ${warmedThrough}ms",
        )
        // Media3 locks a cache entry to one writer, and the audition lets go of
        // its hold as the sources are released rather than as `release()`
        // returns. Swapping onto a key still held would have the real player
        // stream bytes it has already paid to cache, or block behind the lock —
        // the stall [AudioCache]'s key factory documents. Free to wait for: the
        // old stream is still playing.
        delay(AUDITION_RELEASE_MS)
        TrackLog.d("BitChord", AudioCache.cachedSummary(Uri.parse(upgradedUri)))
        return warmedThrough
    }

    /**
     * Tells Media3 the type of a stream found behind one of our virtual
     * `bitchord://` playback URIs.
     *
     * The resolver replaces that URI with the real URL only after
     * [DefaultMediaSourceFactory] has selected a source implementation. A
     * Tidal upgrade is a manifest, but its virtual URI has no manifest suffix,
     * so the factory otherwise locks it into a progressive source and fails to
     * parse the manifest as audio bytes.
     *
     * Which manifest kind arrives is the backend's choice and it has already
     * changed once — see [StreamContainer], which holds that reasoning and the
     * test itself now that three paths need it rather than this one.
     */
    private fun MediaItem.Builder.withResolvedStreamType(streamUrl: String): MediaItem.Builder =
        StreamContainer.manifestMimeOf(streamUrl)?.let(::setMimeType) ?: this

    /** How an audition in progress is coming along — see [auditionUpgrade]. */
    private sealed interface Audition {
        data object Waiting : Audition

        /** Good, and buffered through this position in the track. */
        class Ready(val bufferedTo: Long) : Audition

        class Rejected(val why: String) : Audition
    }

    /** User-selected duration tolerance, exposed in seconds and applied here in milliseconds. */
    val upgradeLengthSlackMs: Long
        get() = AppSettings.upgradeLengthSlackSeconds.value * 1_000L

    /**
     * Main thread. Everything that has to be true before the audio is cut,
     * asked of the audition player rather than of the catalogue that made the
     * claims.
     */
    private fun auditionVerdict(
        audition: ExoPlayer,
        previousDuration: Long,
        stream: SourceStream,
        bytesLoaded: Long,
    ): Audition {
        audition.playerError?.let {
            return Audition.Rejected("${it.errorCodeName} opening ${stream.format.summary}")
        }
        // The failure a mid-track swap cannot survive, and the one that never
        // raises an error: a replacement that came up short does not fail, it
        // reaches the end of what it has and reports the track as over. Caught
        // here it costs nothing at all; caught after the swap it costs the
        // listener their song. See [watchUpgrade].
        if (audition.playbackState == Player.STATE_ENDED) {
            return Audition.Rejected("replacement ended immediately")
        }
        if (audition.playbackState != Player.STATE_READY) return Audition.Waiting
        val length = audition.duration
        if (length <= 0) return Audition.Waiting
        if (previousDuration > 0 && abs(length - previousDuration) > upgradeLengthSlackMs) {
            return Audition.Rejected("replacement is ${length}ms against ${previousDuration}ms")
        }
        // What the decoder was actually configured with, against what the
        // source said it was sending. The one failure mode a claim cannot
        // catch, because the claim is the thing that is wrong: a catalogue
        // advertising FLAC and serving a transcode buys a break in the audio
        // for no gain whatsoever.
        val mime = audition.audioFormat?.sampleMimeType
        if (mime != null && stream.format.isLossless == true && !NerdStats.isLosslessMime(mime)) {
            return Audition.Rejected("promised ${stream.format.summary}, decoder was handed $mime")
        }
        val buffered = audition.bufferedPosition
        // Aimed at where the listener will be, not where they were when this
        // started: the audition buffers ahead of a track that is still playing,
        // so the window it has to cover keeps moving. On any connection worth
        // upgrading over, buffering outruns playback and this converges in a
        // couple of seconds; on one where it doesn't, the swap would have
        // stalled anyway and the timeout is the right answer.
        val wantedThrough = (currentPlayer()?.currentPosition ?: 0L) + UPGRADE_PREBUFFER_MS
        // The only reason to settle for less: there is no more track to buffer.
        //
        // `isLoading` was tried here as a second escape — "the loader has
        // stopped of its own accord, so this is as good as it gets" — and it
        // was wrong every single time. [ChunkedDataSource] closes and reopens
        // the upstream every two megabytes, and `isLoading` goes false in the
        // gap between one range finishing and the next being asked for. A poll
        // landing in that gap read it as a full buffer, so every upgrade was
        // declared ready with roughly one chunk in hand and the swap then
        // landed seconds past the end of it, back on the network:
        //
        // ```
        //   upgrade to FLAC proved in 8701ms, buffered through 32496ms
        //   upgrade landing at 39889ms, past the 32496ms warmed for it
        // ```
        if (buffered >= wantedThrough || audition.bufferedPercentage >= 100) {
            // Measured: "buffered through 23466ms" with 2.7kB actually cached, and the swap then looped.
            if (bytesLoaded < MIN_PROVEN_LOAD_BYTES) return Audition.Waiting
            return Audition.Ready(buffered)
        }
        return Audition.Waiting
    }

    /**
     * The throwaway player an upgrade is proved on.
     *
     * Shares the media source factory, and therefore the disk cache, with the
     * real one — which is the entire point: what this fetches is what the real
     * player reads a moment later. Deliberately plainer than the two players
     * [buildPlayer] builds, because nothing here is ever heard: stock
     * renderers plus the FFmpeg fallback, no spatial processor, no audio
     * session, no focus, no session. The fallback is not optional: without it
     * a rendition only FFmpeg is given (ALAC) can never leave
     * `STATE_BUFFERING`, and every upgrade to one times out unproven.
     *
     * The one thing it does not share is the load control. [farBufferingLoadControl]
     * stops at [FAR_BUFFER_BYTES], which is sized for a player that only has to
     * stay ahead of itself; this one has to buffer past a *moving* target —
     * [UPGRADE_PREBUFFER_MS] beyond wherever the listener has got to by the time
     * it finishes — and eight megabytes is under fifteen seconds of hi-res FLAC,
     * which the drift alone can eat. Held for seconds and then released with the
     * player.
     */
    private fun buildAuditionPlayer(meter: BandwidthMeter): ExoPlayer = ExoPlayer.Builder(context)
        .setRenderersFactory(FfmpegFallbackRenderersFactory(context))
        .setMediaSourceFactory(mediaSourceFactory())
        .setBandwidthMeter(meter)
        .setLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    /* minBufferMs = */ AUDITION_BUFFER_MS,
                    /* maxBufferMs = */ AUDITION_BUFFER_MS,
                    /* bufferForPlaybackMs = */ START_PLAYBACK_MS,
                    /* bufferForPlaybackAfterRebufferMs = */ START_PLAYBACK_MS,
                )
                .setTargetBufferBytes(AUDITION_BUFFER_BYTES)
                .build(),
        )
        .build()
        .apply {
            playWhenReady = false
            volume = 0f
        }

    /**
     * Puts the old stream back if the upgraded one turns out to be broken.
     *
     * Learned the hard way: a swapped-in source that comes up short — a
     * truncated body, a CDN that answers a range request with something other
     * than the file — does not raise an error. It reports no duration, plays
     * for a few seconds and hits end-of-stream, and ExoPlayer does the correct
     * thing with a track that has ended, which is to advance to the next one.
     * The listener's song simply vanishes eight seconds in. That is a far worse
     * outcome than the lossy stream this was trying to improve on, so the new
     * source has to prove itself against the length the old one already knew
     * before it is allowed to keep the track.
     */
    private fun watchUpgrade(
        mediaId: String,
        previousUri: String,
        position: Long,
        previousDuration: Long,
        previousFormat: StreamFormat?,
    ) {
        if (previousDuration <= 0) return
        scope.launch(TrackLog.about(mediaId)) {
            val agreed = withTimeoutOrNull(UPGRADE_PROVE_MS) {
                while (true) {
                    val current = currentPlayer()?.takeIf { it.currentMediaItem?.mediaId == mediaId }
                        ?: return@withTimeoutOrNull false
                    val now = current.duration
                    if (now > 0) return@withTimeoutOrNull abs(now - previousDuration) <= upgradeLengthSlackMs
                    // The failure this whole check exists for, caught when it
                    // happens rather than at the ceiling: a replacement that
                    // came up short does not raise an error, it reaches the
                    // end of what it has and reports the track as over. That
                    // is a decisive no, and waiting out the rest of the window
                    // for it only delays the old stream coming back.
                    if (current.playbackState == Player.STATE_ENDED) return@withTimeoutOrNull false
                    delay(UPGRADE_PROVE_STEP_MS)
                }
                @Suppress("UNREACHABLE_CODE") false
            }
            if (agreed == true) return@launch
            val player = currentPlayer() ?: return@launch
            val item = player.currentMediaItem ?: return@launch
            if (item.mediaId != mediaId) return@launch
            // State and buffered position alongside the length: a replacement
            // that loaded and disagreed about the track looks identical here
            // to one that never loaded at all, and only the second is a fault
            // in the stream rather than a wrong match.
            TrackLog.w(
                "BitChord",
                "upgrade reverted: replacement reports ${player.duration}ms against " +
                    "${previousDuration}ms (state=${player.playbackState}, " +
                    "buffered=${player.bufferedPosition}ms)",
            )
            QualityUpgrade.forget(mediaId)
            // The FLAC/whatever claim recorded when the swap went out is no
            // longer what's playing — restore what was declared before it
            // (or clear it, if nothing was), so "stats for nerds" doesn't
            // keep calling the fallback lossless after the upgrade it
            // borrowed that claim from got reverted.
            if (previousFormat != null) {
                val prevSource = StreamChoice.of(mediaId)?.sourceConfigId?.let { SourceRegistry.config(it)?.displayName }
                    ?: if (StreamChoice.isSubstitute(mediaId)) "Module" else "YouTube"
                NerdStats.onSourceStream(mediaId, previousFormat, prevSource)
            } else {
                NerdStats.clearDeclared(mediaId)
            }
            swappingMediaId = mediaId
            val abandoned = item.localConfiguration?.uri
            player.replaceMediaItem(
                player.currentMediaItemIndex,
                item.buildUpon()
                    .setUri(previousUri)
                    // The item being reverted may have declared DASH/HLS for
                    // its upgrade. The previous stream must be sniffed afresh.
                    .setMimeType(null)
                    .build(),
            )
            player.seekTo(player.currentMediaItemIndex, position)
            player.prepare()
            // Whatever the replacement wrote is a prefix of a file nothing will
            // ever finish, under a key the *next* upgrade of this track would
            // key to as well — see [AudioCache.discardRendition]. Off the main
            // thread and behind the same pause a recovery takes, because the
            // source just released still holds the entry for a moment.
            abandoned?.let {
                launch(Dispatchers.IO) {
                    delay(RECOVERY_DELAY_MS)
                    AudioCache.discardRendition(it)
                }
            }
        }
    }

    /** What [resolveWithModulePriority] settled on. */
    sealed interface Resolved {
        data class Module(val stream: SourceStream) : Resolved
        data class YouTube(val url: String) : Resolved
    }

    /**
     * Resolves a YouTube-queued track by racing the higher-ranked modules
     * against YouTube itself, and handing whatever the modules are still doing
     * to [QualityUpgrade] if YouTube gets there first.
     *
     * Nobody gets a head start. An earlier version gave the modules six
     * seconds of silence to answer in before the fallback was even *asked*
     * for, on the reasoning that a module answering inside that window plays
     * with no seam in it. What that actually bought, on every track the
     * modules were slow on, was six seconds of nothing followed by a YouTube
     * client walk starting from cold — the wait and the seam, rather than one
     * or the other. Starting both at once removes the first of those: the
     * track begins as soon as *anything* can serve it.
     *
     * The speculative resolve this reinstates was dropped once before, for a
     * real reason — it is several round trips to `youtubei.googleapis.com`
     * competing for the same radio and connection pool as the lookup beside
     * it, and on a track the modules do have, that work is thrown away. What
     * changed is that it is no longer speculative: YouTube is now the expected
     * outcome for anything the modules don't answer quickly, so its walk is on
     * the critical path rather than hedging one. It is also coalesced and
     * cached — see [StreamResolver.resolve] — so even a discarded walk warms
     * the URL this track will want if the upgrade later falls through.
     *
     * A module that wins the race outright still wins the track, which is the
     * one thing worth keeping from the old head start: the lossless copy plays
     * from the first note and there is no swap at all. That is a narrower
     * window than it sounds, and deliberately so — read-ahead warms the
     * YouTube URL for the queue (see [AudioCache.prefetchQueue]), so on a
     * track that was read ahead the fallback answers in milliseconds and
     * almost always wins. The swap is the ordinary path now; playing from the
     * first note is the prize for a module quick enough to beat a cached URL.
     *
     * A lookup that loses is not cancelled. It is handed over still running,
     * because it is not wrong, only late, and the thing it is about to return
     * is exactly the stream that would have played seamlessly had it been
     * quicker. It finishes on its own time and the track swaps up to it
     * mid-song, which is the trade this whole path exists to make: a short
     * break in the audio, in exchange for the listener hearing something now
     * rather than waiting in silence for the good copy.
     */
    suspend fun resolveWithModulePriority(
        videoId: String,
        target: TrackMatcher.Target,
    ): Resolved {
        // A substitute already broke this track once — see
        // [StreamChoice.refuseSubstitutes]. Racing the modules again would find
        // the same catalogue holding the same unplayable URL, so there is
        // nothing to race: YouTube is the one answer here that hasn't failed.
        // Skipped entirely rather than merely deprioritised, because a lookup
        // that loses is handed to [QualityUpgrade] rather than dropped, and
        // handing over the search that just cost three attempts would only
        // schedule a fourth.
        if (StreamChoice.substitutesRefused(videoId)) {
            return Resolved.YouTube(StreamResolver.resolve(videoId))
        }
        if (DirectSourceGate.isActive()) return resolveDirect(videoId, target)
        NerdStats.onLosslessRaceStart(videoId)
        // Both legs are parented to the service's scope rather than to the
        // caller, so neither inherits whose track this is — see
        // [TrackLog.about]. Without it the module walk and the client walk both
        // log from a scope that knows nothing, which is most of what a resolve
        // has to say about itself.
        val lookup = scope.async(Dispatchers.IO + TrackLog.about(videoId)) {
            withTimeoutOrNull(SUBSTITUTE_TIMEOUT_MS) { SourceResolver.substituteForYouTube(target) }
        }
        // Started now rather than after the modules have had their say, and
        // wrapped rather than thrown from: it is awaited only on the paths
        // that need it, and an async that fails without ever being awaited is
        // an unhandled exception in this service's scope.
        val fallback = scope.async(Dispatchers.IO + TrackLog.about(videoId)) {
            runCatching { StreamResolver.resolve(videoId) }
        }

        // First past the post. A null because [lookup] won is a module miss; a
        // null because [fallback] won means YouTube has a URL and the modules
        // are still looking — [lookup.isActive] below is what tells those
        // apart, which is the question the old head start answered by timing
        // out rather than by asking.
        val quick: SourceStream? = select {
            lookup.onAwait { it }
            // A fallback that finished without a URL has not won anything.
            //
            // This clause used to yield null unconditionally, which treats "the
            // YouTube walk is over" as "YouTube has a URL" — true only while
            // failing was the slow outcome. It no longer is: [StreamResolver]
            // now answers a known-unplayable track immediately, so the losing
            // leg crosses the line first and, before this, took the track down
            // with it while a module lookup that was about to succeed was still
            // running. Exactly the case in the report — an age-gated track that
            // YouTube would never serve and a catalogue that had it all along.
            fallback.onAwait { resolved -> if (resolved.isSuccess) null else lookup.await() }
        }

        // A manifest cannot be substituted here, however good it is. This
        // function runs on the loader thread, *inside* the open of a media
        // source that was built minutes ago from an extensionless
        // `bitchord://` URI — so the source is already progressive and cannot
        // be told otherwise, and handing it a manifest is a track that fails
        // at 0ms rather than a track that plays lossless. See [StreamContainer]
        // for the log of exactly that.
        //
        // Nothing is thrown away for it. What the modules found is passed to
        // the second look already answered, which is the same handover a lookup
        // that merely lost the race gets — and [QualityUpgrade]'s swap does
        // declare the type, so the manifest plays there. The cost is a seam a
        // few seconds in instead of a clean start, which is the trade this
        // whole path is built to make.
        if (quick != null && StreamContainer.isManifest(quick.url)) {
            val url = runCatching { fallback.await().getOrThrow() }.getOrNull()
            if (url != null) {
                TrackLog.d(
                    "BitChord",
                    "'${target.title}' was offered ${quick.format.summary} as a manifest; " +
                        "starting on YouTube and swapping to it under the music",
                    about = videoId,
                )
                val handed = QualityUpgrade.settledForLess(
                    mediaId = videoId,
                    target = target,
                    inFlight = CompletableDeferred(quick),
                    playing = NerdStats.pickedBitrateKbps(videoId)?.let { StreamFormat(kbps = it) },
                )
                if (!handed) NerdStats.onLosslessRaceEnd(videoId)
                return Resolved.YouTube(url)
            }
            // YouTube cannot serve it either, so there is nothing to start on
            // and nothing to swap from. The manifest goes out as it is: it will
            // fail its first read, and [replayAsManifest] rebuilds the item
            // with the type declared and prepares it again. A cut before the
            // first note beats a track that does not play at all.
            TrackLog.w(
                "BitChord",
                "'${target.title}' has only a manifest and YouTube cannot serve it; " +
                    "letting it fail once to declare its type",
                about = videoId,
            )
            NerdStats.onLosslessRaceEnd(videoId)
            return Resolved.Module(quick)
        }

        if (quick != null) {
            // The modules got there first, so the YouTube walk is genuinely
            // spare work now. Cancelling drops only this service's wait on it;
            // [StreamResolver] parents the walk itself elsewhere and lets it
            // finish into its own cache.
            fallback.cancel()
            // Everything that was asked for, ahead of the fallback: the
            // ordinary good case, and the one with no seam in it.
            if (!quick.belowRequest) {
                NerdStats.onLosslessRaceEnd(videoId)
                return Resolved.Module(quick)
            }
            // Less than was asked for — but a lossy copy from a module still
            // beats going back to YouTube for one. Worth a second look, and
            // with this lookup already finished that look starts from scratch.
            val settled = QualityUpgrade.settledForLess(
                mediaId = videoId,
                target = target,
                playing = quick.format,
                servedBy = quick.sourceConfigId,
            )
            if (!settled) NerdStats.onLosslessRaceEnd(videoId)
            return Resolved.Module(quick)
        }

        val url = fallback.await().getOrThrow()
        // Marked pending only here, with the fallback's own bitrate in hand:
        // that figure is the answer to "better than what?" the second look
        // measures candidates against, and it isn't known until the client
        // walk has picked a format. A lookup still running is handed over to
        // be waited on rather than repeated; one that already finished with
        // nothing leaves the second look to find its own candidates.
        val pending = QualityUpgrade.settledForLess(
            mediaId = videoId,
            target = target,
            inFlight = lookup.takeIf { lookup.isActive },
            playing = NerdStats.pickedBitrateKbps(videoId)?.let { StreamFormat(kbps = it) },
        )
        if (!pending) NerdStats.onLosslessRaceEnd(videoId)
        return Resolved.YouTube(url)
    }

    /**
     * Direct Source Stream's resolve: the addons first and alone, YouTube only
     * when none of them answers in time — see [DirectSourceGate].
     *
     * Nothing races. An addon's answer plays to the end, which is the point:
     * one search and one stream per track, not a YouTube start followed by a
     * swap. A YouTube start is the one case handed to [QualityUpgrade], because
     * that is the swap the addons' slowness has already made necessary.
     *
     * A manifest answer goes out as it is. It fails its first read and
     * [replayAsManifest] re-prepares it with the type declared, which is a
     * short cut before the first note rather than a YouTube start and a seam.
     */
    private suspend fun resolveDirect(videoId: String, target: TrackMatcher.Target): Resolved {
        val direct = runCatching { DirectSourceGate.lookup(videoId, target) }
            .onFailure { if (it is CancellationException) throw it }
            .getOrNull()
        if (direct != null) return Resolved.Module(direct)
        val running = DirectSourceGate.stillLooking(videoId)
        TrackLog.d(
            "BitChord",
            if (running != null) {
                "no addon answered for '${target.title}' within " +
                    "${DirectSourceGate.LOOKUP_TIMEOUT_MS / 1000}s; starting it on YouTube and still looking"
            } else {
                "no addon served '${target.title}' directly; playing it from YouTube"
            },
            about = videoId,
        )
        val url = try {
            StreamResolver.resolve(videoId)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // YouTube cannot serve it, so a lookup still running is all that
            // is left, and there is nothing playing for it to swap into.
            return Resolved.Module(running?.await() ?: throw e)
        }
        DirectSourceGate.startedOnYouTube(videoId, target)
        return Resolved.YouTube(url)
    }

    internal companion object {
        /**
         * Cap on offering a YouTube track to a higher-ranked source.
         *
         * Nothing like [RESOLVE_TIMEOUT_MS], because the two are not the same
         * kind of wait: that one bounds the only way to hear the track, this
         * one bounds an optional upgrade over a stream YouTube will serve
         * anyway. Generous enough for a cold module — index fetch, JS
         * download, engine init, search, then the stream URL — and short
         * enough that a dead server costs a pause rather than a stall.
         */
        const val SUBSTITUTE_TIMEOUT_MS = 20_000L

        /**
         * How much of a track has to be left for a mid-track quality swap to
         * be worth the break in the audio it costs.
         */
        const val UPGRADE_MIN_REMAINING_MS = 20_000L

        /** Minimum interval between accepted listener-requested quality retries. */
        const val MANUAL_UPGRADE_COOLDOWN_MS = 30_000L

        /** How often to recheck [CrossfadeController.isTransitioning] while an upgrade waits on one. */
        const val UPGRADE_CROSSFADE_POLL_MS = 250L

        /**
         * Longest an upgrade waits on a crossfade before giving up and
         * checking once more, authoritatively, right at the swap point. Well
         * past the longest transition either mode plans — 12s for a manual
         * crossfade, or a Automix's own beat-bounded overlap, plus its arm
         * lead — so this is a guard against something stuck, not a limit
         * expected to bind in the ordinary case.
         */
        const val UPGRADE_CROSSFADE_WAIT_TIMEOUT_MS = 20_000L

        /**
         * How long a transition has to have been over before an upgrade may cut
         * into the track it handed to.
         *
         * Not a second guard on the same thing as
         * [UPGRADE_CROSSFADE_WAIT_TIMEOUT_MS]'s loop, which only keeps the swap
         * out of a blend *in flight*. This is about the moment just after one:
         * the mix resolves, and the music stops a quarter of a second later for
         * a rebuild the listener has no reason to connect to bitrate. Long
         * enough for the new track to have established itself as the thing
         * playing, short enough that an upgrade is not being meaningfully
         * delayed — and it applies only where a transition actually ran, so the
         * ordinary swap, minutes from any blend, is as immediate as it was.
         */
        const val UPGRADE_AFTER_CROSSFADE_MS = 5_000L

        /**
         * How long a replacement gets to report a length before it is
         * disbelieved.
         *
         * This is silence, not patience: the swap has already cut the audio,
         * and the track sits in `STATE_BUFFERING` for the whole of it before
         * the old stream comes back. It was cut from eight seconds to two and
         * a half on the strength of "a replacement that works reports its
         * length in well under a tenth of this" — which was true of what the
         * swap landed on at the time, and is not true of a FLAC. Measured
         * here, an upgrade to a 16-bit Qobuz stream was still buffering its
         * first chunk when the window closed:
         *
         * ```
         *   upgrade reverted: replacement reports -9223372036854775807ms
         *     against 259141ms (state=2, buffered=5002ms)
         * ```
         *
         * — a working FLAC thrown away for being slower to open than a lossy
         * MP4, which is the one thing this feature exists to fetch. The
         * failure the short window was protecting against is caught by state
         * now rather than by the clock (see [watchUpgrade]), so the ceiling
         * only bounds the genuinely stuck case, and can afford to be long
         * enough for a large file over a phone connection.
         */
        const val UPGRADE_PROVE_MS = 10_000L
        const val UPGRADE_PROVE_STEP_MS = 200L

        /**
         * How long an upgrade gets to prove itself before the swap is dropped.
         *
         * Nothing like [UPGRADE_PROVE_MS], and for one reason: that window is
         * silence and this one is music. The audition runs on a player nobody
         * is listening to while the old stream plays through the whole of it,
         * so the only thing a longer ceiling costs is a decoder held open a
         * few seconds more. Generous enough for a cold hi-res FLAC over a
         * phone connection, since a stream slow to open is exactly the one
         * this feature exists to fetch and exactly the one the old
         * cut-then-wait order threw away.
         */
        const val UPGRADE_AUDITION_MS = 25_000L

        /**
         * How far past the listener an upgrade has to be buffered before it is
         * allowed to take over.
         *
         * This is the number that makes the swap inaudible. Everything inside
         * this window is on disk by the time the real player asks for it, so
         * the seam is a decoder init rather than a round trip to a CDN. It has
         * to cover the drift as well: the track keeps playing while the
         * audition buffers, so the swap lands some seconds past where the
         * audition started, and a window shorter than the audition takes would
         * put the swap point back on the network. Twelve seconds is comfortably
         * more than either.
         */
        const val UPGRADE_PREBUFFER_MS = 12_000L

        /**
         * How much of the upgraded file's opening is fetched before the
         * audition starts — see [AudioCache.warmRange] for why the audition
         * cannot be relied on to leave it behind.
         *
         * A megabyte because a FLAC header is not a header: STREAMINFO is 34
         * bytes, but the seek table, the tags and an embedded cover in front of
         * the first audio frame routinely run to hundreds of kilobytes, and a
         * range that stops short of the first frame buys nothing at all.
         */
        const val UPGRADE_HEADER_BYTES = 1L * 1024 * 1024

        /** Loaded media bytes an audition needs before its buffered position is believed. */
        const val MIN_PROVEN_LOAD_BYTES = UPGRADE_HEADER_BYTES

        /**
         * Opening fetched after an upgrade so the track stays analysable. Four
         * megabytes is a little over twelve seconds of lossless — the shortest
         * window Automix's head pass accepts — and many times that for a
         * compressed rendition, which simply finishes sooner.
         */
        const val ANALYSIS_HEAD_BYTES = 4L * 1024 * 1024

        /**
         * The audition's own buffer, in time and in bytes.
         *
         * Both well past [FAR_BUFFER_MS]'s byte ceiling, and deliberately: this
         * player has to end up [UPGRADE_PREBUFFER_MS] ahead of a position that
         * keeps moving while it works, so what it needs is the window plus
         * however long it took to fill — and at 4.6Mbit/s a hi-res FLAC eats
         * eight megabytes in under fifteen seconds. Transient, and freed with
         * the player a moment later.
         */
        const val AUDITION_BUFFER_MS = 40_000

        const val AUDITION_BUFFER_BYTES = 24 * 1024 * 1024

        /**
         * The pause between releasing the audition player and swapping onto
         * what it cached. Same reason as [RECOVERY_DELAY_MS] — Media3 lets go
         * of a cache entry as the source is released, not as the call returns —
         * and free here, because the old stream is still playing.
         */
        const val AUDITION_RELEASE_MS = 250L

        /**
         * How long the second look waits for the playing track to report its
         * own length before giving up and going on the claimed one.
         *
         * Costs nothing when it isn't needed — a prepared track answers on the
         * first poll — and it runs with the music still playing, so what it
         * spends is patience rather than silence.
         */
        const val DURATION_SETTLE_MS = 8_000L

        /**
         * The pause before a retry. Media3 refuses to remove a cache entry a
         * reader still holds, and the reader is let go asynchronously as the
         * failed source is released, so the discard needs a moment to land
         * before the same track is asked for again.
         */
        const val RECOVERY_DELAY_MS = 350L
    }
}
