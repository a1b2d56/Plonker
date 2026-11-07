package com.music.bitchord.ui.navigation

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.music.bitchord.R
import com.music.bitchord.data.LocalGroup
import com.music.bitchord.data.listentogether.ListenTogether
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.DetailPage
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.LibraryState
import com.music.bitchord.data.model.PlaybackSourceType
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.UiState
import com.music.bitchord.data.model.UserPlaylist
import com.music.bitchord.data.settings.LibraryViewType
import com.music.bitchord.data.settings.LocalMusicSort
import com.music.bitchord.data.settings.SongSort
import com.music.bitchord.download.Downloads
import com.music.bitchord.download.SavedCollection
import com.music.bitchord.isDeviceFolder
import com.music.bitchord.playback.QueueShuffle
import com.music.bitchord.playback.QueueSource
import com.music.bitchord.playback.cast.CastController
import com.music.bitchord.playback.contextEntryKey
import com.music.bitchord.ui.MainViewModel
import com.music.bitchord.ui.components.BrowseTarget
import com.music.bitchord.ui.components.ReorderPlaylistPage
import com.music.bitchord.ui.screens.CACHE_FOLDER_BROWSE_ID
import com.music.bitchord.ui.screens.DetailScreen
import com.music.bitchord.ui.screens.LocalFolder
import com.music.bitchord.ui.screens.LocalFolderState
import com.music.bitchord.ui.screens.LocalFolderTab
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The playlist being rearranged, drawn on its own page in place of the list —
 * opened from the collection menu, saved or closed from the page, and put away
 * when the page is left by any route.
 */
@Stable
class PlaylistReorderState {
    /** The playlist being rearranged, or null when the reorder list is shut. */
    var target by mutableStateOf<UserPlaylist?>(null)

    /** Its entries as YouTube has them now — fetched fresh when the list opens. */
    var entries by mutableStateOf<UiState<List<Song>>>(UiState.Loading)
    var saving by mutableStateOf(false)
}

/** What a release page reaches that lives with the app: the queue, menus, downloads. */
class ReleasePageActions(
    val scope: CoroutineScope,
    val openSongMenu: (Song) -> Unit,
    val openBrowseMenu: (BrowseTarget) -> Unit,
    val onShelfLongPress: (ShelfItem) -> Unit,
    val onSongSwipe: (Song) -> Unit,
    /** All songs, the index to start at, where they came from, bounded context, continuation. */
    val playFrom: (List<Song>, Int, QueueSource, Boolean, String?) -> Unit,
    val playRadio: (Song, QueueSource) -> Unit,
    val shelfSong: (ShelfItem) -> Song?,
    /** True (and says why) when a party's host has the controls. */
    val refusedByHost: () -> Boolean,
    val uploadToWebDav: (List<Song>) -> Unit,
    val downloadFromCache: (List<Song>) -> Unit,
    val removeFromCache: (List<Song>) -> Unit,
    val createOfflinePlaylist: () -> Unit,
    val showQueueNotice: (String) -> Unit,
    val setSearching: (Boolean) -> Unit,
)

/**
 * An album, playlist or artist page — and Local Music, Downloads and the other
 * device folders, which are this same page with Songs / Artists / Albums under
 * the header. [page] is the page this slot was opened for, held through its
 * exit animation by the caller.
 */
