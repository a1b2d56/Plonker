package com.music.bitchord.playback

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.music.bitchord.data.innertube.StreamResolver
import com.music.bitchord.data.settings.AppSettings
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The pure half of loudness normalization: what gain a track gets, if any. */
object LoudnessRules {
    /** The most a quiet master is lifted: +3 dB, in millibels. */
    const val MAX_GAIN_MB = 300

    /** The most a loud master is cut: -15 dB, in millibels. */
    const val MIN_GAIN_MB = -1500

    /**
     * The gain, in millibels, that brings a track measured at [loudnessDb]
     * (relative to YouTube's reference) back to it. Null when normalization
     * is off, switched off for the phone's own speaker, or the track has no
     * figure yet.
     */
    fun gainMb(enabled: Boolean, offForSpeaker: Boolean, loudnessDb: Double?): Int? {
        if (!enabled || offForSpeaker) return null
        val db = loudnessDb ?: return null
        return (-db * 100.0).roundToInt().coerceIn(MIN_GAIN_MB, MAX_GAIN_MB)
    }

    /** [gainMb] as the linear factor a sample is multiplied by; 1 for no gain. */
    fun linear(gainMb: Int?): Float = gainMb?.let { 10.0.pow(it / 2000.0).toFloat() } ?: 1f
}

/** The track this player will move onto gaplessly, if any. */
fun ExoPlayer.nextMediaIdOrNull(): String? {
    val next = nextMediaItemIndex
    if (next == C.INDEX_UNSET || next !in 0 until mediaItemCount) return null
    return getMediaItemAt(next).mediaId
}

/**
 * Loudness normalization as the service hands it out: the two players' own
 * [LoudnessProcessor]s (one per sink, so each side of a crossfade is levelled
 * for its own song), which of them is the session's, and the gain figure the
 * pipeline readout shows.
 *
 * [aIsActive] answers which sink is the session's, read off the same role the
 * service's transition filters keep, so the two can never disagree.
 */
@UnstableApi
class LoudnessDispatch(
    private val scope: CoroutineScope,
    private val aIsActive: () -> Boolean,
    private val player: () -> ExoPlayer?,
    private val loudnessDbFor: (String) -> Double? = StreamResolver::loudnessDbFor,
    private val enabled: () -> Boolean = { AppSettings.loudnessNormalization.value },
    private val offForSpeaker: () -> Boolean = {
        AppSettings.loudnessOffOnSpeaker.value &&
            AudioOutputStatus.current.value.routeKind == AudioRouting.Kind.PHONE
    },
    private val publish: (gainDb: Float?, lufs: Float?) -> Unit = { gainDb, lufs ->
        AudioOutputStatus.publishLoudness(gainDb = gainDb, lufs = lufs)
    },
) {
    val a = LoudnessProcessor().apply { gainFor = ::gainFor }
    val b = LoudnessProcessor().apply { gainFor = ::gainFor }

    private var retryJob: Job? = null

    /** The session player's loudness stage. */
    fun active(): LoudnessProcessor = if (aIsActive()) a else b

    /** The other player's, armed with the incoming track at a transition. */
    fun spare(): LoudnessProcessor = if (aIsActive()) b else a

    /** Both stages' crossfade headroom trim. */
    fun setBlendTrim(trim: Float) {
        a.setBlendTrim(trim)
        b.setBlendTrim(trim)
    }

    /**
     * Linear gain for [mediaId]. Read lazily by each [LoudnessProcessor] on the
     * audio thread, so a figure that resolves after the track starts glides in
     * on the next block.
     *
     * A track substituted to JioSaavn or an addon still carries a figure as
     * long as it was queued from YouTube, because [StreamResolver] resolves the
     * YouTube stream alongside the substitute lookup rather than only when one
     * fails — see [StreamResolver.loudnessDbFor]'s own doc.
     */
    fun gainFor(mediaId: String): Float = LoudnessRules.linear(gainMb(mediaId))

    fun gainMb(mediaId: String?): Int? {
        val id = mediaId?.takeIf { it.isNotBlank() }
        return LoudnessRules.gainMb(
            enabled = enabled(),
            offForSpeaker = offForSpeaker(),
            loudnessDb = id?.let(loudnessDbFor),
        )
    }

    /**
     * Points the session player's loudness stage at [mediaId] and the track
     * queued after it, and publishes the figure for the pipeline readout.
     *
     * Only the session player's: the spare is pointed at its own track when a
     * transition arms it — see [CrossfadeController]'s `onArmIncoming` — which
     * is the whole point of the stage being per player.
     */
    fun setup(mediaId: String?) {
        val exoPlayer = player() ?: return
        active().track(mediaId, exoPlayer.nextMediaIdOrNull())
        publishFor(mediaId)
    }

    fun publishFor(mediaId: String?) {
        val gainMb = gainMb(mediaId)
        val lufs = mediaId?.takeIf { it.isNotBlank() }?.let(loudnessDbFor)
        if (gainMb == null || lufs == null) {
            publish(null, null)
        } else {
            publish(gainMb / 100f, lufs.toFloat())
        }
    }

    /**
     * One re-read of the readout, a few seconds after a transition, for the
     * track whose YouTube figure had not resolved yet when [setup] first ran —
     * the substitute lookup that wins the race for a JioSaavn or addon track is
     * often quicker than the YouTube walk running alongside it.
     *
     * Only the readout: the gain itself is read lazily by [LoudnessProcessor]
     * and glides in on its own the moment the figure lands.
     */
    fun scheduleRetry(mediaId: String?) {
        retryJob?.cancel()
        val id = mediaId?.takeIf { it.isNotBlank() } ?: return
        if (loudnessDbFor(id) != null) return
        retryJob = scope.launch {
            delay(RETRY_MS)
            if (player()?.currentMediaItem?.mediaId == id) publishFor(id)
        }
    }

    fun cancel() {
        retryJob?.cancel()
        retryJob = null
    }

    private companion object {
        /** How long [scheduleRetry] waits for a still-resolving figure. */
        const val RETRY_MS = 6_000L
    }
}
