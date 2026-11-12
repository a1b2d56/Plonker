package com.music.bitchord.playback

import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.music.bitchord.data.innertube.PlaybackTracker
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.scrobbling.LastFM
import com.music.bitchord.data.scrobbling.ListenBrainzManager
import com.music.bitchord.data.scrobbling.ScrobbleManager
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.stats.ListeningRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * What the listener's plays leave behind, and the sleep timer that ends them.
 *
 * History goes four places, each told about a play on its own terms: YouTube
 * Music's history ([PlaybackTracker], with the watchtime the progress sampler
 * feeds it), the device's own listening record ([ListeningRecorder]), Last.fm
 * ([ScrobbleManager], built and torn down as its settings allow) and
 * ListenBrainz. Only a song that ran to its end, never one skipped past, is
 * submitted as a finished listen.
 *
 * The sleep timer pauses when its deadline arrives, fading the volume out
 * over the last stretch first — see [SleepFade]. "Sleep after this song" ends
 * at the track change that finishes it ([afterTrackChanged]).
 *
 * [player] is read when needed, never captured: the session moves between
 * players at every crossfade.
 *
 * [volumeBusy] is whether something else is driving the players' volume right
 * now: a crossfade or a version swap. The timer's fade stays off the volume
 * for as long as it is, and the crossfade starts nothing new once the timer's
 * fade is running ([SleepFade.running]), so the two never write over each
 * other.
 */
