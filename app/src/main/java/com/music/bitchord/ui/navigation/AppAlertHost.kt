package com.music.bitchord.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.BitPerfectMode
import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.data.webdav.WebDavUploads
import com.music.bitchord.ui.AddonBlockedAlert
import com.music.bitchord.ui.components.ChoiceAlert
import com.music.bitchord.ui.components.ConfirmationAlert
import com.music.bitchord.ui.components.DacVolumeHud
import com.music.bitchord.ui.components.InfoAlert
import com.music.bitchord.ui.components.IosModalContainer
import com.music.bitchord.ui.components.WebDavConflictAlert
import com.music.bitchord.ui.screens.DiscordDialog
import com.music.bitchord.ui.screens.DiscordDialogHost
import com.music.bitchord.ui.screens.PartyServerEditor
import com.music.bitchord.ui.screens.SignatureApiEditor
import com.music.bitchord.ui.screens.SourceEditorAlert
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.CoroutineScope

/**
 * The root-level alerts that settings pages, search rows and downloads ask for.
 *
 * They live at the app root rather than on the pages that open them for two
 * reasons: each is a haze card that has to sample a backdrop it is *not* drawn
 * inside, and each scrim has to cover the tab bar and the mini player, which
 * are drawn after the page. One slot per alert; null / false means shut.
 */
@Stable
class AppAlertState {
    var discordDialog by mutableStateOf<DiscordDialog?>(null)
    var editingPartyServer by mutableStateOf(false)
    var editingSignatureApi by mutableStateOf(false)
    var editingSource by mutableStateOf<SourceConfig?>(null)
    var choosingSourceQuality by mutableStateOf<SourceConfig?>(null)

    /** An addon whose "Outdated addon" line was tapped; see [SourceConfig.needsAddonUpdate]. */
    var explainingOutdatedAddon by mutableStateOf<SourceConfig?>(null)
    var confirmJioSaavn by mutableStateOf(false)
    var confirmBitPerfect by mutableStateOf<BitPerfectMode?>(null)
    var confirmFixedVolume by mutableStateOf(false)

    /** An addon search row tapped while the quality setting skips its addon; see [AddonBlockedAlert]. */
    var blockedAddonSong by mutableStateOf<Song?>(null)

    /** An addon a download just went ahead without, and why; see [SourceRegistry.addonsLeftOutOfDownloads]. */
    var addonLeftOutOfDownload by mutableStateOf<Pair<String, SourceRegistry.DownloadExclusion>?>(null)
}

/**
 * Draws every alert in [AppAlertState], plus the DAC volume HUD between them —
 * over the page and its frosted bars, under the download and blocked-addon
 * notices, exactly as they stacked when they were inline in the activity.
 *
 * [downloadNoticeInOwnWindow] is whether a window of its own (the player, a
 * track or collection menu) is up over the app: a download can be asked for
 * from any of them, and drawn here the notice would open behind it.
 */
