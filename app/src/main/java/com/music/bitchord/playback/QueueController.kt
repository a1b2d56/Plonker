package com.music.bitchord.playback

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.session.MediaController
import com.music.bitchord.R
import com.music.bitchord.data.listentogether.ListenTogether
import com.music.bitchord.data.listentogether.partyQueueIndexOf
import com.music.bitchord.data.model.PlaybackSourceType
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.isUnresolvedSpotify
import com.music.bitchord.playback.cast.CastController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Which play request is the latest, and the station label shown for a radio
 * started off the track already playing.
 *
 * Every play request takes a new [generation]; work that suspends (a station
 * fetched from YouTube, a Top Picks queue) checks [isCurrent] when it lands
 * and drops itself if a later tap has won in the meantime.
 */
@Stable
class PlayRequests {
    /** Bumped by every play request; see [isCurrent]. */
    var generation by mutableIntStateOf(0)
        private set

    /**
     * Starting radio from the item already playing must not replace that media
     * item just to add UI metadata. This temporary (videoId, station name)
     * label covers that seed; every following radio item carries its radioName
     * in its MediaItem extras.
     */
    var radioSeed by mutableStateOf<Pair<String, String>?>(null)

    /** A new request that replaces the queue outright: any seed label goes with it. */
    fun beginReplacing(): Int {
        generation++
        radioSeed = null
        return generation
    }

    /** A new request whose outcome decides the seed later (a station still loading). */
    fun begin(): Int = ++generation

    fun isCurrent(request: Int): Boolean = request == generation

    /** The seed label only ever describes the track it was set for. */
    fun onTrackChanged(videoId: String?) {
        if (radioSeed?.first != videoId) radioSeed = null
    }
}

/** The pure decisions [QueueController.playFrom] makes before touching the player. */
object PlayFromRules {
    /**
     * The songs that can actually be queued, and the tapped one's place among
     * them; null when the tapped row itself can't be played.
     *
     * A Spotify page lists songs it has not found on YouTube Music yet (or
     * never will); those are dropped and the rest keep their order.
     */
    fun playable(allSongs: List<Song>, allIndex: Int): Pair<List<Song>, Int>? {
        val tapped = allSongs.getOrNull(allIndex)
        if (tapped?.isUnresolvedSpotify == true) return null
        val songs = allSongs.filterNot { it.isUnresolvedSpotify }
        return songs to songs.indexOf(tapped).coerceAtLeast(0)
    }

    /**
     * Whether the play goes to the service's bounded-context path rather than
     * a full timeline built here.
     *
     * Any list too long to fit the window goes that way, whatever page it came
     * from: a whole library in the player is a whole library re-sent to every
     * media controller on each queue edit. A Cast session never does — the
     * receiver needs the full queue on the phone to follow its own semantics.
     */
    fun useBoundedContext(requested: Boolean, size: Int, casting: Boolean): Boolean =
        (requested || size > BoundedQueuePlanner.MAX_PHYSICAL_CONTEXT) && !casting
}

/**
 * The queue-building half of playback that the app's pages ask for: play a
 * list from a tapped row, start a station off one song, or YouTube's own radio
 * for it. Decisions live in [PlayFromRules] and [QueueCoordinator]; this
 * carries them out on the [MediaController].
 *
 * [controller], [currentQueue] and [refusedByHost] are reassigned by the
 * composition that owns this on every pass — they follow the connection, the
 * player state and the party, which a remembered object can't capture once.
 */
