package com.music.bitchord.ui.screens

import com.music.bitchord.ui.screens.settings.*
import com.music.bitchord.ui.screens.settings.countOfMonths as settingsCountOfMonths

import com.music.bitchord.ui.components.IosMotion
import androidx.compose.animation.animateContentSize
import com.music.bitchord.ui.icons.OpalineIcons
import com.music.bitchord.ui.icons.SfIcons
import com.music.bitchord.ui.components.IosSlider
import com.music.bitchord.wear.WatchInstaller
import android.content.Context
import androidx.annotation.StringRes
import android.content.Intent
import android.media.AudioFormat
import android.media.audiofx.AudioEffect
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.ScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.rounded.Animation
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BlurOff
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.Brightness4
import androidx.compose.material.icons.rounded.Cached
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.FilterAlt
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.Gradient
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.LocalOffer
import androidx.compose.material.icons.rounded.MusicOff
import androidx.compose.material.icons.rounded.MotionPhotosOff
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Draw
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.SurroundSound
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.WebAssetOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material.icons.rounded.Wifi
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import com.music.bitchord.data.lyrics.translationLanguageName
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import com.music.bitchord.ui.components.IosSwitch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChanged
import kotlin.math.abs
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.viewinterop.AndroidView
import com.music.bitchord.AppIcon
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import com.music.bitchord.ui.components.PillSegmentedControl
import com.music.bitchord.ui.components.isGlassSupported
import com.music.bitchord.ui.components.languageDisplayNameRes
import com.music.bitchord.ui.components.MessageState
import com.music.bitchord.ui.components.SearchField
import com.music.bitchord.ui.components.thumbnailBorder
import com.music.bitchord.ui.performance.resolvePerformanceRefreshRate
import com.music.bitchord.ui.performance.supportedPerformanceRefreshRates
import com.music.bitchord.data.model.Account
import com.music.bitchord.data.LocalMediaRepository
import com.music.bitchord.data.NerdStats
import com.music.bitchord.data.scrobbling.LastFM
import com.music.bitchord.data.listentogether.ListenTogether
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.OutputPcmMode
import com.music.bitchord.data.settings.BitPerfectMode
import com.music.bitchord.playback.AudioOutputStatus
import com.music.bitchord.playback.audio.bitperfect.BitPerfectOutput
import com.music.bitchord.data.settings.AutomixPerformanceMode
import com.music.bitchord.R
import com.music.bitchord.data.sources.DeviceCodecs
import com.music.bitchord.data.settings.AudioQuality
import com.music.bitchord.data.settings.DownloadQuality
import com.music.bitchord.data.settings.ThemeMode
import com.music.bitchord.data.settings.PlayerTheme
import com.music.bitchord.data.stats.Backup
import com.music.bitchord.playback.AudioCache
import com.music.bitchord.ui.player.fullBleedArtworkAvailable
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import java.util.Locale