@UnstableApi
internal class PlaybackHistoryAndSleepTimer(
    private val scope: CoroutineScope,
    private val player: () -> ExoPlayer?,
    private val volumeBusy: () -> Boolean = { false },
) {
    /** Last sampled position of the playing track, in seconds. */
    private var lastPositionSeconds = 0L

    private var scrobbleManager: ScrobbleManager? = null
    private var listenBrainzSong: Song? = null

    private var listenBrainzStartMs: Long = 0L

    private var listenBrainzDurationMs: Long? = null

    fun registerCurrentPlay() {
        player()?.currentMediaItem?.mediaId?.let(PlaybackTracker::onPlaying)
    }

    /**
     * Pause when the sleep timer runs out.
     *
     * `collectLatest` is what makes re-setting the timer work: the pending wait
     * is cancelled and restarted on the new deadline instead of both firing.
     */
    fun watchSleepTimer() {
        scope.launch {
            SleepTimer.deadline.collectLatest { deadline ->
                if (deadline == null) return@collectLatest
                // The player this fade turned down, so that one — and only
                // that one — is turned back up afterwards.
                var faded: ExoPlayer? = null
                try {
                    val plan = SleepFade.plan(deadline - SystemClock.elapsedRealtime())
                    if (plan.waitMs > 0) {
                        delay(plan.waitMs)
                    }

                    if (plan.fadeMs > 0) {
                        // From here the crossfade arms nothing new: a blend
                        // into a track that is paused seconds later would only
                        // fight this fade for the volume.
                        SleepFade.running = true
                        val endsAt = SystemClock.elapsedRealtime() + plan.fadeMs
                        val stepDelayMs = (plan.fadeMs / SleepFade.STEPS).coerceAtLeast(1L)
                        // When this fade got the volume to itself. A crossfade
                        // already in flight keeps it until it lands; the fade
                        // then runs from full over whatever time is left,
                        // instead of dropping to where it would have been.
                        var ownedSince = -1L
                        while (true) {
                            val now = SystemClock.elapsedRealtime()
                            if (now >= endsAt) break
                            if (volumeBusy()) {
                                ownedSince = -1L
                            } else {
                                if (ownedSince < 0L) ownedSince = now
                                player()?.let {
                                    it.volume = SleepFade.gain(now, ownedSince, endsAt)
                                    faded = it
                                }
                            }
                            delay(stepDelayMs)
                        }
                    }

                    player()?.pause()
                    SleepTimer.cancel()
                } finally {
                    SleepFade.running = false
                    // Back to full on completion or cancellation — unless a
                    // crossfade or swap has the volume, in which case it is
                    // mid-ramp and sets both players itself when it lands.
                    if (!volumeBusy()) faded?.volume = 1.0f
                }
            }
        }
    }

    /** Playback started or stopped on the session's player. */
    fun onIsPlayingChanged(isPlaying: Boolean, exoPlayer: ExoPlayer) {
        val song = exoPlayer.currentMediaItem?.toSong()
        val durationMs = exoPlayer.duration.takeIf { it > 0 }
        scrobbleManager?.onPlayerStateChanged(isPlaying, song, durationMs)

        // The listening record has to be told a pause happened, not merely
        // stop being told about play: its sampler measures the gap between
        // ticks, and an unclosed one across a pause is an afternoon on the
        // lock screen arriving as an afternoon of listening.
        if (!isPlaying) ListeningRecorder.onStopped()

        // ListenBrainz: "now playing" on play/resume too, not just on
        // transition — a track started from idle or resumed from pause
        // otherwise stays silent on the site.
        if (isPlaying && song != null) {
            if (listenBrainzSong?.videoId != song.videoId || listenBrainzStartMs == 0L) {
                listenBrainzSong = song
                listenBrainzStartMs = System.currentTimeMillis()
                listenBrainzDurationMs = durationMs
            } else if (listenBrainzDurationMs == null) {
                listenBrainzDurationMs = durationMs
            }
            submitListenBrainzPlayingNow(song, exoPlayer.currentPosition, durationMs)
        }
    }

    /** The last track finished with nothing after it. */
    fun onQueueEnded(exoPlayer: ExoPlayer) {
        // The queue ran dry, so no transition will ever close the last
        // track out. Without this its history entry keeps whatever
        // watchtime the 30-second sampler happened to have reported and
        // is never marked finished — so the one play most likely to be
        // a full, deliberate listen is the one recorded as abandoned.
        PlaybackTracker.onPlaybackFinished(lastPositionSeconds)
        lastPositionSeconds = 0
        // The last track finished with nothing after it, so no
        // transition will ever close it out. Scrobble it now.
        val lastSong = listenBrainzSong
        if (lastSong != null && listenBrainzStartMs > 0L) {
            val lastStart = listenBrainzStartMs
            val lastDuration = listenBrainzDurationMs
                ?: exoPlayer.duration.takeIf { it > 0 }
            submitListenBrainzFinished(lastSong, lastStart, lastDuration)
        }
        listenBrainzSong = null
        listenBrainzStartMs = 0L
        listenBrainzDurationMs = null
    }

    /**
     * A different song became the one playing — see the service's
     * `onTrackBecameCurrent`, which calls this for a queue advance and for a
     * crossfade handoff alike.
     *
     * @param previousEnded whether the song being replaced ran to its end, as
     *   opposed to being skipped past. Only an ended song is a listen.
     */
    fun onTrackChanged(mediaItem: MediaItem?, previousEnded: Boolean, exoPlayer: ExoPlayer) {
        // currentPosition already belongs to the new item by now, so
        // the outgoing track is closed out on the last sampled value.
        PlaybackTracker.onTrackChanged(lastPositionSeconds)
        lastPositionSeconds = 0

        // Scrobbling: stop old song, start new song
        scrobbleManager?.onSongStop()
        // And the local record, which needs the transition even when the track
        // id doesn't change: repeat-one plays the same song again, and without
        // this the second play through is a continuation of the first and is
        // never counted.
        ListeningRecorder.onStopped()
        val newSong = mediaItem?.toSong()
        val durationMs = exoPlayer.duration.takeIf { it > 0 }
        if (exoPlayer.isPlaying) {
            scrobbleManager?.onSongStart(newSong, durationMs)
        }

        // ListenBrainz: submit finished for old song, playing_now for new song.
        // The finished listen only counts when the track actually ended —
        // an auto-advance, a repeat, or a crossfade at the very end. A
        // manual skip (SEEK) means the song wasn't listened to, so it must
        // not be scrobbled.
        val prevSong = listenBrainzSong
        val prevStart = listenBrainzStartMs
        if (prevSong != null && previousEnded && prevStart > 0L) {
            submitListenBrainzFinished(prevSong, prevStart, listenBrainzDurationMs)
        }
        listenBrainzSong = newSong
        listenBrainzStartMs = if (exoPlayer.isPlaying) System.currentTimeMillis() else 0L
        listenBrainzDurationMs = durationMs
        if (newSong != null && exoPlayer.isPlaying) {
            submitListenBrainzPlayingNow(newSong, 0L, durationMs)
        }
    }

    /**
     * The end of a track change, once the service has published the new one:
     * "sleep after this song" stops here, and a track already playing is
     * counted as a play.
     */
    fun afterTrackChanged(previousEnded: Boolean, exoPlayer: ExoPlayer) {
        // "Sleep after this song": the queue moving on by itself is the
        // moment the track the user meant has finished. REPEAT counts
        // too, or the timer would never fire with repeat-one on.
        if (previousEnded && SleepTimer.afterTrack.value) {
            exoPlayer.pause()
            SleepTimer.cancel()
        }
        if (exoPlayer.isPlaying) registerCurrentPlay()
    }

    /** The progress sampler's tick while [player] is playing. */
    fun sample(player: ExoPlayer) {
        lastPositionSeconds = player.currentPosition / 1000
        player.currentMediaItem?.mediaId?.let {
            PlaybackTracker.onProgress(it, lastPositionSeconds)
        }
        // The device's own listening record — see [ListeningRecorder],
        // which counts wall-clock time between ticks rather than
        // reading the position. This loop is the only place in the app
        // that ticks exactly while audio is coming out, which is what
        // makes it the right place to count from.
        player.currentMediaItem?.toSong()?.let {
            ListeningRecorder.onSample(it, player.duration)
        }
    }

    /** The service is going: close out what was playing, off the service's scope. */
    fun release() {
        // The YouTube Music history entry for whatever was playing, closed out
        // on the same terms as the ListenBrainz submit below: a swipe-away never
        // fires STATE_ENDED, and the tracker's own scope outlives this service,
        // so the ping still goes out after the service scope is cancelled.
        PlaybackTracker.onPlaybackFinished(
            player()?.currentPosition?.div(1000) ?: lastPositionSeconds,
        )
        // Also the last chance to close out the track that was playing — a
        // swipe-away or stop never fires STATE_ENDED, so the session would
        // otherwise end with an un-scrobbled song. This must not ride on the
        // service scope: it is cancelled a few lines down, and the request
        // should still reach ListenBrainz.
        val lastSong = listenBrainzSong
        if (lastSong != null && listenBrainzStartMs > 0L) {
            val lbEnabled =
                AppSettings.scrobblingAvailable && AppSettings.listenBrainzEnabled.value
            val lbToken = AppSettings.listenBrainzToken.value
            if (lbEnabled && lbToken.isNotBlank()) {
                val lastStart = listenBrainzStartMs
                val lastDuration = player()?.duration?.takeIf { it > 0 }
                CoroutineScope(Dispatchers.IO).launch {
                    ListenBrainzManager.submitFinished(
                        lbToken, lastSong, lastStart, System.currentTimeMillis(), lastDuration,
                        AppSettings.listenBrainzPrimaryArtistOnly.value,
                    )
                }
            }
        }
        scrobbleManager?.destroy()
        scrobbleManager = null
        // Last chance to get the current track's minutes onto disk: the scope is
        // cancelled a few lines down and the sampler goes with it.
        ListeningRecorder.onStopped()
    }

    fun observeScrobbling() {
        // Keep the manager alive while its timing settings change, so updating
        // a preference does not cancel the current track's scrobble timer.
        scope.launch {
            // Explicit <Any, _>: these flows have mixed element types, and letting
            // the reified vararg combine() infer T lands on an intersection type.
            combine<Any, ScrobblingSnapshot>(
                AppSettings.lastfmEnabled,
                AppSettings.lastfmScrobbleEnabled,
                AppSettings.lastfmNowPlaying,
                AppSettings.lastfmPrimaryArtistOnly,
                AppSettings.lastfmSessionKey,
                AppSettings.lastfmApiKey,
                AppSettings.lastfmSecret,
                AppSettings.lastfmEndpoint,
                AppSettings.scrobbleMinDuration,
                AppSettings.scrobbleDelayPercent,
                AppSettings.scrobbleDelaySeconds,
            ) { values ->
                ScrobblingSnapshot(
                    lastfmEnabled = values[0] as Boolean,
                    scrobbleEnabled = values[1] as Boolean,
                    nowPlaying = values[2] as Boolean,
                    primaryArtistOnly = values[3] as Boolean,
                    sessionKey = values[4] as String,
                    apiKey = values[5] as String,
                    secret = values[6] as String,
                    endpoint = values[7] as String,
                    minDuration = values[8] as Int,
                    delayPercent = values[9] as Float,
                    delaySeconds = values[10] as Int,
                )
            }.collectLatest { snapshot ->
                val shouldEnable = AppSettings.scrobblingAvailable &&
                    snapshot.lastfmEnabled &&
                    snapshot.scrobbleEnabled &&
                    snapshot.sessionKey.isNotBlank() &&
                    snapshot.apiKey.isNotBlank() &&
                    snapshot.secret.isNotBlank()

                if (!shouldEnable) {
                    scrobbleManager?.destroy()
                    scrobbleManager = null
                    return@collectLatest
                }

                LastFM.configure(
                    endpoint = snapshot.endpoint.ifBlank { LastFM.DEFAULT_API_ENDPOINT },
                    apiKey = snapshot.apiKey,
                    secret = snapshot.secret,
                    sessionKey = snapshot.sessionKey,
                )
                val manager = scrobbleManager ?: ScrobbleManager(scope).also {
                    scrobbleManager = it
                }
                manager.minSongDuration = snapshot.minDuration
                manager.scrobbleDelayPercent = snapshot.delayPercent
                manager.scrobbleDelaySeconds = snapshot.delaySeconds
                manager.useNowPlaying = snapshot.nowPlaying
                manager.usePrimaryArtistOnly = snapshot.primaryArtistOnly

                player()?.let { exoPlayer ->
                    if (exoPlayer.isPlaying) {
                        manager.onPlayerStateChanged(
                            isPlaying = true,
                            song = exoPlayer.currentMediaItem?.toSong(),
                            durationMs = exoPlayer.duration.takeIf { it > 0 },
                        )
                    }
                }
            }
        }
    }

    private data class ScrobblingSnapshot(
        val lastfmEnabled: Boolean,
        val scrobbleEnabled: Boolean,
        val nowPlaying: Boolean,
        val primaryArtistOnly: Boolean,
        val sessionKey: String,
        val apiKey: String,
        val secret: String,
        val endpoint: String,
        val minDuration: Int,
        val delayPercent: Float,
        val delaySeconds: Int,
    )

    /**
     * Submits a finished ListenBrainz listen, but only if the service is
     * actually scrobbling — the settings are read at call time so the helper
     * stays a no-op whenever ListenBrainz is switched off.
     */
    private fun submitListenBrainzFinished(song: Song, startMs: Long, durationMs: Long?) {
        val lbEnabled = AppSettings.scrobblingAvailable && AppSettings.listenBrainzEnabled.value
        val lbToken = AppSettings.listenBrainzToken.value
        if (!lbEnabled || lbToken.isBlank()) return
        val endMs = System.currentTimeMillis()
        scope.launch {
            ListenBrainzManager.submitFinished(lbToken, song, startMs, endMs, durationMs, AppSettings.listenBrainzPrimaryArtistOnly.value)
        }
    }

    /** Sends a ListenBrainz "now playing" update for the current track. */
    private fun submitListenBrainzPlayingNow(song: Song, positionMs: Long, durationMs: Long?) {
        val lbEnabled = AppSettings.scrobblingAvailable && AppSettings.listenBrainzEnabled.value
        val lbToken = AppSettings.listenBrainzToken.value
        if (!lbEnabled || lbToken.isBlank()) return
        scope.launch {
            ListenBrainzManager.submitPlayingNow(lbToken, song, positionMs, durationMs, AppSettings.listenBrainzPrimaryArtistOnly.value)
        }
    }
}

