package com.music.bitchord.playback

import android.content.Context
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.session.MediaController
import com.music.bitchord.R
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.listentogether.ListenTogether
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.playback.smart.VersionAudioAligner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Which recording of the playing track is current — the video upload or its
 * catalogue (music-only) release — and what switching between them has done
 * this listening session.
 *
 * Everything here is a decision; [VersionSwitcher] carries them out on the
 * player. The lookups default to the app's caches and are parameters so the
 * rules can be exercised without them.
 */
@Stable
class VersionState(
    private val cachedAudioVersion: (String) -> Song? = YtMusicRepository::cachedAudioVersion,
    private val cachedVideoVersion: (String) -> Song? = YtMusicRepository::cachedVideoVersion,
    /** Whether the time offset between two cuts is already known, so a swap needs no measuring. */
    private val alignmentKnown: (fromId: String, toId: String) -> Boolean = { fromId, toId ->
        VersionAudioAligner.getCachedOffsetMs(fromId, toId) != null ||
            YtMusicRepository.versionTimeMap(fromId, toId) != null
    },
    private val smartAlignment: () -> Boolean = { AppSettings.smartVersionAlignment.value },
) {
    /**
     * Tracks whose other cut a switch looked for and didn't find: their menu
     * row reads "not found" from then on instead of offering it again.
     */
    val notFound = mutableStateListOf<String>()

    // A conversion is deliberately scoped to the current listening session.
    // Keeping the complete original row here lets Revert restore the exact
    // video upload, including its title and playlist identity, rather than
    // trying to reconstruct it from the catalogue match.
    var convertedFromVideo by mutableStateOf<Song?>(null)
        private set
    var convertedAudioId by mutableStateOf<String?>(null)
        private set

    /** The audio → video direction, the inverse of the pair above. */
    var convertedFromAudio by mutableStateOf<Song?>(null)
        private set
    var convertedVideoId by mutableStateOf<String?>(null)
        private set

    /**
     * Revert is an explicit choice for this occurrence of the track. Without
     * remembering it, the automatic preference would see the restored video
     * as a fresh item and immediately convert it again.
     */
    var keepVideoId by mutableStateOf<String?>(null)
        private set

    /** A switch is resolving; a second one is refused until it lands. */
    var switching by mutableStateOf(false)
        private set

    /** The version about to become current, shown before the player gets there. */
    var optimistic by mutableStateOf<Song?>(null)
        private set

    /**
     * Whether a swap from [fromId] to [toId] still has an alignment to measure.
     * While it does, the UI keeps showing the version actually playing: claiming
     * the target early would make the switch look finished before it began.
     */
    fun alignmentPending(fromId: String, toId: String): Boolean =
        smartAlignment() && !alignmentKnown(fromId, toId)

    /**
     * [song] as the UI should describe it: an in-flight optimistic swap wins,
     * so the player and a menu opened mid-switch show the version becoming
     * current rather than the one about to be left.
     */
    fun effectiveSong(song: Song): Song = optimistic?.takeIf {
        it.videoId == convertedAudioId || it.videoId == convertedVideoId || it.videoId == keepVideoId ||
            it.videoId == cachedAudioVersion(song.videoId)?.videoId ||
            it.videoId == cachedVideoVersion(song.videoId)?.videoId
    } ?: song

    /** What the menu's version row reads for [song]: true for the music-only cut. */
    fun isAudioVersion(song: Song): Boolean {
        val pending = optimistic
        return if (pending != null) !pending.isVideo else !song.isVideo && convertedVideoId != song.videoId
    }

    /** The conversion state only ever describes the track it was made for. */
    fun onTrackChanged(videoId: String?) {
        val pending = optimistic
        if (pending?.videoId == videoId ||
            (pending != null && videoId != convertedAudioId && videoId != convertedVideoId && videoId != keepVideoId)
        ) {
            optimistic = null
        }
        if (keepVideoId != videoId) keepVideoId = null
        if (videoId != convertedAudioId) {
            convertedFromVideo = null
            convertedAudioId = null
            switching = false
        }
        if (videoId != convertedVideoId) {
            convertedFromAudio = null
            convertedVideoId = null
            switching = false
        }
    }

    /** What the menu's toggle does, once [prepareToggle] has updated the state for it. */
    sealed interface Toggle {
        /** Go back to the exact row a switch this session replaced. */
        data class Revert(val to: Song) : Toggle
        /** Resolve the catalogue release and switch to it. */
        data object ToAudio : Toggle
        /** Resolve the video upload and switch to it. */
        data object ToVideo : Toggle
    }

    /**
     * Decides what the version toggle does for [song] — [effective] being
     * [effectiveSong] as the menu showed it — and records it: a revert forgets
     * the conversion, and a cut already found is claimed optimistically.
     */
    fun prepareToggle(song: Song, effective: Song = effectiveSong(song)): Toggle {
        val original = convertedFromVideo
        val originalAudio = convertedFromAudio
        return when {
            original != null && (
                convertedAudioId == song.videoId ||
                    convertedAudioId == effective.videoId ||
                    convertedAudioId == optimistic?.videoId
                ) -> {
                keepVideoId = original.videoId
                convertedFromVideo = null
                convertedAudioId = null
                if (!alignmentPending(song.videoId, original.videoId)) optimistic = original
                Toggle.Revert(original)
            }
            originalAudio != null && (
                convertedVideoId == song.videoId ||
                    convertedVideoId == effective.videoId ||
                    convertedVideoId == optimistic?.videoId
                ) -> {
                convertedFromAudio = null
                convertedVideoId = null
                if (!alignmentPending(song.videoId, originalAudio.videoId)) optimistic = originalAudio
                Toggle.Revert(originalAudio)
            }
            effective.isVideo || song.isVideo -> {
                val cached = cachedAudioVersion(song.videoId) ?: cachedAudioVersion(effective.videoId)
                if (cached != null && cached.videoId != song.videoId &&
                    !alignmentPending(song.videoId, cached.videoId)
                ) {
                    optimistic = cached.copy(isVideoOrigin = true).inPlaceOf(song)
                }
                Toggle.ToAudio
            }
            else -> {
                val cached = cachedVideoVersion(song.videoId) ?: cachedVideoVersion(effective.videoId)
                if (cached != null && cached.videoId != song.videoId &&
                    !alignmentPending(song.videoId, cached.videoId)
                ) {
                    optimistic = cached.inPlaceOf(song)
                }
                Toggle.ToVideo
            }
        }
    }

    /** A switch starts resolving. Asking for the music-only cut drops an earlier Revert. */
    fun beginSwitch(towardAudio: Boolean) {
        switching = true
        if (towardAudio) keepVideoId = null
    }

    fun endSwitch() {
        switching = false
    }

    fun markNotFound(videoId: String) {
        if (videoId !in notFound) notFound += videoId
    }

    /**
     * [audio] was found for the video [song]: records the conversion and
     * returns the row to swap in, carrying [song]'s place in the queue.
     * [holdUntilAligned] is the alignment setting as the switch began.
     */
    fun audioFound(song: Song, audio: Song, holdUntilAligned: Boolean): Song {
        convertedFromVideo = song
        convertedAudioId = audio.videoId
        val target = audio.copy(isVideoOrigin = true).inPlaceOf(song)
        if (!holdUntilAligned || alignmentKnown(song.videoId, target.videoId)) optimistic = target
        return target
    }

    /** The inverse of [audioFound]. */
    fun videoFound(song: Song, video: Song, holdUntilAligned: Boolean): Song {
        convertedFromAudio = song
        convertedVideoId = video.videoId
        val target = video.inPlaceOf(song)
        if (!holdUntilAligned || alignmentKnown(song.videoId, target.videoId)) optimistic = target
        return target
    }

    private fun Song.inPlaceOf(song: Song) = copy(
        queueTier = song.queueTier,
        queueEntryId = song.queueEntryId,
        radioName = song.radioName,
        playbackSource = song.playbackSource,
        playbackSourceType = song.playbackSourceType,
        playbackSourceId = song.playbackSourceId,
    )
}