/**
 * Grouped settings, in the shape phones have taught people to expect: inset
 * cards of rows, a leading glyph per row, the current value on the right, and a
 * plain-language footer under any group whose effect isn't obvious from its
 * title. Anything with more than two choices opens a sheet rather than pushing
 * a row of chips into the layout.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    /** The window's width, for the gates that depend on it. */
    windowWidth: Dp,
    signedIn: Boolean,
    account: Account?,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onAccountScrobbling: () -> Unit,
    onEqualizer: () -> Unit,
    onOpenReplay: () -> Unit,
    onLyricsSources: () -> Unit,
    onTranslationLanguage: () -> Unit,
    onSources: () -> Unit,
    onListenTogether: () -> Unit,
    onWearOs: () -> Unit,
    onSpotifyCanvasAuth: () -> Unit,
    onAppLanguage: () -> Unit,
    onEditSignatureApi: () -> Unit,
    /**
     * Asks before [mode] is turned on: it hands volume to the DAC at full
     * scale. Shown in the app's frosted alert, which needs the app's blur.
     */
    onConfirmBitPerfect: (BitPerfectMode) -> Unit,
    /** Asks before a DAC with no settable volume may play at full scale. */
    onConfirmFixedVolume: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    /**
     * The category this page is, or null for the main page — which lists the
     * categories rather than their rows, until something is typed into its
     * search field.
     */
    section: SettingsCategory? = null,
    /** Opens one category as a page of its own. */
    onOpenSection: (SettingsCategory) -> Unit = {},
    /** Opens a choice with more options than its row can show, as a page. */
    onOpenChoice: (SettingsChoiceKind) -> Unit = {},
) {
    val context = LocalContext.current

    val wifiQuality by AppSettings.audioQualityWifi.collectAsStateWithLifecycle()
    val cellularQuality by AppSettings.audioQualityCellular.collectAsStateWithLifecycle()
    val metered by AppSettings.meteredConnection.collectAsStateWithLifecycle()
    val dolbyAtmos by AppSettings.dolbyAtmos.collectAsStateWithLifecycle()
    val directSourceStream by AppSettings.directSourceStream.collectAsStateWithLifecycle()
    val dolbyAtmosSupported = DeviceCodecs.playsDolbyAtmos
    val bitPerfectReady by BitPerfectOutput.ready.collectAsStateWithLifecycle()
    val localMusicFolderUri by AppSettings.localMusicFolderUri.collectAsStateWithLifecycle()

    val party by ListenTogether.state.collectAsStateWithLifecycle()

    // Filters the rows below — see [SettingsSearch]. Blank shows everything,
    // exactly as if the field weren't there.
    // Saveable so a search survives a trip into one of the pages it found.
    var searchQuery by rememberSaveable { mutableStateOf("") }
    // What the last export or import did, shown on the row that did it rather
    // than as a toast: a backup is the one action here whose outcome nobody can
    // check by looking at the app afterwards. Held per direction, or an import's
    // result reports itself under the word "Export".
    var exportStatus by remember { mutableStateOf<String?>(null) }
    var importStatus by remember { mutableStateOf<String?>(null) }
    var confirmImport by remember { mutableStateOf(false) }
    var confirmClearSongCache by remember { mutableStateOf(false) }
    var showPerformanceWarning by remember { mutableStateOf(false) }
    var showPerformanceConfirmation by remember { mutableStateOf(false) }
    val backupScope = rememberCoroutineScope()

    val batterySettingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        showPerformanceConfirmation = true
    }
    val localMusicFolderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { folder ->
        if (folder == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                folder,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        AppSettings.setLocalMusicFolderUri(folder.toString())
    }

    /**
     * Both halves go through the system document picker rather than a path of
     * this app's own choosing. That is what puts the file somewhere the user can
     * actually find it — Drive, Files, a folder they already back up — and it
     * means neither direction needs a storage permission, since the grant
     * arrives with the document they picked.
     */
    val exportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { target ->
        if (target == null) return@rememberLauncherForActivityResult
        backupScope.launch {
            exportStatus = Backup.exportTo(context, target).fold(
                onSuccess = { months ->
                    context.getString(R.string.export_succeeded, context.countOfMonths(months))
                },
                onFailure = {
                    context.getString(R.string.export_failed, it.message ?: context.getString(R.string.unknown_error))
                },
            )
        }
    }
    val importPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { source ->
        if (source == null) return@rememberLauncherForActivityResult
        backupScope.launch {
            importStatus = Backup.importFrom(context, source).fold(
                onSuccess = {
                    context.getString(R.string.import_succeeded, context.countOfMonths(it.months), it.from)
                },
                onFailure = {
                    context.getString(R.string.import_failed, it.message ?: context.getString(R.string.unknown_error))
                },
            )
        }
    }
    var showListenBrainzTokenDialog by remember { mutableStateOf(false) }
    var showLastfmLoginDialog by remember { mutableStateOf(false) }
    val scrobbleScope = rememberCoroutineScope()

    val version = remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "1.0"
    }

    // Made fresh on every pass rather than remembered: it tallies the rows
    // that answered to the query, and that tally has to start from zero each
    // time the query changes.
    //
    // A category page shows all of its rows, so it searches for nothing. The
    // main page searches every category at once: typed into, it lays out the
    // rows that answer from all of them, as the single page Settings once was.
    val search = SettingsSearch(if (section == null) searchQuery else "", inPage = section != null)
    val searching = section == null && searchQuery.isNotBlank()
    fun shows(category: SettingsCategory) = section == category || searching
    val watchAvailable = remember { WatchInstaller.available(context) }

    // What is left after a keystroke is a different list, and the offset the
    // last one was scrolled to means nothing in it.
    val scrollState = rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) }
    var isFirstSearchComposition by remember { mutableStateOf(true) }
    LaunchedEffect(searchQuery) {
        if (isFirstSearchComposition) {
            // Skip the initial composition — on Activity recreation, and on
            // coming back from a page opened here: rememberSaveable already
            // restored the scroll position and the query it belongs to.
            isFirstSearchComposition = false
        } else {
            isFirstSearchComposition = false
            scrollState.scrollTo(0)
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)
            .padding(contentPadding),
    ) {
        SettingsPageTitle(stringResource(section?.title ?: R.string.settings))
        if (section == null) {
            SearchField(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                onSubmit = {},
                placeholder = stringResource(R.string.settings_search_hint),
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 4.dp),
            )

            SearchableSettingsGroup(search) {
                val accountTitle = stringResource(R.string.account_integrations)
                // What the row opens is the scrobbling screen, so the services it
                // signs into are worth typing at this field even though none of
                // them is named on the row itself.
                row(accountTitle, "scrobbling", "last.fm", "listenbrainz") {
                    val avatarUrl = account?.thumbnailUrl?.takeIf { signedIn }
                    SettingsRow(
                        icon = SfIcons.Person,
                        iconContent = avatarUrl?.let { url ->
                            {
                                AsyncImage(
                                    model = url,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.size(ICON_SIZE).clip(CircleShape),
                                )
                            }
                        },
                        title = accountTitle,
                        subtitle = account?.email?.takeIf { it.isNotBlank() }
                            ?: stringResource(if (signedIn) R.string.signed_in else R.string.not_signed_in),
                        onClick = onAccountScrobbling,
                    )
                }
                // Sits with the account rather than with Playback: a party is up to
                // five signed-in people, and being signed in is the whole of what
                // the row needs before it will do anything.
                val listenTogetherTitle = stringResource(R.string.listen_together)
                row(listenTogetherTitle, "jam", "party", "sync", "friends") {
                    SettingsRow(
                        icon = SfIcons.Groups,
                        title = listenTogetherTitle,
                        subtitle = party.code?.let {
                            stringResource(R.string.listen_together_in_party, it)
                        } ?: stringResource(R.string.listen_together_subtitle),
                        badge = party.members.size.takeIf { party.inParty && it > 1 }?.toString(),
                        onClick = onListenTogether,
                    )
                }
            }

            // The row that used to sit at the top of this group was called
            // "Lossless / HQ Audio" and toggled `SourceRegistry.setModuleEnabled` —
            // it switched the *module source* on and off, not lossless. Sources
            // above lists that as the module's own row now. Lossless itself is no
            // longer a setting at all — see
            // [SourceResolver.requestForNow][com.music.bitchord.data.sources.SourceResolver.requestForNow].
            SearchableSettingsGroup(search, header = stringResource(R.string.audio_quality)) {
                val sourceTitle = stringResource(R.string.source)
                val sourceSubtitle = stringResource(R.string.sources_subtitle)
                row(sourceTitle, sourceSubtitle, "addon", "lossless") {
                    SettingsRow(
                        icon = SfIcons.Extension,
                        title = sourceTitle,
                        subtitle = sourceSubtitle,
                        onClick = onSources,
                    )
                }
                val directSourceTitle = stringResource(R.string.direct_source_stream)
                row(directSourceTitle, "addon", "direct", "prefetch", "read-ahead") {
                    // Off for the length of a party: holding every fetch until a
                    // track is about to play would leave this phone behind the
                    // others at each sync — see [DirectSourceGate.isActive].
                    val directSourceOn = directSourceStream && !party.inParty
                    SettingsRow(
                        icon = OpalineIcons.Recording,
                        title = directSourceTitle,
                        subtitle = stringResource(R.string.direct_source_stream_in_party).takeIf { party.inParty },
                        enabled = !party.inParty,
                        trailing = {
                            IosSwitch(
                                checked = directSourceOn,
                                onCheckedChange = AppSettings::setDirectSourceStream,
                                enabled = !party.inParty,
                            )
                        },
                        onClick = { AppSettings.setDirectSourceStream(!directSourceOn) },
                    )
                }

                val onWifiTitle = stringResource(R.string.on_wifi)
                row(onWifiTitle, "wi-fi", "streaming quality") {
                    SettingsRow(
                        icon = SfIcons.Wifi,
                        title = onWifiTitle,
                        badge = stringResource(R.string.in_use).takeIf { metered == false },
                        value = wifiQuality.localizedLabel(),
                        onClick = { onOpenChoice(SettingsChoiceKind.AUDIO_QUALITY_WIFI) },
                    )
                }
                val onMobileDataTitle = stringResource(R.string.on_mobile_data)
                row(onMobileDataTitle, "cellular", "streaming quality") {
                    SettingsRow(
                        icon = SfIcons.SignalCellularAlt,
                        title = onMobileDataTitle,
                        badge = stringResource(R.string.in_use).takeIf { metered == true },
                        value = cellularQuality.localizedLabel(),
                        onClick = { onOpenChoice(SettingsChoiceKind.AUDIO_QUALITY_CELLULAR) },
                    )
                }
                // Sits with the quality ceilings rather than with Playback: it
                // decides which version of a track gets fetched, the same question
                // the two rows above answer, and not how one is played back.
                //
                // Greyed rather than hidden where the device can't decode E-AC-3.
                // A missing row reads as a feature the app doesn't have; a disabled
                // one with a reason under it is the difference between "BitChord
                // has no Atmos" and "this phone has no Dolby decoder", and only the
                // second is true. The stored preference is left untouched either
                // way — see [AppSettings.dolbyAtmos].
                val dolbyAtmosTitle = stringResource(R.string.dolby_atmos)
                row(dolbyAtmosTitle, "surround", "e-ac-3") {
                    BypassedByBitPerfect(bitPerfectReady) {
                        SettingsRow(
                            iconPainter = painterResource(R.drawable.ic_dolby_atmos),
                            title = dolbyAtmosTitle,
                            subtitle = stringResource(
                                if (dolbyAtmosSupported) {
                                    R.string.dolby_atmos_subtitle
                                } else {
                                    R.string.dolby_atmos_unavailable
                                },
                            ),
                            enabled = dolbyAtmosSupported,
                            trailing = {
                                IosSwitch(
                                    checked = dolbyAtmos && dolbyAtmosSupported,
                                    onCheckedChange = AppSettings::setDolbyAtmos,
                                    enabled = dolbyAtmosSupported,
                                )
                            },
                            onClick = { AppSettings.setDolbyAtmos(!dolbyAtmos) },
                        )
                    }
                }
            }

            // The categories, each a page of its own — until there is a query,
            // when their rows answer here instead (below).
            if (!searching) {
                SettingsCategoryList(onOpen = onOpenSection)
                if (watchAvailable) {
                    SettingsGroup {
                        WearOsSettingsRow(onClick = onWearOs)
                    }
                }
            }
        }

        // Its own group rather than rows bolted onto the two above, because a
        // download is not a third kind of connection. The ceilings answer "what
        // does this minute cost"; these answer "what am I keeping, and when may
        // it be fetched" — and those two questions only make sense read
        // together, which is what puts them side by side here.
        if (shows(SettingsCategory.DOWNLOADS)) {
            DownloadsSettingsGroup(search = search, onOpenChoice = onOpenChoice)
        }

        if (shows(SettingsCategory.PLAYBACK)) {
            PlaybackSettingsGroup(
                search = search,
                onOpenChoice = onOpenChoice,
                onEqualizer = onEqualizer,
                onConfirmFixedVolume = onConfirmFixedVolume,
            )
        }

        if (shows(SettingsCategory.LYRICS)) {
            LyricsSettingsGroup(
                search = search,
                onLyricsSources = onLyricsSources,
                onTranslationLanguage = onTranslationLanguage,
            )
        }

        if (shows(SettingsCategory.CONTENT_PREFERENCES)) {
            ContentPreferencesSettingsGroup(search = search)
        }

        if (shows(SettingsCategory.APPEARANCE)) {
            AppearanceSettingsGroup(
                search = search,
                windowWidth = windowWidth,
                onOpenChoice = onOpenChoice,
                onSpotifyCanvasAuth = onSpotifyCanvasAuth,
                onEditSignatureApi = onEditSignatureApi,
            )
        }

        if (shows(SettingsCategory.PERFORMANCE)) {
            PerformanceSettingsGroup(
                search = search,
                onRequestEnablePerformance = { showPerformanceWarning = true },
            )
        }

        if (shows(SettingsCategory.LOCAL_MUSIC)) {
            LocalMusicSettingsGroup(
                search = search,
                localMusicFolderUri = localMusicFolderUri,
                onPickLocalFolder = { localMusicFolderPicker.launch(null) },
            )
        }

        if (shows(SettingsCategory.STORAGE)) {
            StorageSettingsGroup(
                search = search,
                onClearSongCache = { confirmClearSongCache = true },
            )
        }

        // Only in a build that carries the watch app — see app/build.gradle.kts.
        // On the main page it is listed with the categories; here it answers
        // a search.
        if (searching && watchAvailable) {
            SearchableSettingsGroup(search) {
                row(stringResource(R.string.wear_os_section), "watch", "wear os", "galaxy watch", "pixel watch", "install") {
                    WearOsSettingsRow(onClick = onWearOs)
                }
            }
        }

        if (shows(SettingsCategory.YOUR_DATA)) {
            YourDataSettingsGroup(
                search = search,
                exportStatus = exportStatus,
                importStatus = importStatus,
                onOpenReplay = onOpenReplay,
                onExportData = { exportPicker.launch(Backup.suggestedName()) },
                onImportData = { confirmImport = true },
            )
        }

        if (shows(SettingsCategory.MISCELLANEOUS)) {
            MiscellaneousSettingsGroup(search = search)
        }

        if (shows(SettingsCategory.LANGUAGE)) {
            LanguageSettingsGroup(search = search, onAppLanguage = onAppLanguage)
        }

        // Read after every group above has had its turn at the query, which is
        // what makes this an accurate "nothing here" rather than a guess.
        if (searching && !search.anyMatch) {
            MessageState(stringResource(R.string.settings_search_empty, searchQuery))
        }

        // The version line is the page's colophon, not a setting: it belongs to
        // the whole list, so it goes when the list is narrowed to a few rows.
        if (section == null && searchQuery.isBlank()) {
        Text(
            text = buildAnnotatedString {
                append("bitchord $version  ")
                val linkStyles = TextLinkStyles(
                    style = SpanStyle(
                        color = MaterialTheme.colorScheme.primary,
                        textDecoration = TextDecoration.Underline,
                    ),
                )
                withLink(LinkAnnotation.Url("https://github.com/kushagrasinghx/BitChord", linkStyles)) {
                    append("GitHub")
                }
                append("  ")
                withLink(LinkAnnotation.Url("https://github.com/kushagrasinghx", linkStyles)) {
                    append("Developer")
                }
                append("  ")
                withLink(LinkAnnotation.Url("https://discord.gg/pDdKfrdHY6", linkStyles)) {
                    append("Discord")
                }
                append("  ")
                withLink(LinkAnnotation.Url("https://bitchord.kushagrasingh.in/", linkStyles)) {
                    append("Website")
                }
                append("\n~YouTube Music & Listen Together Backend")
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp, bottom = 8.dp),
        )
        }
    }

    if (confirmClearSongCache) {
        ClearSongCacheDialog(onDismiss = { confirmClearSongCache = false })
    }

    if (confirmImport) {
        ImportBackupDialog(
            onDismiss = { confirmImport = false },
            onConfirm = { importPicker.launch(arrayOf("application/json", "text/plain", "*/*")) },
        )
    }

    if (showPerformanceWarning) {
        PerformanceWarningDialog(
            onDismiss = { showPerformanceWarning = false },
            onOpenBatterySettings = {
                showPerformanceWarning = false
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                }
                val settingsIntent = intent.takeIf {
                    it.resolveActivity(context.packageManager) != null
                } ?: Intent(Settings.ACTION_SETTINGS)
                batterySettingsLauncher.launch(settingsIntent)
            },
        )
    }

    if (showPerformanceConfirmation) {
        PerformanceConfirmationDialog(
            onDismiss = { showPerformanceConfirmation = false },
            onConfirm = {
                showPerformanceConfirmation = false
                AppSettings.setHighPerformanceMode(true)
            },
        )
    }

}

