package com.music.bitchord.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.widget.Toast
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import com.music.bitchord.data.TelemetryProvenance
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
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
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
import androidx.media3.datasource.DataSpec
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.guava.future
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
import com.music.bitchord.data.lyrics.EmbeddedLyrics
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricsRepository
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.download.Downloads
import java.util.concurrent.ConcurrentHashMap
import com.music.bitchord.data.Http
import com.music.bitchord.auth.SignedIn
import com.music.bitchord.data.LikeState
import com.music.bitchord.data.NerdStats
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.discord.DiscordRPC
import com.music.bitchord.data.discord.discordAudioQualityLine
import com.music.bitchord.data.stats.ListeningRecorder
import com.music.bitchord.data.innertube.StreamResolver
import com.music.bitchord.data.model.LikeStatus
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.AudioQuality
import com.music.bitchord.data.settings.BitPerfectMode
import com.music.bitchord.data.settings.permits
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceResolver
import com.music.bitchord.data.sources.SourceStream
import com.music.bitchord.data.sources.StreamFormat
import com.music.bitchord.data.sources.TrackMatcher
import com.music.bitchord.playback.smart.AutomixAnalysisSource
import com.music.bitchord.widget.MediaWidget
import com.music.bitchord.widget.MediaWidgetSnapshot
import com.music.bitchord.wear.WearBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlinx.coroutines.TimeoutCancellationException
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
    private val history = PlaybackHistoryAndSleepTimer(scope = scope, player = { player })

    private val autoplay: AutoplaySupply = AutoplaySupply(
        scope = scope,
        player = { player },
        bounded = bounded,
        refreshButtons = { refreshCustomLayouts() },
    )

    private val localBitrateCache = ConcurrentHashMap<String, Int>()

    /** [measuredStreamKbps]'s answers, keyed by rendition URI, and the ones still being read. */
    private val measuredBitrateCache = ConcurrentHashMap<String, Int>()
    private val measuringBitrate: MutableSet<String> = ConcurrentHashMap.newKeySet()

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
    /** The upcoming ids read-ahead was last asked to warm, so an unchanged queue isn't asked again. */
    private var preferredPrefetchRequest: List<String>? = null

    /** Index in the live queue represented by entry zero of the persisted window. */
    private var persistedQueueStart = 0


    /**
     * The last repeat mode seen, so [onRepeatModeChanged] can tell which
     * direction the change went in: the callback reports where the player has
     * arrived, and leaving repeat-all is the half that has to restore.
     */
    private var lastRepeatMode = Player.REPEAT_MODE_OFF


    private var serviceLyricsJob: Job? = null
    private var serviceLyrics: List<LyricLine>? = null
    private var lastPublishedSubtitle: String? = null
    private var lyricsTickerJob: Job? = null

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
            if (isPlaying) prefetchAround(exoPlayer) else cancelPrefetch()
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
                pushDiscordPresence(exoPlayer)
                startLyricsTicker()
            } else {
                clearDiscordPresence()
                stopLyricsTicker()
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
                lastSeekAt = SystemClock.elapsedRealtime()
                if (exoPlayer.isPlaying) pushDiscordPresence(exoPlayer)
                updateLyricSubtitle()
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
            loadLyricsForCurrentTrack()
            if (exoPlayer.isPlaying) startLyricsTicker()
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
            convertVideosAround(exoPlayer)
            if (exoPlayer.isPlaying) prefetchAround(exoPlayer)
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
            publishNerdStats()
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
        localBitrateCache.clear()
        measuredBitrateCache.clear()
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
                    if (lost.isNotEmpty()) onCeilingLowered(ceiling, lost)
                }
        }

        val streamResolver = ResolvingDataSource.Resolver { dataSpec ->
            // Which track everything below is for, said once, because none of
            // it would otherwise know: this runs on ExoPlayer's loader thread
            // with a DataSpec and nothing else, and the work it starts — the
            // source ladder, the module sandbox, a client walk — logs from
            // places several layers deep that have no idea whose bytes they
            // are fetching. Read-ahead means the track being resolved here is
            // usually *not* the one playing, which is exactly why the lines
            // have to say. See [TrackLog.about].
            val about = TrackLog.about(mediaIdIn(dataSpec.uri))
            // A source-backed track is resolved by whichever source can serve
            // it, which is not necessarily the one it was queued from — see
            // [SourceResolver.resolve]. Handled ahead of the YouTube path
            // because these carry no `v` parameter and would otherwise fall
            // straight through unresolved.
            if (dataSpec.uri.authority == "source") {
                // Direct Source Stream: an addon row is held until it is about
                // to play, the same as a YouTube one — see [DirectSourceGate].
                mediaIdIn(dataSpec.uri)?.let { id -> runBlocking(about) { DirectSourceGate.awaitOpen(id) } }
                val directYouTube =
                    dataSpec.uri.getQueryParameter(DIRECT_YOUTUBE_PARAMETER) == "1"
                val stream = runBlocking(about) {
                    withTimeout(RESOLVE_TIMEOUT_MS) {
                        if (directYouTube) {
                            SourceResolver.youtubeFallback(SourceResolver.targetIn(dataSpec.uri))
                        } else {
                            SourceResolver.resolve(dataSpec.uri)
                        }
                    }
                } ?: throw java.io.IOException(
                    if (directYouTube) {
                        "No YouTube audio fallback matched ${dataSpec.uri.getQueryParameter("n")}"
                    } else {
                        "No enabled source could serve ${dataSpec.uri.getQueryParameter("n")}"
                    },
                )
                val configId = dataSpec.uri.getQueryParameter("s")
                val sourceName = if (directYouTube) {
                    "YouTube"
                } else {
                    stream.sourceConfigId?.let { SourceRegistry.config(it)?.displayName }
                        ?: configId?.let { SourceRegistry.config(it)?.displayName }
                        ?: "Source"
                }
                val trackParam = dataSpec.uri.getQueryParameter("t")
                val fullMediaId = mediaIdIn(dataSpec.uri)
                NerdStats.onSourceStream(fullMediaId ?: trackParam, stream.format, sourceName)
                if (fullMediaId != null) {
                    NerdStats.recordSource(fullMediaId, sourceName)
                }
                NerdStats.recordSource(trackParam, sourceName)
                return@Resolver dataSpec.buildUpon()
                    .setUri(Uri.parse(stream.url))
                    .setHttpRequestHeaders(
                        if (directYouTube) StreamResolver.mediaHeadersFor(stream.url)
                        else stream.headers,
                    )
                    .build()
            }
            val videoId = dataSpec.uri.getQueryParameter("v")
                ?: return@Resolver dataSpec
            // Direct Source Stream holds every playback open of a track that
            // is neither playing nor next inside the window: read-ahead and
            // ExoPlayer's own early preparation of the next item alike. Not
            // Automix's Opus reads, which measure the next track ahead of time
            // on purpose and never touch an addon.
            val analysisRead = AutomixAnalysisSource.requestsYouTubeOpus(
                dataSpec.uri.getQueryParameter(AutomixAnalysisSource.OPUS_QUERY_PARAMETER),
            )
            if (!analysisRead) runBlocking(about) { DirectSourceGate.awaitOpen(videoId) }
            // An explicit rollback is not a preference for a different
            // candidate: it means this exact YouTube rendition, immediately.
            // Answer it before StreamChoice, the module race and a pending
            // upgrade can put another source back under the listener.
            if (dataSpec.uri.getQueryParameter(DIRECT_YOUTUBE_PARAMETER) == "1") {
                QualityUpgrade.forget(videoId)
                StreamChoice.forget(videoId)
                NerdStats.clearDeclared(videoId)
                NerdStats.recordSource(videoId, "YouTube")
                val streamUrl = try {
                    runBlocking(about) {
                        withTimeout(RESOLVE_TIMEOUT_MS) { StreamResolver.resolve(videoId) }
                    }
                } catch (e: TimeoutCancellationException) {
                    throw java.io.IOException("Direct YouTube resolution timed out for $videoId", e)
                }
                val headers = StreamResolver.mediaHeadersFor(streamUrl)
                TrackLog.d("BitChord", "serving original YouTube version for $videoId", about = videoId)
                return@Resolver dataSpec.buildUpon()
                    .setUri(Uri.parse(streamUrl))
                    .setHttpRequestHeaders(headers)
                    .build()
            }
            // Automix owns a base-cache Opus rendition. It must bypass the
            // playback race winner (JioSaavn, a module, or a lossless upgrade)
            // and resolve directly to YouTube for this analysis-only request.
            if (analysisRead) {
                NerdStats.recordSource(videoId, "YouTube")
                val streamUrl = try {
                    runBlocking(about) {
                        withTimeout(RESOLVE_TIMEOUT_MS) { StreamResolver.resolve(videoId) }
                    }
                } catch (e: TimeoutCancellationException) {
                    throw java.io.IOException("Automix Opus resolution timed out for $videoId", e)
                }
                val headers = StreamResolver.mediaHeadersFor(streamUrl)
                return@Resolver dataSpec.buildUpon()
                    .setUri(Uri.parse(streamUrl))
                    .setHttpRequestHeaders(headers)
                    .build()
            }
            // An upgraded item carries a marker and its stream has already
            // been found — see [QualityUpgrade]. Answered before anything
            // else, and without re-resolving: this exact URL is what the
            // player was told it was getting when it agreed to the swap.
            QualityUpgrade.forcedStream(dataSpec.uri)?.let { upgraded ->
                // An audition opens this same stream before a note of the one
                // playing has been touched — see [auditionUpgrade] — so what it
                // is about to be handed describes a swap that has not happened
                // and may never. Recording it here would light "Lossless" over
                // the lossy stream still coming out of the speaker. The real
                // open, moments later, records it.
                val proving = QualityUpgrade.isAuditioning(videoId)
                val sourceName = upgraded.sourceConfigId?.let { SourceRegistry.config(it)?.displayName }
                    ?: "Upgrade"
                if (!proving) {
                    NerdStats.onSourceStream(videoId, upgraded.format, sourceName)
                    NerdStats.recordSource(videoId, sourceName)
                }
                // Logged because the alternative — a swap that silently never
                // reached its stream — is indistinguishable in the logs from
                // one that reached it and got nothing back, and the two have
                // opposite fixes.
                TrackLog.d(
                    "BitChord",
                    "${if (proving) "auditioning" else "serving"} upgraded $videoId " +
                        "from ${Uri.parse(upgraded.url).host} " +
                        "at ${dataSpec.position} (${upgraded.format.summary})",
                    about = videoId,
                )
                return@Resolver dataSpec.buildUpon()
                    .setUri(Uri.parse(upgraded.url))
                    .setHttpRequestHeaders(upgraded.headers)
                    .build()
            }
            // A downloaded copy is *not* substituted here, deliberately. This
            // point is inside the HTTP-only half of the chain — below
            // DefaultDataSource, which has already given up on dispatching by
            // scheme, and below the cache bypass that keeps local files from
            // being written to disk a second time. A content:// URI returned
            // from here reaches OkHttp, which refuses it as a malformed URL.
            // Which copy of a track to play is settled where the item is built
            // instead: see [Song.toMediaItem].
            // Whoever is already filling this track's cache entry keeps it.
            // Everything below decides between servers holding *different
            // files*, and this method is called again for every re-open of a
            // track — including the continuation fetch when playback runs off
            // the end of the cached bytes. Deciding afresh each time is how
            // the middle of an MP4 ended up appended to a WebM. See
            // [StreamChoice].
            StreamChoice.of(videoId)?.let { serving ->
                // What the stream claims to be, restated on every open rather
                // than only on the one that chose it.
                //
                // The race below is what used to report this, and it was enough
                // while the race was the only way a substitution could be made.
                // Read-ahead now pins one before the track is reached, so a
                // warmed track arrives *here* on its very first open and never
                // reaches the race at all — leaving the player with a 320kbps
                // stream and nothing on record saying so, and the quality badge
                // reading blank until the decoder got far enough to measure it
                // for itself.
                //
                // Only when the format states something. A plain YouTube choice
                // is remembered with an empty one, and writing that over a
                // claim some other path made would be worse than saying nothing.
                val sourceName = serving.sourceConfigId?.let { SourceRegistry.config(it)?.displayName }
                    ?: if (StreamChoice.isSubstitute(videoId)) "Module" else "YouTube"
                if (serving.format != StreamFormat()) {
                    NerdStats.onSourceStream(videoId, serving.format, sourceName)
                }
                NerdStats.recordSource(videoId, sourceName)
                // Read-ahead can pin JioSaavn's quick 320kbps answer before
                // this track becomes current.  It is the right answer for an
                // immediate start, but it is not the final quality verdict:
                // returning here used to bypass [resolveWithModulePriority],
                // the only path which calls [QualityUpgrade.settledForLess].
                // Consequently a warmed track displayed its high-quality
                // source but never kept the upgrading state or asked the
                // module for its lossless copy.
                //
                // Only substituted, non-lossless choices need this. A pinned
                // YouTube URL has no source result to promote, and a lossless
                // module result already satisfies the request.
                // Direct Source Stream plays the addon's answer as final: no
                // second look, no mid-song swap.
                if (!DirectSourceGate.isActive() &&
                    StreamChoice.isSubstitute(videoId) &&
                    serving.format.isLossless != true &&
                    QualityUpgrade.couldStillUpgrade(videoId, dataSpec.uri)
                ) {
                    val pending = QualityUpgrade.settledForLess(
                        mediaId = videoId,
                        target = SourceResolver.targetIn(dataSpec.uri),
                        playing = serving.format,
                        servedBy = serving.sourceConfigId,
                    )
                    if (!pending) NerdStats.onLosslessRaceEnd(videoId)
                }
                return@Resolver dataSpec.buildUpon()
                    .setUri(Uri.parse(serving.url))
                    .setHttpRequestHeaders(serving.headers)
                    .build()
            }
            // A track queued from YouTube may be held by a source the user
            // ranked above it — see [SourceResolver.substituteForYouTube] and
            // [raceYouTubeOrModule]. Only worth the extra lookup when
            // something actually outranks YouTube; otherwise this is the
            // plain resolve every build before this one made.
            if (!SourceResolver.canSubstituteForYouTube()) {
                val streamUrl = try {
                    runBlocking(about) {
                        withTimeout(RESOLVE_TIMEOUT_MS) { StreamResolver.resolve(videoId) }
                    }
                } catch (e: TimeoutCancellationException) {
                    throw java.io.IOException("Stream resolution timed out for $videoId", e)
                }
                // googlevideo names the client that minted the URL inside the
                // URL itself, and compares it against the request that comes
                // back for the bytes. A mismatch is answered with a throttled
                // trickle or a 403 rather than an error worth the name, so the
                // fetch is dressed as whatever the URL says it should be.
                val headers = StreamResolver.mediaHeadersFor(streamUrl)
                // Recorded even though only one server can answer here: a
                // source enabled from Settings mid-track flips the branch
                // above under a half-filled cache entry, and the entry would
                // then be finished by a different file.
                NerdStats.recordSource(videoId, "YouTube")
                StreamChoice.remember(videoId, SourceStream(streamUrl, headers = headers), substituted = false)
                return@Resolver dataSpec.buildUpon()
                    .setUri(Uri.parse(streamUrl))
                    .setHttpRequestHeaders(headers)
                    .build()
            }
            // The entry already holds YouTube's own bytes, from a play nothing in
            // this process remembers — a restart, or a [StreamChoice] that has
            // expired. This open is where those bytes run out, so it is
            // YouTube's to finish: the race below can hand the rest of the file
            // to a module, streamed into the middle of a WebM. See
            // [AudioCache.heldByYouTube].
            //
            // The better copy still gets its hearing, the way every other
            // YouTube start does: marked for the second look, which swaps it in
            // under the music. Direct Source Stream has settled this before the
            // cache was read — see [AudioCache.playbackFactory].
            if (AudioCache.heldByYouTube(dataSpec.key ?: AudioCache.cacheKeyOf(dataSpec.uri))) {
                val streamUrl = try {
                    runBlocking(about) {
                        withTimeout(RESOLVE_TIMEOUT_MS) { StreamResolver.resolve(videoId) }
                    }
                } catch (e: TimeoutCancellationException) {
                    throw java.io.IOException("Stream resolution timed out for $videoId", e)
                }
                val headers = StreamResolver.mediaHeadersFor(streamUrl)
                NerdStats.recordSource(videoId, "YouTube")
                StreamChoice.remember(videoId, SourceStream(streamUrl, headers = headers), substituted = false)
                if (QualityUpgrade.couldStillUpgrade(videoId, dataSpec.uri)) {
                    val pending = QualityUpgrade.settledForLess(
                        mediaId = videoId,
                        target = SourceResolver.targetIn(dataSpec.uri),
                        playing = NerdStats.pickedBitrateKbps(videoId)?.let { StreamFormat(kbps = it) },
                    )
                    if (!pending) NerdStats.onLosslessRaceEnd(videoId)
                }
                return@Resolver dataSpec.buildUpon()
                    .setUri(Uri.parse(streamUrl))
                    .setHttpRequestHeaders(headers)
                    .build()
            }
            val won = runBlocking(about) {
                recovery.resolveWithModulePriority(
                    videoId = videoId,
                    target = SourceResolver.targetIn(dataSpec.uri),
                )
            }
            when (won) {
                is StreamRecoveryCoordinator.Resolved.Module -> {
                    val sourceName = won.stream.sourceConfigId?.let { SourceRegistry.config(it)?.displayName }
                        ?: "Module"
                    NerdStats.onSourceStream(videoId, won.stream.format, sourceName)
                    NerdStats.recordSource(videoId, sourceName)
                    StreamChoice.remember(videoId, won.stream, substituted = true)
                    dataSpec.buildUpon()
                        .setUri(Uri.parse(won.stream.url))
                        .setHttpRequestHeaders(won.stream.headers)
                        .build()
                }
                // A module could have served this and didn't — it missed, its
                // server was slow, or the lookup ran out of budget. The last
                // of those is worth chasing rather than accepting: measured
                // here, a module's stream URL arrived 66ms after the live path
                // gave up on it, and the difference between a FLAC and a
                // YouTube Opus stream came down to that. The second look has
                // no such deadline, so what was nearly in hand is asked for
                // again while the fallback plays.
                is StreamRecoveryCoordinator.Resolved.YouTube -> {
                    NerdStats.recordSource(videoId, "YouTube")
                    val headers = StreamResolver.mediaHeadersFor(won.url)
                    StreamChoice.remember(videoId, SourceStream(won.url, headers = headers), substituted = false)
                    dataSpec.buildUpon()
                        .setUri(Uri.parse(won.url))
                        .setHttpRequestHeaders(headers)
                        .build()
                }
            }
        }

        this.streamResolver = streamResolver

        // No user agent on the factory: the right one depends on which client
        // minted the URL, so it is set per request below. Setting it here as
        // well would not override that — OkHttpDataSource *appends* the
        // factory's agent after the request's, and the fetch would go out
        // carrying two contradictory User-Agent headers.
        val resolvingFactory = ResolvingDataSource.Factory(
            // Innermost, so it chunks the real googlevideo URL the resolver
            // above has already substituted in — see [ChunkedDataSource] for
            // why an open-ended read of one is worth avoiding.
            ChunkedDataSource.Factory(OkHttpDataSource.Factory(Http.client), STREAM_CHUNK_BYTES),
        ) { dataSpec ->
            // Wrapped rather than folded into the resolver above so the record
            // is made in one place for every branch of it, and made from what
            // the resolver *returned* rather than from what each branch meant
            // to return. Nothing above this line knows what is really on the
            // end of a `bitchord://` URI and nothing below it knows which
            // track's bytes it is fetching; this is the seam where both are in
            // hand. See [StreamContainer].
            streamResolver.resolveDataSpec(dataSpec).also { resolved ->
                mediaIdIn(dataSpec.uri)?.let { StreamContainer.served(it, resolved.uri.toString()) }
                // The same seam tells the cache whose file it is writing, which
                // decides whether the entry outlives the track — see
                // [AudioCache.Origin].
                AudioCache.recordServed(dataSpec, resolved.uri.toString())
            }
        }
        // Read-ahead resolves streams through the same chain the player does.
        // smb:// tracks read straight off the share below the cache (so they
        // cache and seek like HTTP); everything else flows as before, and the
        // YouTube resolver never sees a scheme it cannot answer.
        val defaultDataSourceFactory = SmbDataSource.RoutingFactory(
            DefaultDataSource.Factory(this, resolvingFactory),
        )
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
        observeDiscord()
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
        watchDirectWindow()

        val controller = createCrossfadeController()
        crossfade = controller
        QueueShuffle.isCrossfadeTransitioning = { crossfade?.isTransitioning() == true }
        controller.start()

        mediaSession = MediaLibrarySession.Builder(
            this,
            newSessionPlayer(exoPlayer, controller),
            MediaLibraryCallback(),
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
        onUserIntent = { partySync?.onLocalIntent() },
        deferPlayToParty = { partySync?.shouldDeferPlay() == true },
        lockedTransport = { playing -> partySync?.onLockedTransport(playing) == true },
        cast = speakers,
    ) { lastPublishedSubtitle }

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
            syncedLines = ::loopMixLyrics,
            loudnessGain = loudness::gainFor,
            onMixPositionJump = { wrapped -> (mediaSession?.player as? SessionPlayer)?.announceMixPosition(wrapped) },
        )

    /**
     * A track's synced lyrics for the loop mix, which lets a loop be vouched
     * instrumental by when its lines are sung rather than by the vocal model.
     * Only with synced lyrics switched on, and never for longer than
     * [LOOP_MIX_LYRICS_TIMEOUT_MS]: the mask stands in for whatever doesn't come.
     */
    private suspend fun loopMixLyrics(item: MediaItem, durationMs: Long): List<LyricLine>? {
        if (!AppSettings.syncedLyrics.value) return null
        val song = item.toSong()
        return kotlinx.coroutines.withTimeoutOrNull(LOOP_MIX_LYRICS_TIMEOUT_MS) {
            song.localUri?.let { EmbeddedLyrics.forUri(this@PlaybackService, it) }
                ?: LyricsRepository.lyrics(
                    videoId = song.videoId,
                    title = song.title,
                    artist = song.artist,
                    durationMs = durationMs,
                    album = song.albumName,
                    sources = AppSettings.lyricsSources.value,
                    order = AppSettings.lyricsSourceOrder.value,
                )?.lines
        }
    }

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
            measuredBitrateCache.clear()
            AudioOutputStatus.onTrackTransition()
            return
        }
        bounded.syncBoundedContextCursor(exoPlayer, mediaItem)
        if (bounded.active && !ListenTogether.state.value.inParty) {
            bounded.saveBoundedContextSnapshot(exoPlayer)
        }
        currentAudioInputFormat = null
        NerdStats.onTrackTransition(mediaItem?.mediaId)
        // A rendition discarded and fetched again comes back under the same
        // URI, so a size measured for the last track isn't kept past it.
        measuredBitrateCache.clear()
        AudioOutputStatus.onTrackTransition()
        // The entry this track is read from now counts as played — the only
        // kind the Cached songs folder lists. See [AudioCache.Origin].
        mediaItem?.let { AudioCache.notePlayed(it.toSong(), it.localConfiguration?.uri) }
        scheduleForeignCachePurge()
        mediaItem?.mediaId?.let { id ->
            resolveLocalBitrate(id, mediaItem)
        }

        // A crossfade handoff never fires [formatListener] for the entering
        // track — [CrossfadeController] starts its decoder during ARMING,
        // while the listener is still on the outgoing player, and it isn't
        // reattached until this handoff, by which point the format has
        // already been reported once and won't be again. The claim from
        // source resolution stands in until the decoder itself confirms or
        // corrects it — see [formatListener]'s onAudioInputFormatChanged.
        activeTrackIsDolbyAtmos = NerdStats.declaredFormat(mediaItem?.mediaId)?.isDolbyAtmos == true
        pipeline.applySpatialAudioEnabled()

        // A genuinely different track, so the gain is re-read for it. A
        // quality upgrade never reaches here — it returns early in
        // [Player.Listener.onMediaItemTransition] via [swappingMediaId] — which
        // is precisely what carries the applied gain across the swap.
        loudness.setup(mediaItem?.mediaId)
        loudness.scheduleRetry(mediaItem?.mediaId)

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
        val becameCurrent = mediaItem?.mediaId
        if (becameCurrent == null || becameCurrent != retryingMediaId) {
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
                about = mediaItem?.mediaId,
            )
            // The other way a track becomes audible. `onIsPlayingChanged` never
            // fires across a crossfade handoff — the incoming track has been
            // sounding since before it was current — so without this a blended
            // advance would leave every track looking like it never started.
            audibleMediaId = mediaItem?.mediaId
            consecutiveErrorSkips = 0
        }
        // And the same instant on the wall clock, which is the one
        // logcat stamps its lines with — see [TrackLog].
        mediaItem?.mediaId?.let(TrackLog::onTrackStarted)
        // A play beginning is what ends the last one's upgrade verdict. Here
        // rather than in [QualityUpgrade.forget] because that runs on the
        // failure and rollback paths, where a previous "no" is exactly what
        // should be kept.
        mediaItem?.mediaId?.let {
            QualityUpgrade.onPlaybackStarted(
                it,
                mediaItem.localConfiguration?.uri?.let(QualityUpgrade::cacheTag),
            )
        }
        TrackLog.d(
            "BitChord",
            "TIMING track selected: ${mediaItem?.mediaId} (reason=$reason)",
            about = mediaItem?.mediaId,
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
        if (exoPlayer.isPlaying) pushDiscordPresence(exoPlayer)

        history.afterTrackChanged(previousEnded, exoPlayer)
        savePlaybackState(exoPlayer)
        convertVideosAround(exoPlayer)
        prefetchAround(exoPlayer)
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
        loadLyricsForCurrentTrack()
        if (exoPlayer.isPlaying) startLyricsTicker()
    }

    /**
     * Stop the player, clear all media items, remove the ongoing notification, and reset session state.
     */
    private fun dismissPlaybackInternal() {
        SleepTimer.cancel()
        cancelPrefetch()
        clearDiscordPresence()
        stopLyricsTicker()
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

    /**
     * Publishes what the decoder is really being fed, for "stats for nerds"
     * and for the quality badge above it.
     *
     * Everything the badge is decided on is measured off the stream in hand —
     * see [Measured] and [NerdStats.Snapshot.isLossless]. What a source said
     * it was about to send is carried alongside as [NerdStats.Snapshot.claimed]
     * and used for nothing but the mismatch note, because a claim is the one
     * thing that stays true after the stream it described has stopped playing.
     *
     * Bitrate is the awkward one. A lossless stream gets the rate its samples
     * decode to, which is a product of three figures already measured and is
     * the number a listener can compare between tracks. A lossy one gets the
     * container's, when the container states it — YouTube's WebM and MP4 do
     * not, so [Format.bitrate] arrives as `NO_VALUE` and the honest figure is
     * whatever named this stream instead. The source's own figure comes ahead
     * of YouTube's because a track can have both: one resolved through YouTube
     * and then upgraded to a module stream mid-song has a stale 160 sitting in
     * [NerdStats.pickedBitrateKbps] describing audio that stopped playing
     * several seconds ago. Anything still unknown is left null for the UI to
     * omit — better a shorter line than a made-up number.
     */
    private fun isLocalPlayback(mediaId: String, mediaItem: MediaItem?): Boolean {
        if (mediaId.startsWith("content://") || mediaId.startsWith("file://") || mediaId.startsWith("/")) {
            return true
        }
        val uri = mediaItem?.localConfiguration?.uri
        if (uri != null && (uri.scheme == "file" || uri.scheme == "content")) {
            return true
        }
        val requestUri = mediaItem?.requestMetadata?.mediaUri
        if (requestUri != null && (requestUri.scheme == "file" || requestUri.scheme == "content")) {
            return true
        }
        val extras = mediaItem?.mediaMetadata?.extras
        val localUri = extras?.getString(EXTRA_LOCAL_URI)
        if (!localUri.isNullOrBlank() && (localUri.startsWith("content://") || localUri.startsWith("file://"))) {
            return true
        }
        val localPath = extras?.getString(EXTRA_LOCAL_PATH)
        if (!localPath.isNullOrBlank()) {
            return true
        }
        if (Downloads.verifiedSavedUri(mediaId) != null) {
            return true
        }
        return false
    }

    private fun currentSourceName(mediaId: String?, mediaItem: MediaItem?): String? {
        val id = mediaId ?: return null
        if (isLocalPlayback(id, mediaItem)) {
            return "Local Storage"
        }

        // 1. Forced upgrade stream on active playback URI
        val uri = mediaItem?.localConfiguration?.uri
        if (uri != null) {
            QualityUpgrade.forcedStream(uri)?.let { upgraded ->
                val name = upgraded.sourceConfigId?.let { SourceRegistry.config(it)?.displayName }
                if (!name.isNullOrBlank()) return name
            }
        }

        // 2. Exact recorded source for this media item
        val exactRecorded = NerdStats.exactSourceFor(id)
        if (!exactRecorded.isNullOrBlank()) return exactRecorded

        // 3. Source-backed track key (e.g. src:<configId>::<trackId>)
        val sourceTrack = SourceRegistry.parseTrackKey(id)
        if (sourceTrack != null) {
            val config = SourceRegistry.config(sourceTrack.first)
            if (config != null) return config.displayName
            return "Unknown"
        }

        // 4. Stream substitution (e.g. YouTube substituted by addon)
        StreamChoice.of(id)?.let { serving ->
            val name = serving.sourceConfigId?.let { SourceRegistry.config(it)?.displayName }
                ?: if (StreamChoice.isSubstitute(id)) "Module" else null
            if (!name.isNullOrBlank()) return name
        }

        // 5. Virtual bitchord://source?s=... playback URI
        if (uri?.authority == "source") {
            val configId = uri.getQueryParameter("s")
            val name = configId?.let { SourceRegistry.config(it)?.displayName }
            if (!name.isNullOrBlank()) return name
            return "Unknown"
        }

        // 6. Non-prefixed mediaId fallback to recorded source if any
        val recorded = NerdStats.sourceFor(id)
        if (!recorded.isNullOrBlank()) return recorded

        // 7. YouTube video ID
        if (id.length == 11 || uri?.getQueryParameter("v") != null) {
            return "YouTube"
        }

        return "Unknown"
    }

    private fun resolveLocalUri(mediaId: String, mediaItem: MediaItem?): Uri? {
        val directUriStr = when {
            mediaId.startsWith("content://") || mediaId.startsWith("file://") -> mediaId
            mediaId.startsWith("/") -> "file://$mediaId"
            else -> null
        }
        if (directUriStr != null) return Uri.parse(directUriStr)

        val localConfigUri = mediaItem?.localConfiguration?.uri
        if (localConfigUri != null && (localConfigUri.scheme == "file" || localConfigUri.scheme == "content")) {
            return localConfigUri
        }

        val requestUri = mediaItem?.requestMetadata?.mediaUri
        if (requestUri != null && (requestUri.scheme == "file" || requestUri.scheme == "content")) {
            return requestUri
        }

        val extras = mediaItem?.mediaMetadata?.extras
        val localUriStr = extras?.getString(EXTRA_LOCAL_URI)
        if (!localUriStr.isNullOrBlank()) {
            return Uri.parse(localUriStr)
        }

        val localPath = extras?.getString(EXTRA_LOCAL_PATH)
        if (!localPath.isNullOrBlank()) {
            return Uri.fromFile(java.io.File(localPath))
        }

        val downloadedUri = Downloads.verifiedSavedUri(mediaId)
        if (downloadedUri != null) {
            return Uri.parse(downloadedUri)
        }

        return null
    }

    private fun resolveLocalBitrate(mediaId: String, mediaItem: MediaItem?) {
        if (!isLocalPlayback(mediaId, mediaItem)) return
        if (localBitrateCache.containsKey(mediaId)) return

        val uri = resolveLocalUri(mediaId, mediaItem) ?: return
        scope.launch(Dispatchers.IO) {
            val kbps = runCatching {
                val retriever = MediaMetadataRetriever()
                var bps: Long? = null
                var durMs: Long? = null
                try {
                    retriever.setDataSource(this@PlaybackService, uri)
                    bps = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()
                    durMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                } finally {
                    retriever.release()
                }

                if (bps != null && bps > 0) {
                    ((bps + 500L) / 1000L).toInt()
                } else if (durMs != null && durMs > 0) {
                    val sizeBytes = when (uri.scheme) {
                        "file" -> uri.path?.let { java.io.File(it).length() }
                        "content" -> contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize }
                        else -> null
                    }
                    if (sizeBytes != null && sizeBytes > 0) {
                        ((sizeBytes * 8000L) / durMs / 1000L).toInt().takeIf { it > 0 }
                    } else null
                } else null
            }.getOrNull()

            if (kbps != null && kbps > 0) {
                localBitrateCache[mediaId] = kbps
                withContext(Dispatchers.Main) {
                    if (player?.currentMediaItem?.mediaId == mediaId) {
                        publishNerdStats()
                    }
                }
            }
        }
    }

    private fun publishNerdStats() {
        val player = player ?: return
        val format = player.audioFormat
        val mediaId = player.currentMediaItem?.mediaId
        val generation = NerdStats.currentGeneration
        val measured = format?.measure()
        // DSD reaches the renderer already decimated to PCM (DsdExtractor), so
        // its sample mime is `audio/raw`; the extractor leaves `dsd64` and the
        // like in `codecs`, and that is the name worth showing — with the DSD
        // stream's own bitrate and rate (2.8224 MHz for DSD64) rather than the
        // 176.4 kHz PCM it became.
        val dsd = format?.codecs?.takeIf { it.startsWith("dsd") }
        val dsdRateHz = dsd?.removePrefix("dsd")?.toIntOrNull()?.times(44_100)
        val isLossless = dsd != null || NerdStats.isLosslessMime(format?.sampleMimeType)
        val isRawPcm = NerdStats.isRawPcm(format?.sampleMimeType)
        val pcmDataRate = measured?.pcmBitrateKbps

        val (bitrate, provenance) = when {
            format?.averageBitrate != null && format.averageBitrate > 0 ->
                Pair((format.averageBitrate + 500) / 1000, TelemetryProvenance.AUTHORITATIVE)
            format?.bitrate != null && format.bitrate > 0 ->
                Pair((format.bitrate + 500) / 1000, TelemetryProvenance.AUTHORITATIVE)
            mediaId != null && localBitrateCache.containsKey(mediaId) ->
                Pair(localBitrateCache[mediaId], TelemetryProvenance.AUTHORITATIVE)
            dsd == null && isRawPcm && pcmDataRate != null ->
                Pair(pcmDataRate, TelemetryProvenance.DERIVED)
            else ->
                NerdStats.sourceBitrate(mediaId) { measuredStreamKbps(player) }
                    ?: Pair(null, TelemetryProvenance.UNKNOWN)
        }

        NerdStats.publish(NerdStats.Snapshot(
            mimeType = dsd?.let { "audio/$it" } ?: format?.sampleMimeType,
            bitrateKbps = bitrate,
            bitrateProvenance = provenance,
            pcmDataRateKbps = if (isLossless) pcmDataRate else null,
            sampleRateHz = dsdRateHz ?: measured?.sampleRateHz,
            channels = measured?.channels,
            bitDepth = if (dsd != null) 1 else if (isLossless) measured?.bitDepth else null,
            claimed = NerdStats.declaredFormat(mediaId),
            sourceName = currentSourceName(mediaId, player.currentMediaItem),
            container = when (format?.containerMimeType) {
                "audio/x-dsf" -> "DSF"
                "audio/x-dff" -> "DFF"
                else -> null
            },
            mediaId = mediaId,
        ), expectedGeneration = generation)
    }

    /**
     * The playing stream's real bitrate: its full size over its length, or
     * null until both are known.
     *
     * For an addon that didn't state a bitrate and got one guessed from the
     * tier it was asked for — see [NerdStats.sourceBitrate]. The size is what
     * the server's response said, recorded in the cache index the first time
     * the rendition was opened, so this works on a stream still downloading.
     * Slightly high, because container overhead counts toward the bytes and
     * not toward the audio. Read off the main thread, once per rendition: the
     * cache index lookup takes the cache's lock, which a loader may be holding.
     */
    private fun measuredStreamKbps(player: Player): Int? {
        val uri = player.currentMediaItem?.localConfiguration?.uri ?: return null
        val key = uri.toString()
        measuredBitrateCache[key]?.let { return it }
        val durationMs = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return null
        if (measuringBitrate.add(key)) {
            scope.launch(Dispatchers.IO) {
                try {
                    val bytes = AudioCache.contentLengthOf(uri)
                    if (bytes > 0) {
                        val kbps = (bytes * 8 / durationMs).toInt()
                        if (kbps > 0) measuredBitrateCache[key] = kbps
                    }
                } finally {
                    measuringBitrate.remove(key)
                }
            }
        }
        return null
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
        persistedQueueStart = range.first
        LastPlayed.saveQueueImmediately(
            songs = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).toSong() },
            index = player.currentMediaItemIndex,
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
    private fun scheduleForeignCachePurge() {
        foreignCachePurgeJob?.cancel()
        foreignCachePurgeJob = scope.launch {
            delay(FOREIGN_CACHE_PURGE_DELAY_MS)
            val exoPlayer = player ?: return@launch
            val keep = buildSet {
                exoPlayer.currentMediaItem?.mediaId?.let(::add)
                val current = exoPlayer.currentMediaItemIndex
                if (current != C.INDEX_UNSET) {
                    val window = (current - 1)..(current + FOREIGN_CACHE_KEEP_AHEAD)
                    for (index in window) {
                        if (index in 0 until exoPlayer.mediaItemCount) add(exoPlayer.getMediaItemAt(index).mediaId)
                    }
                }
                spare?.currentMediaItem?.mediaId?.let(::add)
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
    private fun onCeilingLowered(ceiling: AudioQuality, lost: List<SourceKind>) {
        val exoPlayer = player ?: return
        val keep = buildSet {
            exoPlayer.currentMediaItem?.mediaId?.let(::add)
            spare?.currentMediaItem?.mediaId?.let(::add)
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
    private fun prefetchAround(player: ExoPlayer) {
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
    private fun convertVideosAround(exoPlayer: ExoPlayer) {
        if (!MusicOnlyPlayback.isActive()) return
        if (crossfade?.isTransitioning() == true || versionSync.active) return
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
            val live = player ?: return@launch
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
            versionSync.smoothSwapCurrentTrackVersion(replacement)
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
        val wrong = when {
            substituted -> abs(decodedMs - expectedMs) > recovery.upgradeLengthSlackMs + 1_000L
            unattributed -> decodedMs < expectedMs / 2
            else -> false
        }
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
    private var lastSeekAt = 0L

    /** Ticks [driveDirectWindow] for as long as the service runs. */
    private fun watchDirectWindow() {
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
    private fun driveDirectWindow() {
        val exoPlayer = player ?: return
        checkSubstituteLength(exoPlayer)
        val audible = buildSet {
            exoPlayer.currentMediaItem?.mediaId?.let(::add)
            if (crossfade?.isTransitioning() == true) spare?.currentMediaItem?.mediaId?.let(::add)
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
        val boundary = crossfade?.plannedTransitionFor(current.mediaId) ?: duration
        val speed = exoPlayer.playbackParameters.speed.coerceAtLeast(MIN_WINDOW_SPEED)
        val remaining = ((boundary - exoPlayer.currentPosition) / speed).toLong()
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
    private fun cancelPrefetch() {
        preferredPrefetchRequest = null
        AudioCache.cancel()
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
                    // Only two primitive preference values. Queue JSON is
                    // written from onTimelineChanged, never from this loop.
                    savePlaybackState(player)
                    // The renderer can settle on its format a moment after the
                    // track change, which no callback of ours follows up on.
                    publishNerdStats()
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
                cancelPrefetch()
                player?.let { live ->
                    convertVideosAround(live)
                    if (live.isPlaying) prefetchAround(live)
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
                loadLyricsForCurrentTrack()
            }
        }
        scope.launch {
            AppSettings.spatialAudio.collect { pipeline.applySpatialAudioEnabled() }
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

    // ---- Discord Rich Presence -------------------------------------------------

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
    private fun observeDiscord() {
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
                    discordRpc = DiscordRPC(this@PlaybackService, token)
                    // A presence that only appeared at the next track change
                    // would make turning the switch on look like it had done
                    // nothing for the length of a song.
                    player?.takeIf { it.isPlaying }?.let(::pushDiscordPresence)
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
                    player?.takeIf { p -> p.isPlaying }?.let(::pushDiscordPresence)
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
                    player?.takeIf { p -> p.isPlaying }?.let(::pushDiscordPresence)
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
                        player?.takeIf { p -> p.isPlaying }?.let(::pushDiscordPresence)
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
    private fun pushDiscordPresence(exoPlayer: ExoPlayer) {
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
    private fun clearDiscordPresence() {
        val rpc = discordRpc ?: return
        if (!discordPresenceUp) return
        discordPresenceUp = false
        discordUpdateJob?.cancel()
        discordUpdateJob = scope.launch(Dispatchers.IO) {
            runCatching { rpc.close() }
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
        lyricsTickerJob?.cancel()
        lyricsTickerJob = null
        serviceLyricsJob?.cancel()
        serviceLyricsJob = null
        serviceLyrics = null
        cancelPrefetch()
        trackAnalyzer.release()
        loudness.cancel()
        history.release()
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
        NerdStats.forgetLastSession()
        localBitrateCache.clear()
        measuredBitrateCache.clear()
        super.onDestroy()
    }

    private fun loadLyricsForCurrentTrack() {
        val currentSong = player?.currentMediaItem?.toSong() ?: run {
            serviceLyrics = null
            stopLyricsTicker()
            return
        }

        if (!AppSettings.syncedLyrics.value) {
            serviceLyrics = null
            stopLyricsTicker()
            return
        }

        val trackDurationMs = (player?.duration ?: 0L).takeIf { it > 0 } ?: 0L

        serviceLyricsJob?.cancel()
        serviceLyricsJob = scope.launch(Dispatchers.IO) {
            val localUri = currentSong.localUri
            var lines: List<LyricLine>? = null
            if (localUri != null) {
                lines = EmbeddedLyrics.forUri(this@PlaybackService, localUri)
            }
            if (lines == null) {
                val found = LyricsRepository.lyrics(
                    videoId = currentSong.videoId,
                    title = currentSong.title,
                    artist = currentSong.artist,
                    durationMs = trackDurationMs,
                    album = currentSong.albumName,
                    sources = AppSettings.lyricsSources.value,
                    order = AppSettings.lyricsSourceOrder.value,
                    prioritizeSyllableSync = AppSettings.prioritizeSyllableSync.value,
                )
                lines = found?.lines
            }
            withContext(Dispatchers.Main) {
                serviceLyrics = lines
                if (player?.isPlaying == true) {
                    updateLyricSubtitle()
                }
            }
        }
    }

    private fun startLyricsTicker() {
        if (lyricsTickerJob?.isActive == true) return
        lyricsTickerJob = scope.launch(Dispatchers.Main) {
            while (isActive) {
                updateLyricSubtitle()
                delay(500L)
            }
        }
    }

    private fun stopLyricsTicker() {
        lyricsTickerJob?.cancel()
        lyricsTickerJob = null
        updateLyricSubtitle()
    }

    private fun updateLyricSubtitle() {
        val exoPlayer = player ?: return
        val currentSong = exoPlayer.currentMediaItem?.toSong() ?: return
        val lines = serviceLyrics
        val pos = crossfade?.loopPositionMs() ?: exoPlayer.currentPosition
        val subtitleText = if (lines != null && lines.isNotEmpty() && AppSettings.syncedLyrics.value) {
            val idx = lines.indexOfLast { it.timeMs <= pos }
            val currentLine = lines.getOrNull(idx)
            if (currentLine != null && !currentLine.isGap && currentLine.text.isNotBlank()) {
                "♪ ${currentLine.text}"
            } else {
                currentSong.artist
            }
        } else {
            currentSong.artist
        }

        if (subtitleText != lastPublishedSubtitle) {
            lastPublishedSubtitle = subtitleText
            // buildUpon() off the metadata that's already playing, not a fresh
            // Builder() — a synced lyric line can advance every second or two,
            // and re-stating setArtworkUri() on every tick made media3 treat
            // the cover as newly changed that often, racing its own artwork
            // decode/cache against the legacy MediaSession broadcast and
            // crashing with "cannot use a recycled source in createBitmap".
            // Carrying the existing artwork field forward keeps that field
            // untouched while still firing onMediaMetadataChanged for the
            // subtitle itself.
            exoPlayer.playlistMetadata = exoPlayer.mediaMetadata
                .buildUpon()
                .setSubtitle(subtitleText)
                .build()
        }
    }

    /**
     * Handles browsing and content resolution for Android Auto and other MediaBrowser clients.
     */
    private inner class MediaLibraryCallback : MediaLibrarySession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val sessionCommands = SessionCommands.available()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailablePlayerCommands(MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS)
                .setAvailableSessionCommands(sessionCommands)
                .build()
        }

        override fun onPostConnect(session: MediaSession, controller: MediaSession.ControllerInfo) {
            buttons.onConnected(session, controller)
        }

        override fun onDisconnected(session: MediaSession, controller: MediaSession.ControllerInfo) {
            buttons.onDisconnected(controller)
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
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
                else -> return Futures.immediateFuture(
                    SessionResult(SessionError.ERROR_NOT_SUPPORTED),
                )
            }
            // The actual YouTube rating is asynchronous. The command itself has been accepted;
            // the notification is refreshed when the network write completes.
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        // The browse tree, its search and the item lookups are [library]'s;
        // see [LibraryBrowser].
        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> = library.onGetLibraryRoot(params)

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
            library.onGetChildren(session, browser, parentId, page, pageSize, params)

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = library.onGetItem(mediaId)

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future(Dispatchers.IO) {
            // 1. If player currently holds items in its queue, resume from it
            val activePlayer = player
            if (activePlayer != null && activePlayer.mediaItemCount > 0) {
                val currentItems = (0 until activePlayer.mediaItemCount).map { activePlayer.getMediaItemAt(it) }
                val startIndex = activePlayer.currentMediaItemIndex.coerceIn(0, currentItems.size - 1)
                val startPositionMs = activePlayer.currentPosition.coerceAtLeast(0L)
                return@future MediaSession.MediaItemsWithStartPosition(
                    ImmutableList.copyOf(currentItems),
                    startIndex,
                    startPositionMs,
                )
            }

            // 2. Nothing queued: what the library can offer a headset's play
            // button — see [LibraryBrowser.resumptionFallback].
            library.resumptionFallback()
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> = library.onSearch(session, browser, query, params)

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
            library.onGetSearchResult(query, page, pageSize, params)

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future(Dispatchers.IO) {
            val resolved = library.resolveMediaItems(mediaItems)
            val validIndex = startIndex.coerceIn(0, (resolved.size - 1).coerceAtLeast(0))
            MediaSession.MediaItemsWithStartPosition(
                ImmutableList.copyOf(resolved),
                validIndex,
                startPositionMs.coerceAtLeast(0L),
            )
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
        ): ListenableFuture<List<MediaItem>> = scope.future(Dispatchers.IO) {
            library.resolveMediaItems(mediaItems)
        }
    }

    private companion object {
        /** See [scheduleForeignCachePurge]: comfortably past the longest crossfade. */
        const val FOREIGN_CACHE_PURGE_DELAY_MS = 30_000L
        /** How many upcoming tracks [scheduleForeignCachePurge] leaves alone for read-ahead and Automix. */
        const val FOREIGN_CACHE_KEEP_AHEAD = 3
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

        /** How often [driveDirectWindow] checks whether the next track's window has opened. */
        const val DIRECT_WINDOW_STEP_MS = 500L

        /**
         * How long the playhead has to run on its own after a seek before the
         * direct window may open. Long enough that a scrub, which is a burst
         * of seeks, never counts as having reached the end of a track.
         */
        const val SEEK_SETTLE_MS = 2_000L

        /** Floor on the speed the window divides by, so a near-zero speed cannot stretch it to forever. */
        const val MIN_WINDOW_SPEED = 0.25f

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

        /** Upper bound per range; [PlayerClient.rangeBytesFor] narrows it per client. */
        const val STREAM_CHUNK_BYTES = 1L * 1024 * 1024

        /** How long the loop mix waits for a song's synced lyrics before planning on the vocal mask. */
        const val LOOP_MIX_LYRICS_TIMEOUT_MS = 10_000L

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

        /**
         * Outer cap on stream resolution. Individual client calls and probes
         * have their own timeouts, but iterating all seven plus the NewPipe
         * fallback can accumulate far beyond what a listener should wait.
         *
         * The NewPipe fallback alone — a scrape of the watch page, shaped
         * harder than anything else this app asks Google for — routinely
         * takes 45-90s on its own when every player client is bot-checked, a
         * state that has become the common case rather than the rare one. A
         * cap shorter than that doesn't bound the wait; it cancels the
         * resolve just as it was about to succeed, and the retry that
         * follows restarts the same slow walk from zero, so the listener
         * waits *longer* under a tighter cap than a looser one.
         */
        const val RESOLVE_TIMEOUT_MS = 120_000L
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
