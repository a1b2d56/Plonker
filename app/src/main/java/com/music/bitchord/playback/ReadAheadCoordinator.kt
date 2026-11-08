package com.music.bitchord.playback

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.AudioQuality
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.data.sources.SourceResolver
import com.music.bitchord.data.sources.SourceStream
import com.music.bitchord.data.sources.TrackMatcher
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Getting what comes after the playing track ready before it is needed.
 *
 * Ordinary read-ahead hands [AudioCache] the queue ahead ([prefetchAround]);
 * Direct Source Stream instead opens the next track only in the last stretch
 * before its transition ([driveDirectWindow]). "Prefer music-only version"
 * turns the playing and next rows into their audio versions
 * ([convertVideosAround]). An addon copy that decodes to the wrong length is
 * put back on YouTube ([checkSubstituteLength]), a lowered quality ceiling
 * lets go of what it no longer pays for ([onCeilingLowered]), and what an
 * addon served that cannot be kept is dropped once the queue has moved past
 * it ([scheduleForeignCachePurge]).
 *
 * [player] and [spare] are read when needed, never captured: the session
 * moves between them at every crossfade.
 */
@UnstableApi
internal class ReadAheadCoordinator(
    private val scope: CoroutineScope,
    private val player: () -> ExoPlayer?,
    private val spare: () -> ExoPlayer?,
    private val transitioning: () -> Boolean,
    /** Where a planned crossfade or Automix leaves the track with this id, if one is planned. */
    private val plannedTransitionFor: (String) -> Long?,
    private val versionSwapActive: () -> Boolean,
    /** Switch the playing track over to another version of it without a gap. */
    private val swapVersion: (MediaItem) -> Unit,
    private val recovery: StreamRecoveryCoordinator,
) {
    /** The upcoming ids read-ahead was last asked to warm, so an unchanged queue isn't asked again. */
    private var preferredPrefetchRequest: List<String>? = null

    private var foreignCachePurgeJob: Job? = null

    /**
     * Lets go of what an addon or module served that cannot be kept — a partial
     * file, an upgrade's rendition, a manifest — and whatever Automix
     * downloaded only to analyse, for tracks the queue has moved past. A whole
     * addon file stays; see [AudioCache.dropForeignEntries].
     *
     * Delayed so a crossfade has finished with the outgoing track, and a
     * run of skips collapses into one pass. The tracks around the playhead
     * are kept: they may still be read, seeked through or analysed.
     */
    fun scheduleForeignCachePurge() {
        foreignCachePurgeJob?.cancel()
        foreignCachePurgeJob = scope.launch {
            delay(FOREIGN_CACHE_PURGE_DELAY_MS)
            val exoPlayer = player() ?: return@launch
            val keep = buildSet {
                exoPlayer.currentMediaItem?.mediaId?.let(::add)
                val current = exoPlayer.currentMediaItemIndex
                if (current != C.INDEX_UNSET) {
                    val window = (current - 1)..(current + FOREIGN_CACHE_KEEP_AHEAD)
                    for (index in window) {
                        if (index in 0 until exoPlayer.mediaItemCount) add(exoPlayer.getMediaItemAt(index).mediaId)
                    }
                }
                spare()?.currentMediaItem?.mediaId?.let(::add)
            }
            AudioCache.dropForeignEntries(keep)
        }
    }

    /**
     * The quality ceiling just stopped paying for [lost] — the setting was
     * lowered, the phone moved onto mobile data, or the watch took the music.
     *
     * [SourceRegistry.activeForPlayback] already keeps those sources out of
     * every resolve from here on, but a lot of playback is never resolved
     * again: read-ahead pins the next track's source ahead of time
     * ([StreamChoice]), an upgrade search started under the old ceiling can
     * still land ([QualityUpgrade]), and a partial addon file sits in the
     * cache waiting to be finished over the connection the new ceiling is
     * guarding. Each of those would keep an addon streaming after the listener
     * asked for it not to, so each is let go here. A whole addon file is not:
     * playing it off disk costs the connection nothing, which is all the
     * ceiling is about.
     *
     * Except for what is playing. Its cache entry is half filled and only its
     * own stream may finish it, so it plays out where it is and the new
     * ceiling starts with the next track. The next track is spared too once
     * the player has started loading it, for the same reason.
     */
    fun onCeilingLowered(ceiling: AudioQuality, lost: List<SourceKind>) {
        val exoPlayer = player() ?: return
        val keep = buildSet {
            exoPlayer.currentMediaItem?.mediaId?.let(::add)
            spare()?.currentMediaItem?.mediaId?.let(::add)
            val duration = exoPlayer.duration
            val next = exoPlayer.nextMediaItemIndex
            if (duration != C.TIME_UNSET && next != C.INDEX_UNSET &&
                exoPlayer.totalBufferedDuration >= duration - exoPlayer.currentPosition
            ) {
                add(exoPlayer.getMediaItemAt(next).mediaId)
            }
        }
        TrackLog.d(
            "BitChord",
            "quality ceiling now ${ceiling.name}; letting go of ${lost.joinToString { it.label }} " +
                "except for ${keep.joinToString()}",
        )
        val permitted = { stream: SourceStream -> SourceRegistry.permitsForPlayback(stream.sourceConfigId) }
        recovery.cancelStaleUpgradeJob()
        cancelPrefetch()
        StreamChoice.forgetWhere(keep) { !permitted(it) }
        QualityUpgrade.onCeilingLowered(keep, permitted)
        AudioCache.dropForeignEntries(keep, keepAnalysis = true)
        prefetchAround(exoPlayer)
        recovery.lookForBetterCopy(exoPlayer)
    }

    /**
     * Hands the cache the queue ahead of the one playing: [AudioCache.QUEUE_DEPTH]
     * tracks is more than it does anything with, but it decides that, not this.
     */
    fun prefetchAround(player: ExoPlayer) {
        // Direct Source Stream reads ahead from its own window instead — see
        // [driveDirectWindow]. Anything the ordinary read-ahead had running
        // for the old queue is dropped with it.
        if (DirectSourceGate.isActive()) {
            driveDirectWindow()
            if (preferredPrefetchRequest != null) cancelPrefetch()
            return
        }
        val nextIndex = player.nextMediaItemIndex
        val upcomingSongs = if (nextIndex != C.INDEX_UNSET) {
            val end = (nextIndex + AudioCache.QUEUE_DEPTH - 1).coerceAtMost(player.mediaItemCount - 1)
            // A video about to be converted plays under another id, so every
            // byte warmed for it now would be wasted.
            (nextIndex..end).map(player::getMediaItemAt)
                .filterNot(MusicOnlyPlayback::wants)
                .map { it.toSong() }
        } else {
            emptyList()
        }
        // So whatever read-ahead writes for these can be named in the Cached
        // songs folder; its own requests carry nothing but an id.
        AudioCache.noteSongs(upcomingSongs)
        val request = upcomingSongs.map { it.videoId }
        if (request == preferredPrefetchRequest) return
        preferredPrefetchRequest = request

        fun warm(songs: List<Song>) {
            AudioCache.prefetchQueue(
                songs.map { song ->
                    // The title, artist and runtime the item was built with —
                    // see [Song.toMediaItem]. Read from the queued item because
                    // read-ahead has no other way to reach this metadata.
                    AudioCache.Upcoming(
                        mediaId = song.videoId,
                        target = TrackMatcher.targetOf(song),
                    )
                },
            )
        }

        warm(upcomingSongs)
    }

    /** Audio-version lookups running for the music-only setting, by video id. */
    private val musicOnlyLookups = HashMap<String, Job>()

    /**
     * Turns the playing row and the next one into their audio versions, for
     * "Prefer music-only version" — see [MusicOnlyPlayback]. Run whenever either
     * could have changed; a row already answered costs a map lookup.
     *
     * Not mid-blend or mid-swap, which both copy this queue onto the standby
     * player. Both end in [onTrackBecameCurrent], which comes back here.
     */
    fun convertVideosAround(exoPlayer: ExoPlayer) {
        if (!MusicOnlyPlayback.isActive()) return
        if (transitioning() || versionSwapActive()) return
        val rows = listOf(exoPlayer.currentMediaItemIndex, exoPlayer.nextMediaItemIndex)
            .filter { it in 0 until exoPlayer.mediaItemCount }
            .distinct()
        MusicOnlyPlayback.forgetExcept(rows.map { exoPlayer.getMediaItemAt(it).mediaId }.toSet())
        for (index in rows) {
            val item = exoPlayer.getMediaItemAt(index)
            if (!MusicOnlyPlayback.wants(item)) continue
            val video = item.toSong()
            val audio = YtMusicRepository.cachedAudioVersion(video.videoId)
            when {
                audio == null -> lookUpMusicOnly(video)
                audio.videoId == video.videoId || audio.isVideo -> MusicOnlyPlayback.settle(video.videoId)
                else -> {
                    applyMusicOnly(exoPlayer, index, item, video, audio)
                    // Replacing a row can run the whole transition, which trims
                    // history, so the indexes above may no longer hold.
                    convertVideosAround(exoPlayer)
                    return
                }
            }
        }
    }

    private fun lookUpMusicOnly(video: Song) {
        if (musicOnlyLookups[video.videoId]?.isActive == true) return
        musicOnlyLookups[video.videoId] = scope.launch {
            val audio = withTimeoutOrNull(MusicOnlyPlayback.ANSWER_TIMEOUT_MS) {
                runCatching { YtMusicRepository.resolveAudio(video) }.getOrNull()
            }
            musicOnlyLookups.remove(video.videoId)
            if (audio == null || audio.videoId == video.videoId) {
                TrackLog.d("BitChord", "music-only: no audio version, playing the video's own audio", about = video.videoId)
                MusicOnlyPlayback.settle(video.videoId)
            }
            val live = player() ?: return@launch
            convertVideosAround(live)
            if (live.isPlaying) prefetchAround(live)
        }
    }

    private fun applyMusicOnly(exoPlayer: ExoPlayer, index: Int, item: MediaItem, video: Song, audio: Song) {
        TrackLog.d("BitChord", "music-only: playing '${audio.title}' (${audio.videoId})", about = video.videoId)
        YtMusicRepository.rememberVideoVersion(audio.videoId, video)
        val replacement = QueueShuffle.withQueueMetadata(
            audio.asMusicOnlyOf(video).toMediaItem(),
            newCanonicalIndex = item.canonicalIndex,
            newContextCycleId = item.contextCycleId,
        )
        val isCurrent = index == exoPlayer.currentMediaItemIndex
        // Only already audible when the setting was turned on with a video
        // playing: that one is switched over like the player menu's switch.
        if (isCurrent && exoPlayer.isPlaying) {
            swapVersion(replacement)
            return
        }
        val position = if (isCurrent) exoPlayer.currentPosition else 0L
        exoPlayer.replaceMediaItem(index, replacement)
        // A queue restored paused part-way through a video resumes where it was.
        if (position > 0L) {
            val mapped = YtMusicRepository.versionTimeMap(video.videoId, audio.videoId)?.map(position) ?: position
            exoPlayer.seekTo(exoPlayer.currentMediaItemIndex, mapped.coerceAtLeast(0L))
        }
    }

    /** The playback URI [checkSubstituteLength] last measured, so each opened item is measured once. */
    private var lengthCheckedUri: String? = null

    /**
     * Puts a YouTube-queued track back on YouTube when the addon copy it
     * started on decodes to the wrong length — the check the upgrade path's
     * audition makes before any swap, made here for a copy that played from
     * the first note with no audition behind it.
     *
     * What it catches is what the catalogue cannot see: an addon's 30-second
     * preview, or a different cut, behind a row that claimed the right
     * runtime. [SourceResolver.directStream] already refuses what it can
     * measure up front — rows of the wrong length, files too small to hold the
     * track — and this is the guarantee behind it, since a server that will
     * not say how big its file is gets past that.
     *
     * The bar is the listener's "Track length tolerance", the same one the
     * audition and [SourceResolver.upgradeFor] use, measured against the
     * runtime the queue row carried (or the one [DirectSourceGate] looked up
     * for a row that carried none). A second is added for rounding, because
     * that runtime is whole seconds and the decoder's is not.
     *
     * Only a copy known to be an addon's is held to the tolerance. Bytes
     * already on disk from an earlier session have no record of who served
     * them, and may be YouTube's own, whose length can sit a few seconds off
     * its listing; those are refused only when they hold under half the
     * track, which is a preview played back from the cache and nothing else.
     *
     * Ticked from [driveDirectWindow], so it runs whether or not Direct Source
     * Stream is on: an addon that wins the ordinary race plays without an
     * audition too.
     */
    private fun checkSubstituteLength(exoPlayer: ExoPlayer) {
        val item = exoPlayer.currentMediaItem ?: return
        val uri = item.localConfiguration?.uri ?: return
        // A track picked from an addon is that addon's or nothing, an upgrade
        // was auditioned before it was swapped in, and a revert is YouTube's
        // own audio — none of them is a substitute to measure.
        if (uri.scheme != "bitchord" || uri.authority == "source") return
        if (QualityUpgrade.cacheTag(uri) != null) return
        val key = uri.toString()
        if (key == lengthCheckedUri) return
        if (exoPlayer.playbackState != Player.STATE_READY) return
        val decodedMs = exoPlayer.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return
        lengthCheckedUri = key
        val videoId = uri.getQueryParameter("v") ?: return
        val expectedSec = SourceResolver.targetIn(uri).durationSec
            ?: DirectSourceGate.knownLength(videoId)
            ?: return
        val expectedMs = expectedSec * 1_000L
        val substituted = StreamChoice.isSubstitute(videoId)
        val unattributed = StreamChoice.of(videoId) == null && SourceResolver.canSubstituteForYouTube()
        val wrong = ReadAheadRules.wrongLength(
            decodedMs = decodedMs,
            expectedMs = expectedMs,
            substituted = substituted,
            unattributed = unattributed,
            toleranceMs = recovery.upgradeLengthSlackMs,
        )
        if (!wrong) return
        TrackLog.w(
            "BitChord",
            "'${item.mediaMetadata.title}' decodes to ${decodedMs}ms against ${expectedSec}s listed " +
                "(tolerance ±${AppSettings.upgradeLengthSlackSeconds.value}s) on " +
                if (substituted) "an addon copy" else "a cached copy of unknown origin",
            about = item.mediaId,
        )
        // From the top: what played was not this recording, so where it had
        // got to says nothing about where this one should start.
        recovery.replaceWithYouTube(
            item = item,
            playbackUri = uri,
            position = 0L,
            keepComplete = false,
            why = "is the wrong length on the copy it started on",
        )
    }

    /** When the listener last seeked, so [driveDirectWindow] can tell a scrub from playback. */
    var lastSeekAt = 0L

    /** Ticks [driveDirectWindow] for as long as the service runs. */
    fun watchDirectWindow() {
        scope.launch {
            while (isActive) {
                driveDirectWindow()
                delay(DIRECT_WINDOW_STEP_MS)
            }
        }
    }

    /**
     * Direct Source Stream's read-ahead, and the only one it has.
     *
     * Tells [DirectSourceGate] what is audible, then opens the next track once
     * the playing one is within [DirectSourceGate.WINDOW_MS] of its transition.
     * That is the crossfade or Automix start when one is planned, otherwise the
     * end of the file. Opening it releases ExoPlayer's own preparation of the
     * next item and starts [AudioCache.prefetchDirect].
     *
     * Only while playing, and only once the playhead has run on its own for
     * [SEEK_SETTLE_MS] since the last seek, so scrubbing toward the end of a
     * track or skipping through a queue never fetches anything ahead.
     */
    fun driveDirectWindow() {
        val exoPlayer = player() ?: return
        checkSubstituteLength(exoPlayer)
        val audible = buildSet {
            exoPlayer.currentMediaItem?.mediaId?.let(::add)
            if (transitioning()) spare()?.currentMediaItem?.mediaId?.let(::add)
        }
        if (DirectSourceGate.onPlaying(audible)) AudioCache.cancelDirect()
        if (!DirectSourceGate.isActive() || !exoPlayer.isPlaying) return
        if (SystemClock.elapsedRealtime() - lastSeekAt < SEEK_SETTLE_MS) return
        val current = exoPlayer.currentMediaItem ?: return
        val nextIndex = exoPlayer.nextMediaItemIndex
        if (nextIndex == C.INDEX_UNSET) return
        val next = exoPlayer.getMediaItemAt(nextIndex)
        if (MusicOnlyPlayback.wants(next)) return
        val duration = exoPlayer.duration
        if (duration == C.TIME_UNSET || duration <= 0L) return
        val boundary = plannedTransitionFor(current.mediaId) ?: duration
        val remaining = ReadAheadRules.msToTransition(boundary, exoPlayer.currentPosition, exoPlayer.playbackParameters.speed)
        if (remaining > DirectSourceGate.WINDOW_MS) return
        if (!DirectSourceGate.openNext(next.mediaId)) return
        TrackLog.d(
            "BitChord",
            "direct window open: ${next.mediaId} with ${remaining}ms to the transition",
            about = next.mediaId,
        )
        val song = next.toSong()
        AudioCache.noteSongs(listOf(song))
        AudioCache.prefetchDirect(AudioCache.Upcoming(song.videoId, TrackMatcher.targetOf(song)))
    }

    /** Stops both rendition selection and byte read-ahead, and makes resume retry. */
    fun cancelPrefetch() {
        preferredPrefetchRequest = null
        AudioCache.cancel()
    }

    private companion object {
        /** See [scheduleForeignCachePurge]: comfortably past the longest crossfade. */
        const val FOREIGN_CACHE_PURGE_DELAY_MS = 30_000L
        /** How many upcoming tracks [scheduleForeignCachePurge] leaves alone for read-ahead and Automix. */
        const val FOREIGN_CACHE_KEEP_AHEAD = 3

        /** How often [driveDirectWindow] checks whether the next track's window has opened. */
        const val DIRECT_WINDOW_STEP_MS = 500L

        /**
         * How long the playhead has to run on its own after a seek before the
         * direct window may open. Long enough that a scrub, which is a burst
         * of seeks, never counts as having reached the end of a track.
         */
        const val SEEK_SETTLE_MS = 2_000L

    }
}

