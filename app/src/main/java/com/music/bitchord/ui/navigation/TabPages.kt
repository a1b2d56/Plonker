@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.music.bitchord.ui.navigation

import com.music.bitchord.ui.components.RecognitionSheet
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.auth.WebSessionMode
import com.music.bitchord.data.innertube.InnertubeParser
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.EntityType
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.PlaybackSourceType
import com.music.bitchord.data.model.SearchHistoryEntity
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.TopPick
import com.music.bitchord.data.model.TopPickMood
import com.music.bitchord.data.model.UiState
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.AudioQuality
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.download.SavedCollection
import com.music.bitchord.playback.PlayerState
import com.music.bitchord.playback.QueueSource
import com.music.bitchord.ui.MainViewModel
import com.music.bitchord.ui.SearchSource
import com.music.bitchord.ui.components.BrowseTarget
import com.music.bitchord.ui.components.topBarHeight
import com.music.bitchord.ui.replay.ReplayHeroCard
import com.music.bitchord.ui.replay.ReplayState
import com.music.bitchord.ui.replay.ReplayStoryPage
import com.music.bitchord.ui.screens.ExploreScreen
import com.music.bitchord.ui.screens.HomeScreen
import com.music.bitchord.ui.screens.LibraryReplayEntry
import com.music.bitchord.ui.screens.LibraryScreen
import com.music.bitchord.ui.screens.MoodGenrePlaylistsScreen
import com.music.bitchord.ui.screens.SearchScreen
import com.music.bitchord.ui.screens.SegmentedControl
import com.music.bitchord.ui.screens.libraryDeviceItems
import com.music.bitchord.ui.screens.libraryLinks

/*
 * The four tab pages. Each collects the view-model and settings flows only it
 * draws, so a search result arriving or a Home shelf loading recomposes that
 * tab rather than the whole app around it. What the app hoists — list and pull
 * states, so a tab keeps its place — and what it derives comes in as values.
 *
 * The bodies run inside with(alerts/overlays/actions), so their callbacks read
 * as they did inline in the activity: `webSession = …` is the overlay's,
 * `blockedAddonSong = …` the alert's, `playRadio(…)` the app's.
 */

/** What the tabs reach that lives with the app: playback, menus, sheets. */
class TabActions(
    val shelfSong: (ShelfItem) -> Song?,
    val playRadio: (Song, QueueSource) -> Unit,
    val playFrom: (List<Song>, Int, QueueSource) -> Unit,
    val onShelfLongPress: (ShelfItem) -> Unit,
    val openTopPick: (TopPick, String) -> Unit,
    val playMood: (TopPickMood, String) -> Unit,
    val openSongMenu: (Song) -> Unit,
    val onSongSwipe: (Song) -> Unit,
    val openBrowseMenu: (BrowseTarget) -> Unit,
    val downloadIdFor: (String?) -> String?,
    val onSearchSourceChange: (SearchSource) -> Unit,
    val onLibraryItemClick: (ShelfItem) -> Unit,
    val onBrowseLongPress: (ShelfItem) -> Unit,
    val searchFocusHandled: () -> Unit,
    /** The YouTube playlist picker, for [Song]. */
    val pickPlaylistFor: (Song) -> Unit,
    val createPlaylist: () -> Unit,
    val importSpotifyPlaylist: () -> Unit,
    /** Replay, opened at the page a Library card stands for. */
    val openReplayAt: (ReplayStoryPage) -> Unit,
)

