package com.music.bitchord.ui.navigation

import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material.icons.rounded.Upgrade
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.music.bitchord.R
import com.music.bitchord.TAB_HOME
import com.music.bitchord.TAB_LIBRARY
import com.music.bitchord.data.AppUpdateChecker
import com.music.bitchord.data.listentogether.ServerConnectionState
import com.music.bitchord.data.model.Account
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.DetailPage
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.UiState
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.LibrarySort
import com.music.bitchord.data.settings.SongSort
import com.music.bitchord.localizedLabel
import com.music.bitchord.ui.MainViewModel
import com.music.bitchord.ui.components.BrowseTarget
import com.music.bitchord.ui.components.TopBarAccountButton
import com.music.bitchord.ui.components.TopBarDownloadButton
import com.music.bitchord.ui.components.menuAnchor
import com.music.bitchord.ui.icons.BitChordIcons
import com.music.bitchord.ui.screens.sortedForDetail

/**
 * The top bar's trailing controls, which change with the page under it: the
 * party server's ping on Listen Together, the update badge on Home, History on
 * Library, sort on a Library grid, search / sort / ⋯ on a release page or
 * device folder, share on an artist, the downloads button and the account photo.
 *
 * The sort menus themselves are drawn elsewhere (with the app's other frosted
 * overlays, where the haze can see what it blurs); this only opens them.
 */
