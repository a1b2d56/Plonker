package com.music.bitchord.playback

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.listentogether.ListenTogether
import com.music.bitchord.data.model.QueueTier
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.isUnresolvedSpotify
import com.music.bitchord.data.settings.AppSettings
import java.util.UUID
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Spacing between retries of a continuation page that failed to load. */
private val BOUNDED_CONTINUATION_RETRIES_MS = longArrayOf(2_000L, 5_000L, 15_000L)

/**
 * An album or playlist playing as a bounded window: the whole list is held in
 * [boundedContextRegistry] and only a few tracks either side of the playhead
 * are ever Media3 items, so a thousand-track playlist is not a thousand-item
 * timeline re-sent to every controller on each edit.
 *
 * The decisions are [BoundedQueuePlanner]'s, [QueueWindow]'s and
 * [BoundedQueueEdits]'; this carries them out on the session's player:
 * installing the first window, following the list's continuation pages,
 * topping the window up as it is played through, applying a shuffle once a
 * crossfade has settled, and saving and restoring the whole of it across a
 * restart.
 *
 * [player], [transitioning] and [casting] are read when needed, never
 * captured: the session moves between players at every crossfade.
 * [onContextStarting] and [onRestoring] run just before the new queue is
 * installed, because the player reports the change to its listeners from
 * inside that call.
 */