// ---- Re-exports for backwards compatibility ---------------------------------

internal const val CACHE_WARNING_MB = com.music.bitchord.ui.screens.settings.CACHE_WARNING_MB
internal const val CACHE_UNLIMITED_STOP_MB = com.music.bitchord.ui.screens.settings.CACHE_UNLIMITED_STOP_MB

internal val GroupShape = com.music.bitchord.ui.screens.settings.GroupShape
internal val GROUP_INSET = com.music.bitchord.ui.screens.settings.GROUP_INSET
internal val ROW_INSET = com.music.bitchord.ui.screens.settings.ROW_INSET
internal val ICON_SIZE = com.music.bitchord.ui.screens.settings.ICON_SIZE
internal val ICON_GAP = com.music.bitchord.ui.screens.settings.ICON_GAP
internal val TEXT_INSET = com.music.bitchord.ui.screens.settings.TEXT_INSET

typealias SettingsCategory = com.music.bitchord.ui.screens.settings.SettingsCategory
typealias SettingsChoiceKind = com.music.bitchord.ui.screens.settings.SettingsChoiceKind

@Composable
internal fun SettingsPageTitle(text: String) = com.music.bitchord.ui.screens.settings.SettingsPageTitle(text)

@Composable
internal fun SettingsGroup(
    header: String? = null,
    footer: String? = null,
    topSpacing: Dp = 26.dp,
    content: @Composable () -> Unit,
) = com.music.bitchord.ui.screens.settings.SettingsGroup(header, footer, topSpacing, content)

