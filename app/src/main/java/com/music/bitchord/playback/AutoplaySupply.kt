package com.music.bitchord.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.listentogether.ListenTogether
import com.music.bitchord.data.listentogether.partyQueueIndexOf
import com.music.bitchord.data.listentogether.partyUpcomingAfter
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * AutoPlay's supply of suggestions after the queue: keeping up to
 * [MAX_QUEUED_AUTOPLAY] of them waiting ahead of the playing track, asking
 * again (seeding from earlier songs) while a radio comes back empty, putting
 * them into a party's shared queue instead of the player's when one is
 * running, standing them aside for repeat-all, and taking them back when
 * AutoPlay is switched off.
 *
 * The rules (whether it is on in a party, who supplies it, when an emptied
 * tail needs refreshing, the station fetch itself) are [autoplayEnabledFor],
 * [autoplaySupplierId], [autoplayQueueNeedsRefresh] and [loadAutoplayTracks];
 * this applies them to the session's player, read through [player] when
 * needed since the session moves between players at every crossfade. A
 * bounded album or playlist holds AutoPlay back until its own list runs out:
 * see [BoundedContextController.boundedAutoplayEligible].
 */
@UnstableApi
class AutoplaySupply(
    private val scope: CoroutineScope,
    private val player: () -> ExoPlayer?,
    private val bounded: BoundedContextController,
    private val refreshButtons: () -> Unit,
) {
    private var autoplayLoadJob: Job? = null

    /** The track the current top-up was asked for, so one track change asks once. */
    private var autoplaySeed: String? = null

    /**
     * What AutoPlay had queued when repeat-all was switched on, held so
     * switching it off again puts back the same tracks rather than a fresh
     * mix.
     *
     * Repeat-all loops the queue as it stands, so AutoPlay's endless supply of
     * new tracks comes out of it first — see the service's repeat-mode listener. Fetching a
     * replacement mix afterwards is the obvious thing to do and the wrong one:
     * the track that was queued next has usually been analysed for the
     * transition into it by then (see
     * [com.music.bitchord.playback.smart.TrackAnalyzer]), and a different track
     * in its place means that several-second decode is spent again, on a song
     * that is now much closer than it was. Turning repeat on and back off
     * should leave the queue where it found it.
     */
    private var repeatAllStash: List<MediaItem> = emptyList()

    /**
     * The track that was playing when [repeatAllStash] was taken. The stash
     * describes what came after *that* track, so it is only put back if the
     * queue has not moved on in the meantime.
     */
    private var repeatAllStashSeed: String? = null

    /**
     * Every song AutoPlay has offered or played this service instance, kept
     * only so "don't repeat suggestions" has something to check against once
     * a song scrolls out of the live queue or the queue itself is replaced.
     * Never persisted — a fresh process means a fresh session.
     */
    private val sessionSongHistory = mutableListOf<Song>()

    fun toggleFromNotification() {
        val party = ListenTogether.state.value
        val enabled = if (party.inParty) !party.playback.autoplayEnabled else !AppSettings.autoplay.value
        AppSettings.setAutoplay(enabled)
        if (party.inParty) {
            // The party owns this setting while connected. The state frame
            // wakes whichever connected member currently won the supplier
            // election, including after the host has become unreachable.
            ListenTogether.setAutoplay(enabled)
            refreshButtons()
            return
        }
        if (enabled) {
            autoplayLoadJob?.cancel()
            autoplayLoadJob = null
            autoplaySeed = null
            load()
        } else {
            autoplayLoadJob?.cancel()
            autoplayLoadJob = null
            autoplaySeed = null
            dropAutoplayTracksFromQueue()
            // Switching AutoPlay off is the listener saying they don't want
            // those tracks; leaving a stash behind would put them back the next
            // time repeat-all ended.
            repeatAllStash = emptyList()
            repeatAllStashSeed = null
        }
        refreshButtons()
    }

    /**
     * Tops the queue back up to [MAX_QUEUED_AUTOPLAY] AutoPlay-suggested tracks
     * ahead of whatever is currently playing. Run on every track change rather
     * than only once the queue runs dry, so a freshly played suggestion is
     * replaced by a new one appended after the ones still waiting instead of
     * everything arriving in one burst at the end of the queue.
     */
    fun load() {
        val exoPlayer = player() ?: return
        val party = ListenTogether.state.value
        if (!autoplayEnabled(party) ||
            exoPlayer.repeatMode == Player.REPEAT_MODE_ALL ||
            (party.inParty && autoplaySupplierId(party) != party.you?.memberId)
        ) {
            return
        }
        if (bounded.active && !bounded.boundedAutoplayEligible(exoPlayer)) {
            return
        }
        val current = exoPlayer.currentMediaItem?.toSong() ?: return
        if (AppSettings.dontRepeatSuggestions.value) sessionSongHistory += current
        // In a party the server's queue is the one being topped up, and this
        // player's copy of it lags behind by however long reconcile is held off
        // — read from the copy, a batch that had already landed counted as
        // missing and the same station was sent again.
        val queuedAutoplay = partyAutoplayWaiting(party, current.videoId)
            ?: (exoPlayer.currentMediaItemIndex + 1 until exoPlayer.mediaItemCount)
                .count { exoPlayer.getMediaItemAt(it).fromAutoplay }
        val needed = MAX_QUEUED_AUTOPLAY - queuedAutoplay
        if (needed <= 0) return
        if (autoplaySeed == current.videoId) return
        autoplaySeed = current.videoId
        autoplayLoadJob = scope.launch {
            var remaining = needed
            var emptyRefreshesRemaining = MAX_AUTOPLAY_EMPTY_REFRESHES
            // Which song seeds the radio: this one first, then — when its radio
            // has nothing new left — the songs before it, one further back each
            // time, as the web player's watchdog does.
            var seedBack = 0
            while (isActive) {
                val activePlayer = player() ?: return@launch
                val activeParty = ListenTogether.state.value
                if (!autoplayEnabled(activeParty) ||
                    activePlayer.repeatMode == Player.REPEAT_MODE_ALL ||
                    activePlayer.currentMediaItem?.mediaId != current.videoId ||
                    (activeParty.inParty && autoplaySupplierId(activeParty) != activeParty.you?.memberId)
                ) {
                    return@launch
                }
                val queueSongs = (0 until activePlayer.mediaItemCount)
                    .map { activePlayer.getMediaItemAt(it).toSong() } +
                    activeParty.queue.items.takeIf { activeParty.inParty }.orEmpty().map { it.toSong() }
                val existing = if (AppSettings.dontRepeatSuggestions.value) {
                    queueSongs + sessionSongHistory
                } else {
                    queueSongs
                }
                val seedSong = if (seedBack == 0) {
                    current
                } else {
                    val at = activePlayer.currentMediaItemIndex - seedBack
                    if (at >= 0) activePlayer.getMediaItemAt(at).toSong() else current
                }
                val result = loadAutoplayTracks(existing, seedSong, remaining)
                val resolved = result.getOrElse {
                    TrackLog.w(
                        "BitChord",
                        "notification autoplay failed: ${it.message}",
                        about = current.videoId,
                    )
                    emptyList()
                }
                val latestPlayer = player() ?: return@launch
                val latestParty = ListenTogether.state.value
                if (!autoplayEnabled(latestParty) ||
                    latestPlayer.repeatMode == Player.REPEAT_MODE_ALL ||
                    latestPlayer.currentMediaItem?.mediaId != current.videoId ||
                    (latestParty.inParty && autoplaySupplierId(latestParty) != latestParty.you?.memberId)
                ) {
                    return@launch
                }
                if (resolved.isNotEmpty()) {
                    if (latestParty.inParty) {
                        // Do not mutate ExoPlayer directly here. The server's
                        // state broadcast reconciles every device atomically,
                        // including this one, and keeps the AutoPlay section
                        // identical for all listeners.
                        val landed = topUpPartyAutoplay(current.videoId, resolved)
                        if (!landed) {
                            TrackLog.w(
                                "BitChord",
                                "party did not take AutoPlay's tracks; releasing the seed to retry",
                                about = current.videoId,
                            )
                            // Nothing was queued, so these are still unheard and
                            // must not be written off as already suggested.
                            autoplaySeed = null
                            return@launch
                        }
                    } else {
                        // The queue may have changed while the radio request
                        // was out — a station landing behind it is the usual
                        // case — so check against what is queued now, not the
                        // snapshot the batch was picked against.
                        val queuedNow = (0 until latestPlayer.mediaItemCount)
                            .map { latestPlayer.getMediaItemAt(it).toSong() }
                        val room = MAX_QUEUED_AUTOPLAY -
                            (latestPlayer.currentMediaItemIndex + 1 until latestPlayer.mediaItemCount)
                                .count { latestPlayer.getMediaItemAt(it).fromAutoplay }
                        val fresh = resolved
                            .filterNot { song -> queuedNow.any { QueueBuilder.isSameRecording(it, song) } }
                            .take(room.coerceAtLeast(0))
                        if (fresh.isNotEmpty()) latestPlayer.addMediaItems(fresh.map { it.toMediaItem() })
                    }
                    if (AppSettings.dontRepeatSuggestions.value) sessionSongHistory += resolved
                    return@launch
                }

                // Empty and failed responses used to leave [autoplaySeed]
                // latched to this track, suppressing every later callback and
                // leaving AutoPlay visibly on with no queue. While the current
                // item is genuinely the end of the queue, make a delayed fresh
                // request. Keep it bounded: a radio with no usable unique songs
                // must not turn into a permanent background polling loop: it
                // asks again every [AUTOPLAY_WATCHDOG_MS] only while this song
                // plays at the end of the queue, and stops with it.
                val at = latestPlayer.currentMediaItemIndex
                if (at < 0 || at != latestPlayer.mediaItemCount - 1) return@launch
                if (emptyRefreshesRemaining-- > 0) {
                    delay(AUTOPLAY_EMPTY_REFRESH_DELAY_MS)
                } else {
                    delay(AUTOPLAY_WATCHDOG_MS)
                    // Paused: wait for play rather than asking on behalf of nobody.
                    while (isActive && player()?.playWhenReady == false) delay(AUTOPLAY_WATCHDOG_MS)
                    // This song's radio came back with nothing new: seed from an earlier one.
                    seedBack = if (at - (seedBack + 1) >= 0) seedBack + 1 else 0
                }
                remaining = MAX_QUEUED_AUTOPLAY
            }
        }
    }

    /**
     * How many AutoPlay tracks the party already has waiting after [videoId],
     * or null outside a party or while its queue does not hold that track yet.
     */
    private fun partyAutoplayWaiting(party: ListenTogether.State, videoId: String): Int? {
        if (!party.inParty) return null
        if (partyQueueIndexOf(party.queue, party.playback, videoId) < 0) return null
        return partyUpcomingAfter(party.queue, party.playback, videoId).count { it.fromAutoplay }
    }

    /** One party top-up at a time; see [topUpPartyAutoplay]. */
    private val partyAutoplayLock = Mutex()

    /**
     * Sends [suggestions] to the party and waits for them to come back.
     *
     * A control is a request, not a write, and the server does refuse these: a
     * queue already at its upcoming limit, or a party locked to a host this
     * device is not. The refusal comes back on a frame nothing here is waiting
     * for, so the party's own copy of the queue is the only confirmation — and
     * what this returns.
     *
     * Serialised, and the send-and-wait not cancellable, because a track change
     * cancels the load in flight and starts another: the second used to read
     * the party before the first batch had echoed, find nothing waiting, and
     * send the same station again — every suggestion twice, on every device.
     * Holding the lock until the echo means the next top-up reads a queue that
     * already has these in it, and filters against it.
     */
    private suspend fun topUpPartyAutoplay(currentId: String, suggestions: List<Song>): Boolean =
        partyAutoplayLock.withLock {
            withContext(NonCancellable) {
                val party = ListenTogether.state.value
                val waiting = partyUpcomingAfter(party.queue, party.playback, currentId)
                val waitingIds = waiting.mapTo(HashSet()) { it.videoId }
                val room = MAX_QUEUED_AUTOPLAY - waiting.count { it.fromAutoplay }
                val fresh = suggestions.filterNot { it.videoId in waitingIds }.take(room.coerceAtLeast(0))
                if (fresh.isEmpty()) return@withContext true
                ListenTogether.queueAdd(fresh.map { it.toPartyTrack(0L) })
                val added = fresh.first().videoId
                withTimeoutOrNull(PARTY_QUEUE_ECHO_TIMEOUT_MS) {
                    ListenTogether.state.first { state -> state.queue.items.any { it.videoId == added } }
                } != null
            }
        }

    /** AutoPlay tracks ahead of the current one as of the last queue edit. */
    private var autoplayAheadSeen = 0

    /** Re-arms AutoPlay when an external queue edit empties or shrinks its tail. */
    fun refreshIfQueueEmpty() {
        val exoPlayer = player() ?: return
        val aheadNow = (exoPlayer.currentMediaItemIndex + 1 until exoPlayer.mediaItemCount)
            .count { exoPlayer.getMediaItemAt(it).fromAutoplay }
        // Removing some of AutoPlay's tracks leaves a non-empty tail, which the
        // empty-tail check below misses; a shrunken tail needs topping up too.
        val tailShrank = aheadNow < autoplayAheadSeen
        autoplayAheadSeen = aheadNow
        if (!autoplayQueueNeedsRefresh(
                enabled = autoplayEnabled(ListenTogether.state.value),
                repeatAll = exoPlayer.repeatMode == Player.REPEAT_MODE_ALL,
                currentIndex = exoPlayer.currentMediaItemIndex,
                itemCount = exoPlayer.mediaItemCount,
                loadInProgress = autoplayLoadJob?.isActive == true,
            ) && !(tailShrank && aheadNow < MAX_QUEUED_AUTOPLAY &&
                autoplayEnabled(ListenTogether.state.value) &&
                exoPlayer.repeatMode != Player.REPEAT_MODE_ALL &&
                autoplayLoadJob?.isActive != true)
        ) {
            return
        }
        autoplayLoadJob = null
        autoplaySeed = null
        load()
    }

    /** @see autoplayEnabledFor — shared with the player, which draws the toggle. */
    private fun autoplayEnabled(party: ListenTogether.State): Boolean =
        autoplayEnabledFor(party, AppSettings.autoplay.value)

    /**
     * Takes back what AutoPlay queued and hasn't played yet — what switching
     * AutoPlay off means for a queue it has already been extending. Removed
     * from the bottom up so the indexes ahead of each removal still hold, and
     * handed back in queue order for the one caller that intends to put them
     * in again.
     */
    private fun dropAutoplayTracksFromQueue(): List<MediaItem> {
        val exoPlayer = player() ?: return emptyList()
        val dropped = mutableListOf<MediaItem>()
        for (index in exoPlayer.mediaItemCount - 1 downTo exoPlayer.currentMediaItemIndex + 1) {
            val item = exoPlayer.getMediaItemAt(index)
            if (!item.fromAutoplay) continue
            dropped += item
            exoPlayer.removeMediaItem(index)
        }
        return dropped.reversed()
    }

    /** Clears the queue's AutoPlay tail for the duration of repeat-all, keeping it to put back. */
    fun stash() {
        val exoPlayer = player() ?: return
        // Only ever taken once per stretch of repeat-all: cycling
        // OFF -> ALL -> ONE -> OFF sets the mode three times, and the second
        // and third of those must not overwrite a full stash with the empty
        // queue tail the first one left behind.
        if (repeatAllStash.isNotEmpty()) return
        val dropped = dropAutoplayTracksFromQueue()
        if (dropped.isEmpty()) return
        repeatAllStash = dropped
        repeatAllStashSeed = exoPlayer.currentMediaItem?.mediaId
    }

    /**
     * Puts the stashed AutoPlay tracks back when repeat-all ends.
     *
     * Refused, rather than forced, in the cases where the stash no longer
     * describes the queue: AutoPlay switched off while the loop ran, or the
     * loop played on past the track the stash was taken behind. Both leave
     * [load] to fill the queue the ordinary way.
     */
    fun restore() {
        val exoPlayer = player() ?: return
        val stashed = repeatAllStash
        val seed = repeatAllStashSeed
        repeatAllStash = emptyList()
        repeatAllStashSeed = null
        // The seed is stale now either way, so the queue can be topped up again
        // for this track — without this the guard in [load]
        // reads a track it has already loaded for and returns, which is how a
        // queue whose stash was refused ended up with nothing after it at all.
        autoplayLoadJob?.cancel()
        autoplayLoadJob = null
        autoplaySeed = null
        if (stashed.isEmpty() || !autoplayEnabled(ListenTogether.state.value)) return
        if (exoPlayer.currentMediaItem?.mediaId != seed) return
        // A track the listener queued by hand during the loop is not queued
        // twice for having been in the mix before it.
        val present = (0 until exoPlayer.mediaItemCount)
            .mapTo(mutableSetOf()) { exoPlayer.getMediaItemAt(it).mediaId }
        val restored = stashed.filter { it.mediaId !in present }
        if (restored.isEmpty()) return
        exoPlayer.addMediaItems(restored)
    }

    /** Forgets the track it last topped up for, and tops up again for the one playing now. */
    fun reload() {
        autoplayLoadJob?.cancel()
        autoplayLoadJob = null
        autoplaySeed = null
        load()
    }

    /** A new queue (a station) replaces the old one: nothing about the old one carries over. */
    fun resetForNewQueue() {
        autoplayLoadJob?.cancel()
        autoplayLoadJob = null
        autoplaySeed = null
        clearStash()
        sessionSongHistory.clear()
    }

    /** Lets go of the repeat-all stash: it was suggested for music that is going. */
    fun clearStash() {
        repeatAllStash = emptyList()
        repeatAllStashSeed = null
    }

    private companion object {
        /** Avoid hammering the radio endpoint while an empty AutoPlay queue heals. */
        const val AUTOPLAY_EMPTY_REFRESH_DELAY_MS = 2_000L

        /** One quick fresh request first; then the watchdog below. */
        const val MAX_AUTOPLAY_EMPTY_REFRESHES = 1

        /**
         * With nothing after the playing song, AutoPlay asks again this often
         * (seeding from earlier songs once this one's radio is spent), for as
         * long as the song plays. The web player's watchdog does the same.
         */
        const val AUTOPLAY_WATCHDOG_MS = 15_000L

        /**
         * How long AutoPlay waits for the party to echo back the tracks it just
         * sent, before assuming they were refused.
         *
         * A queue control is applied and broadcast in the same handler, so the
         * echo is one round trip — generous here because the cost of being
         * wrong is only a retry, while being impatient on a slow connection
         * would throw away a top-up that did land.
         */
        const val PARTY_QUEUE_ECHO_TIMEOUT_MS = 5_000L
    }
}