@Composable
fun AppAlertHost(
    state: AppAlertState,
    hazeState: HazeState,
    scope: CoroutineScope,
    downloadNoticeInOwnWindow: Boolean,
) {
    IosModalContainer(
        item = state.discordDialog,
        onDismiss = { state.discordDialog = null },
    ) { which ->
        DiscordDialogHost(
            which = which,
            hazeState = hazeState,
            onDismiss = { state.discordDialog = null },
        )
    }

    IosModalContainer(
        visible = state.editingPartyServer,
        onDismiss = { state.editingPartyServer = false },
    ) {
        PartyServerEditor(
            hazeState = hazeState,
            onDismiss = { state.editingPartyServer = false },
        )
    }

    IosModalContainer(
        visible = state.editingSignatureApi,
        onDismiss = { state.editingSignatureApi = false },
    ) {
        SignatureApiEditor(
            hazeState = hazeState,
            onDismiss = { state.editingSignatureApi = false },
        )
    }

    IosModalContainer(
        item = state.editingSource,
        onDismiss = { state.editingSource = null },
    ) { config ->
        SourceEditorAlert(
            hazeState = hazeState,
            config = config,
            onDismiss = { state.editingSource = null },
            onSaved = { state.editingSource = null },
            onDelete = {
                SourceRegistry.remove(config.id)
                state.editingSource = null
            },
            scope = scope,
        )
    }

    IosModalContainer(
        item = state.choosingSourceQuality,
        onDismiss = { state.choosingSourceQuality = null },
    ) { config ->
        ChoiceAlert(
            hazeState = hazeState,
            title = stringResource(R.string.audio_quality),
            message = null,
            options = config.qualityOptions,
            selected = config.activeQuality,
            label = { it?.label.orEmpty() },
            onSelect = { option ->
                // Off the stored config, not the one this list opened with:
                // a probe may have refreshed it since.
                SourceRegistry.config(config.id)?.let {
                    SourceRegistry.update(it.copy(pickedQuality = option?.value))
                }
                state.choosingSourceQuality = null
            },
            onDismiss = { state.choosingSourceQuality = null },
        )
    }

    IosModalContainer(
        item = state.explainingOutdatedAddon,
        onDismiss = { state.explainingOutdatedAddon = null },
    ) { config ->
        InfoAlert(
            hazeState = hazeState,
            title = config.displayName,
            description = stringResource(R.string.source_needs_update),
            onDismiss = { state.explainingOutdatedAddon = null },
        )
    }

    IosModalContainer(
        visible = state.confirmJioSaavn,
        onDismiss = { state.confirmJioSaavn = false },
    ) {
        ConfirmationAlert(
            hazeState = hazeState,
            title = stringResource(R.string.enable_jiosaavn),
            description = stringResource(R.string.jiosaavn_mismatch_warning),
            confirmLabel = stringResource(R.string.enable_anyway),
            onConfirm = {
                SourceRegistry.configs.value
                    .firstOrNull { it.kind == SourceKind.JIOSAAVN }
                    ?.let { SourceRegistry.setEnabled(it.id, true) }
                state.confirmJioSaavn = false
            },
            onDismiss = { state.confirmJioSaavn = false },
        )
    }

    IosModalContainer(
        item = state.confirmBitPerfect,
        onDismiss = { state.confirmBitPerfect = null },
    ) { mode ->
        ConfirmationAlert(
            hazeState = hazeState,
            title = stringResource(R.string.bit_perfect_confirm_title),
            description = stringResource(R.string.bit_perfect_confirm_message),
            confirmLabel = stringResource(R.string.enable),
            onConfirm = {
                AppSettings.setBitPerfectMode(mode)
                state.confirmBitPerfect = null
            },
            onDismiss = { state.confirmBitPerfect = null },
        )
    }

    IosModalContainer(
        visible = state.confirmFixedVolume,
        onDismiss = { state.confirmFixedVolume = false },
    ) {
        ConfirmationAlert(
            hazeState = hazeState,
            title = stringResource(R.string.usb_allow_fixed_volume_confirm_title),
            description = stringResource(R.string.usb_allow_fixed_volume_confirm_message),
            confirmLabel = stringResource(R.string.enable),
            onConfirm = {
                AppSettings.setUsbAllowFixedVolume(true)
                state.confirmFixedVolume = false
            },
            onDismiss = { state.confirmFixedVolume = false },
        )
    }

    // Over the page and its frosted bars, under nothing but the notices below.
    DacVolumeHud(hazeState)

    IosModalContainer(
        item = state.blockedAddonSong,
        onDismiss = { state.blockedAddonSong = null },
    ) { song ->
        AddonBlockedAlert(
            hazeState = hazeState,
            song = song,
            onDismiss = { state.blockedAddonSong = null },
        )
    }

    IosModalContainer(
        item = state.addonLeftOutOfDownload,
        onDismiss = { state.addonLeftOutOfDownload = null },
    ) { (addon, why) ->
        InfoAlert(
            hazeState = hazeState,
            title = stringResource(R.string.download_addon_left_out_title, addon),
            description = when (why) {
                SourceRegistry.DownloadExclusion.DOWNLOADS_NOT_ALLOWED ->
                    stringResource(R.string.download_addon_not_allowed, addon)
                SourceRegistry.DownloadExclusion.NEEDS_LOSSLESS_OUTPUT ->
                    stringResource(R.string.download_addon_needs_lossless_output, addon)
            },
            onDismiss = { state.addonLeftOutOfDownload = null },
            inOwnWindow = downloadNoticeInOwnWindow,
        )
    }
}

/**
 * A clash mid-upload to WebDAV, answered at the root so the scrim covers the
 * tab bar and mini player like every other alert. Backing out is a skip —
 * leaving the batch suspended on a dismissed dialog would hang the upload with
 * no way to reach the question again.
 */
@Composable
fun WebDavConflictHost(hazeState: HazeState) {
    val uploadConflict by WebDavUploads.conflict.collectAsStateWithLifecycle()
    IosModalContainer(
        item = uploadConflict,
        onDismiss = {
            uploadConflict?.answer?.complete(
                WebDavUploads.Resolution(WebDavUploads.Choice.SKIP, false),
            )
        },
    ) { req ->
        var applyToAll by remember(req) { mutableStateOf(false) }
        val answer: (WebDavUploads.Choice) -> Unit = { choice ->
            req.answer.complete(WebDavUploads.Resolution(choice, applyToAll))
        }
        WebDavConflictAlert(
            hazeState = hazeState,
            fileName = req.fileName,
            showApplyToAll = req.remaining > 0,
            applyToAll = applyToAll,
            onApplyToAllChange = { applyToAll = it },
            onOverwrite = { answer(WebDavUploads.Choice.OVERWRITE) },
            onKeepBoth = { answer(WebDavUploads.Choice.KEEP_BOTH) },
            onSkip = { answer(WebDavUploads.Choice.SKIP) },
            onDismiss = { answer(WebDavUploads.Choice.SKIP) },
            animatedScope = this,
        )
    }
}
