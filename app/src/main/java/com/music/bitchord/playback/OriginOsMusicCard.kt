package com.music.bitchord.playback

import android.os.Bundle
import androidx.media3.common.Player
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * The music card of OriginOS (vivo / iQOO), shown on the lock screen and the
 * Origin Island by `com.vivo.musicwidgetmix`.
 *
 * It reads custom keys from the session metadata/extras and expects LRC formatted
 * lyrics resent periodically to keep the Origin Island and lock screen in sync.
 */
object OriginOsMusicCard {
    const val PACKAGE = "com.vivo.musicwidgetmix"
    const val SERVICE_ACTION = "com.vivo.musicwidgetmix.support.service"

    /** Bit mask of what the card may show and control. Without it, the card disables everything. */
    const val KEY_SUPPORT_EVENT = "vivomusicmix.media.metadata.support_event"
    private const val EVENT_PLAY_CONTROL = 1L
    private const val EVENT_MUSIC_INFO = 1L shl 1
    private const val EVENT_MUSIC_IMAGE = 1L shl 2
    private const val EVENT_LYRICS = 1L shl 3
    private const val EVENT_SEEK_POSITION = 1L shl 4
    private const val EVENT_FAVORITE = 1L shl 6
    private const val EVENT_LOOP_MODE = 1L shl 7
    private const val EVENT_LIST_CURRENT = 1L shl 8
    private const val EVENT_LIST_FAVORITE = 1L shl 9
    private const val EVENT_LIST_LOCAL = 1L shl 10

    /** Supported features bitmask sent to vivo music widget. */
    const val SUPPORTED_EVENTS = EVENT_PLAY_CONTROL or EVENT_MUSIC_INFO or EVENT_MUSIC_IMAGE or
        EVENT_LYRICS or EVENT_SEEK_POSITION or EVENT_FAVORITE or EVENT_LOOP_MODE or
        EVENT_LIST_CURRENT or EVENT_LIST_FAVORITE or EVENT_LIST_LOCAL

    // Lyrics for the card / Origin Island arrive through the session extras.
    // The exact misspellings ("meida", "meidia") are required by the vivo framework.
    const val EXTRA_KEY_ACTION = "vivomusicmix.meida.extra.key.action"
    const val ACTION_LRC_CHANGE = "vivomusicmix.extra.lrc_change"
    const val EXTRA_KEY_LYRIC = "vivomusicmix.extra.key.lyric"
    const val EXTRA_KEY_MEDIA_ID = "vivomusicmix.extra.key.meidia_id"

    /**
     * Resend interval in milliseconds. Origin Island and the lock screen widget
     * redraw and drop lyrics on screen wake, so lyrics are resent periodically.
     */
    const val LYRICS_RESEND_INTERVAL_MS = 4_000L

    /** Wait for multi-provider lyric fetch to settle before publishing to widget. */
    const val PLAYER_LYRICS_SETTLE_MS = 2_000L

    data class PlayerLyrics(val trackKey: String, val lines: List<LyricLine>)

    private val _playerLyrics = MutableStateFlow<PlayerLyrics?>(null)
    val playerLyrics: StateFlow<PlayerLyrics?> = _playerLyrics.asStateFlow()

    fun publishPlayerLyrics(title: String, artist: String, videoId: String?, lines: List<LyricLine>) {
        _playerLyrics.value = PlayerLyrics(trackKeyOf(title, artist, videoId), lines)
    }

    fun publishPlayerLyrics(song: Song, lines: List<LyricLine>) {
        publishPlayerLyrics(song.title, song.artist, song.videoId, lines)
    }

    fun trackKeyOf(title: String, artist: String, videoId: String? = null): String {
        return videoId?.takeIf { it.isNotEmpty() } ?: "$title|$artist"
    }

    fun trackKeyOf(song: Song): String = trackKeyOf(song.title, song.artist, song.videoId)

    /** Only vivo / iQOO devices run OriginOS music card. */
    fun isSupportedDevice(manufacturer: String = android.os.Build.MANUFACTURER): Boolean {
        val m = manufacturer.lowercase(Locale.ROOT)
        return m.contains("vivo") || m.contains("iqoo")
    }

    /** Convert synced lines to standard [mm:ss.xx] LRC format. */
    fun lrcOf(lines: List<LyricLine>): String? {
        val timed = lines.filter { it.timeMs >= 0 }
        if (timed.isEmpty() || timed.all { it.timeMs == 0L }) return null
        return timed.joinToString("\n") { line ->
            val totalCs = line.timeMs / 10
            val minutes = totalCs / 6000
            val seconds = (totalCs / 100) % 60
            val centis = totalCs % 100
            "[%02d:%02d.%02d]%s".format(Locale.ROOT, minutes, seconds, centis, line.text.replace('\n', ' '))
        }
    }

    /** Prepare the session extras bundle carrying LRC lyrics. */
    fun lyricsExtras(mediaId: String, lrc: String): Bundle = Bundle().apply {
        putLong(KEY_SUPPORT_EVENT, SUPPORTED_EVENTS)
        putString(EXTRA_KEY_ACTION, ACTION_LRC_CHANGE)
        putString(EXTRA_KEY_LYRIC, lrc)
        putString(EXTRA_KEY_MEDIA_ID, mediaId)
    }

    const val KEY_LOOP_MODE = "vivomusicmix.media.metadata.LOOP_MODE"
    const val LOOP_MODE_LIST = 1
    const val LOOP_MODE_SINGLE = 2
    const val LOOP_MODE_RANDOM = 3

    const val ACTION_PLAY_MODE = "vivomusicmix.media.action.PLAY_MODE"

    private const val QUEUE_PREFIX = "queue:"

    fun mediaIdOf(index: Int, title: String, artist: String, videoId: String?): String {
        val identity = trackKeyOf(title, artist, videoId)
        return "$QUEUE_PREFIX$index:${Integer.toHexString(identity.hashCode())}"
    }

    fun mediaIdOf(index: Int, song: Song): String = mediaIdOf(index, song.title, song.artist, song.videoId)

    fun queueMediaIdOf(mediaId: String): String =
        if (mediaId.startsWith(QUEUE_PREFIX)) mediaId.substringBeforeLast(':').takeIf { it != "queue" } ?: mediaId
        else mediaId

    fun loopModeOf(shuffleEnabled: Boolean, repeatMode: Int): Int = when {
        shuffleEnabled -> LOOP_MODE_RANDOM
        repeatMode == Player.REPEAT_MODE_ONE -> LOOP_MODE_SINGLE
        else -> LOOP_MODE_LIST
    }

    fun playModesOf(loopMode: Int): Pair<Boolean, Int>? = when (loopMode) {
        LOOP_MODE_LIST -> false to Player.REPEAT_MODE_ALL
        LOOP_MODE_SINGLE -> false to Player.REPEAT_MODE_ONE
        LOOP_MODE_RANDOM -> true to Player.REPEAT_MODE_ALL
        else -> null
    }
}
