package com.music.bitchord.playback

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.innertube.PlaybackTracker
import com.music.bitchord.data.listentogether.ListenTogether
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.playback.smart.AutomixAnalysisSource
import com.music.bitchord.playback.smart.VersionAudioAligner
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * How long a version switch will sit on a not-yet-downloaded opening before
 * it gives up on the head — [com.music.bitchord.playback.AudioCache.awaitAnalysisHead]
 * both starts the fetch and waits on it, so this is the wait a listener sees
 * with the bar running. Shared by the two heads, which are fetched in
 * parallel, so it bounds the pair rather than being paid twice.
 */
private const val ALIGNMENT_HEAD_TIMEOUT_MS = 20_000L

/**
 * How long the cross-correlation itself may take once both heads are on disk.
 * It is pure computation over two forty-five second windows, so a run that
 * takes this long has run into contention rather than hard work, and the
 * caller swaps without a shift instead of holding the track any longer.
 *
 * The margin exists because a result that arrives *after* this deadline is
 * discarded whole: the field log showed a measurement succeed 10 ms past a
 * tighter budget, and the swap still went out with no shift even though the
 * offset had been computed and cached. Better a longer bar than a wrong
 * second.
 */
private const val ALIGNMENT_MEASURE_TIMEOUT_MS = 30_000L

/**
 * Switching the playing track between cuts of the same song — a video's audio
 * and the release, YouTube's upload and a lossless copy — without a gap.
 *
 * The incoming cut is prepared on the spare player at the moment the playing
 * one has reached, shifted by the offset between the two: YouTube's own time
 * map when the pair is officially linked, else a measured cross-correlation
 * of their openings ([measureVersionAlignmentAsync], when smart alignment is
 * on), else none. The two then cross with a ~550ms equal-power fade under the
 * same blend headroom a crossfade takes, and the spare becomes the session's
 * player ([handOverDecks]).
 *
 * [player] and [spare] are read when needed, never captured: the session moves
 * between them at every crossfade. Never runs while a crossfade is
 * mid-transition ([transitioning]), and the crossfade likewise waits on
 * [active], since both fades share the one pair of players.
 */
