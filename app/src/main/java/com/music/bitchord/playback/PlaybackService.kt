package com.music.bitchord.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.widget.Toast
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.datasource.DataSpec
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import com.music.bitchord.playback.cast.CastController
import com.music.bitchord.playback.cast.CastPlayback
import com.music.bitchord.playback.cast.Speakers
import com.music.bitchord.playback.cast.CastStream
import com.music.bitchord.playback.dsd.DsdExtractorsFactory
import com.music.bitchord.playback.audio.bitperfect.BitPerfectOutput
import com.music.bitchord.playback.audio.bitperfect.DacVolume
import com.music.bitchord.playback.audio.bitperfect.usb.UsbDirectDevices
import com.music.bitchord.MainActivity
import com.music.bitchord.data.listentogether.ListenTogether
import com.music.bitchord.R
import com.music.bitchord.data.OfflinePlaylists
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.download.Downloads
import com.music.bitchord.auth.SignedIn
import com.music.bitchord.data.LikeState
import com.music.bitchord.data.NerdStats
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.stats.ListeningRecorder
import com.music.bitchord.data.model.LikeStatus
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.BitPerfectMode
import com.music.bitchord.data.settings.permits
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.widget.MediaWidget
import com.music.bitchord.widget.MediaWidgetSnapshot
import com.music.bitchord.wear.WearBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** Session command used by both the player UI and the media notification. */
const val ACTION_TOGGLE_AUTOPLAY = "com.music.bitchord.action.TOGGLE_AUTOPLAY"

/** Backoff before each retry of a failed bounded continuation page. */

/** Session command used by album/playlist playback to install a bounded context. */
const val ACTION_START_BOUNDED_CONTEXT = "com.music.bitchord.action.START_BOUNDED_CONTEXT"
const val ACTION_CLEAR_BOUNDED_CONTEXT = "com.music.bitchord.action.CLEAR_BOUNDED_CONTEXT"
const val EXTRA_BOUNDED_CONTEXT_SONGS = "bitchord.boundedContext.songs"
const val EXTRA_BOUNDED_CONTEXT_START_INDEX = "bitchord.boundedContext.startIndex"
const val EXTRA_BOUNDED_CONTEXT_SOURCE_TITLE = "bitchord.boundedContext.sourceTitle"
const val EXTRA_BOUNDED_CONTEXT_SOURCE_TYPE = "bitchord.boundedContext.sourceType"
const val EXTRA_BOUNDED_CONTEXT_SOURCE_ID = "bitchord.boundedContext.sourceId"
const val EXTRA_BOUNDED_CONTEXT_CONTINUATION = "bitchord.boundedContext.continuation"

/** Session command used to smoothly swap the current track's version (film vs release). */
const val ACTION_SWAP_VERSION = "com.music.bitchord.action.SWAP_VERSION"
const val EXTRA_SWAP_MEDIA_ITEM = "bitchord.swap.media_item"

/** Session command used by the media notification's and the 4×1 widget's Favorite button. */
/** How long the service may stay in the foreground, paused, while the watch plays. */
private const val WATCH_SESSION_FOREGROUND_MS = 6 * 60 * 60 * 1000L

const val ACTION_TOGGLE_FAVORITE = "com.music.bitchord.action.TOGGLE_FAVORITE"

/** Session command used by the media notification's and the 4×1 widget's Shuffle button. */
const val ACTION_TOGGLE_SHUFFLE = "com.music.bitchord.action.TOGGLE_SHUFFLE"

/** Session actions exposed to Android Auto for the track that is playing. */
const val ACTION_START_STATION = "com.music.bitchord.action.START_STATION"
const val ACTION_REVERT_TO_ORIGINAL = "com.music.bitchord.action.REVERT_TO_ORIGINAL"
const val ACTION_SWAP_TO_VERSION = "com.music.bitchord.action.SWAP_TO_VERSION"

/** Session commands bracketing an explicit radio queue replacement. */
const val ACTION_BEGIN_RADIO_QUEUE = "com.music.bitchord.action.BEGIN_RADIO_QUEUE"
const val ACTION_COMMIT_RADIO_QUEUE = "com.music.bitchord.action.COMMIT_RADIO_QUEUE"

/** Session command behind the player menu's "Upgrade quality". */
const val ACTION_UPGRADE_QUALITY = "com.music.bitchord.action.UPGRADE_QUALITY"

/** Session command used to dismiss and stop playback from the UI, gestures, or widgets. */
const val ACTION_DISMISS_PLAYBACK = "com.music.bitchord.action.DISMISS_PLAYBACK"

/**
 * Session command marking the span of a queue drag in the UI — see
 * [PartySync.beginQueueDrag]. Dragging a queue row sends a [Player.moveMediaItem]
 * per neighbour it crosses, same as before; what this brackets is only the
 * party publish those moves would otherwise trigger one at a time, so a jam
 * hears about the reorder once, when the row is dropped, not mid-drag.
 */
const val ACTION_QUEUE_DRAG = "com.music.bitchord.action.QUEUE_DRAG"
const val EXTRA_QUEUE_DRAG_ACTIVE = "bitchord.queueDrag.active"

/**
 * Play a history row again from the queue screen, keeping later history as
 * history. Takes the target index and mediaId.
 */
const val ACTION_QUEUE_REPLAY_HISTORY = "com.music.bitchord.action.QUEUE_REPLAY_HISTORY"
const val EXTRA_QUEUE_MOVE_INDEX = "bitchord.queueMove.index"
const val EXTRA_QUEUE_MOVE_MEDIA = "bitchord.queueMove.media"

/** A full first page for an explicitly requested station. */
private const val INITIAL_STATION_TRACKS = 24

/**
 * Background playback via Media3. A [MediaLibraryService] gives us the media
 * notification, lockscreen/Bluetooth controls, and Android Auto surface for
 * free; UI processes attach with a MediaController.
 *
 * Queue items carry a `bitchord://watch?v=<videoId>` URI. The actual stream
 * URL is resolved lazily by [ResolvingDataSource] the moment ExoPlayer opens
 * the item — stream URLs expire after a few hours, so resolving at play time
 * (on Media3's loader thread, hence runBlocking is safe) keeps queues valid.
 *
 * A single ExoPlayer owns the queue and backs the session for the service's
 * whole life; [CrossfadeController] rides on top of it as volume automation.
 */
@UnstableApi
class PlaybackService : MediaLibraryService() {

    private var mediaSession: MediaLibrarySession? = null

    /** The browse tree Android Auto and other media browsers see. */
    private val library by lazy { LibraryBrowser(this, scope) }

    /** Favorite, Shuffle, Radio and Revert on the notification and in the car. */
    private val buttons by lazy {
        SessionButtons(
            context = this,
            session = { mediaSession },
            currentItem = { player?.currentMediaItem },
            canLike = ::canLike,
            isLiked = ::isLiked,
        )
    }

    /**
     * The player the session is on. Swaps with [spare] at every crossfade — see
     * [adoptPlayer] — so anything reading it must read it *now* rather than
     * capturing it.
     */
    private var player: ExoPlayer? = null

    /**
     * The idle player. Between transitions it holds nothing; to arm one,
     * [CrossfadeController] loads it with the queue positioned on the incoming
     * track.
     */
    private var spare: ExoPlayer? = null

    private var crossfade: CrossfadeController? = null

    /** Automix's DSP analyzer — see [com.music.bitchord.playback.smart.TrackAnalyzer]. */
    private val trackAnalyzer = com.music.bitchord.playback.smart.TrackAnalyzer(this, AudioCache)

    /** Shared with the crossfade's tail player, so both read the same disk cache. */
    private var mediaSourceFactory: DefaultMediaSourceFactory? = null

    /** When the current track was chosen, for the time-to-first-audio log. */
    private var trackSelectedAt: Long? = null

    /**
     * The last track that actually made a sound, which is how [recoverFrom]
     * tells "this never started" from "this died in the middle".
     *
     * Deliberately a mediaId rather than a flag that gets cleared on every
     * transition. A retry *is* a seek, so a transition fires for it — the same
     * trap [recoveries] fell into — and a flag cleared there would make a track
     * that failed twenty seconds in look like a track that never began, which is
     * precisely the case that must not be skipped past. Holding the id instead
     * means the queue moving to a different track invalidates it for free, and
     * nothing has to be cleared anywhere.
     *
     * The one thing it is deliberately wrong about: a track played earlier in
     * the session and returned to — by repeat-one, by the previous button —
     * still counts as audible, so a later failure to start it is only retried
     * and not skipped. That errs towards leaving the listener where they are,
     * which is the safe direction for a queue.
     */
    private var audibleMediaId: String? = null

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /**
     * The two players' loudness stages and the gain readout. Which stage is the
     * session's is read off [activeFilter]'s role, which every handoff, version
     * swap and rebuild already keeps right, so the two can never disagree
     * about which sink is which.
     */
    /**
     * The two players' DSP stages, where their output goes, and how — see
     * [AudioRoutingAndDspPipeline].
     */
    private val pipeline = AudioRoutingAndDspPipeline(
        context = this,
        scope = scope,
        players = { listOfNotNull(player, spare) },
        transitioning = { crossfade?.isTransitioning() == true },
        rebuildPlayers = { enableFloat -> rebuildPlayersForOutput(enableFloat) },
    )
    private var configuredFloatOutput by pipeline::configuredFloatOutput
    private var currentAudioInputFormat by pipeline::currentAudioInputFormat
    private var activeTrackIsDolbyAtmos by pipeline::activeTrackIsDolbyAtmos

    private val loudness = LoudnessDispatch(
        scope = scope,
        aIsActive = { pipeline.aIsActive },
        player = { player },
    )

    /** The album or playlist playing as a bounded window — see [BoundedContextController]. */
    private val bounded: BoundedContextController = BoundedContextController(
        scope = scope,
        player = { player },
        transitioning = { crossfade?.isTransitioning() == true },
        casting = { CastController.state.value.casting || castPlayback.active },
        refreshButtons = { refreshCustomLayouts() },
        onContextStarting = {
            // The old queue's AutoPlay tail is not carried over, as the flat path
            // in [QueueTimeline.buildContextQueue] does not: it was suggested for
            // the music being replaced.
            autoplay.clearStash()
        },
        onRestoring = { persistedQueueStart = 0 },
    )

    /** AutoPlay's tail of suggestions after the queue — see [AutoplaySupply]. */
    /**
     * Picking a failed track back up, and the second look for a better copy of
     * a playing one — see [StreamRecoveryCoordinator].
     */
    private val recovery: StreamRecoveryCoordinator = StreamRecoveryCoordinator(
        context = this,
        scope = scope,
        currentPlayer = { player },
        crossfade = { crossfade },
        mediaSourceFactory = { requireNotNull(mediaSourceFactory) },
        audibleMediaId = { audibleMediaId },
    )
    private var swappingMediaId by recovery::swappingMediaId
    private var swapCutAt by recovery::swapCutAt
    private var audioFormatFor by recovery::audioFormatFor
    private var retryingMediaId by recovery::retryingMediaId
    private var consecutiveErrorSkips by recovery::consecutiveErrorSkips

    /** Switching the playing track between cuts of the same song — see [VersionAudioSynchronizer]. */
    private val versionSync = VersionAudioSynchronizer(
        context = this,
        scope = scope,
        player = { player },
        spare = { spare },
        transitioning = { crossfade?.isTransitioning() == true },
        loudness = loudness,
        markSwapping = { swappingMediaId = it },
        refreshButtons = { refreshCustomLayouts() },
        handOverDecks = ::handOverDecks,
        onBecameCurrent = { item ->
            onTrackBecameCurrent(
                item,
                previousEnded = false,
                reason = Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED,
                alreadyAudible = true,
            )
        },
    )

    /** What the listener's plays leave behind, and the sleep timer — see [PlaybackHistoryAndSleepTimer]. */
    private val history = PlaybackHistoryAndSleepTimer(
        scope = scope,
        player = { player },
        volumeBusy = { crossfade?.isTransitioning() == true || versionSync.active },
    )

    /** What's playing, published to the listener's Discord profile — see [DiscordPresence]. */
    private val discord = DiscordPresence(context = this, scope = scope, player = { player })

    /** The lyric line under the notification's title — see [ServiceLyrics]. */
    private val lyrics = ServiceLyrics(
        context = this,
        scope = scope,
        player = { player },
        loopPositionMs = { crossfade?.loopPositionMs() },
        setSessionExtras = { mediaSession?.setSessionExtras(it) },
    )

    /** Auto-skips sponsor & non-music segments during playback — see [SponsorBlockCoordinator]. */
    private val sponsorBlock = SponsorBlockCoordinator(
        context = this,
        scope = scope,
        player = { player },
    )