class QueueController(
    private val context: Context,
    private val scope: CoroutineScope,
    val requests: PlayRequests,
) {
    var controller: MediaController? = null

    /** The player's current queue as the UI knows it. */
    var currentQueue: List<Song> = emptyList()

    /** True (after saying why) when a party's host has the controls. */
    var refusedByHost: () -> Boolean = { false }

    private fun timelineOf(c: MediaController): List<Song> =
        currentQueue.takeIf { it.size == c.mediaItemCount }
            ?: (0 until c.mediaItemCount).map { c.getMediaItemAt(it).toSong() }

    fun playFrom(
        allSongs: List<Song>,
        allIndex: Int,
        source: QueueSource,
        boundedContext: Boolean = false,
        continuationToken: String? = null,
    ) {
        val (songs, index) = PlayFromRules.playable(allSongs, allIndex) ?: return
        requests.beginReplacing()
        val captured = controller
        scope.launch {
            if (refusedByHost()) return@launch
            val c = captured ?: return@launch
            val currentTimeline = timelineOf(c)
            val currentIndex = c.currentMediaItemIndex

            if (ListenTogether.state.value.inParty) {
                val selectedSong = songs.getOrNull(index) ?: return@launch
                val party = ListenTogether.state.value
                val partyQueue = party.queue.items
                val partyIndex = partyQueueIndexOf(party.queue, party.playback, party.playback.track?.videoId)
                val upcomingPartyTracks = if (partyIndex >= 0) {
                    partyQueue.drop(partyIndex + 1)
                } else {
                    emptyList()
                }
                val timeline = QueueCoordinator.buildPartyPlaybackQueue(
                    tappedSong = selectedSong,
                    source = source,
                    upcomingPartyTracks = upcomingPartyTracks,
                )
                c.playSongs(timeline, 0)
            } else if (
                // The service repeats the casting guard for commands that race
                // a newly connected receiver.
                PlayFromRules.useBoundedContext(boundedContext, songs.size, CastController.state.value.casting)
            ) {
                c.playBoundedContext(songs, index, source, continuationToken)
            } else {
                val result = QueueCoordinator.buildContextQueue(
                    currentTimeline = currentTimeline,
                    currentIndex = currentIndex,
                    newContextSongs = songs,
                    selectedIndex = index,
                    contextSource = source,
                )
                c.playSongs(result.timeline, result.startIndex)
            }
            // Playback starts in the mini player; the user opens the full view by tapping it.
        }
    }

    /**
     * A song picked on its own — off a home card or a search hit — starts a
     * station rather than queueing the list it was shown in. Searching
     * "Perfect" and tapping the top hit otherwise queues twenty covers and
     * remixes of the same song. Album, artist and playlist pages keep
     * [playFrom], where the surrounding list *is* the thing the user asked for.
     */
    fun playRadio(song: Song, source: QueueSource) {
        requests.beginReplacing()
        val captured = controller
        scope.launch {
            if (refusedByHost()) return@launch
            val c = captured ?: return@launch
            val oneOffQueue = QueueCoordinator.buildOneOffQueue(
                currentTimeline = timelineOf(c),
                currentIndex = c.currentMediaItemIndex,
                tappedSong = song,
                source = source,
            )
            c.playSongs(oneOffQueue, 0)
        }
    }

    /**
     * Starts the explicit station offered by every song overflow menu.
     *
     * The related tracks come from YouTube Music's own RDAMVM watch queue.
     * Loading happens before the player is touched so a failed request cannot
     * destroy the queue already playing. Once ready, the whole old queue is
     * replaced in one Media3 operation.
     */
    fun startRadio(song: Song) {
        val originalController = controller ?: return
        if (refusedByHost()) return
        val request = requests.begin()
        // Ignore AutoPlay's tail: it may legitimately grow while the
        // request is in flight and does not mean the listener chose a
        // different queue. A new album/song queue does.
        val originalManualQueue = manualQueueOf(originalController)
        scope.launch {
            val seed = song.copy(
                radioName = song.title,
                playbackSource = song.title,
                playbackSourceType = PlaybackSourceType.SHARED_LINK,
                playbackSourceId = song.videoId,
            )
            val related = loadAutoplayTracks(
                existing = listOf(seed),
                seedSong = seed,
                limit = INITIAL_RADIO_TRACKS,
            ).getOrElse {
                if (requests.isCurrent(request)) {
                    Toast.makeText(context, R.string.couldnt_load_tracks, Toast.LENGTH_SHORT).show()
                }
                return@launch
            }
            if (related.isEmpty()) {
                if (requests.isCurrent(request)) {
                    Toast.makeText(context, R.string.couldnt_load_tracks, Toast.LENGTH_SHORT).show()
                }
                return@launch
            }
            val activeController = originalController
            if (!requests.isCurrent(request) || manualQueueOf(activeController) != originalManualQueue) {
                return@launch
            }
            // Cancel any armed crossfade/AutoPlay work and erase the old
            // cold-start snapshot before the visible queue is replaced.
            activeController.beginRadioQueue()
            val currentIndex = activeController.currentMediaItemIndex
            val currentItem = activeController.currentMediaItem
            if (currentIndex >= 0 && currentItem?.mediaId == song.videoId) {
                // Keep the current MediaItem itself untouched. Replacing it,
                // even with the same song, reparses the source at position
                // zero and audibly stops/restarts the track.
                // Station first, old tail after: removing first leaves the
                // current track briefly last, and AutoPlay answers that by
                // appending this same radio a second time.
                activeController.addMediaItems(currentIndex + 1, related.map { it.toMediaItem() })
                val oldTail = currentIndex + 1 + related.size
                if (oldTail < activeController.mediaItemCount) {
                    activeController.removeMediaItems(oldTail, activeController.mediaItemCount)
                }
                if (currentIndex > 0) activeController.removeMediaItems(0, currentIndex)
                requests.radioSeed = song.videoId to song.title
            } else {
                requests.radioSeed = null
                activeController.playSongs(listOf(seed) + related, 0)
            }
            // Make this station — never the queue from before it — what a
            // fresh process restores, even if it is killed immediately.
            activeController.commitRadioQueue()
            Toast.makeText(
                context,
                context.getString(R.string.radio_started, song.title),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private fun manualQueueOf(c: MediaController): List<String> =
        (0 until c.mediaItemCount)
            .map { c.getMediaItemAt(it) }
            .filterNot { it.fromAutoplay }
            .map { it.mediaId }

    private companion object {
        const val INITIAL_RADIO_TRACKS = 24
    }
}