@UnstableApi
internal class VersionAudioSynchronizer(
    private val context: Context,
    private val scope: CoroutineScope,
    private val player: () -> ExoPlayer?,
    private val spare: () -> ExoPlayer?,
    private val transitioning: () -> Boolean,
    private val loudness: LoudnessDispatch,
    /** Tells the service the next item transition is this swap, not the queue moving on. */
    private val markSwapping: (String?) -> Unit,
    private val refreshButtons: () -> Unit,
    private val handOverDecks: (outgoing: ExoPlayer, incoming: ExoPlayer) -> Unit,
    /** The swapped-in item is now current, and already audible. */
    private val onBecameCurrent: (MediaItem?) -> Unit,
) {
    /** Whether a version swap is preparing or fading. */
    val active: Boolean get() = versionSwapJob?.isActive == true

    private var versionSwapJob: Job? = null
    private var versionSwapGeneration = 0

    /**
     * Seamlessly transitions between two versions/sources of the same track
     * (e.g. switching between YouTube film/video audio and release FLAC/streaming audio)
     * using a smooth equal-power crossfade across the two ExoPlayers so there is zero
     * audible stutter, gap, pause or loading hiccup.
     */
    fun smoothSwapCurrentTrackVersion(
        targetMediaItem: MediaItem,
        onSwapCommitted: (() -> Unit)? = null,
    ) {
        val activePlayer = player() ?: return
        val currentIndex = activePlayer.currentMediaItemIndex
        if (currentIndex !in 0 until activePlayer.mediaItemCount) return
        val currentItem = activePlayer.currentMediaItem ?: return
        // Listen Together plays the one track every member has, and a version
        // swap is a purely local choice about which cut of it to hear — see
        // [ListenTogether]'s own note on why Automix stays off in a party for
        // the same reason. The toggle is hidden client-side for this, but the
        // command can still arrive from a controller that predates the hide.
        if (ListenTogether.state.value.inParty) {
            TrackLog.d("BitChord", "version swap ignored: in a Listen Together party", about = currentItem.mediaId)
            return
        }
        // Never while Automix is mid-blend: both fades share the same
        // active/standby pair, and starting a version swap here would tear
        // the standby player away from a transition already using it. The
        // reverse — Automix arming while a version swap is running — is
        // guarded symmetrically in [createCrossfadeController].
        if (transitioning()) {
            TrackLog.d("BitChord", "version swap deferred: Automix is mid-transition", about = currentItem.mediaId)
            return
        }

        val sourceSong = currentItem.toSong()
        val targetSong = targetMediaItem.toSong()
        // YouTube's own map for an officially paired cut, which needs nothing
        // fetched or measured — so it is used whether or not smart alignment
        // is on, and the measurement is skipped.
        val officialMap = YtMusicRepository.versionTimeMap(sourceSong.videoId, targetSong.videoId)
        val measureAlignment = officialMap == null && AppSettings.smartVersionAlignment.value
        // The bar's sheen still covers the whole swap — standby buffering and
        // the fade — for a mapped cut. Switching back to the version a switch
        // came from has no resolve in front of it, so without this the only
        // thing on the bar would be the fade, too short to read as a switch.
        val holdsSwitchBar = measureAlignment || officialMap != null

        versionSwapJob?.cancel()
        val generation = ++versionSwapGeneration
        versionSwapJob = scope.launch(Dispatchers.Main) {
            val mediaId = targetMediaItem.mediaId
            markSwapping(mediaId)
            if (holdsSwitchBar) {
                AppSettings.versionAlignmentInProgress.value = true
            }
            // What a cancellation has to undo — see [abandonSwap].
            var standby: ExoPlayer? = null
            var landed = false
            try {
                val startPosition = activePlayer.currentPosition
                val alignmentOffsetMs = if (measureAlignment) {
                    prepareVersionAlignment(sourceSong, targetSong, startPosition)
                } else {
                    0L
                }
                // Where a moment of the playing cut lands in the other one. A
                // measured offset is one shift for the whole track; YouTube's
                // map is per stretch, so a skit mid-video maps correctly too.
                val alignedPosition: (Long) -> Long = if (officialMap != null) {
                    { pos -> officialMap.map(pos).coerceAtLeast(0L) }
                } else {
                    { pos -> VersionSwapFade.shifted(pos, alignmentOffsetMs) }
                }
                if (officialMap != null) {
                    val mapped = alignedPosition(startPosition)
                    VersionAudioAligner.publishPhase(
                        VersionAudioAligner.beginAttempt(sourceSong.videoId, targetSong.videoId),
                        VersionAudioAligner.CutPhase.ALIGNED,
                        mapped - startPosition,
                    )
                    TrackLog.d(
                        "BitChord",
                        "version swap using YouTube's time map: ${startPosition}ms -> ${mapped}ms",
                        about = sourceSong.videoId,
                    )
                }

                if (player() !== activePlayer ||
                    activePlayer.currentMediaItemIndex != currentIndex ||
                    activePlayer.currentMediaItem?.mediaId != sourceSong.videoId
                ) {
                    markSwapping(null)
                    return@launch
                }

                val standbyPlayer = spare()
                // Crossfade is unconditional: the setting that used to gate it
                // is gone, so the only thing that can decline the fade is having
                // no standby player to fade with.
                val canCrossfade = standbyPlayer != null &&
                    (activePlayer.isPlaying || activePlayer.playbackState == Player.STATE_READY)

                if (!canCrossfade || standbyPlayer == null) {
                    val pos = alignedPosition(activePlayer.currentPosition)
                    val wasPlaying = activePlayer.playWhenReady
                    activePlayer.replaceMediaItem(currentIndex, targetMediaItem)
                    activePlayer.seekTo(currentIndex, pos)
                    if (wasPlaying) activePlayer.play()
                    onSwapCommitted?.invoke()
                    refreshButtons()
                    markSwapping(null)
                    return@launch
                }

                standby = standbyPlayer
                // Build replacement playlist for standby player
                val newItems = (0 until activePlayer.mediaItemCount).map { i ->
                    if (i == currentIndex) targetMediaItem else activePlayer.getMediaItemAt(i)
                }
                val wasPlaying = activePlayer.playWhenReady
                val alignedStartPos = alignedPosition(activePlayer.currentPosition)

            // Reset and configure standby player
            standbyPlayer.stop()
            standbyPlayer.clearMediaItems()
            standbyPlayer.skipSilenceEnabled = activePlayer.skipSilenceEnabled
            standbyPlayer.repeatMode = activePlayer.repeatMode
            standbyPlayer.shuffleModeEnabled = activePlayer.shuffleModeEnabled
            // The listener's speed, not the active player's: straight after an
            // Advanced Automix blend the active player is still easing off its
            // beatmatch stretch, and copying that would keep it for good.
            standbyPlayer.setPlaybackSpeed(AppSettings.playbackSpeed.value)
            standbyPlayer.volume = 0f
            standbyPlayer.setMediaItems(newItems, currentIndex, alignedStartPos)
            loudness.spare().track(mediaId, standbyPlayer.nextMediaIdOrNull())
            standbyPlayer.playWhenReady = false
            standbyPlayer.prepare()

            // Wait for standby to buffer and reach STATE_READY in the background
            val timeoutAt = SystemClock.elapsedRealtime() + VersionSwapFade.STANDBY_READY_TIMEOUT_MS
            var isReady = false
            while (isActive && SystemClock.elapsedRealtime() < timeoutAt) {
                if (player() !== activePlayer ||
                    activePlayer.currentMediaItemIndex != currentIndex
                ) {
                    // Active player moved to another track or was changed
                    standbyPlayer.stop()
                    standbyPlayer.clearMediaItems()
                    standbyPlayer.volume = 1f
                    markSwapping(null)
                    return@launch
                }

                if (standbyPlayer.playbackState == Player.STATE_READY) {
                    isReady = true
                    break
                }
                if (standbyPlayer.playbackState == Player.STATE_IDLE && standbyPlayer.playerError != null) {
                    break
                }
                delay(20)
            }

            if (!isReady) {
                TrackLog.w("BitChord", "version swap standby player failed to prepare, applying directly", about = mediaId)
                standbyPlayer.stop()
                standbyPlayer.clearMediaItems()
                standbyPlayer.volume = 1f
                markSwapping(null)
                val pos = alignedPosition(activePlayer.currentPosition)
                activePlayer.replaceMediaItem(currentIndex, targetMediaItem)
                activePlayer.seekTo(currentIndex, pos)
                if (wasPlaying) activePlayer.play()
                onSwapCommitted?.invoke()
                onBecameCurrent(targetMediaItem)
                refreshButtons()
                return@launch
            }

            // Sync playback position with exact live position right before starting playback on standby
            standbyPlayer.seekTo(currentIndex, alignedPosition(activePlayer.currentPosition))

            val willPlay = wasPlaying && activePlayer.playWhenReady
            if (willPlay) {
                // Start it right away rather than waiting the seek out first:
                // the outgoing player keeps running through every one of these
                // steps, so each moment the incoming one spends frozen before
                // its audio actually starts is a moment it enters the fade
                // behind — a few hundred reads as a doubled word rather than
                // as an alignment.
                standbyPlayer.play()
                val runningTimeout = SystemClock.elapsedRealtime() + 600L
                while (isActive && !standbyPlayer.isPlaying && SystemClock.elapsedRealtime() < runningTimeout) {
                    delay(10)
                }
                if (standbyPlayer.isPlaying) {
                    // Now that it is producing audio — still at zero volume —
                    // put it exactly where the outgoing player is *now*. The
                    // near-seek lands inside the buffer it already holds, so
                    // the drift accumulated above collapses to this seek's own
                    // resume time, which is the one part that cannot go.
                    standbyPlayer.seekTo(currentIndex, alignedPosition(activePlayer.currentPosition))
                    val resyncTimeout = SystemClock.elapsedRealtime() + 300L
                    while (isActive && standbyPlayer.playbackState != Player.STATE_READY && SystemClock.elapsedRealtime() < resyncTimeout) {
                        delay(10)
                    }
                }
            } else {
                // Paused swap: nothing is running on either side, so the first
                // seek is already exact — it only has to finish before the
                // fade adopts the player.
                val seekTimeout = SystemClock.elapsedRealtime() + 400L
                while (isActive && standbyPlayer.playbackState != Player.STATE_READY && SystemClock.elapsedRealtime() < seekTimeout) {
                    delay(10)
                }
            }

            // Smooth equal-power crossfade transition — see [VersionSwapFade].
            val fadeStart = SystemClock.elapsedRealtime()
            if (!holdsSwitchBar) {
                AppSettings.smartMixInProgress.value = true
            }

            try {
                while (isActive) {
                    val elapsed = SystemClock.elapsedRealtime() - fadeStart
                    val progress = VersionSwapFade.progress(elapsed)

                    if (player() !== activePlayer || activePlayer.currentMediaItemIndex != currentIndex) {
                        standbyPlayer.stop()
                        standbyPlayer.clearMediaItems()
                        standbyPlayer.volume = 1f
                        activePlayer.volume = 1f
                        markSwapping(null)
                        return@launch
                    }

                    if (standbyPlayer.playWhenReady != activePlayer.playWhenReady) {
                        standbyPlayer.playWhenReady = activePlayer.playWhenReady
                    }

                    val gains = VersionSwapFade.gains(progress)
                    standbyPlayer.volume = gains.incoming
                    activePlayer.volume = gains.outgoing
                    loudness.setBlendTrim(gains.trim)

                    if (progress >= 1f) break
                    delay(16)
                }

                // Adopt incoming player cleanly
                adoptPlayerForVersionSwap(outgoing = activePlayer, incoming = standbyPlayer)
                landed = true
                onSwapCommitted?.invoke()
            } finally {
                loudness.setBlendTrim(1f)
                if (!holdsSwitchBar) {
                    AppSettings.smartMixInProgress.value = false
                }
            }
            } catch (e: CancellationException) {
                if (!landed) abandonSwap(activePlayer, standby, generation)
                throw e
            } finally {
                if (generation == versionSwapGeneration) {
                    AppSettings.versionAlignmentInProgress.value = false
                }
            }
        }
    }

    /**
     * Puts back what a swap cancelled part-way had set up. The loops above only
     * clean up when they notice the track changed; a cancellation leaves them
     * at whichever `delay` they were in, which used to mean the playing track
     * stuck at its part-faded volume, the standby still running silently, and
     * the service still told the next transition was this swap.
     *
     * A newer swap ([generation] no longer current) resets the standby and
     * the marker itself, so only the volume is put back for it.
     */
    private fun abandonSwap(activePlayer: ExoPlayer, standby: ExoPlayer?, generation: Int) {
        runCatching { activePlayer.volume = 1f }
        if (generation != versionSwapGeneration) return
        runCatching {
            if (standby != null && standby !== player()) {
                standby.stop()
                standby.clearMediaItems()
                standby.volume = 1f
            }
        }
        markSwapping(null)
    }

    /**
     * Fetches the other cut's opening and measures its offset against the
     * playing one. Playback stays on the current version until this returns.
     *
     * Returns `0` — no shift — rather than refusing the switch: a swap landing
     * on the wrong second beats staying put. What it never does is return `0`
     * silently. [VersionAudioAligner.status] walks fetch → measure → result
     * the whole way through and stays at the result once the swap lands, so
     * stats for nerds can say which of the three happened and what the shift
     * came out as.
     */
    /**
     * One measurement per pair, shared by every caller that asks for it, and
     * launched on [scope] rather than on whichever caller asked first.
     *
     * That is the whole point: [versionSwapJob] gets cancelled every time a
     * newer switch outranks it or the listener backs out, and before this the
     * FFT pass cancelled with it — so backing out of a switch and asking for
     * it again a moment later paid for the decode and the correlation twice.
     * A caller that stops waiting now only stops *waiting*; the pass keeps
     * running here and still lands in [VersionAudioAligner]'s cache for
     * whoever asks next, including a caller that never existed yet when this
     * was started.
     */
    private val versionAlignmentMeasurements = ConcurrentHashMap<Pair<String, String>, Deferred<Long?>>()

    private fun measureVersionAlignmentAsync(sourceSong: Song, targetSong: Song): Deferred<Long?> {
        val sourceId = sourceSong.videoId
        val targetId = targetSong.videoId
        val key = sourceId to targetId
        VersionAudioAligner.getCachedOffsetMs(sourceId, targetId)?.let { return CompletableDeferred(it) }
        versionAlignmentMeasurements[key]?.let { return it }
        val deferred = scope.async(Dispatchers.Main) {
            try {
                val sourceUri = Uri.parse(sourceSong.localUri ?: AutomixAnalysisSource.opusUri(sourceId))
                val targetUri = Uri.parse(targetSong.localUri ?: AutomixAnalysisSource.opusUri(targetId))
                withContext(Dispatchers.IO) {
                    // Together rather than one after the other: two independent
                    // range reads that the sequential version made the listener pay
                    // for in series, on top of a wait they were already in.
                    val sourceHead = async {
                        sourceSong.localUri != null ||
                            AudioCache.awaitAnalysisHead(sourceUri, ALIGNMENT_HEAD_TIMEOUT_MS)
                    }
                    val targetHead = async {
                        targetSong.localUri != null ||
                            AudioCache.awaitAnalysisHead(targetUri, ALIGNMENT_HEAD_TIMEOUT_MS)
                    }
                    val sourceReady = sourceHead.await()
                    val targetReady = targetHead.await()
                    if (!sourceReady || !targetReady) {
                        // Carried on to the measurement anyway: the aligner opens
                        // whatever opening is largest on disk, and a short one
                        // still correlates better than not trying at all.
                        TrackLog.w(
                            "BitChord",
                            "version alignment head short on disk (source=$sourceReady, " +
                                "target=$targetReady) for '${sourceSong.title}'",
                            sourceId,
                        )
                    }
                }
                withTimeoutOrNull(ALIGNMENT_MEASURE_TIMEOUT_MS) {
                    VersionAudioAligner.findOffsetMs(
                        context = context,
                        sourceSong = sourceSong,
                        targetSong = targetSong,
                    )
                }
            } finally {
                // The cache entry [VersionAudioAligner] wrote on success is the
                // one that matters from here on; this map only exists to fold
                // concurrent askers into the one pass actually running.
                versionAlignmentMeasurements.remove(key)
            }
        }
        versionAlignmentMeasurements[key] = deferred
        return deferred
    }

    private suspend fun prepareVersionAlignment(
        sourceSong: Song,
        targetSong: Song,
        currentPosMs: Long,
    ): Long {
        val sourceId = sourceSong.videoId
        val targetId = targetSong.videoId

        VersionAudioAligner.getCachedOffsetMs(sourceId, targetId)?.let { cached ->
            val cachedToken = VersionAudioAligner.beginAttempt(sourceId, targetId)
            VersionAudioAligner.publishPhase(
                cachedToken,
                VersionAudioAligner.CutPhase.ALIGNED,
                cached,
                fromCache = true,
            )
            return cached
        }

        val token = VersionAudioAligner.beginAttempt(sourceId, targetId)
        try {
            VersionAudioAligner.publishPhase(token, VersionAudioAligner.CutPhase.FETCHING)
            val deferred = measureVersionAlignmentAsync(sourceSong, targetSong)
            VersionAudioAligner.publishPhase(token, VersionAudioAligner.CutPhase.MEASURING)
            val measured = deferred.await()
            if (measured == null) {
                TrackLog.w(
                    "BitChord",
                    "version alignment found nothing to measure for '${sourceSong.title}'; switching with no shift",
                    sourceId,
                )
                VersionAudioAligner.publishPhase(token, VersionAudioAligner.CutPhase.FAILED)
                return 0L
            }
            VersionAudioAligner.publishPhase(token, VersionAudioAligner.CutPhase.ALIGNED, measured)
            return measured
        } catch (e: CancellationException) {
            // Outranked by a second switch, or the service went away mid-measure.
            // Left as it stands, the status line would keep reporting a swap
            // that is never going to land. The measurement itself is
            // unaffected — see [measureVersionAlignmentAsync].
            VersionAudioAligner.publishPhase(token, VersionAudioAligner.CutPhase.FAILED)
            throw e
        }
    }

    /**
     * Takes over [incoming] once the fade has landed on it: the service moves
     * the session onto it ([handOverDecks]), and [outgoing] is stopped and
     * emptied for the next transition to use.
     */
    private fun adoptPlayerForVersionSwap(outgoing: ExoPlayer, incoming: ExoPlayer) {
        handOverDecks(outgoing, incoming)

        incoming.volume = 1f
        outgoing.stop()
        outgoing.clearMediaItems()
        outgoing.volume = 1f

        markSwapping(null)
        refreshButtons()

        onBecameCurrent(incoming.currentMediaItem)
    }

    /** Reopens the current song through YouTube and remembers that choice. */
    fun revertCurrentToOriginal() {
        val activePlayer = player() ?: return
        val index = activePlayer.currentMediaItemIndex
        if (index !in 0 until activePlayer.mediaItemCount) return
        val song = activePlayer.currentMediaItem?.toSong() ?: return
        if (!song.hasYouTubeOriginal() ||
            song.localUri != null ||
            OriginalVersion.isPinned(song.videoId)
        ) {
            return
        }

        OriginalVersion.pin(song.videoId)
        val position = activePlayer.currentPosition
        val wasPlaying = activePlayer.isPlaying
        markSwapping(song.videoId)
        activePlayer.replaceMediaItem(index, song.toDirectYouTubeMediaItem())
        activePlayer.seekTo(index, position)
        if (wasPlaying) activePlayer.play()
        refreshButtons()
    }

    /**
     * Swaps the current track to an alternate version (video/audio).
     * This is called from the UI when the user wants to switch between
     * video and audio versions of a track.
     */
    fun swapCurrentToVersion(args: Bundle) {
        val activePlayer = player() ?: return
        val index = activePlayer.currentMediaItemIndex
        if (index !in 0 until activePlayer.mediaItemCount) return
        val song = activePlayer.currentMediaItem?.toSong() ?: return

        val targetVideoId = args.getString("targetVideoId")
        val targetIsVideo = args.getBoolean("targetIsVideo", false)

        if (targetVideoId == null || targetVideoId == song.videoId) return

        val position = activePlayer.currentPosition
        val wasPlaying = activePlayer.isPlaying
        // The new item has a different id, so the transition callback must be
        // told to treat it as the same listen, not a new track to record.
        markSwapping(targetVideoId)
        PlaybackTracker.onVersionSwapped(song.videoId, targetVideoId)

        // Create the target song with the appropriate flags
        val targetSong = song.copy(
            videoId = targetVideoId,
            isVideo = targetIsVideo,
            isVideoOrigin = if (targetIsVideo) false else song.isVideoOrigin,
        )

        activePlayer.replaceMediaItem(index, targetSong.toMediaItem())
        activePlayer.seekTo(index, position)
        if (wasPlaying) activePlayer.play()
        refreshButtons()
    }
}