@Composable
internal fun RowDivider() = com.music.bitchord.ui.screens.settings.RowDivider()

@Composable
internal fun SettingsRow(
    icon: ImageVector? = null,
    title: String,
    subtitle: String? = null,
    subtitleContent: (@Composable () -> Unit)? = null,
    value: String? = null,
    badge: String? = null,
    enabled: Boolean = true,
    iconPainter: Painter? = null,
    iconContent: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) = com.music.bitchord.ui.screens.settings.SettingsRow(
    icon, title, subtitle, subtitleContent, value, badge, enabled, iconPainter, iconContent, onClick, trailing
)

@Composable
internal fun SettingsSubRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    badge: String? = null,
    subtitle: String? = null,
    enabled: Boolean = true,
) = com.music.bitchord.ui.screens.settings.SettingsSubRow(
    title, checked, onCheckedChange, badge, subtitle, enabled
)

@Composable
internal fun Badge(text: String, modifier: Modifier = Modifier) =
    com.music.bitchord.ui.screens.settings.Badge(text, modifier)

@Composable
internal fun Chevron() = com.music.bitchord.ui.screens.settings.Chevron()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SliderRow(
    icon: ImageVector,
    title: String,
    value: String,
    sliderValue: Float,
    onSliderValue: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    subtitle: String? = null,
    subtitleMinLines: Int = 1,
) = com.music.bitchord.ui.screens.settings.SliderRow(
    icon, title, value, sliderValue, onSliderValue, valueRange, steps, subtitle, subtitleMinLines
)

