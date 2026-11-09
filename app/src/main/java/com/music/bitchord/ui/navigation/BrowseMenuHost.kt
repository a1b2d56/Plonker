package com.music.bitchord.ui.navigation

import android.content.Intent
import android.widget.Toast
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.LocalGroups
import com.music.bitchord.data.OfflinePlaylists
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.UserPlaylist
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.download.DownloadTarget
import com.music.bitchord.download.Downloads
import com.music.bitchord.playback.QueueShuffle
import com.music.bitchord.ui.MainViewModel
import com.music.bitchord.ui.components.BrowseActionsMenu
import com.music.bitchord.ui.components.BrowseTarget
import com.music.bitchord.ui.components.HeldContextMenu
import com.music.bitchord.ui.components.HeldItem
import com.music.bitchord.ui.components.IosMenuPopover
import com.music.bitchord.ui.components.backdrop.backdrops.SnapshotBackdrop
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The album / playlist menu's one slot. Opened by holding a card on any tab,
 * a search row, an artist page's carousel, or from a release page's own
 * overflow — only one of them can be held at a time, so one slot serves all.
 */
@Stable
class BrowseMenuState {
    /** What the menu is open on, or null when it is shut. */
    var target by mutableStateOf<BrowseTarget?>(null)

    /** The card held to open it, which the menu lifts; null for a ⋯ tap. */
    var origin by mutableStateOf<HeldItem?>(null)

    /** The ⋯ it was opened from, for the popover to hang off. */
    var anchor by mutableStateOf<Rect?>(null)

    /** Rename asked for from the menu, which hands it on to a sheet holding only the form. */
    var renameInSheet by mutableStateOf(false)

    /** Whether it is up as a menu over the page (rather than lifted off a card or on its sheet). */
    val floatingOverPage: Boolean
        get() = target != null && origin == null && !renameInSheet
}

/**
 * What the menu's rows reach that live with the app rather than the menu:
 * the queue, playback, downloads and the playlist reorder page.
 */
class BrowseMenuActions(
    /** Fetches the target's tracks if they aren't in hand yet, then runs the action on them. */
    val withSongs: (BrowseTarget, (List<Song>) -> Unit) -> Unit,
    val playNext: (List<Song>) -> Unit,
    val addToQueue: (List<Song>) -> Unit,
    val play: (List<Song>, Int) -> Unit,
    val startDownload: (List<Song>, DownloadTarget?) -> Unit,
    val addToPlaylist: (List<Song>, BrowseTarget) -> Unit,
    /** Opens the reorder list on [UserPlaylist]'s page. */
    val reorder: (UserPlaylist) -> Unit,
)

/**
 * Draws the album / playlist menu in whichever frame it was opened in — lifted
 * off a held card, hung off a ⋯, or (for Rename) on a sheet of its own — and
 * clears the frame's state as it shuts.
 */