    /** What the decoder is really being fed, for stats for nerds — see [NerdStatsPublisher]. */
    private val nerdStats = NerdStatsPublisher(context = this, scope = scope, player = { player })

    /** Getting the queue ahead of the playhead ready — see [ReadAheadCoordinator]. */
    private val readAhead = ReadAheadCoordinator(
        scope = scope,
        player = { player },
        spare = { spare },
        transitioning = { crossfade?.isTransitioning() == true },
        plannedTransitionFor = { mediaId -> crossfade?.plannedTransitionFor(mediaId) },
        versionSwapActive = { versionSync.active },
        swapVersion = { item -> versionSync.smoothSwapCurrentTrackVersion(item) },
        recovery = recovery,
    )

    private val autoplay: AutoplaySupply = AutoplaySupply(
        scope = scope,
        player = { player },
        bounded = bounded,
        refreshButtons = { refreshCustomLayouts() },
    )

    /**
     * The stream resolver the player's own data source runs, kept so a Cast
     * receiver can be handed the URL it would have produced — see
     * [resolveForCast]. Null until [onCreate] has built it.
     */
    private var streamResolver: ResolvingDataSource.Resolver? = null

    /**
     * What plays on a Cast receiver, when the music is on one. The phone's
     * player keeps the queue and is held paused; this is what the session
     * player answers with in the meantime. See [CastPlayback].
     */
    private val castPlayback = CastPlayback(
        scope = scope,
        localPlayer = { player },
        resolve = ::resolveForCast,
        say = { message -> Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show() },
    )

    /**
     * The BitChord watch app as the speaker — the same model as Cast: the phone
     * keeps the queue paused, the watch plays it and owns the clock. See
     * [WearBridge.createSpeaker].
     */
    private val watchSpeaker by lazy {
        WearBridge.createSpeaker(this, scope, localPlayer = { player }, onChanged = { publishWidgetState() })
    }

    /** Every remote speaker, as the one the session player reports. */
    private val speakers by lazy { Speakers(listOf(castPlayback, watchSpeaker)) }

    private val zeroVolumeObserver by lazy {
        ZeroVolumePlaybackObserver(
            context = this,
            audioManager = getSystemService(android.media.AudioManager::class.java),
            isPauseOnZeroVolumeEnabled = { com.music.bitchord.data.settings.AppSettings.pauseOnZeroVolume.value },
            isPlaying = { player?.isPlaying == true },
            onPausePlayback = { player?.pause() },
            onResumePlayback = { player?.play() },
        )
    }

    /**
     * Binds playback to a Listen Together party, when there is one.
     *
     * Here rather than in the UI because a party has to outlive the app
     * being backgrounded and the screen going off — see [PartySync].
     */
    private var partySync: PartySync? = null

    private var favoriteActionJob: Job? = null
    private var stationActionJob: Job? = null
    /** Rate limit for listener-requested retries; automatic upgrades do not touch it. */

    /** Index in the live queue represented by entry zero of the persisted window. */
    private var persistedQueueStart = 0

    /**
     * The last repeat mode seen, so [onRepeatModeChanged] can tell which
     * direction the change went in: the callback reports where the player has
     * arrived, and leaving repeat-all is the half that has to restore.
     */
    private var lastRepeatMode = Player.REPEAT_MODE_OFF

    /**
     * Everything the service books against the player it is currently on.
     *
     * A field rather than an anonymous object registered once, because the
     * session moves between two players at every crossfade — see [adoptPlayer]
     * — and this has to move with it. It is attached to exactly one player at a
     * time: the one [player] names.
     */
    private val playbackListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            val activePlayer = this@PlaybackService.player ?: return
            if (player === activePlayer) pipeline.updateAudioEffectSession(activePlayer)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            // The player this fired on, which is by definition the one the
            // session is currently pointed at.
            val exoPlayer = player ?: return
            // The only number that describes what a listener actually
            // waits through. Every other timing in this app measures one
            // leg of getting a track started — a resolve, a client walk, an
            // extraction — and a leg being fast has repeatedly turned out
            // to say nothing about whether sound arrived quickly, because
            // the legs that were measured were the ones running in the
            // background for tracks nobody was waiting on.
            if (isPlaying) {
                trackSelectedAt?.let {
                    TrackLog.d(
                        "BitChord",
                        "TIMING first audio: ${SystemClock.elapsedRealtime() - it}ms since track selected",
                        about = exoPlayer.currentMediaItem?.mediaId,
                    )
                    trackSelectedAt = null
                }
                // Sound is out of the speaker, so whatever happens to this track
                // from here is a failure mid-song. Also the one thing that can
                // say the queue is not simply unplayable end to end.
                audibleMediaId = exoPlayer.currentMediaItem?.mediaId
                consecutiveErrorSkips = 0
            }
            if (isPlaying) history.registerCurrentPlay()
            // Nothing to read ahead for while paused, and a pause is often
            // the last thing that happens before the process goes idle.
            if (isPlaying) readAhead.prefetchAround(exoPlayer) else readAhead.cancelPrefetch()
            if (isPlaying) recovery.lookForBetterCopy(exoPlayer)
            savePlaybackState(exoPlayer)
            // Not strictly needed for the glyph — onPlayWhenReadyChanged has
            // already flipped that — but this is where hasNext/hasPrevious and
            // the artwork are known to be settled.
            publishWidgetState()

            history.onIsPlayingChanged(isPlaying, exoPlayer)

