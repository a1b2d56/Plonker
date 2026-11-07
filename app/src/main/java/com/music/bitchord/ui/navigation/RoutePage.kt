package com.music.bitchord.ui.navigation

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import com.music.bitchord.R
import com.music.bitchord.auth.WebSessionMode
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.Account
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.PlaybackSourceType
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.TopPickMood
import com.music.bitchord.data.model.UiState
import com.music.bitchord.data.spotify.SPOTIFY_PAGE_PREFIX
import com.music.bitchord.data.stats.ReplayPeriod
import com.music.bitchord.playback.PlayerState
import com.music.bitchord.playback.QueueSource
import com.music.bitchord.ui.MainViewModel
import com.music.bitchord.ui.components.DownloadManagerPage
import com.music.bitchord.ui.replay.ReplayScreen
import com.music.bitchord.ui.replay.ReplayState
import com.music.bitchord.ui.replay.ReplayStoryPage
import com.music.bitchord.ui.screens.AccountAndScrobblingScreen
import com.music.bitchord.ui.screens.DiscordScreen
import com.music.bitchord.ui.screens.EqualizerPresetPage
import com.music.bitchord.ui.screens.EqualizerScreen
import com.music.bitchord.ui.screens.HistoryScreen
import com.music.bitchord.ui.screens.LibraryGridPage
import com.music.bitchord.ui.screens.ListenTogetherScreen
import com.music.bitchord.ui.screens.MoodGridPage
import com.music.bitchord.ui.screens.SettingsChoicePage
import com.music.bitchord.ui.screens.SettingsScreen
import com.music.bitchord.ui.screens.SourcesScreen
import com.music.bitchord.ui.screens.SpotifyLibraryScreen
import com.music.bitchord.ui.screens.WearOsScreen

/**
 * The sheets and full-screen sign-ins the pushed pages ask for, which are drawn
 * at the app root (over the tab bar and mini player) rather than on the page.
 * One flag each; [webSession] is what the in-app browser is open for, or null.
 */
@Stable
class AppOverlayState {
    var webSession by mutableStateOf<WebSessionMode?>(null)
    var showSpotifyCanvasAuth by mutableStateOf(false)
    var showLyricsSources by mutableStateOf(false)
    var showAppLanguage by mutableStateOf(false)
    var showTranslationLanguage by mutableStateOf(false)
    var showAccountSelector by mutableStateOf(false)
    var showListenBrainzLogin by mutableStateOf(false)
    var showLastfmLogin by mutableStateOf(false)
    var showWebDavEditor by mutableStateOf(false)
    var showSmbEditor by mutableStateOf(false)
    var showDiscordLogin by mutableStateOf(false)
    var showSpotifyLogin by mutableStateOf(false)
}

/** What the pushed pages reach that lives with the app: playback, menus, Replay. */
class RouteActions(
    val playFrom: (List<Song>, Int, QueueSource) -> Unit,
    val openSongMenu: (Song) -> Unit,
    /** The track menu, marked as opened from History so it offers "Remove from history". */
    val openSongMenuFromHistory: (Song) -> Unit,
    val onSongSwipe: (Song) -> Unit,
    val onLibraryItemClick: (ShelfItem) -> Unit,
    val onBrowseLongPress: (ShelfItem) -> Unit,
    val playMood: (TopPickMood, String) -> Unit,
    val playRadio: (Song, QueueSource) -> Unit,
    /** Browse id (if known), name, subtitle, type, artwork. */
    val openByName: (String?, String, String?, BrowseType, String?) -> Unit,
    val setReplayPeriod: (ReplayPeriod) -> Unit,
    val openReplayStory: (ReplayStoryPage) -> Unit,
    /** The share sheet for the whole Replay. */
    val shareReplay: () -> Unit,
    /** Replay from its first page — the Settings row's way in. */
    val openReplay: () -> Unit,
    val createPlaylist: () -> Unit,
    /** A Jam invite has been taken up by the Listen Together page. */
    val inviteHandled: () -> Unit,
)

/** Whether [route] is one [RoutePage] draws — the rest are release pages or tabs. */
fun handlesRoute(route: AppRoute?): Boolean = when (route) {
    AppRoute.History, is AppRoute.LibraryShowAll, AppRoute.Moods, AppRoute.Replay,
    AppRoute.Spotify, AppRoute.Discord, AppRoute.AccountScrobbling, AppRoute.Sources,
    AppRoute.Downloads, AppRoute.WearOs, AppRoute.ListenTogether, AppRoute.Equalizer,
    AppRoute.EqualizerPresets, is AppRoute.SettingsChoice, AppRoute.Settings,
    is AppRoute.SettingsSection -> true
    else -> false
}

