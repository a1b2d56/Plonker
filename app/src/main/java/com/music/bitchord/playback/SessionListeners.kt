package com.music.bitchord.playback

import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener

/**
 * The service's two listeners on whichever player is the session's: the
 * player events ([events]: track changes, state, errors) and the analytics
 * ([analytics]: the decoder's format, a swap's seam timing).
 *
 * They move as one. The session changes player at every crossfade, version
 * swap and output rebuild, and each of those used to remove and add the pair
 * by hand. A pair half moved leaves the idle player reporting into the
 * service as though it were playing: its next track's format read as the
 * current one, its buffering taken for the session's.
 *
 * [detach] and [attach] are separate steps on purpose. A handoff detaches,
 * moves the service's own fields onto the new player, and only then attaches,
 * so the first callback already reads the new arrangement.
 */
@UnstableApi
class SessionListeners(
    private val events: Player.Listener,
    private val analytics: AnalyticsListener,
) {
    /** The player the pair is on, or null between a detach and an attach. */
    var bound: ExoPlayer? = null
        private set

    /** Puts the pair on [player], taking it off any other player first. */
    fun attach(player: ExoPlayer) {
        if (bound === player) return
        detach()
        player.addListener(events)
        player.addAnalyticsListener(analytics)
        bound = player
    }

    /** Takes the pair off the player it is on, whichever that is. */
    fun detach() {
        val player = bound ?: return
        player.removeListener(events)
        player.removeAnalyticsListener(analytics)
        bound = null
    }
}