            // Discord: a pause has to clear the presence, not just stop
            // refreshing it. Discord's countdown runs on its own clock from the
            // timestamps it was given, so a presence left up while paused goes
            // on advancing through a song that has stopped — and finishes it.
            if (isPlaying) {
                discord.push(exoPlayer)
                lyrics.startTicker()
                sponsorBlock.startTicker()
            } else {
                discord.clear()
                lyrics.stopTicker()
                sponsorBlock.stopTicker()
            }
        }

        /**
         * The one callback the home-screen widget can be driven from.
         *
         * `onIsPlayingChanged` is too late by seconds: this app resolves a
         * stream before it can buffer one, and for a YouTube track that means a
         * NewPipe extraction, all of which happens with `isPlaying` still false.
         * A widget keyed on that answers a tap on play by leaving the play glyph
         * exactly where it was — the control reads as broken, and the obvious
         * response is to tap it again. `playWhenReady` flips on the command, not
         * on the audio, which is what the media notification shows too.
         */
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            // Something started the phone's own player while a receiver is the
            // speaker — an internal path, since the session player's door
            // already sends a listener's play to the receiver. The phone goes
            // quiet again and the receiver is asked instead.
            if (playWhenReady) player?.let(speakers::onLocalStartedPlaying)
            publishWidgetState(playing = playWhenReady)
            // The only place the *reason* can be read. A party has to tell a
            // pause the listener asked for from one another app imposed, and
            // [Player] does not keep the answer around to be asked later.
            partySync?.onPlayWhenReadyChanged(playWhenReady, reason)
        }

        /**
         * A seek is the one change to a playing track that no other callback
         * reports, and the only one Discord cannot work out for itself: its bar
         * is drawn from two absolute instants, so moving the playhead without
         * sending new ones leaves the profile counting down from where the
         * listener no longer is.
         */
        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            val exoPlayer = player ?: return
            if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                readAhead.lastSeekAt = SystemClock.elapsedRealtime()
                if (exoPlayer.isPlaying) discord.push(exoPlayer)
                lyrics.update()
                // The watch extrapolates its progress ring from the last
                // position it was given, the same way Discord does.
                WearBridge.republish(this@PlaybackService, exoPlayer)
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // The player this fired on, which is by definition the one the
            // session is currently pointed at.
            val exoPlayer = player ?: return
            // A quality swap replaces the playing item, which Media3
            // reports here as a playlist change — indistinguishable, from
            // this callback's point of view, from the queue moving on. It
            // is not the queue moving on: it is the same song, at the same
            // position, from a better source. Letting the bookkeeping below
            // run for it scrobbled the track twice, wrote a second history
            // entry, resubmitted it to ListenBrainz and closed out its
            // play count mid-play — all of which happened, and all of which
            // are invisible until someone reads their listening history.
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED &&
                mediaItem?.mediaId != null &&
                mediaItem.mediaId == swappingMediaId
            ) {
                swappingMediaId = null
                return
            }

            // A receiver, if it is the speaker, is put on whatever became
            // current — or it is the one that moved, and this is the phone
            // catching up, which [CastPlayback] tells apart by track.
            speakers.onLocalTransition(mediaItem, reason)

            // No crossfade case to allow for here any more. A blended advance
            // never reaches this callback — the incoming track starts as the
            // *first* item of the other player — so it is booked by
            // [adoptPlayer] instead, and what is left arriving here is only ever
            // ExoPlayer moving the queue on by itself, a repeat, or a skip.
            onTrackBecameCurrent(
                mediaItem,
                previousEnded = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                    reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT,
                reason = reason,
            )
            // Prune consumed USER_QUEUE entries when playback enters CONTEXT,
            // keeping the timeline clean and ensuring REPEAT_MODE_ALL loops only CONTEXT items.
            QueueCoordinator.consumePlayedUserQueue(exoPlayer)

            // The queue moving on by itself. Nobody pressed anything, but it
            // is still this device deciding what the party plays next, and no
            // other path reports it: an automatic advance never passes through
            // the session wrapper. A skip does pass through it, and is reported
            // there; publishing is debounced, so being told twice costs nothing.
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                partySync?.onLocalIntent()
            }
            autoplay.reload()
            lyrics.load()
            sponsorBlock.onMediaItemTransition(mediaItem?.mediaId)
            if (exoPlayer.isPlaying) {
                lyrics.startTicker()
                sponsorBlock.startTicker()
            }
            refreshCustomLayouts()
        }

        /**
         * A failed stream is not a failed track: nothing else in this
         * service ever calls [Player.prepare] again, so before this
         * existed a single read error left the player in `STATE_IDLE` for
         * good. The notification kept the song on it, the play button kept
         * being pressed, and nothing happened — which is exactly what a
         * broken app looks like from the outside.
         */
        override fun onPlayerError(error: PlaybackException) {
            // The player this fired on, which is by definition the one the
            // session is currently pointed at.
            val exoPlayer = player ?: return
            recovery.recoverFrom(error, exoPlayer)
        }

        // Nothing follows the last track, so there is no transition to
        // pause on — the queue simply runs out and the timer is spent.
        override fun onPlaybackStateChanged(state: Int) {
            // The player this fired on, which is by definition the one the
            // session is currently pointed at.
            val exoPlayer = player ?: return
            if (state == Player.STATE_ENDED) {
                SleepTimer.cancel()
                history.onQueueEnded(exoPlayer)
                // A missed/empty AutoPlay response can let the last queued
                // track finish before anything is appended. There will be no
                // item transition to run the ordinary refill path, so give the
                // still-enabled empty queue another chance here.
                autoplay.refreshIfQueueEmpty()
            }
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            val previous = lastRepeatMode
            lastRepeatMode = repeatMode
            speakers.onLocalRepeatChanged(repeatMode)
            player?.let { WearBridge.republish(this@PlaybackService, it) }
            // Repeat-one makes the "next" track this one again.
            player?.let { loudness.active().nextMediaId = it.nextMediaIdOrNull() }
            // Repeat-all loops the queue as it stands; AutoPlay's tracks are the
            // opposite of that — an endless supply of new ones — so they come
            // back out first, and native REPEAT_MODE_ALL then wraps a plain
            // queue exactly as it should. [loadAutoplayForCurrentTrack] leaves
            // it alone for as long as repeat-all stays on.
            //
            // Done here rather than in the UI that used to do it because this is
            // the only place that sees the *previous* mode, and taking the
            // tracks back is only half the job: they have to go in again when
            // the loop ends, or a listener who cycles repeat on and straight
            // back off is left with a queue that simply stops after the playing
            // track.
            when {
                repeatMode == Player.REPEAT_MODE_ALL -> {
                    autoplay.stash()
                    // A bounded context may retain physical history before its
                    // final current item. Materialise the logical next cycle
                    // before native repeat-all can wrap into that history.
                    // The scheduler defers this append while crossfade owns
                    // the players and applies it at settlement.
                    if (bounded.active) bounded.scheduleBoundedContextTopUp()
                }
                previous == Player.REPEAT_MODE_ALL -> autoplay.restore()
            }
            // Turning repeat-all back off can leave the current item at the end
            // of the queue, which is the same trigger as a normal transition.
            autoplay.load()
        }

        /**
         * AutoPlay appends to the queue after the transition that ran it
         * dry, so the track to read ahead for often only exists once the
         * timeline has changed.
         */
        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            // The player this fired on, which is by definition the one the
            // session is currently pointed at.
            val exoPlayer = player ?: return
            // Whether or not it is playing: a held video buffers until this answers.
            readAhead.convertVideosAround(exoPlayer)
            if (exoPlayer.isPlaying) readAhead.prefetchAround(exoPlayer)
            // A queue edit can change what follows gaplessly, and the loudness
            // stage switches to that track on its own at the boundary.
            loudness.active().nextMediaId = exoPlayer.nextMediaIdOrNull()
            if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) {
                speakers.onLocalQueueChanged()
                saveQueueSnapshot(exoPlayer)
                refreshCustomLayouts()
                // Queue edits can remove AutoPlay's whole tail while leaving
                // its old seed memoized. Detect that state at its source and
                // make the current track eligible for a fresh load.
                autoplay.refreshIfQueueEmpty()
            }
        }
    }

    /** Registered alongside [playbackListener], and moved with it. */
    private val formatListener = object : AnalyticsListener {
        override fun onAudioInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: Format,
            decoderReuseEvaluation: DecoderReuseEvaluation?,
        ) {
            // Taken off the event's own window rather than off the player,
            // so it names the track this format arrived for even if the
            // queue has moved on again since. See [audioFormatFor].
            audioFormatFor = eventTime.mediaId()
            // Ground truth for a real-device listening test: this is the
            // renderer's own Format, straight off the decoder with none of
            // the app's caching/upgrade logic in between, so it's the one
            // line that can prove a "hi-res" session never quietly slid
            // onto a lower-rate stream mid-track. `adb logcat -s DECODE:I`.
            val measured = format.measure()
            val khz = measured.sampleRateHz
                ?.let { "%.1fkHz".format(Locale.ROOT, it / 1000.0) } ?: "?kHz"
            val kbps = format.bitrate.takeIf { it > 0 }
                ?.let { "${it / 1000}kbps" } ?: "bitrate n/a"
            val depth = measured.bitDepth?.let { "${it}-bit" } ?: "?-bit"
            val channels = measured.channels?.let { "${it}ch" } ?: "?ch"
            TrackLog.i(
                "DECODE",
                "$audioFormatFor <- ${format.sampleMimeType} $khz $kbps $depth $channels",
                about = audioFormatFor,
            )
            activeTrackIsDolbyAtmos = NerdStats.isDolbyAtmosMime(format.sampleMimeType)
            pipeline.applySpatialAudioEnabled()
            nerdStats.publish()
            currentAudioInputFormat = format
            pipeline.applyOutputRoute()
        }

        /**
         * The seam, measured rather than described. This fires when the
         * audio track starts putting samples out again after the sink was
         * flushed, which for a quality swap is the exact instant the music
         * comes back — and the gap between it and the swap is the only
         * number that says whether any of the work above paid off. Every
         * other timing here brackets a fetch, and a fetch being fast has
         * repeatedly said nothing about whether the listener heard a hole.
         */
        override fun onAudioPositionAdvancing(
            eventTime: AnalyticsListener.EventTime,
            playoutStartSystemTimeMs: Long,
        ) {
            val cutAt = swapCutAt ?: return
            swapCutAt = null
            TrackLog.d(
                "BitChord",
                "swap seam: ${SystemClock.elapsedRealtime() - cutAt}ms of silence",
                about = eventTime.mediaId(),
            )
        }

        /**
         * The three legs the seam breaks into, logged separately because
         * they have entirely different fixes: getting the new source
         * loaded and past the load control's gate, standing a decoder up,
         * and opening an audio track. Only the first is ours to shorten.
         */
        override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) {
            val cutAt = swapCutAt ?: return
            if (state == Player.STATE_READY) {
                TrackLog.d(
                    "BitChord",
                    "swap leg: ready ${SystemClock.elapsedRealtime() - cutAt}ms after the cut",
                    about = eventTime.mediaId(),
                )
            }
        }

        override fun onAudioDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            AudioOutputStatus.publishDecoder(decoderName)
            TrackLog.i(
                "AUDIO_OUT",
                "decoder=$decoderName initialized in ${initializationDurationMs}ms",
                about = eventTime.mediaId(),
            )
            val cutAt = swapCutAt ?: return
            TrackLog.d(
                "BitChord",
                "swap leg: $decoderName stood up in ${initializationDurationMs}ms, " +
                    "${SystemClock.elapsedRealtime() - cutAt}ms after the cut",
                about = eventTime.mediaId(),
            )
        }

        override fun onAudioTrackInitialized(
            eventTime: AnalyticsListener.EventTime,
            audioTrackConfig: AudioSink.AudioTrackConfig,
        ) {
            AudioOutputStatus.publishAudioTrack(
                encoding = audioTrackConfig.encoding,
                sampleRateHz = audioTrackConfig.sampleRate,
                bufferSize = audioTrackConfig.bufferSize,
            )
            TrackLog.i(
                "AUDIO_OUT",
                "AudioTrack ${audioTrackConfig.sampleRate}Hz encoding=${audioTrackConfig.encoding}",
                about = eventTime.mediaId(),
            )
        }

        /**
         * The true-bitrate feed: every completed media load adds its bytes
         * — and the stretch of the track's timeline those bytes decode to —
         * to [nerdStats], attributed to the track whose window the
         * load belongs to. Manifest, DRM and initialization loads are not
         * the audio and are left out.
         */
        override fun onLoadCompleted(
            eventTime: AnalyticsListener.EventTime,
            loadEventInfo: LoadEventInfo,
            mediaLoadData: MediaLoadData,
        ) {
            if (mediaLoadData.dataType != C.DATA_TYPE_MEDIA &&
                mediaLoadData.dataType != C.DATA_TYPE_MEDIA_PROGRESSIVE_LIVE
            ) {
                return
            }
            nerdStats.onBytesLoaded(
                mediaId = eventTime.mediaId(),
                bytes = loadEventInfo.bytesLoaded,
                mediaStartMs = mediaLoadData.mediaStartTimeMs,
                mediaEndMs = mediaLoadData.mediaEndTimeMs,
            )
        }

        /**
         * A seek lands here, and so does every other position jump: the
         * meter must not count the jumped-over stretch as played time.
         */
        override fun onPositionDiscontinuity(
            eventTime: AnalyticsListener.EventTime,
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            nerdStats.onDiscontinuity(eventTime.mediaId())
        }

    }

    /** [playbackListener] and [formatListener] on the session's player, moved as one. */
    private val sessionListeners = SessionListeners(playbackListener, formatListener)

    /**
     * Which track an analytics event is about, taken off the event's own window.
     *
     * The player has moved on by the time some of these arrive — a format
     * change for the outgoing track lands after the transition — so its
     * `currentMediaItem` names the wrong one. The event carries the timeline it
     * was raised against, which does not.
     */
    private fun AnalyticsListener.EventTime.mediaId(): String? = timeline
        .takeIf { windowIndex < it.windowCount }
        ?.getWindow(windowIndex, Timeline.Window())
        ?.mediaItem
        ?.mediaId

    override fun onCreate() {
        super.onCreate()

        if (com.music.bitchord.data.innertube.Innertube.cookie == null) {
            com.music.bitchord.data.innertube.Innertube.cookie = com.music.bitchord.auth.AuthStore(this).cookie
        }

        // First, because everything below assumes it is standing up fresh and
        // one of the two ways this service starts does not give it that.
        //
        // A cold start runs in a new process, where the session-scoped state
        // these two hold is empty anyway. A *warm* one doesn't: closing the app
        // destroys the service while Android keeps the process to reuse, so
        // without this a second service inherits the first one's idea of what
        // was playing and what has already been asked about. That cost the
        // reported bug all three of its symptoms — a badge reading "Lossless"
        // over a player holding no bytes, and a track that had been upgraded to
        // FLAC playing its cached Opus with no second look, permanently, because
        // its id was still recorded as answered. Both are documented where the
        // state lives.
        NerdStats.forgetLastSession()
        nerdStats.forgetSession()
        QualityUpgrade.forgetLastSession()

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelId(CHANNEL_ID)
                .setChannelName(R.string.playback_channel_name)
                .build()
                .apply { setSmallIcon(R.drawable.ic_notification_logo) },
        )

        // Both the player screen and notification route their Shuffle command
        // here. Observe its state so either surface's icon follows the queue
        // edit once that single service-side transaction has completed.
        scope.launch {
            QueueShuffle.enabled
                .collectLatest {
                    refreshCustomLayouts()
                    publishWidgetState()
                }
        }
        // While the watch is the speaker this process serves its audio, and a
        // paused service drops out of the foreground after ten minutes — after
        // which Android may freeze the process mid-stream. Held up for the
        // length of the session instead.
        scope.launch {
            WearBridge.watchSession.collect { onWatch ->
                setForegroundServiceTimeoutMs(
                    if (onWatch) WATCH_SESSION_FOREGROUND_MS else DEFAULT_FOREGROUND_SERVICE_TIMEOUT_MS,
                )
            }
        }
        // Signing in or out adds or removes Like everywhere at once.
        scope.launch {
            SignedIn.state(this@PlaybackService)
                .drop(1)
                .collectLatest {
                    refreshCustomLayouts()
                    publishWidgetState()
                }
        }
        scope.launch {
            LikeState.overrides
                .map { it[player?.currentMediaItem?.mediaId] }
                .distinctUntilChanged()
                .collectLatest {
                    refreshCustomLayouts()
                    publishWidgetState()
                }
        }
        // A device file's heart, liked from the app, the notification or a widget.
        scope.launch {
            OfflinePlaylists.liked
                .map { liked -> player?.currentMediaItem?.toSong()?.localUri?.let { it in liked } }
                .distinctUntilChanged()
                .collectLatest {
                    refreshCustomLayouts()
                    publishWidgetState()
                }
        }

        pipeline.bluetoothTracker.start()
        zeroVolumeObserver.register()
        scope.launch {
            zeroVolumeObserver.noticeEvent.collect {
                Toast.makeText(applicationContext, R.string.zero_volume_play_blocked, Toast.LENGTH_SHORT).show()
            }
        }
        scope.launch {
            // A codec change (LDAC 24-bit ↔ SBC) can flip float output on or off,
            // which needs a sink rebuild, not just a route refresh. Any other real
            // change — codec name, sample rate — still refreshes the route, so the
            // readout follows it; only the update timestamp is ignored.
            pipeline.bluetoothTracker.telemetry
                .map { it.copy(lastUpdatedMs = 0L) }
                .distinctUntilChanged()
                .collect { pipeline.requestOutputReconfiguration() }
        }
        scope.launch {
            // Every resolve reads the ceiling afresh, but not everything that
            // plays is resolved afresh — see [onCeilingLowered].
            var previous = AppSettings.effectiveAudioQuality
            combine(
                AppSettings.audioQualityWifi,
                AppSettings.audioQualityCellular,
                AppSettings.meteredConnection,
                AppSettings.streamCeilingOverride,
            ) { _, _, _, _ -> AppSettings.effectiveAudioQuality }
                .distinctUntilChanged()
                .collect { ceiling ->
                    val lost = SourceKind.entries.filter { previous.permits(it) && !ceiling.permits(it) }
                    previous = ceiling
                    if (lost.isNotEmpty()) readAhead.onCeilingLowered(ceiling, lost)
                }
        }

        val streamResolver = PlaybackStreamResolver(recovery)
        this.streamResolver = streamResolver

        val defaultDataSourceFactory = streamResolver.upstreamFactory(this)
        AudioCache.setUpstream(defaultDataSourceFactory)
        // DsdExtractorsFactory: the stock extractors plus DSF/DFF, which Media3
        // cannot open at all. A DSD file leaves the extractor as float PCM.
        mediaSourceFactory = DefaultMediaSourceFactory(
            MusicOnlyPlayback.holdingFactory(AudioCache.playbackFactory(defaultDataSourceFactory)),
            DsdExtractorsFactory(),
        )
            .setLoadErrorHandlingPolicy(PermanentAwareLoadErrorPolicy())
        // The watch, when it is the speaker, reads tracks through this same chain.
        WearBridge.attachStreams(AudioCache.playbackFactory(defaultDataSourceFactory))

        configuredFloatOutput = pipeline.shouldEnableFloatOutput()
        val exoPlayer = buildPlayer(
            pipeline.a,
            loudness.a,
            ownsSession = true,
        )
        val sparePlayer = buildPlayer(
            pipeline.b,
            loudness.b,
            ownsSession = false,
        )
        player = exoPlayer
        spare = sparePlayer
        // Both sinks feed the same session id, so the system equalizer and any
        // other effect attached to the app applies to whichever player happens
        // to be audible. Without it a crossfade would audibly change EQ halfway
        // through, and again at every handoff.
        sparePlayer.audioSessionId = exoPlayer.audioSessionId
        pipeline.watchOutputDevices()
        pipeline.applyOutputRoute()

        AppSettings.audioSessionId.value = exoPlayer.audioSessionId
        applySettings(exoPlayer)
        applySettings(sparePlayer)
        observeSettings()
        history.observeScrobbling()
        discord.observe()
        history.watchSleepTimer()
        val restored = if (PartyPersonalQueueStash.hasStash()) {
            val stashed = PartyPersonalQueueStash.load()
            PartyPersonalQueueStash.clear()
            if (stashed != null && stashed.songs.isNotEmpty()) {
                persistedQueueStart = 0
                exoPlayer.setMediaItems(stashed.songs.map { it.toMediaItem() }, stashed.index, stashed.positionMs)
                true
            } else {
                restoreLastQueue(exoPlayer)
            }
        } else {
            restoreBoundedQueueOrLast(exoPlayer)
        }
        if (restored) {
            publishWidgetState()
        } else {
            MediaWidgetSnapshot.save(this, MediaWidgetSnapshot.EMPTY)
            MediaWidget.refresh(this)
        }
        // History pings fire once a track is actually audible — both when
        // playback starts and when the queue moves on while already playing.
        lastRepeatMode = exoPlayer.repeatMode
        sessionListeners.attach(exoPlayer)
        // After the player exists and before the session is built: the
        // session's wrapper reports the user's actions to it.
        partySync = PartySync(scope) { player }.also { it.start() }
        // AutoPlay has one shared supplier in a party. The host supplies it
        // while connected; if they disappear, the lowest stable connected member
        // ID takes over. That election is deterministic on every phone, so two
        // listeners never append different recommendations at once.
        scope.launch {
            ListenTogether.state
                .map { state -> Triple(state.code, state.playback.autoplayEnabled, autoplaySupplierId(state)) }
                .distinctUntilChanged()
                .collectLatest { (code, enabled, supplierId) ->
                    if (code != null && enabled && supplierId == ListenTogether.state.value.you?.memberId) {
                        autoplay.reload()
                    }
                }
        }
        scope.launch {
            var wasInParty = ListenTogether.state.value.inParty
            ListenTogether.state
                .map { it.inParty }
                .distinctUntilChanged()
                .collect { inParty ->
                    if (wasInParty && !inParty) bounded.rebuildBoundedContextAfterParty()
                    wasInParty = inParty
                }
        }
        autoplay.load()

        // Only the analytics listener reports the format the audio renderer was
        // configured with. Treated as a trigger rather than a source: the
        // publisher reads the format off the player, so it can't go stale
        // against the track the bitrate is looked up for. It went on with
        // [playbackListener] above — see [SessionListeners].

        reportProgress()
        readAhead.watchDirectWindow()

        val controller = createCrossfadeController()
        crossfade = controller
        QueueShuffle.isCrossfadeTransitioning = { crossfade?.isTransitioning() == true }
        controller.start()

        mediaSession = MediaLibrarySession.Builder(
            this,
            newSessionPlayer(exoPlayer, controller),
            SessionCallback(
                scope = scope,
                library = library,
                buttons = buttons,
                player = { player },
                perform = ::performSessionCommand,
            ),
        )
            .setId(SESSION_ID)
            .setSessionActivity(sessionActivity())
            .build()
        refreshCustomLayouts()

        // Last, so a receiver that outlived the app is adopted by a service
        // whose queue is already restored.
        CastController.ensureStarted(this)
        CastController.attach(castPlayback)
    }

    private fun newSessionPlayer(player: Player, crossfade: CrossfadeController) = SessionPlayer(
        player,
        crossfade,
        onUserIntent = {
            partySync?.onLocalIntent()
            zeroVolumeObserver.onManualAction()
        },
        deferPlayToParty = { partySync?.shouldDeferPlay() == true },
        lockedTransport = { playing -> partySync?.onLockedTransport(playing) == true },
        cast = speakers,
        shouldBlockPlay = { zeroVolumeObserver.shouldBlockPlayAction() },
    ) { lyrics.lastPublishedSubtitle }

    /**
     * The stream a Cast receiver can open for [item], or null when there is none
     * it can: a file on this phone, or a source that needs headers the
     * receiver cannot send. Runs the same resolver the player's own data source
     * does, so the receiver is handed the URL the phone would have played.
     */
    private suspend fun resolveForCast(item: MediaItem): CastStream? = withContext(Dispatchers.IO) {
        val uri = item.localConfiguration?.uri ?: return@withContext null
        val resolver = streamResolver ?: return@withContext null
        // Ungated: the receiver plays items this player is not on.
        val resolved = DirectSourceGate.ungated { resolver.resolveDataSpec(DataSpec(uri)) }.uri
        if (resolved.scheme == "http" || resolved.scheme == "https") {
            CastStream(resolved.toString(), CastPlayback.mimeTypeOf(resolved))
        } else {
            // The item's URI may be a local/offline path for a downloaded track.
            // Fall back to resolving the remote stream URL using the same path
            // non-downloaded tracks use, so the receiver gets an HTTP URL it can
            // reach — see #1085. Only allow this when the videoId is a known
            // downloaded YouTube id (key in Downloads.saved); reject external
            // local files whose videoId is a content:// URI that cannot resolve.
            val fallback = if (uri.scheme == "content" || uri.scheme == "file") {
                item.mediaId.takeIf { it.isNotBlank() }?.let { vid ->
                    if (Downloads.saved.value.containsKey(vid)) "bitchord://watch?v=$vid" else null
                }
            } else null
            if (fallback != null) {
                runCatching {
                    DirectSourceGate.ungated { resolver.resolveDataSpec(DataSpec(fallback.toUri())) }.uri
                        .takeIf { it.scheme == "http" || it.scheme == "https" }
                }.getOrNull()?.let { url ->
                    CastStream(url.toString(), CastPlayback.mimeTypeOf(url))
                }
            } else null
        }
    }

    private fun createCrossfadeController() = CrossfadeController(
            scope,
            active = { requireNotNull(player) },
            standby = { requireNotNull(spare) },
            onHandoff = ::adoptPlayer,
            analysisFor = { item -> trackAnalyzer.analysisFor(item.mediaId) },
            requestAnalysis = { item, durationMs ->
                item.localConfiguration?.uri?.let { uri ->
                    trackAnalyzer.request(item.mediaId, uri, durationMs / 1000.0)
                }
            },
            // "Incoming" and "outgoing" here are session roles, not players:
            // the session player's sink and the spare's. The controller maps
            // its two tracks onto them according to which side of the
            // mid-blend handoff it is on — so these read the role fields fresh
            // on every call rather than closing over an instance that will
            // have changed hands.
            filters = object : TransitionFilters {
                override fun incoming(lowPassHz: Float, highPassHz: Float) =
                    pipeline.activeFilter.setCutoffs(lowPassHz, highPassHz)

                override fun outgoing(lowPassHz: Float, highPassHz: Float) =
                    pipeline.spareFilter.setCutoffs(lowPassHz, highPassHz)

                override fun incomingLeadMs(): Long = pipeline.activeFilter.leadUs / 1000

                override fun outgoingLeadMs(): Long = pipeline.spareFilter.leadUs / 1000

                override fun incomingEcho(delaySeconds: Float, send: Float, dry: Float) =
                    pipeline.activeFilter.setEcho(delaySeconds, send, dry)

                override fun outgoingEcho(delaySeconds: Float, send: Float, dry: Float) =
                    pipeline.spareFilter.setEcho(delaySeconds, send, dry)

                override fun parkEchoes() {
                    pipeline.a.filter.parkEcho()
                    pipeline.b.filter.parkEcho()
                }
            },
            analysisRunningFor = { item -> trackAnalyzer.isAnalysing(item.mediaId) },
            // The standby is still the spare when this runs — it only becomes
            // the session player at the handoff — so the incoming track is
            // levelled on its own sink before it renders a frame, and the
            // outgoing track keeps its own gain for the rest of the blend.
            onArmIncoming = { item, nextId -> loudness.spare().track(item.mediaId, nextId) },
            // Both sinks, not a role: the trim is about what the pair sums to.
            onBlendHeadroom = { trim ->
                loudness.setBlendTrim(trim)
            },
            onSettled = {
                if (bounded.hasPendingEdits) {
                    scope.launch {
                        bounded.applyDeferredBoundedQueueEdit()
                    }
                }
            },
            versionSwapActive = { versionSync.active },
            // Direct Source Stream fetches the next track only in the last
            // seconds before the blend, so the marker waits for its audio:
            // a window shown over a track that may still fall back to YouTube
            // promises a blend that could start late or not at all.
            // The same for a next track still waiting to become its audio version.
            nextStreamReady = { item ->
                !MusicOnlyPlayback.wants(item) &&
                    (!DirectSourceGate.isActive() || DirectSourceGate.isStreamReady(item))
            },
            mixWaveform = { item, durationMs, from, to, bins ->
                item.localConfiguration?.uri?.let { uri ->
                    trackAnalyzer.mixWaveform(item.mediaId, uri, durationMs / 1000.0, from, to, bins)
                }
            },
            // Each sink's loop-mix port rides on its transition filter, so the
            // player and the port trade roles together at every handoff.
            loopPortOf = { target ->
                when {
                    target === player -> pipeline.activeFilter.loop
                    target === spare -> pipeline.spareFilter.loop
                    else -> null
                }
            },
            loopRegion = { item, durationMs, from, to, rate ->
                item.localConfiguration?.uri?.let { uri ->
                    trackAnalyzer.loopRegion(item.mediaId, uri, durationMs / 1000.0, from, to, rate)
                }
            },
            syncedLines = lyrics::loopMixLyrics,
            loudnessGain = loudness::gainFor,
            onMixPositionJump = { wrapped -> (mediaSession?.player as? SessionPlayer)?.announceMixPosition(wrapped) },
        )

    /**
     * Whether Like is offered for [item] — the player's own rule, so no surface
     * shows a heart the player doesn't: there has to be an account to record it
     * against, unless it is a local file or a download, which is liked into the
     * device's own Liked playlist instead (see [OfflinePlaylists]).
     */
    private fun canLike(item: MediaItem?): Boolean =
        item != null && (item.toSong().localUri != null || SignedIn.state(this).value)

    /** Whether [item] is liked, by whichever of the two records owns it. */
    private fun isLiked(item: MediaItem?): Boolean {
        val song = item?.toSong() ?: return false
        return if (song.localUri != null) {
            OfflinePlaylists.isLiked(song)
        } else {
            LikeState.overrides.value[song.videoId] == LikeStatus.LIKE
        }
    }

    /** Pushes the current button state to every surface — see [SessionButtons]. */
    private fun refreshCustomLayouts() = buttons.refresh()

    /**
     * Replaces everything around the current track with its YouTube Music
     * radio. The playing item itself is retained, so asking from Android Auto
     * does not restart the song or lose the current position.
     */
    private fun startStationFromSession() {
        val originalPlayer = player ?: return
        val currentItem = originalPlayer.currentMediaItem ?: return
        val seed = currentItem.toSong().takeIf { it.canStartStation() } ?: return
        val originalManualQueue = (0 until originalPlayer.mediaItemCount)
            .map { originalPlayer.getMediaItemAt(it) }
            .filterNot { it.fromAutoplay }
            .map { it.mediaId }

        stationActionJob?.cancel()
        stationActionJob = scope.launch {
            val radioSeed = seed.copy(radioName = seed.title)
            val related = loadAutoplayTracks(
                existing = listOf(radioSeed),
                seedSong = radioSeed,
                limit = INITIAL_STATION_TRACKS,
            ).getOrElse {
                TrackLog.w(
                    "BitChord",
                    "Android Auto station failed: ${it.message}",
                    about = seed.videoId,
                )
                return@launch
            }
            if (related.isEmpty()) return@launch

            val activePlayer = player ?: return@launch
            val activeManualQueue = (0 until activePlayer.mediaItemCount)
                .map { activePlayer.getMediaItemAt(it) }
                .filterNot { it.fromAutoplay }
                .map { it.mediaId }
            if (activePlayer.currentMediaItem?.mediaId != currentItem.mediaId ||
                activeManualQueue != originalManualQueue
            ) {
                return@launch
            }

            beginRadioQueue()
            val currentIndex = activePlayer.currentMediaItemIndex
            // Station first, old tail after, so the current track is never
            // briefly last and AutoPlay does not append this radio again.
            activePlayer.addMediaItems(currentIndex + 1, related.map { it.toMediaItem() })
            val oldTail = currentIndex + 1 + related.size
            if (oldTail < activePlayer.mediaItemCount) {
                activePlayer.removeMediaItems(oldTail, activePlayer.mediaItemCount)
            }
            if (currentIndex > 0) activePlayer.removeMediaItems(0, currentIndex)
            saveQueueSnapshotImmediately(activePlayer)
        }
    }

    /**
     * Credits [incoming]'s configured audio format to its own current track,
     * for a player taken over mid-play.
     *
     * [formatListener] reports a format once, when the renderer is configured,
     * and the incoming player configured its renderer during the blend, while
     * the listener was still on the outgoing one. So [audioFormatFor] went on
     * naming the outgoing track for the whole of the incoming one, and
     * [adoptCachedTrack] — which refuses any format not credited to the track
     * asking — never got past it: a track crossfaded into from the disk cache
     * never had its second look, and never showed "Upgrading quality".
     *
     * A renderer that has no format yet credits nothing; its callback is still
     * to come, and now there is a listener to hear it.
     */
    private fun adoptFormatOf(incoming: ExoPlayer) {
        if (incoming.audioFormat == null) return
        audioFormatFor = incoming.currentMediaItem?.mediaId
    }

    /**
     * Makes [incoming] the player the session, its listeners and the audio
     * effects belong to, and [outgoing] the spare — the part of taking over a
     * player that a crossfade ([adoptPlayer]) and a version swap
     * ([VersionAudioSynchronizer]) share.
     */
    private fun handOverDecks(outgoing: ExoPlayer, incoming: ExoPlayer) {
        setSessionOwner(outgoing, owns = false)
        setSessionOwner(incoming, owns = true)

        sessionListeners.detach()
        // The fields move before the listeners are attached, so anything the
        // first callback reads already describes the new arrangement.
        player = incoming
        spare = outgoing
        pipeline.swapFilterRoles()
        sessionListeners.attach(incoming)
        adoptFormatOf(incoming)

        mediaSession?.player = newSessionPlayer(incoming, requireNotNull(crossfade))
    }

    private fun toggleShuffleFromSession() {
        if (bounded.requestShuffleToggle()) return
        // A party's queue is the server's, not the album last played here.
        // A stale canonical order would let turning shuffle off splice that
        // album back in whenever the party plays one of its tracks.
        if (ListenTogether.state.value.inParty) QueueShuffle.clearCanonicalContext()
        player?.let(QueueShuffle::toggle)
        refreshCustomLayouts()
    }

    private fun toggleFavoriteFromNotification(videoId: String) {
        favoriteActionJob?.cancel()
        val previous = LikeState.overrides.value[videoId] ?: LikeStatus.INDIFFERENT
        val target = if (previous == LikeStatus.LIKE) {
            LikeStatus.INDIFFERENT
        } else {
            LikeStatus.LIKE
        }

        // Match the player UI: update both surfaces immediately, then reconcile
        // the optimistic state with YouTube in the background.
        LikeState.set(videoId, target)
        refreshCustomLayouts()
        favoriteActionJob = scope.launch {
            YtMusicRepository.rate(videoId, target)
                .onFailure {
                    LikeState.set(videoId, previous)
                    refreshCustomLayouts()
                    TrackLog.w("BitChord", "notification favorite failed: ${it.message}", about = videoId)
                }
        }
    }

    /**
     * Both players, built identically. Only [ownsSession] differs, and only at
     * construction — it moves at every handoff, see [setSessionOwner].
     *
     * They share the media source factory, so whichever one is arming reads from
     * the same on-disk cache the other is playing out of rather than
     * re-resolving a stream URL for audio that is already local.
     */
    private fun buildPlayer(
        deck: AudioRoutingAndDspPipeline.Deck,
        loudness: LoudnessProcessor,
        ownsSession: Boolean,
    ): ExoPlayer = ExoPlayer.Builder(this)
        .setRenderersFactory(pipeline.renderersFor(deck, loudness))
        .setMediaSourceFactory(requireNotNull(mediaSourceFactory))
        .setLoadControl(farBufferingLoadControl())
        .setAudioAttributes(AUDIO_ATTRIBUTES, /* handleAudioFocus = */ ownsSession)
        .setHandleAudioBecomingNoisy(ownsSession)
        // Keeps the CPU awake while this player is playing. Normal output gets
        // that from Android's audio server, but direct USB and AAudio exclusive
        // feed the DAC from this process, and without it a screen-off phone
        // suspends mid-track and the stream underruns.
        .setWakeMode(C.WAKE_MODE_LOCAL)
        // Back restarts the track once you're this far into it; only a
        // press before that steps to the previous one.
        .setMaxSeekToPreviousPositionMs(BACK_RESTARTS_AFTER_MS)
        .build()

    /**
     * Moves the session onto the player the crossfade has just started the
     * incoming track on. This is the whole of the handoff: no seek, no
     * re-buffer, and no audio rendered twice.
     *
     * Order matters in one place — focus is released on the outgoing player
     * *before* the incoming one asks for it, so the app never holds two focus
     * requests at once and never briefly holds none.
     */
    private fun adoptPlayer(outgoing: ExoPlayer, incoming: ExoPlayer) {
        handOverDecks(outgoing, incoming)

        // The queue moving on used to arrive here as an item transition on the
        // one player that owned the queue. It cannot any more — the incoming
        // track started as its own player's *first* item, which fires on a
        // player nothing was listening to yet — so the bookkeeping that hung off
        // that callback is driven explicitly instead. Without this the crossfade
        // would silently stop scrobbling, stop writing history, stop honouring
        // "sleep after this song" and stop reading ahead.
        onTrackBecameCurrent(
            incoming.currentMediaItem,
            previousEnded = true,
            reason = Player.MEDIA_ITEM_TRANSITION_REASON_AUTO,
            alreadyAudible = true,
        )

        // A crossfade starts the incoming item on the player that was idle, so
        // it never delivers onMediaItemTransition to playbackListener. Keep
        // AutoPlay's refill on the same track-change path as the ordinary
        // player transition: otherwise the initial suggestions are consumed
        // one by one and a long background session eventually runs dry.
        autoplay.reload()
    }

    /**
     * Only one player may handle audio focus at a time.
     *
     * Two focus-handling players in one process fight each other: the standby
     * taking focus as it starts would have Media3 pause the player that lost it,
     * cutting the outgoing track dead instead of fading it. Focus follows the
     * session, and so does "becoming noisy" — unplugging headphones should pause
     * the song you are listening to, which is whichever one the session is on.
     */
    private fun setSessionOwner(target: ExoPlayer, owns: Boolean) {
        target.setAudioAttributes(AUDIO_ATTRIBUTES, /* handleAudioFocus = */ owns)
        target.setHandleAudioBecomingNoisy(owns)
    }

    /**
     * Where a tap on the session lands. Media3 uses this both as the media
     * notification's contentIntent and as the session activity handed to the
     * platform MediaSession.
     *
     * This is not cosmetic on One UI: Samsung's Now Bar / Live Notification
     * chip is a launcher for the session, so a session that advertises nowhere
     * to go is skipped and only the plain shade notification survives. Same
     * reason the notification itself was previously un-tappable.
     */
    private fun sessionActivity(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            // MainActivity is singleTask, so this resumes the existing task
            // rather than stacking a second copy of the UI.
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /**
     * Everything that has to happen when a different song becomes the one
     * playing: history, scrobbles, ListenBrainz, the sleep timer, read-ahead
     * and the second look for a better copy.
     *
     * Called from two places, and it has to be, because there are now two ways
     * for the current song to change. ExoPlayer's own item transition covers
     * the ordinary ones — the queue advancing, a skip, a repeat. A crossfade
     * covers none of them: the incoming track starts life as the *first* item
     * of the other player, which fires a transition on a player nothing is
     * listening to yet, so [adoptPlayer] calls this by hand at the handoff.
     * Before that split existed this logic lived inside the callback, and
     * moving to two players would have silently stopped every crossfaded track
     * from being scrobbled, recorded, or read ahead for.
     *
     * @param previousEnded whether the song being replaced ran to its end, as
     *   opposed to being skipped past. Only an ended song is a listen.
     * @param alreadyAudible whether the track was already sounding when it
     *   became current, which is only true of a crossfade handoff.
     */
    private fun onTrackBecameCurrent(
        mediaItem: MediaItem?,
        previousEnded: Boolean,
        reason: Int,
        alreadyAudible: Boolean = false,
    ) {
        val exoPlayer = player ?: return
        if (mediaItem == null || exoPlayer.mediaItemCount <= 0) {
            currentAudioInputFormat = null
            NerdStats.onTrackTransition(null)
            nerdStats.onTrackTransition()
            AudioOutputStatus.onTrackTransition()
            return
        }
        bounded.syncBoundedContextCursor(exoPlayer, mediaItem)
        if (bounded.active && !ListenTogether.state.value.inParty) {
            bounded.saveBoundedContextSnapshot(exoPlayer)
        }
        currentAudioInputFormat = null
        NerdStats.onTrackTransition(mediaItem.mediaId)
        // A rendition discarded and fetched again comes back under the same
        // URI, so a size measured for the last track isn't kept past it.
        nerdStats.onTrackTransition()
        AudioOutputStatus.onTrackTransition()
        // The entry this track is read from now counts as played — the only
        // kind the Cached songs folder lists. See [AudioCache.Origin].
        mediaItem.let { AudioCache.notePlayed(it.toSong(), it.localConfiguration?.uri) }
        readAhead.scheduleForeignCachePurge()
        mediaItem.mediaId.let { id ->
            nerdStats.resolveLocalBitrate(id, mediaItem)
        }

        // A crossfade handoff never fires [formatListener] for the entering
        // track — [CrossfadeController] starts its decoder during ARMING,
        // while the listener is still on the outgoing player, and it isn't
        // reattached until this handoff, by which point the format has
        // already been reported once and won't be again. The claim from
        // source resolution stands in until the decoder itself confirms or
        // corrects it — see [formatListener]'s onAudioInputFormatChanged.
        activeTrackIsDolbyAtmos = NerdStats.declaredFormat(mediaItem.mediaId)?.isDolbyAtmos == true
        pipeline.applySpatialAudioEnabled()

        // A genuinely different track, so the gain is re-read for it. A
        // quality upgrade never reaches here — it returns early in
        // [Player.Listener.onMediaItemTransition] via [swappingMediaId] — which
        // is precisely what carries the applied gain across the swap.
        loudness.setup(mediaItem.mediaId)
        loudness.scheduleRetry(mediaItem.mediaId)

        // Keep a real, bounded history in the player rather than merely hiding
        // old rows in Compose. MediaController mirrors the playlist across the
        // session boundary, persistence snapshots it, and crossfade duplicates
        // it onto the standby player, so letting completed entries accumulate
        // made every one of those paths progressively more expensive.
        val expiredHistory = queueHistoryTrimCount(exoPlayer.currentMediaItemIndex)
        if (expiredHistory > 0) {
            if (bounded.active) {
                // In bounded mode the logical QueueWindow owns repeat/cycle
                // state. The player timeline only retains the recent history;
                // rotating expired rows would reintroduce them ahead of the
                // logical cursor and make a cycle look unconsumed.
                exoPlayer.removeMediaItems(0, expiredHistory)
            } else if (exoPlayer.repeatMode == Player.REPEAT_MODE_ALL) {
                // Repeat-all still means the whole queue. Rotate history that
                // has fallen out of the visible 25-song window to the end
                // instead of deleting it; it becomes upcoming again in the
                // same order and the loop can continue indefinitely.
                exoPlayer.rotateExpiredHistory(expiredHistory)
            } else {
                exoPlayer.removeMediaItems(0, expiredHistory)
            }
        }
        bounded.scheduleBoundedContextTopUp()
        // A *different* track is a clean slate for [recoverFrom], and so is the
        // same track becoming current for any reason other than that method's
        // own retry. The distinction is the whole of the reported loading loop.
        //
        // This was an unconditional clear, on the reasoning that the count exists
        // to stop one broken stream looping rather than to hold a grudge for the
        // session — and that reasoning is right about the listener pressing play
        // again, which is why it is kept below. What it missed is that "a track
        // became current" is not the same event as "something other than the
        // retry happened": ExoPlayer fires a transition for PLAYLIST_CHANGED and
        // for SEEK, and [recoverFrom]'s recovery *is* a seek — so the counter was
        // reset by the very retries it was counting. The report shows four resets
        // in 2m41s, each followed by a fresh "attempt 1", eight failures against
        // a budget of two, and roughly twenty-seven full resolve walks for one
        // unplayable track. [retryingMediaId] is the one case that must not
        // reset; everything else still does.
        val becameCurrent = mediaItem.mediaId
        if (becameCurrent != retryingMediaId) {
            recovery.recoveries.clear()
        }
        retryingMediaId = null

        // Where the wait starts, for the log in onIsPlayingChanged — unless
        // there was no wait. A crossfaded track has been audible for as long as
        // it has been current, so `onIsPlayingChanged` will never fire for it
        // and an armed timer would sit there until some unrelated buffering
        // blip tripped it, reporting a wait of seconds for a track that started
        // instantly. Measured one at 16871ms.
        trackSelectedAt = if (alreadyAudible) null else SystemClock.elapsedRealtime()
        if (alreadyAudible) {
            TrackLog.d(
                "BitChord",
                "TIMING first audio: 0ms, the crossfade covered it",
                about = mediaItem.mediaId,
            )
            // The other way a track becomes audible. `onIsPlayingChanged` never
            // fires across a crossfade handoff — the incoming track has been
            // sounding since before it was current — so without this a blended
            // advance would leave every track looking like it never started.
            audibleMediaId = mediaItem.mediaId
            consecutiveErrorSkips = 0
        }
        // And the same instant on the wall clock, which is the one
        // logcat stamps its lines with — see [TrackLog].
        mediaItem.mediaId.let(TrackLog::onTrackStarted)
        // A play beginning is what ends the last one's upgrade verdict. Here
        // rather than in [QualityUpgrade.forget] because that runs on the
        // failure and rollback paths, where a previous "no" is exactly what
        // should be kept.
        mediaItem.mediaId.let {
            QualityUpgrade.onPlaybackStarted(
                it,
                mediaItem.localConfiguration?.uri?.let(QualityUpgrade::cacheTag),
            )
        }
        TrackLog.d(
            "BitChord",
            "TIMING track selected: ${mediaItem.mediaId} (reason=$reason)",
            about = mediaItem.mediaId,
        )

        history.onTrackChanged(mediaItem, previousEnded, exoPlayer)

        // The renderer is still configured for the track that just ended at
        // this point. Clear its measurements before Discord takes its snapshot
        // too, otherwise a new lossy track briefly inherits the previous
        // track's Lossless/Atmos line and that stale network push can win the
        // race against the decoder's correction.
        NerdStats.current.value = null

        // Discord: the whole of "live updating" for a card whose bar Discord
        // draws itself. Only a track change needs a new presence; the countdown
        // in between is Discord's own arithmetic.
        if (exoPlayer.isPlaying) discord.push(exoPlayer)

        history.afterTrackChanged(previousEnded, exoPlayer)
        savePlaybackState(exoPlayer)
        readAhead.convertVideosAround(exoPlayer)
        readAhead.prefetchAround(exoPlayer)
        // The second look belongs to the track it was started for; the
        // queue moving on ends it — unless it is already proving what it
        // found, see [cancelStaleUpgradeJob] — and starts the new
        // track's own, which nothing else here would. The
        // track arriving has usually been resolved already, by
        // ExoPlayer preparing the next item while this one played, so
        // it is pending by now; the ones that aren't are picked up by
        // the sampler in [reportProgress].
        recovery.cancelStaleUpgradeJob()
        recovery.lookForBetterCopy(exoPlayer)
        // Covers crossfades too: a blended advance never reaches
        // onMediaItemTransition, and [adoptPlayer] calls this handler by hand.
        publishWidgetState()
        lyrics.load()
        sponsorBlock.onMediaItemTransition(exoPlayer.currentMediaItem?.mediaId)
        if (exoPlayer.isPlaying) {
            lyrics.startTicker()
            sponsorBlock.startTicker()
        }
    }

    /**
     * Stop the player, clear all media items, remove the ongoing notification, and reset session state.
     */
    private fun dismissPlaybackInternal() {
        SleepTimer.cancel()
        readAhead.cancelPrefetch()
        discord.clear()
        lyrics.stopTicker()
        sponsorBlock.stopTicker()
        ListeningRecorder.onStopped()
        bounded.forget()
        eachPlayer { p ->
            p.stop()
            p.clearMediaItems()
            p.shuffleModeEnabled = false
        }
        LastPlayed.clear()
        BoundedContextPersistence.clear()
        publishWidgetState(playing = false)
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    /** Serialize only the bounded window needed for a future cold-start resume. */
    private fun saveQueueSnapshot(player: ExoPlayer) {
        if (ListenTogether.state.value.inParty) return
        if (player.mediaItemCount == 0) {
            persistedQueueStart = 0
            LastPlayed.clear()
            BoundedContextPersistence.clear()
            return
        }
        val range = LastPlayed.window(player.mediaItemCount, player.currentMediaItemIndex)
        if (range.isEmpty()) return
        persistedQueueStart = range.first
        LastPlayed.saveQueue(
            songs = range.map { player.getMediaItemAt(it).toSong() },
            index = player.currentMediaItemIndex - range.first,
        )
        bounded.saveBoundedContextSnapshot(player)
        savePlaybackState(player)
    }

    /** Make the newly installed radio queue the durable cold-start boundary. */
    private fun saveQueueSnapshotImmediately(player: ExoPlayer) {
        if (ListenTogether.state.value.inParty) return
        if (player.mediaItemCount == 0) {
            persistedQueueStart = 0
            LastPlayed.clearImmediately()
            BoundedContextPersistence.clearImmediately()
            return
        }
        val range = LastPlayed.window(player.mediaItemCount, player.currentMediaItemIndex)
        if (range.isEmpty()) return
        persistedQueueStart = range.first
        // Only the window that is kept: converting the whole queue here, on
        // the main thread, was work thrown away for all but 76 of its tracks.
        LastPlayed.saveQueueImmediately(
            songs = range.map { player.getMediaItemAt(it).toSong() },
            index = player.currentMediaItemIndex - range.first,
            positionMs = player.currentPosition,
        )
        bounded.saveBoundedContextSnapshot(player, immediately = true)
    }

    /** Drop every service-side reference that could resurrect the former queue. */
    private fun beginRadioQueue() {
        crossfade?.onSkipRequested()
        bounded.clearBoundedContext()
        autoplay.resetForNewQueue()
        persistedQueueStart = 0
        LastPlayed.clearImmediately()
    }

    /** Persist index and position without touching or serializing queue contents. */
    private fun savePlaybackState(player: ExoPlayer) {
        if (player.mediaItemCount == 0) return
        if (bounded.active && !ListenTogether.state.value.inParty) {
            BoundedContextPersistence.savePlaybackState(
                currentEntryId = player.currentMediaItem?.queueEntryId,
                positionMs = player.currentPosition,
            )
        }
        LastPlayed.savePlaybackState(
            index = player.currentMediaItemIndex - persistedQueueStart,
            positionMs = player.currentPosition,
        )
    }

    /** Restore the bounded queue without preparing or resolving a stream. */
    private fun restoreLastQueue(player: ExoPlayer): Boolean {
        val last = LastPlayed.load() ?: return false
        persistedQueueStart = 0
        player.setMediaItems(last.songs.map { it.toMediaItem() }, last.index, last.positionMs)
        return true
    }

    /**
     * Restore the logical bounded context first, falling back to the unchanged
     * LastPlayed physical snapshot when the new record is absent or unsafe.
     */
    private fun restoreBoundedQueueOrLast(player: ExoPlayer): Boolean {
        val persisted = BoundedContextPersistence.load()
        if (persisted != null && bounded.restoreBoundedQueue(player, persisted)) return true
        if (persisted != null) BoundedContextPersistence.clearImmediately()
        return restoreLastQueue(player)
    }

    /**
     * Tell the home-screen widgets what is playing.
     *
     * Kept out of [saveQueue] even though every caller does both: that one also
     * runs from the per-second sampler in [reportProgress], and pushing a bitmap
     * to the launcher once a second would be a lot of work to redraw the same
     * picture.
     *
     * [playing] overrides what the player reports, for the one caller that knows
     * better than it does — teardown, where the player is still nominally set to
     * play right up to the moment it is released.
     */
    private fun publishWidgetState(playing: Boolean? = null) {
        val exoPlayer = player ?: return
        val song = exoPlayer.currentMediaItem?.toSong() ?: return
        // LikeState only knows ratings this process has seen: a fresh service
        // started from the widget or a restart has none until something seeds
        // it. Unknown is not "not liked", so keep what was last published for
        // this same track rather than emptying a heart that was right.
        val liked = (if (song.localUri != null) OfflinePlaylists.isLiked(song) else null)
            ?: LikeState.overrides.value[song.videoId]?.let { it == LikeStatus.LIKE }
            ?: MediaWidgetSnapshot.load(this).takeIf { it.mediaId == song.videoId }?.isLiked
            ?: false
        val reported: Player = mediaSession?.player ?: exoPlayer
        val snapshot = MediaWidgetSnapshot(
            mediaId = song.videoId,
            title = song.title,
            artist = song.artist,
            artworkUrl = song.thumbnailUrl,
            // playWhenReady, not isPlaying — see MediaWidgetSnapshot.isPlaying.
            // Through the session player, which answers for Cast or the watch
            // while one of them is the speaker.
            isPlaying = playing ?: reported.playWhenReady,
            hasPrevious = exoPlayer.hasPreviousMediaItem(),
            hasNext = exoPlayer.hasNextMediaItem(),
            isLiked = liked,
            canLike = canLike(exoPlayer.currentMediaItem),
            shuffleEnabled = QueueShuffle.enabled.value,
        )
        MediaWidgetSnapshot.save(this, snapshot)
        MediaWidget.refresh(this)
        // The watch app follows the widget — same moments, same snapshot.
        WearBridge.publish(this, snapshot, reported, playing)
    }

    /**
     * Feeds played-seconds to [PlaybackTracker]. The tracker can't read the
     * player itself — ExoPlayer is confined to this thread — and a history
     * entry with no watchtime behind it barely registers as a listen, so the
     * sampling has to come from here.
     */
    private fun reportProgress() {
        scope.launch {
            while (isActive) {
                // Re-read every tick rather than captured once: the session
                // moves between two players, and a sampler pinned to the one
                // that happened to be first would go on reporting a player that
                // has been silent since the last crossfade.
                val player = this@PlaybackService.player
                if (player != null && player.isPlaying) {
                    history.sample(player)
                    player.currentMediaItem?.mediaId?.let { id ->
                        nerdStats.onPlayTick(
                            mediaId = id,
                            positionMs = player.currentPosition,
                            elapsedRealtimeMs = SystemClock.elapsedRealtime(),
                        )
                    }
                    // Only two primitive preference values. Queue JSON is
                    // written from onTimelineChanged, never from this loop.
                    savePlaybackState(player)
                    // The renderer can settle on its format a moment after the
                    // track change, which no callback of ours follows up on.
                    nerdStats.publish()
                    // The backstop for the second look. The callbacks that
                    // start it fire at moments a track may not be resolved
                    // yet — the resolve happens on the loader thread when the
                    // source is opened, which for a track skipped to directly
                    // is after its own transition has been and gone. Cheap to
                    // repeat: it returns immediately unless the track is
                    // pending and nothing is already looking.
                    recovery.lookForBetterCopy(player)
                }
                delay(PROGRESS_SAMPLE_MS)
            }
        }
    }

    /**
     * Buffers as far ahead as a whole track rather than a rolling window.
     *
     * Media3's audio default stops loading at 13 buffer segments — around 830kB,
     * or 40 seconds of a 160kbps stream — and everything past that is fetched
     * only as playback consumes it. Since the data source writes through to
     * [AudioCache], how far ahead the player loads is also how much of the
     * track ends up on disk, and a seek past the buffered part is the one that
     * has to wait on the network.
     *
     * This matters for the track playback *starts* on. Everything after it is
     * on disk in full before it is reached, read ahead while it was still the
     * queued track — a first track has had no such chance.
     *
     * The byte ceiling is what governs; the duration is set past any song so
     * that it never becomes the binding constraint.
     *
     * Two further departures from the defaults, both about how long the
     * listener waits for sound:
     *
     *  - **Back buffer.** Media3 keeps nothing behind the playhead, so a seek
     *    *backwards* drops the buffer and reloads, while a seek forwards lands
     *    in samples already held. Half a minute of history closes that gap for
     *    the seek people actually make — nudging back a few seconds to catch a
     *    lyric — and it is deliberately no longer than that. The byte ceiling
     *    above counts *everything* the player holds, history included, so a
     *    back buffer wide enough to keep a whole track would spend the entire
     *    read-ahead budget on audio already heard: past the ceiling, loading
     *    stops, and since every second played moves a second from the front of
     *    the buffer to the back, the total never falls again and it never
     *    restarts. Read-ahead collapses and the track stalls every couple of
     *    seconds for the rest of its length. Seeking further back than this
     *    window is a disk read anyway, not a network one — [AudioCache] has
     *    written every byte already played.
     *  - **Thresholds to (re)start playback.** The defaults — 2.5s of audio
     *    before starting, 5s before resuming after a rebuffer — are sized for
     *    streaming video over a network that might stall again. Here the bytes
     *    are usually already on disk, so those seconds are spent waiting on a
     *    buffer that fills instantly and are simply dead air after a seek.
     *    Resuming is given more room than starting: a stall means the network
     *    is genuinely struggling, and coming back with a second of audio in
     *    hand only buys the next stall.
     */
    private fun farBufferingLoadControl() = DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
            /* maxBufferMs = */ FAR_BUFFER_MS,
            /* bufferForPlaybackMs = */ START_PLAYBACK_MS,
            /* bufferForPlaybackAfterRebufferMs = */ RESUME_PLAYBACK_MS,
        )
        .setTargetBufferBytes(FAR_BUFFER_BYTES)
        .setBackBuffer(/* backBufferDurationMs = */ BACK_BUFFER_MS, /* retainBackBufferFromKeyframe = */ true)
        .build()

    /**
     * Push current settings onto a player. Called for both: whichever one is
     * idle right now is the one the next transition will start a song on, so it
     * cannot be left on stale settings.
     */
    private fun applySettings(player: ExoPlayer) {
        player.skipSilenceEnabled = AppSettings.skipSilence.value
        player.setPlaybackSpeed(AppSettings.playbackSpeed.value)
        // Restore persisted shuffle and repeat states on startup. Assign both
        // values: Android can destroy and recreate the service while retaining
        // this process, so QueueShuffle's process-wide object may still hold
        // the previous service instance's value. Only assigning `true` made a
        // stale enabled state consume the first tap meant to enable Shuffle.
        QueueShuffle.setEnabled(AppSettings.shuffleEnabled.value)
        player.repeatMode = AppSettings.repeatMode.value
    }

    /**
     * Rebuilds Media3's immutable AudioSink configuration without restarting
     * the app or service. Queue, item, position and play state are transferred
     * to the replacement player; the old AudioTrack is released only after the
     * MediaSession points at the new one.
     */
    private fun rebuildPlayersForOutput(enableFloat: Boolean) {
        val oldActive = player ?: return
        val oldSpare = spare ?: return
        val items = List(oldActive.mediaItemCount) { oldActive.getMediaItemAt(it) }
        val index = oldActive.currentMediaItemIndex.takeIf { it != C.INDEX_UNSET } ?: 0
        val position = oldActive.currentPosition.coerceAtLeast(0L)
        val playWhenReady = oldActive.playWhenReady
        val repeatMode = oldActive.repeatMode
        val shuffleMode = oldActive.shuffleModeEnabled

        oldActive.playWhenReady = false
        crossfade?.release()
        sessionListeners.detach()

        configuredFloatOutput = enableFloat
        pipeline.resetFilterRoles()
        val newActive = buildPlayer(
            pipeline.a,
            loudness.a,
            ownsSession = true,
        )
        val newSpare = buildPlayer(
            pipeline.b,
            loudness.b,
            ownsSession = false,
        )
        player = newActive
        spare = newSpare
        newSpare.audioSessionId = newActive.audioSessionId
        AppSettings.audioSessionId.value = newActive.audioSessionId
        applySettings(newActive)
        applySettings(newSpare)
        newActive.repeatMode = repeatMode
        newActive.shuffleModeEnabled = shuffleMode
        pipeline.applyEqualizer()
        pipeline.applySpatialAudioEnabled()
        if (items.isNotEmpty()) {
            newActive.setMediaItems(items, index.coerceIn(items.indices), position)
            // By hand, because the listener is attached below this: the
            // transition `setMediaItems` fires lands before anything is
            // listening, so the ordinary setup path in [onTrackBecameCurrent]
            // never runs for the track the rebuild resumes on.
            loudness.setup(items.getOrNull(index.coerceIn(items.indices))?.mediaId)
        }
        sessionListeners.attach(newActive)

        val newCrossfade = createCrossfadeController()
        crossfade = newCrossfade
        newCrossfade.start()
        mediaSession?.player = newSessionPlayer(newActive, newCrossfade)
        pipeline.applyOutputRoute()
        if (items.isNotEmpty()) newActive.prepare()
        newActive.playWhenReady = playWhenReady

        oldActive.release()
        oldSpare.release()
        TrackLog.i(
            "AUDIO_OUT",
            "live output switch: ${if (enableFloat) "PCM_FLOAT" else "PCM_16BIT"}",
            about = newActive.currentMediaItem?.mediaId,
        )
    }

    /** Runs [body] against both players, in whichever roles they currently hold. */
    private inline fun eachPlayer(body: (ExoPlayer) -> Unit) {
        player?.let(body)
        spare?.let(body)
    }

    private fun observeSettings() {
        scope.launch {
            AppSettings.skipSilence.collect { on -> eachPlayer { it.skipSilenceEnabled = on } }
        }
        scope.launch {
            // The queue as it stands is re-judged at once, rather than at the
            // next track, and read-ahead may have been warming a video.
            AppSettings.preferMusicOnly.drop(1).collect {
                MusicOnlyPlayback.reset()
                readAhead.cancelPrefetch()
                player?.let { live ->
                    readAhead.convertVideosAround(live)
                    if (live.isPlaying) readAhead.prefetchAround(live)
                }
            }
        }
        scope.launch {
            AppSettings.preferUsbDac.drop(1).collect { pipeline.requestOutputReconfiguration() }
        }
        scope.launch {
            AudioRouting.selectedId.drop(1).collect { pipeline.requestOutputReconfiguration() }
        }
        scope.launch {
            AppSettings.outputPcmMode.drop(1).collect { pipeline.requestOutputReconfiguration() }
        }
        // Direct USB needs Android's permission to the DAC, asked for when the
        // mode is chosen and again whenever a DAC is attached; an answer, an
        // attach or a detach all send the playing track back to be re-routed.
        UsbDirectDevices.init(this)
        BitPerfectOutput.watch(this)
        scope.launch {
            var available = false
            DacVolume.level.collect { level ->
                val now = level != null
                (mediaSession?.player as? SessionPlayer)?.onDacVolumeChanged(routeChanged = now != available)
                available = now
            }
        }
        UsbDirectDevices.onChanged = {
            BitPerfectOutput.refreshReady(this)
            scope.launch {
                if (AppSettings.bitPerfectMode.value == BitPerfectMode.USB_DIRECT) {
                    UsbDirectDevices.requestPermission(this@PlaybackService)
                    pipeline.requestOutputReconfiguration(rebuild = true)
                }
            }
        }
        scope.launch {
            // The sinks decide per format, so the playing track only moves on
            // or off the exclusive output when its sink is configured again.
            AppSettings.bitPerfectMode.collect { mode ->
                BitPerfectOutput.refreshReady(this@PlaybackService)
                if (mode == BitPerfectMode.USB_DIRECT) UsbDirectDevices.requestPermission(this@PlaybackService)
            }
        }
        scope.launch {
            AppSettings.bitPerfectMode.drop(1).collect { pipeline.requestOutputReconfiguration(rebuild = true) }
        }
        scope.launch {
            AppSettings.usbAllowFixedVolume.drop(1).collect {
                if (AppSettings.bitPerfectMode.value == BitPerfectMode.USB_DIRECT) {
                    pipeline.requestOutputReconfiguration(rebuild = true)
                }
            }
        }
        scope.launch {
            AppSettings.convertForLimitedDac.drop(1).collect {
                if (AppSettings.bitPerfectMode.value != BitPerfectMode.OFF) {
                    pipeline.requestOutputReconfiguration(rebuild = true)
                }
            }
        }
        scope.launch {
            // Not applied to a player mid-transition: [CrossfadeController]
            // stacks a beatmatch stretch on top of this setting, and writing the
            // raw value over it would drop the incoming track back to its own
            // tempo halfway through a blend. The controller re-reads the setting
            // when it restores the rate, so the change still lands.
            AppSettings.playbackSpeed.collect { speed ->
                if (crossfade?.isTransitioning() == true) return@collect
                crossfade?.applyPlaybackSpeed(speed) ?: eachPlayer { it.setPlaybackSpeed(speed) }
            }
        }
        scope.launch {
            combine(
                AppSettings.syncedLyrics,
                AppSettings.lyricsSources,
                AppSettings.lyricsSourceOrder,
                AppSettings.prioritizeSyllableSync,
            ) { synced, sources, order, prio ->
                synced to sources
            }.distinctUntilChanged().collect {
                lyrics.load()
            }
        }
        scope.launch {
            AppSettings.spatialAudio.collect { pipeline.applySpatialAudioEnabled() }
        }
        scope.launch {
            AppSettings.singLevel.collect { pipeline.applySingLevel() }
        }
        scope.launch {
            AppSettings.singMethod.collect { pipeline.applySingMethod() }
        }
        scope.launch {
            var wasOffline = AppSettings.effectiveOffline.value
            AppSettings.effectiveOffline.collect { offline ->
                if (wasOffline && !offline) restoreStreamingAfterOffline()
                if (!wasOffline && offline) applyOfflineFilesToQueue()
                wasOffline = offline
            }
        }
        scope.launch {
            AppSettings.loudnessNormalization.collect {
                loudness.setup(player?.currentMediaItem?.mediaId)
            }
        }
        scope.launch {
            // Same route the player's output caption reads, so "on speaker"
            // means the same thing here as there.
            combine(
                AppSettings.loudnessOffOnSpeaker,
                AudioOutputStatus.current.map { it.routeKind == AudioRouting.Kind.PHONE }.distinctUntilChanged(),
            ) { off, onSpeaker -> off && onSpeaker }
                .distinctUntilChanged()
                .drop(1)
                .collect { loudness.setup(player?.currentMediaItem?.mediaId) }
        }
        scope.launch {
            // Explicit <Any, _>: these flows have mixed element types, and
            // letting the reified vararg combine() infer T lands on an
            // intersection type. Nothing is read out of the array — the
            // equaliser reads the settings it wants directly — because seven
            // sources of one curve is seven chances to destructure them in the
            // wrong order.
            combine<Any, Unit>(
                AppSettings.equalizerEnabled,
                AppSettings.equalizerMode,
                AppSettings.equalizerToneX,
                AppSettings.equalizerToneY,
                AppSettings.equalizerFocused,
                AppSettings.equalizerBalance,
                AppSettings.equalizerBands,
            ) { }.collect { pipeline.applyEqualizer() }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? =
        mediaSession

    /**
     * Called by Android when the user swipes this app's task away from the
     * recent apps screen.
     *
     * When [AppSettings.stopOnTaskRemoved] is on we stop the player and let the
     * service die naturally; otherwise we leave it running in the background so
     * music continues past the swipe, which is the default Android behaviour for
     * a foreground-service-backed media session.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (AppSettings.stopOnTaskRemoved.value) {
            // A receiver left playing with nothing on the phone to drive it
            // would run out its few queued tracks and stop on its own anyway;
            // better to say so now than leave the TV on a casting screen.
            if (castPlayback.active) CastController.disconnect(resumeHere = false)
            // Both, or a swipe-away mid-crossfade leaves the outgoing track
            // playing on its own out of a service that is on its way out.
            eachPlayer { it.stop() }
            stopSelf()
        }
    }

    override fun onDestroy() {
        WearBridge.attachStreams(null)
        WearBridge.releaseSpeaker()
        // Let go of the receiver's player but leave its session alone: a
        // service that idles out while casting is not the listener choosing to
        // stop, and the next one picks the session up where it was.
        CastController.detach(castPlayback)
        castPlayback.release()
        sponsorBlock.release()
        pipeline.release()
        partySync?.stop()
        partySync = null
        player?.let {
            saveQueueSnapshot(it)
            savePlaybackState(it)
        }
        // And to leave the widgets showing a play button. Nothing else reports a
        // swipe-away, so a widget left on the home screen would sit there with a
        // pause glyph on a service that no longer exists.
        publishWidgetState(playing = false)
        lyrics.release()
        readAhead.cancelPrefetch()
        trackAnalyzer.release()
        loudness.cancel()
        history.release()
        discord.release()
        scope.cancel()
        QueueShuffle.isCrossfadeTransitioning = null
        crossfade?.release()
        crossfade = null
        mediaSession?.release()
        mediaSession = null
        sessionListeners.detach()
        player?.release()
        player = null
        // Released too, and not conditionally: mid-crossfade it is holding a
        // decoder and an open audio track of its own, and the service going away
        // is not a reason to leave either behind.
        spare?.release()
        spare = null
        AudioOutputStatus.reset()
        zeroVolumeObserver.unregister()
        NerdStats.forgetLastSession()
        nerdStats.forgetSession()
        super.onDestroy()
    }

    /**
     * Carries out one of the app's own session commands — the notification's
     * and Android Auto's buttons, the widget, the watch and the app itself all
     * send them. False for one this service doesn't know.
     */
    private fun performSessionCommand(session: MediaSession, action: String, args: Bundle): Boolean {
    when (action) {
            ACTION_START_BOUNDED_CONTEXT -> bounded.startBoundedContext(args)
            ACTION_CLEAR_BOUNDED_CONTEXT -> bounded.clearBoundedContext()
            ACTION_TOGGLE_AUTOPLAY -> autoplay.toggleFromNotification()
            ACTION_TOGGLE_SHUFFLE -> toggleShuffleFromSession()
            ACTION_START_STATION -> startStationFromSession()
            ACTION_REVERT_TO_ORIGINAL -> versionSync.revertCurrentToOriginal()
            ACTION_SWAP_TO_VERSION -> versionSync.swapCurrentToVersion(args)
            ACTION_BEGIN_RADIO_QUEUE -> beginRadioQueue()
            ACTION_COMMIT_RADIO_QUEUE -> player?.let(::saveQueueSnapshotImmediately)
            ACTION_UPGRADE_QUALITY -> recovery.upgradeQualityNow()
            ACTION_DISMISS_PLAYBACK -> dismissPlaybackInternal()
            ACTION_SWAP_VERSION -> {
                val bundle = args.getBundle(EXTRA_SWAP_MEDIA_ITEM)
                if (bundle != null) {
                    val targetSong = songFromBundle(bundle)
                    // The listener's own choice outranks the music-only setting.
                    if (targetSong.isVideo) {
                        MusicOnlyPlayback.keep(targetSong.videoId)
                    } else {
                        session.player.currentMediaItem?.mediaId?.let(MusicOnlyPlayback::letGo)
                    }
                    versionSync.smoothSwapCurrentTrackVersion(targetSong.toMediaItem())
                }
            }
            ACTION_QUEUE_DRAG -> {
                if (args.getBoolean(EXTRA_QUEUE_DRAG_ACTIVE, false)) {
                    partySync?.beginQueueDrag()
                } else {
                    partySync?.endQueueDrag()
                }
            }
            ACTION_QUEUE_REPLAY_HISTORY -> {
                val index = args.getInt(EXTRA_QUEUE_MOVE_INDEX, -1)
                val mediaId = args.getString(EXTRA_QUEUE_MOVE_MEDIA)
                replayFromHistory(session, index, mediaId)
            }
            // A widget or watch drawn before the track changed can still
            // send this for one the player offers no Like on.
            ACTION_TOGGLE_FAVORITE -> session.player.currentMediaItem
                ?.takeIf(::canLike)
                ?.let { item ->
                    val song = item.toSong()
                    if (song.localUri != null) {
                        // On the device and nowhere else: nothing to
                        // reconcile, and the button follows [OfflinePlaylists.liked].
                        OfflinePlaylists.toggleLike(song)
                    } else {
                        toggleFavoriteFromNotification(item.mediaId)
                    }
                }
            else -> return false
        }
        return true
    }

    /**
     * A queue-screen tap on a history row. Outside a party, the row is played
     * again at the playhead (see [QueueCoordinator.replayFromHistory]).
     * False means the row could not be found or the caller should fall back to seek.
     */
    private fun replayFromHistory(session: MediaSession, requestedIndex: Int, mediaId: String?): Boolean {
        if (ListenTogether.state.value.controlsLocked || ListenTogether.state.value.inParty) return false
        val p = session.player
        val current = p.currentMediaItemIndex
        if (requestedIndex !in 0 until current || current >= p.mediaItemCount) return false
        val item = p.getMediaItemAt(requestedIndex)
        if (mediaId != null && item.mediaId != mediaId) return false
        val success = QueueCoordinator.replayFromHistory(p, requestedIndex)
        if (success) {
            this.player?.let(::saveQueueSnapshotImmediately)
        }
        return success
    }

    private suspend fun restoreStreamingAfterOffline() {
        val player = player ?: return
        val count = player.mediaItemCount
        if (count == 0) return
        val items: List<MediaItem> = (0 until count).map { player.getMediaItemAt(it) }
        val from = player.currentMediaItemIndex + 1
        if (from >= items.size) return
        val rebuilt = (from until items.size)
            .mapNotNull { items[it].toSong().takeIf { song -> song.localUri != null } }
            .map { it.toMediaItem() }
        if (rebuilt.isEmpty()) return
        TrackLog.d(
            "BitChord",
            "back online; ${rebuilt.size} queued track(s) return to streaming",
            about = player.currentMediaItem?.mediaId,
        )
        player.replaceMediaItems(from, items.size, rebuilt)
    }

    private fun applyOfflineFilesToQueue() {
        val player = player ?: return
        val count = player.mediaItemCount
        if (count == 0) return
        val items: List<MediaItem> = (0 until count).map { player.getMediaItemAt(it) }
        val from = player.currentMediaItemIndex + 1
        if (from >= items.size) return
        val rebuilt = (from until items.size)
            .mapNotNull { items[it].toSong().takeIf { song -> song.localUri != null } }
            .map { it.toMediaItem() }
        if (rebuilt.isEmpty()) return
        TrackLog.d(
            "BitChord",
            "offline; ${rebuilt.size} queued track(s) with files switch to them",
            about = player.currentMediaItem?.mediaId,
        )
        player.replaceMediaItems(from, items.size, rebuilt)
    }

    private companion object {
        /**
         * Shared by both players. Identical on purpose: they take turns being
         * the session, and a difference here would be an audible change of
         * routing at the handoff.
         */
        val AUDIO_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        const val CHANNEL_ID = "plonker_playback"
        const val SESSION_ID = "PlonkerPlayback"

        /** How often played-seconds are sampled off the player. */
        const val PROGRESS_SAMPLE_MS = 5_000L

        /** Past any song, so the byte ceiling is what stops loading. */
        const val FAR_BUFFER_MS = 15 * 60 * 1000

        /** ~6 minutes at 160kbps: a whole track, for all but the longest. */
        const val FAR_BUFFER_BYTES = 8 * 1024 * 1024

        /**
         * A short nudge backwards, and no more: this shares the byte ceiling
         * above with the read-ahead it would otherwise starve.
         */
        const val BACK_BUFFER_MS = 30 * 1000

        /** More room after a stall than at the start — see the load control. */
        const val RESUME_PLAYBACK_MS = 2_000

    }
}

/**
 * The stock renderers, plus FFmpeg after the MediaCodec one.
 *
 * FFmpeg comes last so it only gets a track no platform decoder takes: AC-4
 * and Dolby Digital (Plus, Atmos) on phones without Dolby's own decoder. Atmos
 * then plays as its 5.1 bed, folded down to stereo — the object layer needs
 * Dolby's decoder, which is the only thing that can read it. Same sink as the
 * MediaCodec renderer, so the DSP chain and the precision path see no
 * difference between the two.
 *
 * ALAC goes to FFmpeg on every device, because MediaCodec is never offered it
 * ([withoutPlatformAlac]). Samsung ships no ALAC codec, which is where FFmpeg
 * was proved; Qualcomm vendor images ship `c2.qti.alac.sw.decoder`, which
 * released itself on its first input buffer (realme RMX3842, Android 16) and
 * misreports position on the tracks it does play. A codec that fails mid-
 * playback is never retried on the next renderer — the track just falls to
 * YouTube — so the only fix is not to start there. Nothing is given up: the
 * vendor codec is software as well, and lossless decoders agree to the bit.
 *
 * Shared by the real players and the upgrade audition, which must be able to
 * decode whatever it is auditioning or it can never prove it — and with the
 * same decoder, or the proof is of something else.
 */
/** Enough to cover the decoder's own latency, not seconds of dead air. */
internal const val START_PLAYBACK_MS = 500

internal open class FfmpegFallbackRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>,
    ) {
        super.buildAudioRenderers(
            context,
            extensionRendererMode,
            withoutPlatformAlac(mediaCodecSelector),
            enableDecoderFallback,
            audioSink,
            eventHandler,
            eventListener,
            out,
        )
        out.add(FfmpegAudioRenderer(eventHandler, eventListener, audioSink))
    }
}