/**
 * The pages pushed over the tabs: History, Replay, the settings tree and its
 * sub-pages, and the account and service pages. Only call it for a route
 * [handlesRoute] accepts.
 */
@Composable
fun RoutePage(
    pageRoute: AppRoute,
    viewModel: MainViewModel,
    navigator: AppNavigator,
    alerts: AppAlertState,
    overlays: AppOverlayState,
    actions: RouteActions,
    player: PlayerState,
    historyState: UiState<List<Song>>,
    historyListState: LazyListState,
    libraryShowAllGridState: LazyGridState,
    moodsGridState: LazyGridState,
    replay: ReplayState,
    replayListState: LazyListState,
    replayLandingPage: ReplayStoryPage,
    account: Account?,
    selectedChannelName: String?,
    signedIn: Boolean,
    activeJamInviteCode: String?,
    activeJamInviteServer: String?,
    windowWidth: Dp,
    listPadding: PaddingValues,
) {
    val context = LocalContext.current
    val historyLabel = stringResource(R.string.history)
    val replayLabel = stringResource(R.string.replay)
    // Inside these, the page callbacks below read exactly as they did when
    // they were written inline in the activity: `showSmbEditor = true` is the
    // overlay's flag, `editingSource = it` the alert's slot, `playFrom` the
    // app's.
    with(alerts) { with(overlays) { with(actions) {
        if (pageRoute == AppRoute.History) {
            HistoryScreen(
                state = historyState,
                listState = historyListState,
                currentSong = player.song,
                isPlaying = player.isPlaying,
                onSongClick = { songs, index ->
                    playFrom(songs, index, QueueSource(historyLabel, PlaybackSourceType.HISTORY))
                },
                onSongLongPress = { song ->
                    openSongMenuFromHistory(song)
                },
                onSongSwipe = onSongSwipe,
                onRetry = viewModel::loadHistory,
                contentPadding = listPadding,
            )
        } else if (pageRoute is AppRoute.LibraryShowAll) {
            pageRoute.shelf.let { shelf ->
                LibraryGridPage(
                    shelf = shelf,
                    gridState = libraryShowAllGridState,
                    onItemClick = onLibraryItemClick,
                    onItemLongPress = onBrowseLongPress,
                    // Only the Playlists shelf can grow one — see
                    // [PlaylistShelf].
                    onNewPlaylist = if (shelf.title == YtMusicRepository.PLAYLISTS_SHELF) {
                        createPlaylist
                    } else {
                        null
                    },
                    contentPadding = listPadding,
                )
            }
        } else if (pageRoute == AppRoute.Moods) {
            MoodGridPage(
                gridState = moodsGridState,
                onClick = playMood,
                contentPadding = listPadding,
            )
        } else if (pageRoute == AppRoute.Replay) {
            ReplayScreen(
                state = replay,
                holder = account?.name.orEmpty(),
                onPeriodChange = setReplayPeriod,
                onOpenStory = openReplayStory,
                // A track tapped on a chart is one the user already
                // knows they like, so it starts a station off itself
                // rather than queueing the chart it was on — the
                // same reading [playRadio] makes of a search hit.
                onPlaySong = { song ->
                    playRadio(song, QueueSource(replayLabel, PlaybackSourceType.REPLAY))
                },
                // Pushed over Replay, so back returns to it.
                onOpenArtist = { id, name ->
                    openByName(id, name, null, BrowseType.ARTIST, null)
                },
                onOpenAlbum = { id, title, artist, art ->
                    openByName(id, title, artist, BrowseType.ALBUM, art)
                },
                onShare = shareReplay,
                contentPadding = listPadding,
                listState = replayListState,
                landingPage = replayLandingPage,
            )
        } else if (pageRoute == AppRoute.Spotify) {
            SpotifyLibraryScreen(
                onOpenPlaylist = { playlist ->
                    viewModel.openDetail(
                        browseId = SPOTIFY_PAGE_PREFIX + playlist.id,
                        title = playlist.name,
                        subtitle = playlist.owner ?: context.getString(R.string.spotify),
                        thumbnailUrl = playlist.imageUrl,
                        type = BrowseType.PLAYLIST,
                    )
                },
                onOpenLogin = { showSpotifyLogin = true },
                onOpenTokenSetup = { showSpotifyCanvasAuth = true },
                contentPadding = listPadding,
            )
        } else if (pageRoute == AppRoute.Discord) {
            DiscordScreen(
                song = player.song,
                positionMs = player.position.positionMs,
                durationMs = player.durationMs,
                onOpenLogin = { showDiscordLogin = true },
                onOpenDialog = { discordDialog = it },
                contentPadding = listPadding,
            )
        } else if (pageRoute == AppRoute.AccountScrobbling) {
            AccountAndScrobblingScreen(
                signedIn = signedIn,
                account = account,
                channelName = selectedChannelName,
                onSignIn = {
                    navigator.clear()
                    webSession = WebSessionMode.SIGN_IN
                },
                onSwitchChannel = {
                    viewModel.loadChannels()
                    showAccountSelector = true
                },
                onSignOut = { viewModel.signOut() },
                onOpenListenBrainzLogin = { showListenBrainzLogin = true },
                onOpenLastfmLogin = { showLastfmLogin = true },
                onOpenDiscord = { navigator.push(AppRoute.Discord) },
                onOpenSpotify = { navigator.push(AppRoute.Spotify) },
                contentPadding = listPadding,
            )
        } else if (pageRoute == AppRoute.Sources) {
            SourcesScreen(
                contentPadding = listPadding,
                onEditSource = { editingSource = it },
                onChooseSourceQuality = { choosingSourceQuality = it },
                onExplainOutdatedAddon = { explainingOutdatedAddon = it },
                onEditWebDav = { showWebDavEditor = true },
                onEditSmb = { showSmbEditor = true },
                onConfirmJioSaavn = { confirmJioSaavn = true },
            )
        } else if (pageRoute == AppRoute.Downloads) {
            DownloadManagerPage(
                onCleared = { navigator.pop() },
                contentPadding = listPadding,
            )
        } else if (pageRoute == AppRoute.WearOs) {
            WearOsScreen(contentPadding = listPadding)
        } else if (pageRoute == AppRoute.ListenTogether) {
            ListenTogetherScreen(
                signedIn = signedIn,
                inviteCode = activeJamInviteCode,
                inviteServer = activeJamInviteServer,
                onInviteHandled = inviteHandled,
                onSignIn = {
                    navigator.clear()
                    webSession = WebSessionMode.SIGN_IN
                },
                contentPadding = listPadding,
                onEditServer = { editingPartyServer = true },
            )
        } else if (pageRoute == AppRoute.Equalizer) {
            EqualizerScreen(
                contentPadding = listPadding,
                onOpenPresets = { navigator.push(AppRoute.EqualizerPresets) },
            )
        } else if (pageRoute == AppRoute.EqualizerPresets) {
            EqualizerPresetPage(
                onChosen = { navigator.pop() },
                contentPadding = listPadding,
            )
        } else if (pageRoute is AppRoute.SettingsChoice) {
            SettingsChoicePage(
                choice = pageRoute.choice,
                onChosen = { navigator.pop() },
                onConfirmBitPerfect = { confirmBitPerfect = it },
                contentPadding = listPadding,
            )
        } else if (pageRoute == AppRoute.Settings || pageRoute is AppRoute.SettingsSection) {
            // The main page and every category's page are the
            // one screen, each showing its own share of the rows.
            SettingsScreen(
                section = (pageRoute as? AppRoute.SettingsSection)?.category,
                onOpenSection = { navigator.push(AppRoute.SettingsSection(it)) },
                onOpenChoice = { navigator.push(AppRoute.SettingsChoice(it)) },
                windowWidth = windowWidth,
                signedIn = signedIn,
                account = account,
                onSignIn = {
                    navigator.clear()
                    webSession = WebSessionMode.SIGN_IN
                },
                onSignOut = { viewModel.signOut() },
                onAccountScrobbling = { navigator.push(AppRoute.AccountScrobbling) },
                onEqualizer = { navigator.push(AppRoute.Equalizer) },
                onOpenReplay = openReplay,
                onLyricsSources = { showLyricsSources = true },
                onTranslationLanguage = { showTranslationLanguage = true },
                onSources = { navigator.push(AppRoute.Sources) },
                onListenTogether = { navigator.push(AppRoute.ListenTogether) },
                onWearOs = { navigator.push(AppRoute.WearOs) },
                onSpotifyCanvasAuth = { showSpotifyCanvasAuth = true },
                onAppLanguage = { showAppLanguage = true },
                onEditSignatureApi = { editingSignatureApi = true },
                onConfirmBitPerfect = { confirmBitPerfect = it },
                onConfirmFixedVolume = { confirmFixedVolume = true },
                contentPadding = listPadding,
            )
        }
    } } }
}