@Composable
fun HomeTab(
    viewModel: MainViewModel,
    navigator: AppNavigator,
    alerts: AppAlertState,
    overlays: AppOverlayState,
    actions: TabActions,
    visibleHomeState: UiState<List<HomeShelf>>,
    homeListState: LazyListState,
    homePull: PullToRefreshState,
    player: PlayerState,
    signedIn: Boolean,
    artworkMotion: Boolean,
    listPadding: PaddingValues,
) {
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val homeLoadingMore by viewModel.homeLoadingMore.collectAsStateWithLifecycle()
    val homeRecentlyPlayedLoading by viewModel.homeRecentlyPlayedLoading.collectAsStateWithLifecycle()
    val topPicks by viewModel.topPicks.collectAsStateWithLifecycle()
    val topPicksLoading by viewModel.topPicksLoading.collectAsStateWithLifecycle()
    val account by viewModel.account.collectAsStateWithLifecycle()
    val hideRecents by AppSettings.hideRecents.collectAsStateWithLifecycle()
    val hideTopPicks by AppSettings.hideTopPicks.collectAsStateWithLifecycle()
    val hideHomeMoods by AppSettings.hideHomeMoods.collectAsStateWithLifecycle()
    var signInBannerDismissed by rememberSaveable { mutableStateOf(false) }
    with(alerts) { with(overlays) { with(actions) {
        HomeScreen(
            state = visibleHomeState,
            listState = homeListState,
            currentSong = player.song,
            isPlaying = player.isPlaying,
            title = stringResource(R.string.listen_now),
            signedIn = signedIn,
            onSignIn = { webSession = WebSessionMode.SIGN_IN },
            signInBannerDismissed = signInBannerDismissed,
            onDismissSignInBanner = { signInBannerDismissed = true },
            onItemClick = { item, shelfTitle ->
                val song = shelfSong(item)
                // Hoisted because ShelfItem lives in :shared, and
                // Kotlin will not smart-cast a public nullable
                // property declared in another module.
                val browseId = item.browseId
                when {
                    song != null -> playRadio(
                        song,
                        QueueSource(shelfTitle, PlaybackSourceType.HOME),
                    )
                    browseId != null -> viewModel.openDetail(
                        browseId = browseId,
                        title = item.title,
                        subtitle = item.subtitle,
                        thumbnailUrl = item.thumbnailUrl,
                    )
                }
            },
            onItemLongPress = onShelfLongPress,
            onRetry = viewModel::loadHome,
            refreshing = MainViewModel.Feed.HOME in refreshing,
            onRefresh = { viewModel.refresh(MainViewModel.Feed.HOME) },
            pullState = homePull,
            contentPadding = listPadding,
            onLoadMore = viewModel::loadMoreHome,
            loadingMore = homeLoadingMore,
            recentlyPlayedLoading = homeRecentlyPlayedLoading && !hideRecents,
            topPicks = topPicks,
            topPicksLoading = topPicksLoading,
            listenerName = account?.name,
            // Neither shelf is drawn without its callback.
            onTopPickClick = openTopPick.takeUnless { hideTopPicks },
            onMoodClick = playMood.takeUnless { hideHomeMoods },
            onMoodsShowAll = { navigator.push(AppRoute.Moods) },
            // Nothing on Home is seen while the greeting covers it.
            artworkMotion = artworkMotion,
            onTopPickLongPress = { pick ->
                pick.browseId?.let { browseId ->
                    onShelfLongPress(
                        ShelfItem(
                            title = pick.title,
                            subtitle = pick.artistName.orEmpty(),
                            thumbnailUrl = pick.images.firstOrNull(),
                            videoId = null,
                            browseId = browseId,
                        ),
                    )
                }
            },
        )
    } } }
}