@Suppress("DEPRECATION") // Icons.Rounded.Sort, as before the move.
@Composable
fun TopBarActions(
    viewModel: MainViewModel,
    navigator: AppNavigator,
    route: AppRoute?,
    detail: DetailPage?,
    detailActiveShelf: HomeShelf?,
    isReleaseDetail: Boolean,
    isLocalDetail: Boolean,
    selectedTab: Int,
    partyServerStatus: ServerConnectionState,
    updateNotice: AppUpdateChecker.UpdateInfo?,
    onShowUpdate: () -> Unit,
    librarySort: LibrarySort,
    librarySortMenuOpen: Boolean,
    onLibrarySortMenuOpenChange: (Boolean) -> Unit,
    detailSearching: Boolean,
    onToggleDetailSearching: () -> Unit,
    /** The release page's song-sort menu, or a device folder's sort-and-view menu. */
    onOpenSortMenu: () -> Unit,
    songSort: SongSort,
    openBrowseMenu: (BrowseTarget) -> Unit,
    downloadIdFor: (String?) -> String?,
    account: Account?,
    signedIn: Boolean,
    onOpenAccountSelector: () -> Unit,
    onOpenRecognition: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    // This is intentionally scoped to Listen together: the
    // round-trip time is meaningful while coordinating a
    // party, but would be noise in the rest of the app.
    if (route == AppRoute.ListenTogether) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp),
        ) {
            when (val state = partyServerStatus) {
                is ServerConnectionState.CustomFallback -> {
                    Icon(
                        Icons.Rounded.CloudOff,
                        contentDescription = stringResource(R.string.listen_together_top_bar_fallback, stringResource(R.string.listen_together_ping, state.latencyMs)),
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.listen_together_ping, state.latencyMs),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 1,
                    )
                }
                is ServerConnectionState.CustomOnline -> {
                    Text(
                        text = stringResource(R.string.listen_together_ping, state.latencyMs),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                    )
                }
                is ServerConnectionState.DefaultOnline -> {
                    Text(
                        text = stringResource(R.string.listen_together_ping, state.latencyMs),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                    )
                }
                ServerConnectionState.Checking -> {
                    Text(
                        text = stringResource(R.string.listen_together_server_checking),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                ServerConnectionState.Offline -> {
                    Text(
                        text = stringResource(R.string.listen_together_server_offline_dash),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 1,
                    )
                }
            }
        }
    }
    // Only worth surfacing where there's room for it and it won't
    // be mistaken for a per-page action — Home, at rest.
    if (route == null && detail == null && selectedTab == TAB_HOME) {
        updateNotice?.let { update ->
            IconButton(onClick = onShowUpdate) {
                Icon(
                    // An arrow rising out of a bar, not the
                    // little phone-with-an-arrow: at 24dp the
                    // handset outline is mush, and the glyph
                    // has to read as "newer version" rather
                    // than as "something about your device".
                    Icons.Rounded.Upgrade,
                    contentDescription = stringResource(R.string.update_available, update.version),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
    // None on Settings or the pages opened from it: those are
    // about the account, and the photo would only reopen them.
    if (!(route != null && AppRoute.Settings in navigator)) {
        // Left of the account photo, and only on Library itself:
        // a history is a record of what was played, which reads
        // as that tab's business rather than every tab's.
        if (route == null && detail == null && selectedTab == TAB_LIBRARY) {
            IconButton(
                onClick = {
                    navigator.push(AppRoute.History)
                    viewModel.loadHistory()
                },
            ) {
                Icon(
                    Icons.Rounded.History,
                    contentDescription = stringResource(R.string.listening_history),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        // Left of the account photo, and only on a Library
        // "Show all" grid — the same control the Downloads
        // folder offers (see `LocalSearchField`), adapted to
        // the one thing a playlist or album card carries: a
        // title.
        if (route is AppRoute.LibraryShowAll) {
            Box {
                IconButton(onClick = { onLibrarySortMenuOpenChange(true) }) {
                    Icon(
                        Icons.Rounded.Sort,
                        contentDescription = stringResource(R.string.sort_library),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                DropdownMenu(
                    expanded = librarySortMenuOpen,
                    onDismissRequest = { onLibrarySortMenuOpenChange(false) },
                ) {
                    LibrarySort.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.localizedLabel()) },
                            trailingIcon = if (option == librarySort) {
                                { Icon(Icons.Rounded.Check, contentDescription = null) }
                            } else null,
                            onClick = {
                                AppSettings.setLibrarySort(option)
                                onLibrarySortMenuOpenChange(false)
                            },
                        )
                    }
                }
            }
        }
        // Sort, only on an album or playlist page or a device
        // folder — an artist page has no single track list to
        // reorder. A folder's opens its own menu: its sort
        // orders, and list or grid for its tabs.
        //
        // Search and the overflow flank it, as the share and
        // "…" pair do on Apple Music's album page: the header
        // below is left with just the sleeve, the title and
        // Shuffle / Play / Add.
        if ((isReleaseDetail || isLocalDetail) && detail != null) {
            val releaseSongs = (detail.songs as? UiState.Success)?.data.orEmpty()
            if (releaseSongs.isNotEmpty()) {
                IconButton(onClick = onToggleDetailSearching) {
                    Icon(
                        if (detailSearching) Icons.Rounded.Close else BitChordIcons.Search,
                        contentDescription = stringResource(
                            if (detailSearching) R.string.close_search else R.string.search_this_list,
                        ),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            // The menu itself is [FrostedSortMenu], composed
            // with the app's other frosted overlays further
            // down — in the main hierarchy, where the haze
            // can see the content it blurs.
            IconButton(
                onClick = onOpenSortMenu,
            ) {
                Icon(
                    Icons.Rounded.Sort,
                    contentDescription = stringResource(
                        if (isLocalDetail) R.string.sort_music else R.string.sort_songs,
                    ),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            if (isReleaseDetail && releaseSongs.isNotEmpty()) {
                // The page's own tracks, in the order the page
                // shows them, so the sheet has them already and
                // Play, Shuffle and Open are the buttons beside
                // the one that opened it rather than rows on it.
                // Download lives in the sheet too — and the
                // tracks arrive stamped with the album they came
                // off, which is what the download record groups
                // them under.
                IconButton(
                    modifier = Modifier.menuAnchor(),
                    onClick = {
                        openBrowseMenu(BrowseTarget(
                            browseId = detail.browseId,
                            title = detail.title,
                            subtitle = detail.subtitle,
                            thumbnailUrl = detail.thumbnailUrl,
                            type = detail.type,
                            songs = releaseSongs.sortedForDetail(songSort).map { song ->
                                if (detail.type == BrowseType.ALBUM) {
                                    song.copy(albumName = song.albumName ?: detail.title)
                                } else {
                                    song
                                }
                            },
                            fromCard = false,
                            downloadId = downloadIdFor(detail.browseId),
                        ))
                    },
                ) {
                    Icon(
                        Icons.Rounded.MoreHoriz,
                        contentDescription = stringResource(R.string.more),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
        // Left of the account photo, on an artist page: iOS's
        // share glyph, sending the artist's channel link — the
        // one YouTube Music itself shares for an artist.
        if (detail != null && !isLocalDetail && detail.type == BrowseType.ARTIST &&
            detailActiveShelf == null && detail.browseId.startsWith("UC")
        ) {
            IconButton(
                onClick = {
                    val sendIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(
                            Intent.EXTRA_TEXT,
                            "https://music.youtube.com/channel/${detail.browseId}",
                        )
                    }
                    context.startActivity(Intent.createChooser(sendIntent, detail.title))
                },
            ) {
                Icon(
                    Icons.Rounded.IosShare,
                    contentDescription = stringResource(R.string.share),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        // Left of the account photo, and only there while
        // there is a batch to report on — see
        // [TopBarDownloadButton], which decides that for
        // itself rather than being told. Not on the page it
        // opens, where it would only lead back to itself.
        if (route != AppRoute.Downloads) {
            TopBarDownloadButton(onClick = { navigator.push(AppRoute.Downloads) })
        }
        // Not on a release page, whose pill already carries
        // three of its own controls — Apple Music leaves the
        // profile off its album pages too.
        if (!isReleaseDetail) TopBarAccountButton(
            account = account,
            onClick = {
                if (signedIn) {
                    viewModel.loadChannels()
                    onOpenAccountSelector()
                } else navigator.push(AppRoute.Settings)
            },
            onSwipeProfile = { forward -> viewModel.stepProfile(forward) },
        )
    }
}
