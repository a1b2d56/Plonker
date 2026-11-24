package com.music.bitchord.playback

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.music.bitchord.data.lyrics.EmbeddedLyrics
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricsRepository
import com.music.bitchord.data.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.collectLatest

/**
 * The lyric line under the notification's title.
 *
 * Synced lyrics for the playing track are looked up once per track ([load]),
 * from the file itself for a local one and the lyrics sources otherwise, and
 * while it plays a half-second ticker puts the line being sung into the
 * session's subtitle, or the artist between lines and when there are none.
 * The loop mix reads its playhead ([loopPositionMs]) while one is running,
 * since that, not the player's position, is what is being heard.
 *
 * Also the loop mix's own lookup ([loopMixLyrics]), which plans a song's
 * instrumental stretches from when its lines are sung.
 */
internal class ServiceLyrics(
    private val context: Context,
    private val scope: CoroutineScope,
    private val player: () -> ExoPlayer?,
    private val loopPositionMs: () -> Long?,
    private val setSessionExtras: (android.os.Bundle) -> Unit = {},
) {
    private var serviceLyricsJob: Job? = null
    private var serviceLyrics: List<LyricLine>? = null
    /** What the session's subtitle last said, so an unchanged line is not re-sent. */
    var lastPublishedSubtitle: String? = null
        private set
    private var lyricsTickerJob: Job? = null
    private var originLyricsTickerJob: Job? = null
    private var currentOriginOsExtras: android.os.Bundle? = null

    init {
        if (OriginOsMusicCard.isSupportedDevice()) {
            scope.launch {
                OriginOsMusicCard.playerLyrics.collectLatest { playerLrc ->
                    if (playerLrc == null) return@collectLatest
                    val currentSong = player()?.currentMediaItem?.toSong() ?: return@collectLatest
                    val trackKey = OriginOsMusicCard.trackKeyOf(currentSong)
                    if (playerLrc.trackKey != trackKey) return@collectLatest
                    delay(OriginOsMusicCard.PLAYER_LYRICS_SETTLE_MS)
                    val lrc = OriginOsMusicCard.lrcOf(playerLrc.lines) ?: return@collectLatest
                    val mediaId = player()?.currentMediaItem?.mediaId ?: return@collectLatest
                    val extras = OriginOsMusicCard.lyricsExtras(mediaId, lrc)
                    currentOriginOsExtras = extras
                    withContext(Dispatchers.Main) {
                        setSessionExtras(extras)
                    }
                }
            }
        }
    }

    /**
     * A track's synced lyrics for the loop mix, which lets a loop be vouched
     * instrumental by when its lines are sung rather than by the vocal model.
     * Only with synced lyrics switched on, and never for longer than
     * [LOOP_MIX_LYRICS_TIMEOUT_MS]: the mask stands in for whatever doesn't come.
     */
    suspend fun loopMixLyrics(item: MediaItem, durationMs: Long): List<LyricLine>? {
        if (!AppSettings.syncedLyrics.value) return null
        val song = item.toSong()
        return kotlinx.coroutines.withTimeoutOrNull(LOOP_MIX_LYRICS_TIMEOUT_MS) {
            song.localUri?.let { EmbeddedLyrics.forUri(context, it) }
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

    fun load() {
        val currentSong = player()?.currentMediaItem?.toSong() ?: run {
            serviceLyrics = null
            stopTicker()
            return
        }

        if (!AppSettings.syncedLyrics.value) {
            serviceLyrics = null
            stopTicker()
            return
        }

        val trackDurationMs = (player()?.duration ?: 0L).takeIf { it > 0 } ?: 0L

        serviceLyricsJob?.cancel()
        serviceLyricsJob = scope.launch(Dispatchers.IO) {
            val localUri = currentSong.localUri
            var lines: List<LyricLine>? = null
            if (localUri != null) {
                lines = EmbeddedLyrics.forUri(context, localUri)
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
                if (OriginOsMusicCard.isSupportedDevice() && lines != null) {
                    val lrc = OriginOsMusicCard.lrcOf(lines)
                    val mediaId = currentSong.videoId
                    if (lrc != null) {
                        val extras = OriginOsMusicCard.lyricsExtras(mediaId, lrc)
                        currentOriginOsExtras = extras
                        setSessionExtras(extras)
                    }
                }
                if (player()?.isPlaying == true) {
                    update()
                }
            }
        }
    }

    fun startTicker() {
        if (OriginOsMusicCard.isSupportedDevice() && originLyricsTickerJob?.isActive != true) {
            originLyricsTickerJob = scope.launch(Dispatchers.Main) {
                while (isActive) {
                    val extras = currentOriginOsExtras
                    if (extras != null) {
                        setSessionExtras(extras)
                    }
                    delay(OriginOsMusicCard.LYRICS_RESEND_INTERVAL_MS)
                }
            }
        }
        if (lyricsTickerJob?.isActive == true) return
        lyricsTickerJob = scope.launch(Dispatchers.Main) {
            while (isActive) {
                update()
                delay(500L)
            }
        }
    }

    fun stopTicker() {
        originLyricsTickerJob?.cancel()
        originLyricsTickerJob = null
        lyricsTickerJob?.cancel()
        lyricsTickerJob = null
        update()
    }

    fun update() {
        val exoPlayer = player() ?: return
        val currentSong = exoPlayer.currentMediaItem?.toSong() ?: return
        val lines = serviceLyrics
        val pos = loopPositionMs() ?: exoPlayer.currentPosition
        val subtitleText = lyricSubtitle(
            lines = lines.takeIf { AppSettings.syncedLyrics.value },
            positionMs = pos,
            artist = currentSong.artist,
        )

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

    /** The service is going. */
    fun release() {
        originLyricsTickerJob?.cancel()
        originLyricsTickerJob = null
        currentOriginOsExtras = null
        lyricsTickerJob?.cancel()
        lyricsTickerJob = null
        serviceLyricsJob?.cancel()
        serviceLyricsJob = null
        serviceLyrics = null
    }

    private companion object {
        /** How long the loop mix waits for a song's synced lyrics before planning on the vocal mask. */
        const val LOOP_MIX_LYRICS_TIMEOUT_MS = 10_000L
    }
}

/**
 * What the session's subtitle says at [positionMs]: the line being sung, marked
 * with a note, or [artist] before the first line, in an instrumental gap, and
 * when there are no synced [lines] at all.
 */
internal fun lyricSubtitle(lines: List<LyricLine>?, positionMs: Long, artist: String): String {
    if (lines.isNullOrEmpty()) return artist
    val line = lines.getOrNull(lines.indexOfLast { it.timeMs <= positionMs })
    return if (line != null && !line.isGap && line.text.isNotBlank()) "♪ ${line.text}" else artist
}