@Composable
fun ExploreTab(
    viewModel: MainViewModel,
    navigator: AppNavigator,
    alerts: AppAlertState,
    overlays: AppOverlayState,
    actions: TabActions,
    exploreListState: LazyListState,
    moodGenreListState: LazyListState,
    explorePull: PullToRefreshState,
    listPadding: PaddingValues,
) {
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val exploreState by viewModel.explore.collectAsStateWithLifecycle()
    val selectedMoodGenre by viewModel.selectedMoodGenre.collectAsStateWithLifecycle()
    val moodGenreShelves by viewModel.moodGenreShelves.collectAsStateWithLifecycle()
    with(alerts) { with(overlays) { with(actions) {
        selectedMoodGenre?.let { category ->
            MoodGenrePlaylistsScreen(
                title = category.title,
                state = moodGenreShelves,
                listState = moodGenreListState,
                onItemClick = { item ->
                    item.videoId?.let { videoId ->
                        playRadio(
                            Song(
                                videoId = videoId,
                                title = item.title,
                                artist = InnertubeParser.artistFromSubtitle(item.subtitle),
                                thumbnailUrl = item.thumbnailUrl,
                                isVideo = item.isVideo,
                            ),
                            QueueSource(
                                category.title,
                                PlaybackSourceType.EXPLORE,
                                category.browseId,
                            ),
                        )
                    } ?: item.browseId?.let { browseId ->
                        viewModel.openDetail(
                            browseId = browseId,
                            title = item.title,
                            subtitle = item.subtitle,
                            thumbnailUrl = item.thumbnailUrl,
                        )
                    }
                },
                onRetry = { viewModel.openMoodGenre(category) },
                contentPadding = listPadding,
            )
        } ?: ExploreScreen(
            state = exploreState,
            listState = exploreListState,
            onCategoryClick = viewModel::openMoodGenre,
            onRetry = viewModel::loadExplore,
            refreshing = MainViewModel.Feed.EXPLORE in refreshing,
            onRefresh = { viewModel.refresh(MainViewModel.Feed.EXPLORE) },
            pullState = explorePull,
            contentPadding = listPadding,
        )
    } } }
}