/**
 * Switches the playing track between its video upload and its catalogue
 * release, as [VersionState] decides.
 *
 * [controller] and [showNotice] are reassigned by the owning composition on
 * every pass, as for [QueueController].
 */
class VersionSwitcher(
    private val context: Context,
    private val scope: CoroutineScope,
    val state: VersionState,
) {
    var controller: MediaController? = null
    var showNotice: (String) -> Unit = {}

    /**
     * What the track menu's version row says and does for [song]: whether it
     * is the music-only cut, and the toggle — offered for the playing track
     * wherever its menu is opened, whether or not the other version has been
     * found yet. The switch looks it up, and says so when there isn't one.
     */
    fun menuRow(song: Song, closeMenu: () -> Unit): Pair<Boolean, (() -> Unit)?> {
        val effective = state.effectiveSong(song)
        val onToggle: (() -> Unit)? = if (controller?.currentMediaItem?.mediaId == song.videoId) {
            toggle@{
                closeMenu()
                // A party plays one cut for everyone in it — see
                // [PlaybackService.smoothSwapCurrentTrackVersion].
                if (ListenTogether.state.value.inParty) {
                    showNotice(context.getString(R.string.version_switch_in_party))
                    return@toggle
                }
                if (state.switching) return@toggle
                val c = controller ?: return@toggle
                when (val toggle = state.prepareToggle(song, effective)) {
                    is VersionState.Toggle.Revert -> c.swapToVersion(toggle.to)
                    VersionState.Toggle.ToAudio ->
                        scope.launch { switchToMusicOnly(song, pauseWhileResolving = false) }
                    VersionState.Toggle.ToVideo ->
                        scope.launch { switchToVideo(song, pauseWhileResolving = false) }
                }
            }
        } else {
            null
        }
        return state.isAudioVersion(song) to onToggle
    }

    /**
     * Resolve and apply the catalogue release without replacing the video row
     * up front. That makes the video's title/artwork visible immediately and
     * leaves it in place as the fallback if matching fails.
     *
     * Automatic requests temporarily hold playback because the music-only
     * preference is the version the listener asked to hear. Manual requests
     * preserve the old behaviour and let the video keep playing meanwhile.
     */
    suspend fun switchToMusicOnly(song: Song, pauseWhileResolving: Boolean) =
        switch(song, pauseWhileResolving, towardAudio = true)

    /**
     * Resolve and apply the video version without replacing the audio row
     * up front. This is the inverse of [switchToMusicOnly].
     */
    suspend fun switchToVideo(song: Song, pauseWhileResolving: Boolean) =
        switch(song, pauseWhileResolving, towardAudio = false)

    private suspend fun switch(song: Song, pauseWhileResolving: Boolean, towardAudio: Boolean) {
        val c = controller ?: return
        val index = c.currentMediaItemIndex
        if (index !in 0 until c.mediaItemCount ||
            c.currentMediaItem?.mediaId != song.videoId ||
            state.switching
        ) return

        val kind = if (towardAudio) "audio" else "video"
        val resumeAfterResolution = pauseWhileResolving && c.playWhenReady
        state.beginSwitch(towardAudio)
        // The bar lights up for the resolve and the service keeps it lit
        // through the measure-and-cut, and the row stays on the old version
        // until that swap actually commits — see [VersionState.alignmentPending].
        val holdUntilAligned = AppSettings.smartVersionAlignment.value
        if (holdUntilAligned) AppSettings.versionAlignmentInProgress.value = true
        if (pauseWhileResolving) c.pause()
        try {
            TrackLog.d("Player", "$kind switch requested for '${song.title}'", song.videoId)
            val found = runCatching {
                if (towardAudio) YtMusicRepository.resolveAudio(song) else YtMusicRepository.resolveVideo(song)
            }.getOrNull()
            val stillCurrent = c.currentMediaItemIndex == index &&
                c.currentMediaItem?.mediaId == song.videoId

            // The original MediaItem was never removed, so failure only needs
            // to release the loading state and resume it immediately.
            if (found == null || found.videoId == song.videoId) {
                TrackLog.w(
                    "Player",
                    if (towardAudio) {
                        "audio switch found no distinct official song"
                    } else {
                        "video switch found no distinct video"
                    },
                    song.videoId,
                )
                // The switch is always offered now, so say when there was nothing to switch to.
                state.markNotFound(song.videoId)
                if (stillCurrent) {
                    showNotice(
                        context.getString(if (towardAudio) R.string.no_audio_version else R.string.no_video_version),
                    )
                }
                if (stillCurrent && resumeAfterResolution) c.play()
                return
            }
            if (!stillCurrent) {
                TrackLog.d("Player", "$kind switch discarded; listener changed track", song.videoId)
                return
            }

            TrackLog.d("Player", "$kind switch applying '${found.title}' (${found.videoId})", song.videoId)
            val target = if (towardAudio) {
                state.audioFound(song, found, holdUntilAligned)
            } else {
                state.videoFound(song, found, holdUntilAligned)
            }
            // Let go of the bar *before* the command goes out: the service
            // raises it again the moment its own job starts, and releasing
            // first is what makes the handover correct whichever way that
            // command dispatches — inline or on the next turn of the loop.
            // The same line in the finally is the catch-all for every path
            // that never got this far, where nothing else would release it.
            if (holdUntilAligned) AppSettings.versionAlignmentInProgress.value = false
            c.swapToVersion(target)
        } finally {
            if (holdUntilAligned) AppSettings.versionAlignmentInProgress.value = false
            state.endSwitch()
        }
    }
}