/** The read-ahead decisions that need no player, kept apart so they can be checked. */
internal object ReadAheadRules {
    /** Floor on the speed the window divides by, so a near-zero speed cannot stretch it to forever. */
    const val MIN_WINDOW_SPEED = 0.25f

    /** The decoder's runtime is a whole-seconds catalogue figure's rounding away from it. */
    const val ROUNDING_MS = 1_000L

    /**
     * Whether a copy that decoded to [decodedMs] is not the recording listed
     * at [expectedMs]. An addon's copy ([substituted]) is held to the
     * listener's [toleranceMs]; bytes on disk with no record of who served
     * them ([unattributed]) only when they hold under half the track — a
     * cached preview. Anything else is YouTube's own and is left alone.
     */
    fun wrongLength(
        decodedMs: Long,
        expectedMs: Long,
        substituted: Boolean,
        unattributed: Boolean,
        toleranceMs: Long,
    ): Boolean = when {
        substituted -> abs(decodedMs - expectedMs) > toleranceMs + ROUNDING_MS
        unattributed -> decodedMs < expectedMs / 2
        else -> false
    }

    /** Real time until the playhead at [positionMs] reaches [boundaryMs] at [speed]. */
    fun msToTransition(boundaryMs: Long, positionMs: Long, speed: Float): Long =
        ((boundaryMs - positionMs) / speed.coerceAtLeast(MIN_WINDOW_SPEED)).toLong()
}