@Composable
fun SearchTab(
    viewModel: MainViewModel,
    navigator: AppNavigator,
    alerts: AppAlertState,
    overlays: AppOverlayState,
    actions: TabActions,
    searchListState: LazyListState,
    player: PlayerState,
    /** Set each time the Search tab is tapped: focus the field. */ searchFocusRequested: Boolean,
    /** The quality cap addon rows are dimmed against. */ addonCeiling: AudioQuality,
    onOpenRecognition: (() -> Unit)? = null,
    listPadding: PaddingValues,
) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()
    val searchLoadingMore by viewModel.searchLoadingMore.collectAsStateWithLifecycle()
    val searchScrollReset by viewModel.searchScrollReset.collectAsStateWithLifecycle()
    val searchHistory by viewModel.searchHistory.collectAsStateWithLifecycle()
    val searchSuggestions by viewModel.suggestions.collectAsStateWithLifecycle()
    val searchSource by viewModel.searchSource.collectAsStateWithLifecycle()
    val libraryResults by viewModel.libraryResults.collectAsStateWithLifecycle()
    val addonResults by viewModel.addonResults.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val searchLabel = stringResource(R.string.search)
    var showRecognitionSheet by rememberSaveable { mutableStateOf(false) }

    if (showRecognitionSheet) {
        RecognitionSheet(
            onDismiss = { showRecognitionSheet = false },
            onPlaySong = { title, artist ->
                viewModel.onQueryChange("$title $artist")
                viewModel.submitSearch()
            },
            onQueueSong = { title, artist ->
                viewModel.onQueryChange("$title $artist")
                viewModel.submitSearch()
            },
        )
    }

    with(alerts) { with(overlays) { with(actions) {
        SearchScreen(
            query = query,
            onQueryChange = viewModel::onQueryChange,
            filter = filter,
            currentSong = player.song,
            isPlaying = player.isPlaying,
            onFilterChange = viewModel::onFilterChange,
            results = results,
            loadingMore = searchLoadingMore,
            onLoadMore = viewModel::loadMoreSearchResults,
            listState = searchListState,
            scrollResetTrigger = searchScrollReset,
            focusRequested = searchFocusRequested,
            onFocusHandled = searchFocusHandled,
            onOpenRecognition = onOpenRecognition ?: { showRecognitionSheet = true },
            // Search hits are alternatives to each other, not a running
            // order — play the one tapped and build a station from it.
            onSongClick = { songs, index ->
                songs.getOrNull(index)?.let { song ->
                    viewModel.recordEntity(SearchHistoryEntity(
                        id = song.videoId,
                        title = song.title,
                        subtitle = song.artist.ifEmpty { "" },
                        artworkUrl = song.thumbnailUrl,
                        entityType = EntityType.TRACK,
                    ))
                    playRadio(song, QueueSource(searchLabel, PlaybackSourceType.SEARCH))
                }
            },
            onSongLongPress = openSongMenu,
            onSongSwipe = onSongSwipe,
            onTopResultPlay = { song ->
                viewModel.recordEntity(SearchHistoryEntity(
                    id = song.videoId,
                    title = song.title,
                    subtitle = song.artist.ifEmpty { "" },
                    artworkUrl = song.thumbnailUrl,
                    entityType = EntityType.TRACK,
                ))
                playRadio(song, QueueSource(searchLabel, PlaybackSourceType.SEARCH))
            },
            onTopResultPlaylist = { song ->
                viewModel.recordEntity(SearchHistoryEntity(
                    id = song.videoId,
                    title = song.title,
                    subtitle = song.artist.ifEmpty { "" },
                    artworkUrl = song.thumbnailUrl,
                    entityType = EntityType.TRACK,
                ))
                viewModel.loadPlaylists()
                pickPlaylistFor(song)
            },
            onBrowseClick = { item ->
                viewModel.recordEntity(SearchHistoryEntity(
                    id = item.browseId ?: "",
                    title = item.title,
                    subtitle = item.subtitle.ifBlank { "" },
                    artworkUrl = item.thumbnailUrl,
                    entityType = when (item.type) {
                        BrowseType.ALBUM -> EntityType.ALBUM
                        BrowseType.ARTIST -> EntityType.ARTIST
                        BrowseType.PLAYLIST -> EntityType.PLAYLIST
                        BrowseType.OTHER -> EntityType.OTHER
                    },
                ))
                viewModel.openDetail(
                    browseId = item.browseId,
                    title = item.title,
                    subtitle = item.subtitle,
                    thumbnailUrl = item.thumbnailUrl,
                    type = item.type,
                )
            },
            onBrowseLongPress = { item ->
                // A search row does say what it is, so its own type is
                // better than what the browse id can be read to mean.
                if (item.type != BrowseType.ARTIST) {
                    openBrowseMenu(
                        BrowseTarget(
                            browseId = item.browseId,
                            title = item.title,
                            subtitle = item.subtitle,
                            thumbnailUrl = item.thumbnailUrl,
                            type = item.type,
                            downloadId = downloadIdFor(item.browseId),
                        ),
                    )
                }
            },
            history = searchHistory,
            suggestions = searchSuggestions,
            typeaheadResults = viewModel.typeaheadResults.collectAsStateWithLifecycle().value,
            onSubmit = viewModel::submitSearch,
            // Suggestions land in search history via searchFor → recordSearch.
            // History items (onHistoryClick) navigate/play without re-logging.
            onSuggestionClick = viewModel::searchFor,
            onHistoryClick = { entity ->
                // Tap a history entity: navigate to it or play it directly.
                // Do NOT recordEntity here — tapping an existing history item
                // must not update its timestamp and push it to the top.
                when (entity.entityType) {
                    EntityType.TRACK -> {
                        // Play the track by its video id
                        playRadio(
                            com.music.bitchord.data.model.Song(
                                videoId = entity.id,
                                title = entity.title,
                                artist = entity.subtitle,
                                thumbnailUrl = entity.artworkUrl,
                            ),
                            QueueSource(entity.title, PlaybackSourceType.SEARCH),
                        )
                    }
                    EntityType.ALBUM, EntityType.ARTIST, EntityType.PLAYLIST, EntityType.OTHER -> {
                        viewModel.openDetail(
                            browseId = entity.id,
                            title = entity.title,
                            subtitle = entity.subtitle,
                            thumbnailUrl = entity.artworkUrl,
                        )
                    }
                }
            },
            onHistoryRemove = viewModel::removeSearch,
            onHistoryClear = viewModel::clearSearchHistory,
            onTypeaheadLongPress = openSongMenu,
            contentPadding = listPadding,
            // Snug under the bar rather than a page's breathing
            // gap below it: the field is in the bar (see the bar's
            // accessory), so what comes next is the field's own
            // suggestions and filters, and a gap reads as a break.
            topPadding = topBarHeight() + 4.dp,
            showField = false,
            // The settings page's own two-state selector, so the
            // app has one look for "pick one of these".
            sourceSwitcher = {
                SegmentedControl(
                    options = listOf(
                        stringResource(R.string.search_source_youtube),
                        stringResource(R.string.library),
                        stringResource(R.string.search_source_addons),
                    ),
                    selectedIndex = searchSource.ordinal,
                    onSelect = { index -> onSearchSourceChange(SearchSource.entries[index]) },
                )
            },
            searchingLibrary = searchSource == SearchSource.LIBRARY,
            libraryResults = libraryResults,
            searchingAddons = searchSource == SearchSource.ADDONS,
            addonResults = addonResults,
            isBlocked = { song ->
                val configId = SourceRegistry.parseTrackKey(song.videoId)?.first
                !SourceRegistry.permitsForPlayback(configId, addonCeiling)
            },
            onBlockedSongClick = { song -> blockedAddonSong = song },
            addonHistory =viewModel.addonSearchHistory.collectAsStateWithLifecycle().value,
            onAddonHistoryClick = { term ->
                viewModel.onQueryChange(term)
                viewModel.submitSearch()
            },
            onAddonHistoryRemove = viewModel::removeAddonSearch,
            onAddonHistoryClear = viewModel::clearAddonSearchHistory,
            // The same queue opening the Local Music folder and
            // tapping the row there would give.
            onLibrarySongClick = { songs, index ->
                playFrom(
                    songs,
                    index,
                    if (searchSource == SearchSource.ADDONS) {
                        QueueSource(
                            context.getString(R.string.search_source_addons),
                            PlaybackSourceType.BROWSE,
                            "addons:search",
                        )
                    } else {
                        QueueSource(
                            context.getString(R.string.local_music),
                            PlaybackSourceType.BROWSE,
                            "local:all",
                        )
                    },
                )
            },
        )
    } } }
}