@UnstableApi
class BoundedContextController(
    private val scope: CoroutineScope,
    private val player: () -> ExoPlayer?,
    private val transitioning: () -> Boolean,
    private val casting: () -> Boolean,
    private val refreshButtons: () -> Unit,
    private val onContextStarting: () -> Unit,
    private val onRestoring: () -> Unit,
) {
    /** Canonical context owner for the active local bounded album/playlist. */
    private val boundedContextRegistry = ContextRegistry { snapshot ->
        scope.launch {
            val window = boundedContextWindow ?: return@launch
            if (snapshot.contextToken != boundedContextToken) return@launch
            window.extendTo(snapshot.songs.size)
            boundedContextRequest?.let { BoundedContextRequests.publish(it, snapshot) }
            player()?.let(::saveBoundedContextSnapshot)
            scheduleBoundedContextTopUp()
        }
    }

    private var boundedContextWindow: QueueWindow? = null
    private var boundedContextToken: String? = null
    private var boundedContextSource: QueueSource? = null
    private var boundedContextRequest: BoundedContextRequests.Request? = null
    private var boundedCanonicalJob: Job? = null
    private var boundedContinuationJob: Job? = null
    private var boundedTopUpJob: Job? = null
    /** Coalesced bounded edits waiting for CrossfadeController settlement. */
    private var boundedEditState = BoundedQueueEditState()

    /**
     * Latest desired shuffle state wins. Nothing logical is mutated until the
     * actual crossfade settlement callback; thus the outgoing and standby
     * queues cannot be based on different generations of the logical order.
     */
    fun applyDeferredBoundedQueueEdit() {
        val exoPlayer = player() ?: return
        val window = boundedContextWindow ?: return
        if (ListenTogether.state.value.inParty || transitioning()) return
        val settlement = BoundedQueueEdits.settle(
            state = boundedEditState,
            settledGeneration = boundedEditState.contextGeneration,
        )
        val edit = settlement.edit ?: return
        boundedEditState = settlement.state
        boundedContextAnchor(exoPlayer)?.let { anchor ->
            if (window.currentCanonicalIndex != anchor) window.jumpTo(anchor)
        }
        if (edit.desiredShuffle != null) {
            window.setShuffle(edit.desiredShuffle, shuffleSeed = Random.nextLong())
            QueueShuffle.setEnabled(edit.desiredShuffle)
            AppSettings.setShuffleEnabled(edit.desiredShuffle)
            replaceBoundedUpcoming(exoPlayer, window)
        }
        if (edit.refresh) replaceBoundedUpcoming(exoPlayer, window)
        if (edit.topUp) {
            applyBoundedContextTopUp()
        }
        refreshButtons()
    }

    /** Replace only the bounded upcoming tail, retaining current/history and tiers. */
    private fun replaceBoundedUpcoming(exoPlayer: ExoPlayer, window: QueueWindow) {
        val snapshot = boundedContextRegistry.snapshot() ?: return
        if (snapshot.contextToken != boundedContextToken) return
        window.extendTo(snapshot.songs.size)
        val from = exoPlayer.currentMediaItemIndex + 1
        if (from < 0) return
        val existing = (from until exoPlayer.mediaItemCount).map(exoPlayer::getMediaItemAt)
        val users = existing.filter { it.queueTier == QueueTier.USER_QUEUE }
        val autoplay = existing.filter { it.queueTier == QueueTier.AUTOPLAY }
        val existingContext = existing.filter { it.queueTier == QueueTier.CONTEXT }
            .associateBy { QueueWindowEntry(it.canonicalIndex ?: -1, it.contextCycleId ?: window.cycleId) }
        val context = BoundedQueuePlanner.physicalUpcoming(
            window = window,
            repeatAll = exoPlayer.repeatMode == Player.REPEAT_MODE_ALL &&
                BoundedQueuePlanner.canRepeat(snapshot.continuationState),
        ).map { entry ->
            existingContext[entry] ?: boundedMediaItem(
                snapshot.songs[entry.canonicalIndex], entry.canonicalIndex, entry.cycleId,
            )
        }
        QueueShuffle.replaceChanged(exoPlayer, from, exoPlayer.mediaItemCount, users + context + autoplay)
    }

    /**
     * Installs the first bounded window for an album/playlist request.  The
     * complete list stays in [boundedContextRegistry]; only the bounded plan is
     * converted to Media3 items.  This command is intentionally handled by the
     * service so the physical edit uses the raw ExoPlayer and never trips the
     * PartySync user-intent path.
     */
    fun startBoundedContext(args: Bundle) {
        val exoPlayer = player() ?: return
        val requestToken = args.getString(EXTRA_BOUNDED_CONTEXT_SONGS) ?: return
        val request = BoundedContextRequests.take(requestToken) ?: return
        if (casting()) {
            // MainActivity normally keeps Cast on the full-queue path. This
            // closes the race where a receiver connects after the tap but
            // before this session command is handled.
            BoundedContextRequests.discard(requestToken)
            return
        }
        BoundedContextPersistence.clearImmediately()
        boundedEditState = BoundedQueueEdits.invalidateContext(boundedEditState)

        val source = request.source
        val requestedSongs = request.songs
            .filterNot { it.isUnresolvedSpotify }
            .map { it.copy(
                queueTier = QueueTier.CONTEXT,
                playbackSource = source.title,
                playbackSourceType = source.type,
                playbackSourceId = source.id,
            ) }
        if (requestedSongs.isEmpty()) return

        val requestedIndex = request.startIndex
            .coerceIn(requestedSongs.indices)
        val selectedKey = requestedSongs[requestedIndex].contextEntryKey()
        val contextToken = "${source.type.name}:${source.id.orEmpty()}:${UUID.randomUUID()}"
        val registered = boundedContextRegistry.register(
            contextToken = contextToken,
            sourceKey = "${source.type.name}:${source.id.orEmpty()}",
            browseId = source.id,
            songs = requestedSongs,
            continuationToken = request.continuationToken,
        )
        val selectedIndex = registered.songs.indexOfFirst { it.contextEntryKey() == selectedKey }
            .takeIf { it >= 0 } ?: 0
        val shuffle = request.shuffle
        // A fresh seed per start: the default one is fixed, which gave the
        // same album the same "shuffled" order every time it was played.
        val window = QueueWindow(registered.songs.size, shuffleSeed = Random.nextLong())
            .startAt(selectedIndex, shuffle)

        boundedContinuationJob?.cancel()
        boundedContextWindow = window
        boundedContextToken = contextToken
        boundedContextSource = source
        boundedContextRequest = request
        BoundedContextRequests.publish(request, registered)

        val currentIndex = exoPlayer.currentMediaItemIndex
        val upcoming = if (currentIndex in 0 until exoPlayer.mediaItemCount) {
            (currentIndex + 1 until exoPlayer.mediaItemCount).map { exoPlayer.getMediaItemAt(it) }
        } else {
            emptyList()
        }
        val userQueue = upcoming.filter { it.queueTier == QueueTier.USER_QUEUE }.map(MediaItem::toSong)
        // The old queue's AutoPlay tail is not carried over, as the flat path
        // in [QueueTimeline.buildContextQueue] does not: it was suggested for
        // the music being replaced, and with ten of them already waiting at the
        // end [loadAutoplayForCurrentTrack] never asks for this context's own.
        onContextStarting()
        val repeatAll = exoPlayer.repeatMode == Player.REPEAT_MODE_ALL
        val plan = BoundedQueuePlanner.buildInitialPlan(
            contextSnapshot = registered,
            selectedIndex = selectedIndex,
            queueWindow = window,
            preservedUserQueue = userQueue,
            repeatAll = repeatAll,
        )

        // Off the main thread: this is the one step here that scales with the
        // whole list, and [toMediaItem] stats every downloaded track. Shuffle
        // toggled before it lands reads the player's own rows, then this replaces them.
        boundedCanonicalJob?.cancel()
        QueueShuffle.clearCanonicalContext()
        boundedCanonicalJob = scope.launch(Dispatchers.Default) {
            val canonicalItems = registered.songs.mapIndexed { index, song ->
                val canonical = song.copy(queueEntryId = "context:$contextToken:$index")
                QueueShuffle.withQueueMetadata(
                    canonical.toMediaItem(),
                    newEntryId = canonical.queueEntryId,
                    newCanonicalIndex = index,
                    newTier = QueueTier.CONTEXT,
                )
            }
            if (isActive) QueueShuffle.setCanonicalContext(canonicalItems)
        }

        val items = boundedMediaItems(plan, window)
        val start = plan.history.size
        exoPlayer.setMediaItems(items, start.coerceIn(items.indices), 0L)
        saveBoundedContextSnapshot(exoPlayer)
        exoPlayer.prepare()
        exoPlayer.play()
        startBoundedContinuationLoading(contextToken)
    }

    /** Clears local bounded state before a non-context queue replaces the player. */
    fun clearBoundedContext() {
        boundedEditState = BoundedQueueEdits.invalidateContext(boundedEditState)
        boundedContextRequest?.handoffToken?.let(BoundedContextRequests::discard)
        boundedCanonicalJob?.cancel()
        boundedCanonicalJob = null
        boundedContinuationJob?.cancel()
        boundedContinuationJob = null
        boundedTopUpJob?.cancel()
        boundedTopUpJob = null
        boundedContextRegistry.register(
            contextToken = "cleared:${UUID.randomUUID()}",
            sourceKey = "cleared",
            browseId = null,
            songs = emptyList(),
            continuationToken = null,
        )
        BoundedContextPersistence.clearImmediately()
        boundedContextWindow = null
        boundedContextToken = null
        boundedContextSource = null
        boundedContextRequest = null
        QueueShuffle.clearCanonicalContext()
    }

    /** Follows the continuation independently of the detail-page lifecycle. */
    private fun startBoundedContinuationLoading(contextToken: String) {
        boundedContinuationJob?.cancel()
        boundedContinuationJob = scope.launch {
            var failures = 0
            while (isActive && boundedContextToken == contextToken) {
                val request = boundedContextRegistry.claimContinuation(contextToken) ?: return@launch
                val page = withContext(Dispatchers.IO) {
                    YtMusicRepository.moreSongs(request.continuationToken).getOrNull()
                }
                if (page == null) {
                    // fail() leaves the token claimable. A single dropped
                    // request used to end loading for good, leaving both the
                    // queue and the page cut short; retry with backoff, then
                    // hand the walk back to the detail page.
                    boundedContextRegistry.fail(request)
                    if (++failures > BOUNDED_CONTINUATION_RETRIES_MS.size) {
                        boundedContextRequest?.handoffToken?.let(BoundedContextRequests::release)
                        return@launch
                    }
                    delay(BOUNDED_CONTINUATION_RETRIES_MS[failures - 1])
                    continue
                }
                failures = 0
                val source = boundedContextSource
                val songs = page.songs.map { song ->
                    if (source == null) song else song.copy(
                        queueTier = QueueTier.CONTEXT,
                        playbackSource = source.title,
                        playbackSourceType = source.type,
                        playbackSourceId = source.id,
                    )
                }
                val result = boundedContextRegistry.complete(
                    request,
                    ContinuationPage(songs = songs, continuationToken = page.continuation),
                )
                if (result.status != ContinuationResultStatus.COMPLETED) return@launch
            }
        }
    }

    fun scheduleBoundedContextTopUp() {
        if (boundedContextWindow == null || boundedContextToken == null) return
        boundedEditState = BoundedQueueEdits.requestTopUp(boundedEditState)
        if (boundedTopUpJob?.isActive == true) return
        boundedTopUpJob = scope.launch {
            if (ListenTogether.state.value.inParty) return@launch
            if (transitioning()) {
                return@launch
            }
            applyBoundedContextTopUp()
            boundedEditState = boundedEditState.copy(topUpPending = false)
        }
    }

    private fun applyBoundedContextTopUp() {
        val exoPlayer = player() ?: return
        val window = boundedContextWindow ?: return
        val snapshot = boundedContextRegistry.snapshot() ?: return
        if (snapshot.contextToken != boundedContextToken) return
        window.extendTo(snapshot.songs.size)
        val currentIndex = boundedContextAnchor(exoPlayer) ?: window.currentCanonicalIndex ?: return
        if (window.currentCanonicalIndex != currentIndex) {
            window.jumpTo(currentIndex)
        }
        val now = exoPlayer.currentMediaItemIndex
        val afterCurrent = if (now in 0 until exoPlayer.mediaItemCount) {
            (now + 1 until exoPlayer.mediaItemCount).map { exoPlayer.getMediaItemAt(it) }
        } else emptyList()
        val existingEntries = afterCurrent
            .filter { it.queueTier == QueueTier.CONTEXT }
            .mapNotNull { item ->
                val canonicalIndex = QueueShuffle.getCanonicalIndex(item) ?: return@mapNotNull null
                QueueWindowEntry(canonicalIndex, item.contextCycleId ?: window.cycleId)
            }
        val repeatAll = exoPlayer.repeatMode == Player.REPEAT_MODE_ALL &&
            BoundedQueuePlanner.canRepeat(snapshot.continuationState)
        val additions = BoundedQueuePlanner.topUpEntries(
            window = window,
            materialized = existingEntries,
            repeatAll = repeatAll,
        )
        if (additions.isEmpty()) return
        val autoplayIndex = (now + 1 until exoPlayer.mediaItemCount)
            .firstOrNull { exoPlayer.getMediaItemAt(it).queueTier == QueueTier.AUTOPLAY }
            ?: exoPlayer.mediaItemCount
        val items = additions.map { entry ->
            boundedMediaItem(snapshot.songs[entry.canonicalIndex], entry.canonicalIndex, entry.cycleId)
        }
        exoPlayer.addMediaItems(autoplayIndex, items)
    }

    private fun boundedContextAnchor(exoPlayer: ExoPlayer): Int? {
        val current = exoPlayer.currentMediaItem
        if (current?.queueTier == QueueTier.CONTEXT) {
            QueueShuffle.getCanonicalIndex(current)?.let { return it }
        }
        val currentIndex = exoPlayer.currentMediaItemIndex
        for (index in currentIndex - 1 downTo 0) {
            val item = exoPlayer.getMediaItemAt(index)
            if (item.queueTier == QueueTier.CONTEXT) {
                QueueShuffle.getCanonicalIndex(item)?.let { return it }
            }
        }
        return null
    }

    fun syncBoundedContextCursor(exoPlayer: ExoPlayer, mediaItem: MediaItem?) {
        val window = boundedContextWindow ?: return
        if (ListenTogether.state.value.inParty) return
        if (mediaItem?.queueTier != QueueTier.CONTEXT) return
        val canonicalIndex = QueueShuffle.getCanonicalIndex(mediaItem) ?: return
        val cycle = mediaItem.contextCycleId
        if (cycle != null) {
            window.commit(QueueWindowEntry(canonicalIndex, cycle))
        } else if (window.currentCanonicalIndex != canonicalIndex) {
            window.jumpTo(canonicalIndex)
        }
    }

    /** Re-establishes the local bounded projection after PartySync gives up ownership. */
    fun rebuildBoundedContextAfterParty() {
        val exoPlayer = player() ?: return
        val window = boundedContextWindow ?: return
        val snapshot = boundedContextRegistry.snapshot() ?: return
        if (snapshot.contextToken != boundedContextToken) return
        val anchor = boundedContextAnchor(exoPlayer)
        if (anchor == null || anchor !in snapshot.songs.indices) {
            // The party may have replaced the queue with tracks that have no
            // local canonical identity. Preserve that queue rather than reset
            // playback into an unproven bounded reconstruction.
            clearBoundedContext()
            return
        }
        if (window.currentCanonicalIndex != anchor) window.jumpTo(anchor)
        // Shuffle toggled in the party cleared this; the projection needs it back.
        QueueShuffle.setCanonicalContext(
            snapshot.songs.mapIndexed { index, song ->
                val canonical = song.copy(queueEntryId = "context:${snapshot.contextToken}:$index")
                QueueShuffle.withQueueMetadata(
                    canonical.toMediaItem(),
                    newEntryId = canonical.queueEntryId,
                    newCanonicalIndex = index,
                    newTier = QueueTier.CONTEXT,
                )
            },
        )
        boundedEditState = BoundedQueueEdits.requestRefresh(boundedEditState)
        if (!transitioning()) applyDeferredBoundedQueueEdit()
    }

    private fun boundedMediaItems(plan: BoundedContextPlan, window: QueueWindow): List<MediaItem> {
        val canonical = plan.canonicalContext
        val history = plan.history.map { song ->
            boundedMediaItem(song, canonical.indexOfFirst { it.contextEntryKey() == song.contextEntryKey() }, window.cycleId)
        }
        val current = boundedMediaItem(plan.current, plan.currentCanonicalIndex, window.cycleId)
        val user = plan.userQueue.map { it.copy(queueEntryId = it.queueEntryId ?: UUID.randomUUID().toString()).toMediaItem() }
        val context = plan.contextUpcoming.map { entry ->
            boundedMediaItem(canonical[entry.canonicalIndex], entry.canonicalIndex, entry.cycleId)
        }
        val autoplay = plan.autoplay.map { it.copy(queueEntryId = it.queueEntryId ?: UUID.randomUUID().toString()).toMediaItem() }
        return history + listOf(current) + user + context + autoplay
    }

    private fun boundedMediaItem(song: Song, canonicalIndex: Int, cycleId: Long): MediaItem {
        val entry = song.copy(
            queueTier = QueueTier.CONTEXT,
            queueEntryId = "window:${UUID.randomUUID()}",
        )
        return QueueShuffle.withQueueMetadata(
            entry.toMediaItem(),
            newEntryId = entry.queueEntryId,
            newCanonicalIndex = canonicalIndex,
            newTier = QueueTier.CONTEXT,
            newContextCycleId = cycleId,
        )
    }

    fun boundedAutoplayEligible(exoPlayer: ExoPlayer): Boolean {
        val window = boundedContextWindow ?: return true
        val snapshot = boundedContextRegistry.snapshot() ?: return false
        if (snapshot.contextToken != boundedContextToken) return false
        boundedContextAnchor(exoPlayer)?.let { anchor ->
            if (window.currentCanonicalIndex != anchor) window.jumpTo(anchor)
        }
        return BoundedQueuePlanner.autoplayEligible(
            window = window,
            continuationState = snapshot.continuationState,
            repeatAll = exoPlayer.repeatMode == Player.REPEAT_MODE_ALL,
        )
    }

    /** Capture logical state and only the materialized bounded projection. */
    fun saveBoundedContextSnapshot(player: ExoPlayer, immediately: Boolean = false) {
        if (ListenTogether.state.value.inParty) return
        val window = boundedContextWindow ?: return
        val context = boundedContextRegistry.snapshot() ?: return
        val source = boundedContextSource ?: return
        if (context.contextToken != boundedContextToken || context.songs.isEmpty()) return
        if (player.mediaItemCount <= 0) return
        val currentIndex = player.currentMediaItemIndex.coerceIn(0, player.mediaItemCount - 1)
        val range = LastPlayed.window(player.mediaItemCount, currentIndex)
        if (range.isEmpty()) return
        val entries = range.map { index ->
            val item = player.getMediaItemAt(index)
            BoundedContextPersistence.Entry(
                song = item.toSong(),
                canonicalIndex = if (item.queueTier == QueueTier.CONTEXT) item.canonicalIndex else null,
                cycleId = if (item.queueTier == QueueTier.CONTEXT) item.contextCycleId else null,
            )
        }
        val state = BoundedContextPersistence.State(
            context = context,
            source = source,
            window = window.snapshot(),
            entries = entries,
            currentIndex = currentIndex - range.first,
            positionMs = player.currentPosition,
        )
        if (immediately) BoundedContextPersistence.saveImmediately(state) else BoundedContextPersistence.save(state)
    }

    fun restoreBoundedQueue(
        exoPlayer: ExoPlayer,
        persisted: BoundedContextPersistence.State,
    ): Boolean {
        val projected = BoundedContextPersistence.restoreProjection(
            persisted,
            repeatAll = exoPlayer.repeatMode == Player.REPEAT_MODE_ALL,
        ) ?: return false
        val restoredWindow = runCatching { QueueWindow.restore(projected.window) }.getOrNull()
            ?: return false
        if (restoredWindow.canonicalSize != projected.context.songs.size) return false
        val restoredCurrent = restoredWindow.currentCanonicalIndex ?: return false
        if (projected.currentIndex !in projected.entries.indices) return false

        val restoredContext = runCatching {
            boundedContextRegistry.restore(projected.context)
        }.getOrNull() ?: return false
        if (restoredContext.songs.isEmpty()) return false

        val contextToken = restoredContext.contextToken
        val canonicalMediaItems = restoredContext.songs.mapIndexed { index, song ->
            val canonical = song.copy(queueEntryId = "context:$contextToken:$index")
            QueueShuffle.withQueueMetadata(
                canonical.toMediaItem(),
                newEntryId = canonical.queueEntryId,
                newCanonicalIndex = index,
                newTier = QueueTier.CONTEXT,
            )
        }
        QueueShuffle.setCanonicalContext(canonicalMediaItems)

        val items = projected.entries.map { entry ->
            val canonicalIndex = entry.canonicalIndex
            if (canonicalIndex == null) {
                entry.song.toMediaItem()
            } else {
                boundedMediaItem(
                    entry.song.copy(queueTier = QueueTier.CONTEXT),
                    canonicalIndex,
                    entry.cycleId ?: restoredWindow.cycleId,
                )
            }
        }
        if (items.size != projected.entries.size) return false

        boundedContextWindow = restoredWindow
        boundedContextToken = contextToken
        boundedContextSource = persisted.source
        boundedContextRequest = BoundedContextRequests.restore(
            BoundedContextRequests.Request(
                songs = restoredContext.songs,
                startIndex = restoredCurrent,
                source = persisted.source,
                continuationToken = restoredContext.continuationToken,
                shuffle = restoredWindow.isShuffleEnabled,
            ),
        )
        boundedEditState = BoundedQueueEdits.invalidateContext(boundedEditState)
        onRestoring()
        exoPlayer.setMediaItems(items, projected.currentIndex, projected.positionMs)
        startBoundedContinuationLoading(contextToken)
        return true
    }

    /** Whether an album or playlist is playing as a bounded window. */
    val active: Boolean get() = boundedContextWindow != null

    /** Edits waiting for a crossfade to settle. */
    val hasPendingEdits: Boolean get() = boundedEditState.hasPendingEdits

    /**
     * Shuffle asked for from the session while a bounded context plays, applied
     * at once or, mid-crossfade, when it settles. False when there is no
     * bounded context here (or a party owns the queue), for the caller's flat path.
     */
    fun requestShuffleToggle(): Boolean {
        val window = boundedContextWindow ?: return false
        if (ListenTogether.state.value.inParty) return false
        val target = !(boundedEditState.desiredShuffle ?: window.isShuffleEnabled)
        boundedEditState = BoundedQueueEdits.requestShuffle(
            boundedEditState,
            desiredShuffle = target,
        )
        if (!transitioning()) applyDeferredBoundedQueueEdit()
        return true
    }

    /**
     * Playback dismissed: the window is let go, so nothing is topped up or
     * saved for it, and a continuation still walking stops at its next page.
     */
    fun forget() {
        boundedContextWindow = null
        boundedContextToken = null
        boundedContextSource = null
    }
}
