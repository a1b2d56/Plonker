package com.music.bitchord.ui.player.sheets

import com.music.bitchord.ui.player.lyrics.LyricsShareRequest
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.music.bitchord.data.lyrics.LyricsSource
import com.music.bitchord.sharedui.resources.*
import com.music.bitchord.ui.LyricsProviderState
import com.music.bitchord.ui.player.*
import com.music.bitchord.ui.player.sheets.AudioOutputSheet
import com.music.bitchord.ui.player.sheets.ListenTogetherMembersSheet
import com.music.bitchord.ui.player.sheets.LyricsOffsetSheet
import com.music.bitchord.ui.player.sheets.LyricsProviderSheet
import dev.chrisbanes.haze.HazeState

/**
 * Which of the player's drawers and dialogs are up. One holder rather than a
 * flag each in the screen, so the screen and [PlayerOverlayHost] agree on
 * them by construction.
 */
@Stable
internal class PlayerOverlays {
    var audioPipeline by mutableStateOf(false)
    var cast by mutableStateOf(false)
    var audioOutput by mutableStateOf(false)
    var videoQuality by mutableStateOf(false)
    var lyricsProviders by mutableStateOf(false)
    var listenTogetherMembers by mutableStateOf(false)
}

/**
 * The drawers and dialogs the player raises over itself: video quality, the
 * audio output picker and what it leads to (the pipeline, casting), the
 * lyrics provider and offset sheets, the Listen Together members, a lyric
 * share, and the mix view. Mounted beside whichever layout is on screen, by
 * [FrostedPlayerFrame] — they are overlays over the player, not part of any
 * shape of it.
 */
@Composable
internal fun PlayerOverlayHost(
    overlays: PlayerOverlays,
    hazeState: HazeState,
    accountName: String?,
    isPlaying: Boolean,
    lyricsSource: LyricsSource?,
    lyricsProviderStates: Map<LyricsSource, LyricsProviderState>,
    onSelectLyricsProvider: (LyricsSource) -> Unit,
    /** From the members sheet's "manage" row: the Listen Together screen itself. */
    onManageListenTogether: () -> Unit,
    lyricsOffsetOpen: Boolean,
    onDismissLyricsOffset: () -> Unit,
    lyricsShare: LyricsShareRequest?,
    onDismissLyricsShare: () -> Unit,
) {
    if (overlays.videoQuality) {
        VideoQualitySheet(
            hazeState = hazeState,
            onDismiss = { overlays.videoQuality = false },
        )
    }
    if (overlays.audioOutput) {
        AudioOutputSheet(
            hazeState = hazeState,
            accountName = accountName,
            onDismiss = { overlays.audioOutput = false },
            onOpenPipeline = { overlays.audioPipeline = true },
            onOpenCast = { overlays.cast = true },
            onListenTogether = {
                overlays.audioOutput = false
                onManageListenTogether()
            },
            onOpenListenTogetherMembers = {
                overlays.audioOutput = false
                overlays.listenTogetherMembers = true
            },
        )
    }
    if (overlays.lyricsProviders) {
        LyricsProviderSheet(
            hazeState = hazeState,
            currentSource = lyricsSource,
            states = lyricsProviderStates,
            onSelect = onSelectLyricsProvider,
            onDismiss = { overlays.lyricsProviders = false },
        )
    }
    if (overlays.audioPipeline) {
        AudioPipelineDialog(
            hazeState = hazeState,
            isPlaying = isPlaying,
            onDismiss = { overlays.audioPipeline = false },
        )
    }
    if (overlays.cast) {
        PlayerPlatform.host.CastDialog(
            hazeState = hazeState,
            onDismiss = { overlays.cast = false },
        )
    }
    if (overlays.listenTogetherMembers) {
        ListenTogetherMembersSheet(
            hazeState = hazeState,
            onDismiss = { overlays.listenTogetherMembers = false },
            onManage = {
                overlays.listenTogetherMembers = false
                onManageListenTogether()
            },
        )
    }
    if (lyricsOffsetOpen) {
        LyricsOffsetSheet(
            hazeState = hazeState,
            onDismiss = onDismissLyricsOffset,
        )
    }
    lyricsShare?.let { request ->
        PlayerPlatform.host.LyricsShareSheet(
            hazeState = hazeState,
            request = request,
            onDismiss = onDismissLyricsShare,
        )
    }
    MixViewOverlay(hazeState = hazeState)
}