@Composable
fun LibraryTab(
    viewModel: MainViewModel,
    navigator: AppNavigator,
    alerts: AppAlertState,
    overlays: AppOverlayState,
    actions: TabActions,
    libraryListState: LazyListState,
    libraryPull: PullToRefreshState,
    signedIn: Boolean,
    replay: ReplayState,
    replayCards: List<ReplayHeroCard>,
    downloadedReleases: List<SavedCollection>,
    localPlaylistItems: List<ShelfItem>,
    listPadding: PaddingValues,
) {
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val libraryState by viewModel.library.collectAsStateWithLifecycle()
    val account by viewModel.account.collectAsStateWithLifecycle()
    with(alerts) { with(overlays) { with(actions) {
        LibraryScreen(
            signedIn = signedIn,
            state = libraryState,
            listState = libraryListState,
            onShelfItemClick = onLibraryItemClick,
            // Every shelf here has a menu behind it now — the account's
            // own playlists get rename and delete on top of what a saved
            // album or a Liked Music card gets. Holding an artist still
            // does nothing; see [onBrowseLongPress].
            onShelfItemLongPress = onBrowseLongPress,
            onNewPlaylist = createPlaylist,
            onImportSpotifyPlaylist = importSpotifyPlaylist,
            onShowAll = { shelf -> navigator.push(AppRoute.LibraryShowAll(shelf)) },
            replay = {
                LibraryReplayEntry(
                    cards = replayCards,
                    loading = replay.loading,
                    holder = account?.name.orEmpty(),
                    memberSince = replay.memberSince,
                    onOpenReplay = openReplayAt,
                )
            },
            onSignIn = { webSession = WebSessionMode.SIGN_IN },
            onRetry = viewModel::loadLibrary,
            refreshing = MainViewModel.Feed.LIBRARY in refreshing,
            onRefresh = { viewModel.refresh(MainViewModel.Feed.LIBRARY) },
            pullState = libraryPull,
            contentPadding = listPadding,
            links = libraryLinks(),
            deviceItems = libraryDeviceItems(downloadedReleases) + localPlaylistItems,
        )
    } } }
}