@Composable
fun ReleasePage(
    page: DetailPage,
    viewModel: MainViewModel,
    actions: ReleasePageActions,
    navigator: AppNavigator,
    reorder: PlaylistReorderState,
    currentSong: Song?,
    isPlaying: Boolean,
    listState: LazyListState,
    activeShelf: HomeShelf?,
    /** Whether this page's filter box is up — only ever the page on its way in. */
    searching: Boolean,
    signedIn: Boolean,
    releaseLibrary: Map<String, LibraryState>,
    songSort: SongSort,
    listPadding: PaddingValues,
    webdavUrl: String,
    /** The download record, read as a key so a folder re-groups when it changes. */
    savedCollections: Map<String, SavedCollection>,
    localFolderStates: MutableMap<String, LocalFolderState>,
    folderSortOf: (String) -> LocalMusicSort,
    folderViewTypeOf: (String) -> LibraryViewType,
    offlinePlaylistGroups: List<LocalGroup>,
) {
    val context = LocalContext.current
    val folder = if (page.browseId.isDeviceFolder()) {
        val isDownloads = page.browseId == "local:downloads"
        val isCache = page.browseId == CACHE_FOLDER_BROWSE_ID
        // The whole device: the one folder that is an offline
        // player in its own right, with playlists of its own
        // and the directories the files are kept in.
        val isDevice = page.browseId == "local:all"
        val folderSongs = (page.songs as? UiState.Success)?.data.orEmpty()
        // Only the Downloads folder has releases behind it: Local
        // Music is files this app never asked for, so there is
        // nothing on record about how they were grouped. Keyed on
        // the record as well as the list, so downloading an album
        // while its folder is open adds the folder rather than
        // waiting for the page to be reopened.
        val downloadCollections = remember(folderSongs, savedCollections, isDownloads) {
            if (isDownloads) Downloads.collectionsAmong(folderSongs) else emptyList()
        }
        LocalFolder(
            state = localFolderStates.getOrPut(page.browseId) { LocalFolderState() },
            sort = folderSortOf(page.browseId),
            viewType = folderViewTypeOf(page.browseId),
            collections = downloadCollections,
            selectable = isDownloads || isCache,
            onOpenGroup = viewModel::openLocalGroup,
            onGroupLongPress = { group ->
                actions.openBrowseMenu(
                    BrowseTarget(
                        browseId = group.browseId,
                        title = group.title,
                        subtitle = group.subtitle,
                        thumbnailUrl = group.thumbnailUrl,
                        type = group.type,
                        songs = group.songs,
                        downloadId = group.downloadId,
                    ),
                )
            },
            onDelete = if (isDownloads) {
                { selected ->
                    actions.scope.launch {
                        selected.forEach { song -> Downloads.delete(context, song.videoId) }
                    }
                }
            } else {
                null
            },
            onUpload = if (isDownloads &&
                com.music.bitchord.data.webdav.WebDavConfig.isConfigured(webdavUrl)
            ) {
                { selected -> actions.uploadToWebDav(selected) }
            } else {
                null
            },
            onDownload = if (isCache) actions.downloadFromCache else null,
            onRemoveFromCache = if (isCache) actions.removeFromCache else null,
            tabs = if (isDevice) LocalFolderTab.DEVICE else LocalFolderTab.BASIC,
            playlists = if (isDevice) offlinePlaylistGroups else emptyList(),
            onCreatePlaylist = if (isDevice) (actions.createOfflinePlaylist) else null,
        )
    } else {
        null
    }
    // An album page's rows carry no album name of their own — the
    // release is billed once, in the header the rows hang under — so
    // the page title is stamped on as they leave for the download
    // queue or the track menu. Without it every track saved from an
    // album arrives in the Downloads folder with nothing to group it
    // under, and its Albums tab stays empty however much is in it.
    val withAlbum: (Song) -> Song = { song ->
        if (page.type == BrowseType.ALBUM) {
            song.copy(albumName = song.albumName ?: page.title)
        } else {
            song
        }
    }
    // The visible list can be one render behind the page
    // while fill-in is completing. For a local release
    // handoff, cancel that fill and use the newest complete
    // page snapshot; Cast deliberately keeps the old full
    // queue path.
    fun playDetailSongs(songs: List<Song>, index: Int) {
        // Do not cancel a page's fill-in merely because a
        // listener tapped while the host owns playback.
        if (ListenTogether.state.value.controlsLocked) {
            actions.refusedByHost()
            return
        }
        val source = QueueSource(
            page.title,
            PlaybackSourceType.BROWSE,
            page.browseId,
        )
        val selected = songs.getOrNull(index)
        val pageSongKeys =
            (page.songs as? UiState.Success)?.data.orEmpty().mapTo(HashSet()) { it.contextEntryKey() }
        val useBounded =
            (page.type == BrowseType.ALBUM || page.type == BrowseType.PLAYLIST) &&
                selected?.contextEntryKey()?.let(pageSongKeys::contains) == true &&
                !CastController.state.value.casting
        val latestPage = if (useBounded) {
            viewModel.takePlaybackContext(page.browseId)
        } else {
            null
        }
        val latestSongs = latestPage
            ?.let { (it.songs as? UiState.Success)?.data.orEmpty() }
            ?.map(withAlbum)
            ?.takeIf { it.isNotEmpty() }
        val queue = latestSongs ?: songs.map(withAlbum)
        if (queue.isEmpty()) return
        val queueIndex = selected?.let { tapped ->
            queue.indexOfFirst { it.contextEntryKey() == tapped.contextEntryKey() }
        }?.takeIf { it >= 0 } ?: index
        actions.playFrom(
        queue,
        queueIndex.coerceIn(queue.indices),
        source,
        /* boundedContext = */ useBounded,
        /* continuationToken = */ latestPage?.continuation ?: page.continuation,
    )
    }
    // This page's songs being rearranged, drawn in place of its list.
    val reordering = reorder.target?.takeIf { it.browseId == page.browseId }
    DetailScreen(
        page = page,
        currentSong = currentSong,
        isPlaying = isPlaying,
        listState = listState,
        activeShelf = activeShelf,
        onActiveShelfChange = { shelf ->
            if (shelf != null) navigator.push(AppRoute.ArtistShelf(page.browseId, shelf))
            else navigator.pop()
        },
        // Album/playlist playback hands the page snapshot to
        // the service, which then owns continuation loading.
        onSongClick = ::playDetailSongs,
        onSongLongPress = { actions.openSongMenu(withAlbum(it)) },
        onSongSwipe = { actions.onSongSwipe(withAlbum(it)) },
        onShuffle = { songs ->
            if (songs.isNotEmpty()) {
                // Shuffle goes on first so the queue is built shuffled
                // as it is set — the random pick here only decides
                // which track leads it.
                QueueShuffle.enableForNextQueue()
                playDetailSongs(songs, songs.indices.random())
            }
        },
        onSectionItemClick = { item ->
            val video = actions.shelfSong(item)?.takeIf { item.browseId == null }
            if (video != null) {
                // An artist's Videos shelf plays, from the page it is on.
                actions.playRadio(video, QueueSource(page.title, PlaybackSourceType.BROWSE, page.browseId))
            } else {
                item.browseId?.let { id ->
                    viewModel.openDetail(
                        browseId = id,
                        title = item.title,
                        subtitle = item.subtitle,
                        thumbnailUrl = item.thumbnailUrl,
                        type = BrowseType.ALBUM,
                    )
                }
            }
        },
        onSectionItemLongPress = actions.onShelfLongPress,
        // Only the page on its way in answers to the bar's
        // search button; one fading out keeps its list whole.
        searching = searching,
        onSearchingChange = actions.setSearching,
        onArtistClick = { id, name ->
            viewModel.openDetail(id, name, "Artist", null, BrowseType.ARTIST)
        },
        onAddSuggested = { song -> viewModel.addSuggestedSong(page.browseId, song) },
        // Saving is an account action, so it isn't offered to a
        // guest at all — same as the like and add-to-playlist rows
        // in the track menu.
        onToggleLibrary = if (signedIn) {
            { viewModel.toggleLibrary(page.browseId) }
        } else {
            null
        },
        // Same rule for the artist page's subscribe circle:
        // a channel subscription is the account's, so a
        // guest is never shown the button.
        onToggleSubscription = if (signedIn) {
            { viewModel.toggleSubscription(page.browseId) }
        } else {
            null
        },
        releaseLibrary = releaseLibrary,
        onLoadReleaseLibrary = viewModel::loadReleaseLibrary,
        onToggleReleaseLibrary = if (signedIn) {
            viewModel::toggleReleaseLibrary
        } else {
            null
        },
        songSort = songSort,
        folder = folder,
        contentPadding = listPadding,
        reorder = if (reordering != null) {
            { palette ->
                ReorderPlaylistPage(
                    playlist = reordering,
                    entries = reorder.entries,
                    saving = reorder.saving,
                    palette = palette,
                    contentPadding = PaddingValues(
                        bottom = listPadding.calculateBottomPadding(),
                    ),
                    onClose = { reorder.target = null },
                    onSave = { reordered ->
                        val original = (reorder.entries as? UiState.Success)?.data.orEmpty()
                        reorder.saving = true
                        viewModel.reorderPlaylist(reordering, original, reordered) { saved ->
                            reorder.saving = false
                            actions.showQueueNotice(
                                context.getString(
                                    if (saved) R.string.playlist_reordered else R.string.reorder_failed,
                                ),
                            )
                            if (saved && reorder.target == reordering) reorder.target = null
                        }
                    },
                )
            }
        } else {
            null
        },
    )
}