/**
 * [selector] with no decoders for ALAC, so `MediaCodecAudioRenderer` reports
 * it unsupported and the track selector hands it to FFmpeg — unless this
 * build's FFmpeg cannot decode it (an ABI it was not built for), where a
 * vendor codec is still better than none. Wraps rather than replaces, so a
 * subclass's own selector (the FLAC one) keeps working underneath.
 */
private fun withoutPlatformAlac(selector: MediaCodecSelector) =
    MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
        if (mimeType == MimeTypes.AUDIO_ALAC && ffmpegDecodesAlac) {
            emptyList()
        } else {
            selector.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
        }
    }

/**
 * Asked once per process, and only when an ALAC track is first looked at: the
 * native library then loads on the playback thread, as it did before, rather
 * than on the main thread while a player is built.
 */
private val ffmpegDecodesAlac by lazy { FfmpegLibrary.supportsFormat(MimeTypes.AUDIO_ALAC) }

private class SponsorBlockCoordinator(
    private val context: Context,
    private val scope: CoroutineScope,
    private val player: () -> ExoPlayer?,
) {
    private var job: Job? = null
    private var fetch: Job? = null
    private var currentVideoId: String? = null
    private var segments: List<SponsorSegment> = emptyList()
    private val skippedUuids = mutableSetOf<String>()

    fun onMediaItemTransition(mediaId: String?) {
        if (mediaId == currentVideoId && currentVideoId != null) return
        currentVideoId = mediaId
        skippedUuids.clear()
        segments = emptyList()
        job?.cancel()
        job = null
        fetch?.cancel()
        fetch = null

        // Local files and source tracks carry a path or key as their media
        // id; only a YouTube id is ever sent out.
        if (mediaId == null || !SponsorBlockApi.isYouTubeVideoId(mediaId)) return

        // On Main, like every other touch of this class's state: the answer
        // for a track that has since been replaced is dropped by the cancel
        // above rather than by a check another thread could lose a race with.
        fetch = scope.launch(Dispatchers.Main) {
            val enabled = AppSettings.sponsorBlockEnabled.value
            if (!enabled) return@launch

            val categories = mutableListOf<String>()
            if (AppSettings.sponsorBlockSkipNonMusic.value) categories.add("music_offtopic")
            if (AppSettings.sponsorBlockSkipSponsors.value) categories.add("sponsor")
            if (AppSettings.sponsorBlockSkipSelfPromo.value) categories.add("selfpromo")
            if (AppSettings.sponsorBlockSkipIntroOutro.value) {
                categories.add("intro")
                categories.add("outro")
            }
            if (categories.isEmpty()) return@launch

            val result = SponsorBlockApi.getSkipSegments(mediaId, categories)
            val list = result.getOrDefault(emptyList())
            if (list.isNotEmpty() && currentVideoId == mediaId) {
                segments = list
                if (player()?.isPlaying == true) startTicker()
            }
        }
    }

    fun startTicker() {
        if (job != null || segments.isEmpty()) return
        job = scope.launch(Dispatchers.Main) {
            while (isActive) {
                val p = player()
                if (p != null && p.isPlaying) {
                    val currentPos = p.currentPosition
                    val segment = segments.find { seg ->
                        currentPos >= seg.startMs && currentPos < (seg.endMs - 300) && !skippedUuids.contains(seg.uuid)
                    }
                    if (segment != null) {
                        skippedUuids.add(segment.uuid)
                        p.seekTo(segment.endMs)
                        TrackLog.i("BitChord", "SponsorBlock auto-skipped ${segment.category} segment: ${segment.startMs}ms -> ${segment.endMs}ms", about = currentVideoId)
                        if (AppSettings.sponsorBlockShowToast.value) {
                            val msg = when (segment.category) {
                                "music_offtopic" -> context.getString(R.string.sponsorblock_skipped_non_music)
                                "sponsor" -> context.getString(R.string.sponsorblock_skipped_sponsor)
                                "selfpromo" -> context.getString(R.string.sponsorblock_skipped_selfpromo)
                                "intro" -> context.getString(R.string.sponsorblock_skipped_intro)
                                "outro" -> context.getString(R.string.sponsorblock_skipped_outro)
                                else -> context.getString(R.string.sponsorblock_skipped_segment)
                            }
                            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                delay(250)
            }
        }
    }

    fun stopTicker() {
        job?.cancel()
        job = null
    }

    fun release() {
        stopTicker()
        fetch?.cancel()
        fetch = null
        segments = emptyList()
        skippedUuids.clear()
        currentVideoId = null
    }
}
