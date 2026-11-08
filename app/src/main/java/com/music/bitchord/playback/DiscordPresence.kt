package com.music.bitchord.playback

import android.content.Context
import androidx.media3.exoplayer.ExoPlayer
import com.music.bitchord.data.NerdStats
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.discord.DiscordRPC
import com.music.bitchord.data.discord.discordAudioQualityLine
import com.music.bitchord.data.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What's playing, published to the listener's Discord profile as a Rich
 * Presence card, over a gateway connection built and torn down as the account
 * and the master switch allow ([observe]).
 *
 * The card is pushed at each track change and on play, and cleared on pause
 * rather than left to run: Discord's countdown runs on its own clock, so a
 * card left up while paused would finish a song that had stopped.
 *
 * [player] is read when needed, never captured: the session moves between
 * players at every crossfade.
 */
internal class DiscordPresence(
    private val context: Context,
    private val scope: CoroutineScope,
    private val player: () -> ExoPlayer?,
) {
    /**
     * The gateway connection publishing what's playing to Discord, or null when
     * the feature is off or no account is connected. See [DiscordRPC].
     */
    private var discordRpc: DiscordRPC? = null

    /**
     * The in-flight presence push. Held so the next one can cancel it: the
     * pushes hit the network — the artwork has to be mirrored onto Discord's CDN
     * before the activity can name it — and a skipped-through queue would
     * otherwise land its presences in whatever order the requests happened to
     * finish in, leaving the profile on a track the listener passed seconds ago.
     */
    private var discordUpdateJob: Job? = null

    /**
     * Whether a presence has been published and not yet taken down.
     *
     * Tracked because [KizzyRPC.close] — which is what clears the card — opens a
     * gateway connection first if one isn't already up. Clearing unconditionally
     * would therefore dial Discord for the sole purpose of sending it nothing,
     * every time playback paused without a presence ever having been set.
     */
    private var discordPresenceUp = false

    /**
     * Keeps the gateway connection in step with the settings that decide whether
     * there should be one, and re-pushes the presence when the settings that
     * decide what it *says* change.
     *
     * Two collectors rather than one because the two do different work. The
     * account and the master switch can only be honoured by building or tearing
     * down a connection; everything else is a field in a payload that can be
     * re-sent over the connection already open. Combining them would reconnect
     * the socket every time the user typed a character into a button label.
     */
    fun observe() {
        scope.launch {
            combine(
                AppSettings.discordToken,
                AppSettings.discordRpcEnabled,
            ) { token, enabled -> token.takeIf { enabled && it.isNotBlank() } }
                .distinctUntilChanged()
                .collectLatest { token ->
                    // Torn down before anything is built, so switching accounts
                    // can't leave the old one's socket up publishing under a
                    // profile the user has just disconnected.
                    discordUpdateJob?.cancel()
                    discordRpc?.let { rpc ->
                        val wasUp = discordPresenceUp
                        discordPresenceUp = false
                        // On IO, not on this collector's main thread: the
                        // teardown closes a socket, and closing one gracefully
                        // — which is what flushes the presence-clear queued on
                        // the line above — blocks until the frame is away.
                        //
                        // Bounded, and that is the point rather than a
                        // precaution. This runs on the way to *replacing*
                        // [discordRpc], so for as long as it takes the field is
                        // null and the feature is off: a teardown that hung —
                        // which one waiting on an unreachable socket did — read
                        // to the user as a switch that had stopped working
                        // altogether until the app was restarted.
                        withContext(Dispatchers.IO + NonCancellable) {
                            withTimeoutOrNull(DISCORD_TEARDOWN_TIMEOUT_MS) {
                                if (wasUp) runCatching { rpc.close() }
                            }
                            runCatching { rpc.closeRPC() }
                        }
                    }
                    discordRpc = null

                    if (token == null) return@collectLatest
                    discordRpc = DiscordRPC(context, token)
                    // A presence that only appeared at the next track change
                    // would make turning the switch on look like it had done
                    // nothing for the length of a song.
                    player()?.takeIf { it.isPlaying }?.let(::push)
                }
        }

        // The card's own contents, plus the playback rate — which is not
        // cosmetic here: the timestamps are wall-clock instants with the rate
        // divided out, so a change to it invalidates a presence already up.
        scope.launch {
            // Explicit <Any, _> for the same reason as [observeScrobbling]:
            // mixed element types, and the reified vararg combine() otherwise
            // infers an intersection type. Compared as a list rather than a
            // joined string so two different settings can't stringify alike.
            combine<Any, List<Any>>(
                AppSettings.discordUseDetails,
                AppSettings.discordStatus,
                AppSettings.discordActivityType,
                AppSettings.discordActivityName,
                AppSettings.discordButton1Text,
                AppSettings.discordButton1Visible,
                AppSettings.discordButton2Text,
                AppSettings.discordButton2Visible,
                AppSettings.playbackSpeed,
            ) { it.toList() }
                .distinctUntilChanged()
                // Dropped so the collector's first emission — which arrives at
                // startup, before anything is playing — isn't treated as a
                // change the user made.
                .drop(1)
                .collect {
                    player()?.takeIf { p -> p.isPlaying }?.let(::push)
                }
        }

        // The first track push usually happens before Media3 has configured
        // its decoder, so measured quality arrives just afterwards. Re-send
        // only when the rendered premium line changes; the periodic stats
        // sampler creates fresh snapshots, but must not generate a Discord
        // network update every time when their visible values are identical.
        scope.launch {
            combine(
                NerdStats.current,
                AppSettings.discordShowAudioQuality,
            ) { stats, enabled ->
                discordAudioQualityLine(stats).takeIf { enabled }
            }
                .distinctUntilChanged()
                .drop(1)
                .collect {
                    player()?.takeIf { p -> p.isPlaying }?.let(::push)
                }
        }

        // A network coming back, which is the other half of surviving a spell in
        // the background: the gateway heals itself, but its retry backoff climbs
        // to a minute, and a listener who walked back into Wi-Fi shouldn't watch
        // a blank profile for that long. Nudging it here collapses the wait.
        //
        // [AppSettings.meteredConnection] is null only while there is no active
        // network, so null -> non-null is exactly "we are online again". A
        // metered/unmetered flip is worth acting on too: the socket does not
        // survive a transport handover, and the old one may not have noticed yet.
        scope.launch {
            AppSettings.meteredConnection
                .drop(1)
                .collect { metered ->
                    if (metered == null) return@collect
                    val rpc = discordRpc ?: return@collect
                    withContext(Dispatchers.IO) { runCatching { rpc.wakeUp() } }
                    // Re-pushed rather than left to the gateway's own replay,
                    // because a handover can strand the socket in a state where
                    // it believes it is still connected: the push is what makes
                    // it prove otherwise.
                    if (discordPresenceUp) {
                        player()?.takeIf { p -> p.isPlaying }?.let(::push)
                    }
                }
        }
    }

    /**
     * Publishes the track [exoPlayer] is on as the user's Discord presence.
     *
     * A no-op with no connection, which is the ordinary case — most people will
     * never connect an account, and this is called from the middle of every
     * track change.
     */
    fun push(exoPlayer: ExoPlayer) {
        val rpc = discordRpc ?: return
        val song = exoPlayer.currentMediaItem?.toSong() ?: return
        // Read on the main thread, before the push is handed to IO: by the time
        // a coroutine gets to run, the queue may have moved on, and ExoPlayer's
        // state is only legal to read from the thread it was built on.
        val positionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
        val durationMs = exoPlayer.duration.takeIf { it > 0 } ?: 0L
        // The listener's own rate, not the player's instantaneous one.
        //
        // This is called from the middle of a track change, which is exactly
        // when [CrossfadeController] has a beatmatch stretch stacked on the
        // incoming player — so the player reads back 1.06x for the couple of
        // seconds the blend lasts. Discord only hears about a track when it
        // changes, so that transient rate got stamped into the title as
        // "Song [1.06x]" and stayed there for the rest of the song, over a
        // track that had already been put back on the listener's own tempo.
        // The setting is what the track plays at for all but the handoff, so
        // it is both the honest tag and the right divisor for the countdown.
        val speed = AppSettings.playbackSpeed.value
        val audioQuality = discordAudioQualityLine(NerdStats.current.value)
            .takeIf { AppSettings.discordShowAudioQuality.value }

        discordUpdateJob?.cancel()
        discordPresenceUp = true
        discordUpdateJob = scope.launch(Dispatchers.IO) {
            rpc.updateSong(
                song = song,
                currentPlaybackTimeMillis = positionMs,
                durationMillis = durationMs,
                playbackSpeed = speed,
                useDetails = AppSettings.discordUseDetails.value,
                status = AppSettings.discordStatus.value,
                button1Text = AppSettings.discordButton1Text.value,
                button1Visible = AppSettings.discordButton1Visible.value,
                button2Text = AppSettings.discordButton2Text.value,
                button2Visible = AppSettings.discordButton2Visible.value,
                activityType = AppSettings.discordActivityType.value,
                activityName = AppSettings.discordActivityName.value,
                audioQuality = audioQuality,
            ).onFailure {
                TrackLog.d("BitChord", "Discord presence failed: ${it.message}", about = song.videoId)
            }
        }
    }

    /**
     * Takes the presence down but leaves the socket up, so resuming doesn't pay
     * for a reconnect. Discord clears the card on an activity-less presence.
     */
    fun clear() {
        val rpc = discordRpc ?: return
        if (!discordPresenceUp) return
        discordPresenceUp = false
        discordUpdateJob?.cancel()
        discordUpdateJob = scope.launch(Dispatchers.IO) {
            runCatching { rpc.close() }
        }
    }

    /** The service is going: take the card down off the service's scope, which is about to be cancelled. */
    fun release() {
    // Discord, on the same terms as the ListenBrainz submit above: the
    // service scope is cancelled a few lines down, and a presence left up
    // would advertise a track that stopped when the process did — until
    // Discord noticed the socket had gone, which can take minutes.
    discordRpc?.let { rpc ->
        discordRpc = null
        val wasUp = discordPresenceUp
        discordPresenceUp = false
        CoroutineScope(Dispatchers.IO).launch {
            withTimeoutOrNull(DISCORD_TEARDOWN_TIMEOUT_MS) {
                if (wasUp) runCatching { rpc.close() }
            }
            runCatching { rpc.closeRPC() }
        }
    }
    }

    private companion object {
        /**
         * How long a Discord teardown may spend clearing the presence before the
         * socket is closed out from under it.
         *
         * Closing the gateway ends the session, which clears the card on
         * Discord's side anyway — the explicit clear only makes it immediate. So
         * this is a bound on politeness, not on correctness, and it is short
         * because whatever is tearing down is waiting on it.
         */
        const val DISCORD_TEARDOWN_TIMEOUT_MS = 3_000L
    }
}