/**
 * The shape of a version swap's fade: two cuts of one song crossing in about
 * half a second at equal power, so the pair never dips or swells mid-cross.
 */
internal object VersionSwapFade {
    /** Long enough to hide the seam between two masters, short enough to read as one song. */
    const val FADE_MS = 550L

    /** How long the incoming cut gets to buffer before the swap is applied directly instead. */
    const val STANDBY_READY_TIMEOUT_MS = 7_000L

    /** The volumes of the two players at one moment, and the blend headroom over them. */
    data class Gains(val incoming: Float, val outgoing: Float, val trim: Float)

    /** How far through the fade [elapsedMs] is, from 0 to 1. */
    fun progress(elapsedMs: Long): Float = (elapsedMs.toFloat() / FADE_MS).coerceIn(0f, 1f)

    /**
     * Equal-power gains at [progress], with the same headroom a crossfade
     * takes, and for the same reason — see [LoudnessProcessor]. More so here:
     * two versions of one song line up peak for peak.
     */
    fun gains(progress: Float): Gains {
        val p = progress.coerceIn(0f, 1f)
        val incoming = sin(p * (PI / 2.0)).toFloat()
        val outgoing = cos(p * (PI / 2.0)).toFloat()
        return Gains(incoming, outgoing, trim = 1f / sqrt((incoming + outgoing).coerceAtLeast(1f)))
    }

    /** A playing position moved by a measured offset between the cuts, never before the start. */
    fun shifted(positionMs: Long, offsetMs: Long): Long = (positionMs + offsetMs).coerceAtLeast(0L)
}