/**
 * The sleep timer's fade-out: the last stretch before the deadline, at most
 * [MAX_FADE_MS], and half the time left when less than twice that remains,
 * stepped down in [STEPS] equal steps so the pause lands on silence.
 */
internal object SleepFade {
    const val MAX_FADE_MS = 10_000L
    const val STEPS = 20

    /** How long to wait before fading, and how long the fade takes. */
    data class Plan(val waitMs: Long, val fadeMs: Long)

    fun plan(remainingMs: Long): Plan {
        val fadeMs = minOf(MAX_FADE_MS, remainingMs.coerceAtLeast(0L) / 2)
        return Plan(waitMs = remainingMs - fadeMs, fadeMs = fadeMs)
    }

    /**
     * Whether the timer's fade is running. Read by the crossfade, which starts
     * no new transition while it is. Main thread only, like the players.
     */
    @Volatile
    var running = false

    /**
     * The volume at [nowMs] of a fade that got the volume to itself at
     * [fromMs] and must reach silence at [endMs]: full at [fromMs], linear to
     * zero. A fade given no time at all is already silent.
     */
    fun gain(nowMs: Long, fromMs: Long, endMs: Long): Float {
        val span = endMs - fromMs
        if (span <= 0L) return 0f
        return ((endMs - nowMs).toFloat() / span).coerceIn(0f, 1f)
    }

    /** The volume at [step], counted down from `STEPS - 1` to 0. */
    fun volumeAt(step: Int): Float = (step.toFloat() / STEPS).coerceIn(0f, 1f)
}