@Composable
internal fun DestructiveRow(
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) = com.music.bitchord.ui.screens.settings.DestructiveRow(label, enabled, onClick)

@Composable
internal fun SegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) = com.music.bitchord.ui.screens.settings.SegmentedControl(options, selectedIndex, onSelect, modifier, enabled)

@Composable
internal fun <T> ChoiceGroup(
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    footer: String?,
    detail: (@Composable (T) -> String)? = null,
    enabled: (T) -> Boolean = { true },
    onSelect: (T) -> Unit,
) = com.music.bitchord.ui.screens.settings.ChoiceGroup(options, selected, label, footer, detail, enabled, onSelect)

@Composable
internal fun AccountCard(
    signedIn: Boolean,
    account: Account?,
    onSignIn: () -> Unit,
    onClick: (() -> Unit)? = null,
    grouped: Boolean = false,
) = com.music.bitchord.ui.screens.settings.AccountCard(signedIn, account, onSignIn, onClick, grouped)

@Composable
internal fun BypassedByBitPerfect(bypassed: Boolean, content: @Composable () -> Unit) =
    com.music.bitchord.ui.screens.settings.BypassedByBitPerfect(bypassed, content)

internal fun openEqualizer(context: Context, sessionId: Int) = com.music.bitchord.ui.screens.settings.openEqualizer(context, sessionId)
internal fun formatCacheSize(mb: Int): String = com.music.bitchord.ui.screens.settings.formatCacheSize(mb)
internal fun Context.countOfMonths(months: Int): String = this.settingsCountOfMonths(months)

@Composable
fun SettingsChoicePage(
    choice: SettingsChoiceKind,
    onChosen: () -> Unit,
    onConfirmBitPerfect: (BitPerfectMode) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) = com.music.bitchord.ui.screens.settings.SettingsChoicePage(
    choice = choice,
    onChosen = onChosen,
    onConfirmBitPerfect = onConfirmBitPerfect,
    contentPadding = contentPadding,
    modifier = modifier,
)