@Composable
fun BrowseMenuHost(
    state: BrowseMenuState,
    viewModel: MainViewModel,
    actions: BrowseMenuActions,
    signedIn: Boolean,
    playlists: List<UserPlaylist>,
    /** The release page on screen, if any — Reorder and Delete act on it. */
    openDetailId: String?,
    hazeState: HazeState,
    pageBackdrop: SnapshotBackdrop,
    scope: CoroutineScope,
) {
    val body: @Composable (BrowseTarget) -> Unit = { target ->
        BrowseMenuBody(
            target = target,
            state = state,
            viewModel = viewModel,
            actions = actions,
            signedIn = signedIn,
            playlists = playlists,
            openDetailId = openDetailId,
            scope = scope,
        )
    }
    val openTarget: (BrowseTarget) -> Unit = { target ->
        target.browseId?.let { id ->
            state.target = null
            viewModel.openDetail(
                browseId = id,
                title = target.title,
                subtitle = target.subtitle,
                thumbnailUrl = target.thumbnailUrl,
                type = target.type,
            )
        }
    }

    // Rename is a text field and a keyboard, which have no place on a menu
    // floating over the page: the menu hands it on, and the form comes up
    // here on a sheet of its own.
    RenameSheet(state, body)
    HeldContextMenu(
        item = state.target,
        held = state.origin,
        hazeState = hazeState,
        onDismiss = { state.target = null },
        // Tapping the lifted card opens it, as a tap on it in the list
        // would have. Not for a Local Music grouping, which has no page.
        onPreviewClick = openTarget,
    ) { target ->
        body(target)
    }
    // A tap on a ⋯ — the release page's own — hangs the menu off it.
    IosMenuPopover(
        item = state.target?.takeIf { state.floatingOverPage },
        anchor = state.anchor,
        onDismiss = { state.target = null },
        backdrop = pageBackdrop,
        hazeState = hazeState,
    ) { target ->
        body(target)
    }
    LaunchedEffect(state.target == null) {
        if (state.target == null) {
            state.origin = null
            state.anchor = null
            state.renameInSheet = false
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RenameSheet(state: BrowseMenuState, body: @Composable (BrowseTarget) -> Unit) {
    state.target?.takeIf { state.renameInSheet }?.let { target ->
        ModalBottomSheet(
            onDismissRequest = { state.target = null },
            containerColor = MaterialTheme.colorScheme.background,
        ) {
            body(target)
        }
    }
}

/**
 * The rows themselves, identical in every frame. Every row closes the menu
 * first: the tracks may still have to be fetched, and leaving the menu up over
 * a request nothing on it reports on reads as a tap that didn't land.
 */
@Composable
private fun BrowseMenuBody(
    target: BrowseTarget,
    state: BrowseMenuState,
    viewModel: MainViewModel,
    actions: BrowseMenuActions,
    signedIn: Boolean,
    playlists: List<UserPlaylist>,
    openDetailId: String?,
    scope: CoroutineScope,
) {
    val context = LocalContext.current
    val act: ((List<Song>) -> Unit) -> () -> Unit = { action ->
        {
            state.target = null
            actions.withSongs(target, action)
        }
    }
    // Whose playlist this is, asked here rather than carried in by
    // whatever opened the menu.
    //
    // Only the playlist's own page states it (see
    // InnertubeParser.parsePlaylistOwned), so a card has to send for the
    // answer and the menu is already up by the time it lands — hence
    // read as state rather than settled once when the target was built.
    // Rename and Delete are absent until the answer says they apply, so
    // the menu's worst moment is a beat without them on the user's own
    // playlist, rather than offering to delete a stranger's.
    LaunchedEffect(target.browseId) {
        viewModel.resolvePlaylistOwnership(target.browseId)
    }
    // Spelt out here rather than left to MainViewModel.editablePlaylist,
    // which is the same rule over the same two lists: that reads them as
    // plain values, which is right for a click handler and invisible to
    // Compose. Both are read from collected state so this menu actually
    // recomposes when the answer arrives.
    val ownedPlaylists by viewModel.playlistOwned.collectAsStateWithLifecycle()
    val playlist = target.browseId
        ?.takeIf { signedIn && ownedPlaylists[it] == true }
        ?.let { id -> playlists.firstOrNull { it.browseId == id } }
    val remote = target.browseId?.startsWith("local:") == false
    // Spotify playlists saved on this device ("local:playlist:sp_local_…")
    // carry real YouTube video ids, so they download like a remote
    // playlist — but they have no YouTube Music page, so Share stays
    // on [remote] alone. The device-folder ids ("local:downloads",
    // "local:all", …) are neither.
    val downloadable = remote ||
        target.browseId?.startsWith("local:playlist:sp_local_") == true
    val pinnedPlaylists by AppSettings.pinnedPlaylists.collectAsStateWithLifecycle()
    // Device playlists and directories have nowhere on the Library
    // page to be pinned to that would still mean anything off-device.
    val pinnableId = target.browseId?.takeIf {
        target.type == BrowseType.PLAYLIST &&
            !it.startsWith(OfflinePlaylists.PREFIX) && !it.startsWith(LocalGroups.PREFIX)
    }
    // One of the offline player's own playlists, which are the user's
    // to rename and delete — all but Liked, which is the app's.
    val offlineEditable = OfflinePlaylists.get(target.browseId)?.takeUnless { it.isLiked }
    BrowseActionsMenu(
        // The live answer, not the one the target was built with.
        target = target.copy(
            playlist = playlist ?: offlineEditable?.let {
                UserPlaylist(playlistId = it.id, title = it.title, subtitle = "", thumbnailUrl = null)
            },
        ),
        onRenameInSheet = {
            state.renameInSheet = true
            state.origin = null
        },
        startRenaming = state.renameInSheet,
        onPlayNext = act(actions.playNext),
        onAddToQueue = act(actions.addToQueue),
        onPlay = act { songs -> actions.play(songs, 0) }.takeIf { target.fromCard },
        onShuffle = act { songs ->
            // As on a release page: shuffle goes on before the queue
            // is built, so it is built shuffled rather than played
            // out of order.
            QueueShuffle.enableForNextQueue()
            actions.play(songs, songs.indices.random())
        }.takeIf { target.fromCard },
        onOpen = target.browseId
            ?.takeIf { target.fromCard }
            ?.let { id ->
                {
                    state.target = null
                    viewModel.openDetail(
                        browseId = id,
                        title = target.title,
                        subtitle = target.subtitle,
                        thumbnailUrl = target.thumbnailUrl,
                        type = target.type,
                    )
                }
            },
        // The one place a whole release is asked for, from a card and
        // from the release's own page alike. Nothing on this device needs
        // fetching to be on it, so a local page is the exception. What the
        // target carries that the tracks don't is the release's own name
        // and cover, which is exactly what the record wants.
        onAddToPlaylist = act { songs ->
            actions.addToPlaylist(songs, target)
        }.takeIf {
            signedIn && remote &&
                (target.type == BrowseType.ALBUM || target.type == BrowseType.PLAYLIST)
        },
        onDownloadAll = act { songs ->
            actions.startDownload(
                songs,
                target.browseId
                    ?.takeIf { target.type != BrowseType.ARTIST }
                    ?.let { id ->
                        DownloadTarget(
                            id = id,
                            title = target.title,
                            subtitle = target.subtitle,
                            thumbnailUrl = target.thumbnailUrl,
                            playlist = target.type == BrowseType.PLAYLIST,
                        )
                    },
            )
        }.takeIf { downloadable },
        // The same link a share off YouTube Music's own overflow
        // gives — built from the browse id rather than fetched,
        // since nothing about it depends on the tracks or the
        // account. Left off an artist card (Share there is a
        // channel link, not a release, and nobody asked for it)
        // and off anything with no real browse id behind it.
        onShare = target.browseId
            ?.takeIf { remote && (target.type == BrowseType.ALBUM || target.type == BrowseType.PLAYLIST) }
            ?.let { id ->
                {
                    val url = if (target.type == BrowseType.PLAYLIST) {
                        "https://music.youtube.com/playlist?list=${id.removePrefix("VL")}"
                    } else {
                        "https://music.youtube.com/browse/$id"
                    }
                    val sendIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, url)
                    }
                    context.startActivity(Intent.createChooser(sendIntent, target.title))
                    state.target = null
                }
            },
        isPinned = pinnableId != null && pinnableId in pinnedPlaylists,
        onTogglePin = pinnableId?.let { id ->
            {
                val nowPinned = AppSettings.togglePinnedPlaylist(id)
                if (!nowPinned && id !in pinnedPlaylists) {
                    Toast.makeText(
                        context,
                        context.getString(
                            R.string.pinned_playlist_limit,
                            AppSettings.MAX_PINNED_PLAYLISTS,
                        ),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                state.target = null
            }
        },
        onRename = playlist?.let { p ->
            { name: String ->
                state.target = null
                viewModel.renamePlaylist(p, name)
            }
        } ?: offlineEditable?.let { p ->
            { name: String ->
                state.target = null
                OfflinePlaylists.rename(p.browseId, name)
                viewModel.retitleDetail(p.browseId, name.trim())
            }
        },
        onReorder = playlist?.let { p ->
            {
                state.target = null
                actions.reorder(p)
            }
        },
        onDelete = playlist?.let { p ->
            {
                state.target = null
                viewModel.deletePlaylist(p)
            }
        } ?: offlineEditable?.let { p ->
            {
                state.target = null
                // Its page has nothing left to show; take it away too.
                if (openDetailId == p.browseId) viewModel.closeDetail()
                OfflinePlaylists.delete(p.browseId)
            }
        },
        onDeleteDownload = target.downloadId?.let { id ->
            {
                state.target = null
                scope.launch { Downloads.deleteCollection(context, id) }
            }
        },
    )
}
