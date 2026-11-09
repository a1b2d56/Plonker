package com.music.bitchord.ui.navigation

import android.content.Intent
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.session.MediaController
import com.music.bitchord.R
import com.music.bitchord.data.NerdStats
import com.music.bitchord.data.OfflinePlaylists
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.LikeStatus
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.playback.AudioCache
import com.music.bitchord.playback.hasYouTubeOriginal
import com.music.bitchord.playback.revertToOriginal
import com.music.bitchord.playback.upgradeQuality
import com.music.bitchord.playingYouTubesOwn
import com.music.bitchord.shouldSkipAfterDislike
import com.music.bitchord.ui.MainViewModel
import com.music.bitchord.ui.components.SongActionsMenu
import com.music.bitchord.ui.player.MixViewRequest
import com.music.bitchord.ui.screens.CACHE_FOLDER_BROWSE_ID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What the track menu reaches that belongs to the app rather than the menu:
 * the queue, the player, downloads, and the playlist picker it hands on to.
 *
 * Properties are read when the menu asks, never captured, so a state the app
 * holds (the pinned set, the controller, the open page) is read in the menu's
 * own recompose scope and a change to it redraws the menu, not the whole app.
 */
interface SongMenuEnv {
    /** Opened from the player's ⋯ — see the activity's `menuFromPlayer`. */
    val fromPlayer: Boolean
    val fromHistory: Boolean
    val isSignedIn: Boolean
    /** The release page on screen, if any. */
    val openDetailId: String?
    val webDavServerUrl: String
    /** The player's own album/artist lookup for its current track. */
    val playerLinksLoading: Boolean
    /** Tracks held on YouTube's own upload — see [com.music.bitchord.playback.OriginalVersion]. */
    val pinnedTracks: Set<String>
    val upgradesInFlight: Set<String>
    /** Tracks whose other cut a switch looked for and didn't find. */
    val versionsNotFound: Collection<String>
    val mediaController: MediaController?
    val playingVideoId: String?
    val coroutineScope: CoroutineScope

    fun close()
    fun closePlayer()
    fun endPlayback()
    fun likeStatus(song: Song): LikeStatus
    fun toggleLike(song: Song)
    fun queueNext(song: Song)
    fun queueLast(song: Song)
    fun radioFrom(song: Song)
    fun download(song: Song)
    fun downloadCached(songs: List<Song>)
    fun evictFromCache(songs: List<Song>)
    fun uploadToServer(songs: List<Song>)
    /** Hands [song] on to the right playlist picker (device or YouTube). */
    fun addToPlaylist(song: Song)
    fun openLyricsOffset()
    /**
     * Whether [song] is playing as its audio version, and the switch to the
     * other one (null when it isn't the playing track).
     */
    fun versionRow(song: Song): Pair<Boolean, (() -> Unit)?>
}

/**
 * The track menu's rows — one body for every frame the menu comes in: the
 * panel hung off a ⋮ or the player's ⋯, and the one a held row lifts into.
 * Every callback is the same in each; only the frame differs.
 */
@Composable
fun SongMenuBody(heldSong: Song, env: SongMenuEnv, viewModel: MainViewModel) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    // Set by whoever opened it — see [SongMenuEnv.fromPlayer]. It cannot be
    // read off the player's own visibility any more, because on a
    // tablet the player is visible whatever the menu was opened from.
    val fromPlayer = env.fromPlayer
    // A row from search results, recents or a feed rarely knows which
    // album or artist page it belongs to, which left the menu with no
    // way to get there. Ask as the menu opens, the way the player
    // backfills its own track — the player's copy is already kept in
    // sync by the activity, so it is left alone here.
    val needsLinks = !fromPlayer && heldSong.localUri == null &&
        heldSong.videoId.isNotBlank() &&
        (heldSong.albumId == null || heldSong.artistId == null)
    var menuLinks by remember(heldSong.videoId) { mutableStateOf<Song?>(null) }
    var menuLinksLoading by remember(heldSong.videoId) { mutableStateOf(needsLinks) }
    LaunchedEffect(heldSong.videoId, needsLinks) {
        if (!needsLinks) return@LaunchedEffect
        menuLinksLoading = true
        menuLinks = YtMusicRepository.trackLinks(heldSong.videoId).getOrNull()
        menuLinksLoading = false
    }
    val song = menuLinks?.takeIf { needsLinks && it.videoId == heldSong.videoId }?.let { extra ->
        heldSong.copy(
            artistId = heldSong.artistId ?: extra.artistId,
            albumId = heldSong.albumId ?: extra.albumId,
            albumName = heldSong.albumName ?: extra.albumName,
        )
    } ?: heldSong
    val share: () -> Unit = {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "https://music.youtube.com/watch?v=${song.videoId}")
        }
        context.startActivity(Intent.createChooser(sendIntent, song.title))
        env.close()
    }
    // Navigating has to take the player down with the sheet, or the
    // page it opens lands behind a still-covering player.
    // The track's cover stands in for an album's, but never for an
    // artist's picture — that page loads its own.
    val openPage: (String, String, String, BrowseType) -> Unit = { id, title, sub, type ->
        env.close()
        env.closePlayer()
        val art = song.thumbnailUrl.takeUnless { type == BrowseType.ARTIST }
        viewModel.openDetail(id, title, sub, art, type)
    }
    // Which recording is playing, and the switch to the other one, are
    // the app's: the conversion state lives with the playback code.
    val (menuIsAudioVersion, onToggleVersion) = env.versionRow(song)
    // The library toggle needs tokens only YouTube can mint, and the
    // rating it comes back with is more authoritative than anything
    // the library feed knew — so the menu asks as it opens.
    LaunchedEffect(song.videoId) { viewModel.loadSongMenu(song.videoId) }
    // "Remove from this playlist" is only a sentence on a playlist
    // page the account can actually edit, and only for a row that
    // carries the per-entry id a removal is expressed in.
    val editable = viewModel.editablePlaylist(env.openDetailId)
        ?.takeIf { !fromPlayer && song.setVideoId != null }
    // Asked of the cache's index off the main thread as the menu opens,
    // so "Remove from cache" is only offered for a track it holds.
    // Re-asked when the cache changes, which is what drops the row
    // once the track has gone.
    val automixOn by AppSettings.smartFadeEnabled.collectAsStateWithLifecycle()
    val cacheVersion by AudioCache.contentsChanged.collectAsStateWithLifecycle()
    val inCache by produceState(false, song.videoId, cacheVersion) {
        value = song.localUri == null &&
            withContext(Dispatchers.IO) { AudioCache.holds(song.videoId) }
    }
    SongActionsMenu(
        song = song,
        signedIn = env.isSignedIn,
        likeStatus = env.likeStatus(song),
        onPlayNext = { env.queueNext(song); env.close() },
        onAddToQueue = { env.queueLast(song); env.close() },
        onStartRadio = { env.radioFrom(song); env.close() },
        // Stays open: the row it replaces itself with is the
        // progress, and closing the sheet would hide the only
        // answer to "did that work?".
        // From the Cached songs folder, the cache's own copy is the
        // download — the same as the row's download button there.
        onDownload = {
            if (!fromPlayer && env.openDetailId == CACHE_FOLDER_BROWSE_ID) {
                env.downloadCached(listOf(song))
            } else {
                env.download(song)
            }
        },
        // The other direction: a device file going up to the
        // server. Closed first, unlike a download — progress and
        // the summary notice live outside the sheet.
        onUploadToWebDav =
            if (com.music.bitchord.data.webdav.WebDavConfig.isConfigured(env.webDavServerUrl) &&
                com.music.bitchord.data.webdav.WebDavUploads.isUploadable(song)
            ) {
                {
                    env.close()
                    env.uploadToServer(listOf(song))
                }
            } else {
                null
            },
        // The sheet stays up for a rating: it shows the new state
        // in place, and people often thumb a song and then queue it.
        onToggleLike = { env.toggleLike(song) },
        onToggleDislike = {
            val previousStatus = viewModel.toggleDislike(song.videoId)
            if (
                previousStatus != null &&
                shouldSkipAfterDislike(
                    previousStatus = previousStatus,
                    targetVideoId = song.videoId,
                    currentVideoId = env.playingVideoId,
                )
            ) {
                env.mediaController?.seekToNextMediaItem()
            }
        },
        onAddToPlaylist = {
            env.close()
            env.addToPlaylist(song)
        },
        onRemoveFromPlaylist = editable?.let {
            {
                env.close()
                viewModel.removeFromPlaylist(it.browseId, song)
            }
        } ?: env.openDetailId
            ?.takeIf { !fromPlayer && song.localUri != null && OfflinePlaylists.isOfflinePlaylist(it) }
            ?.let { id ->
                {
                    env.close()
                    OfflinePlaylists.remove(id, song)
                }
            },
        onRemoveFromCache = if (inCache) {
            {
                env.close()
                env.evictFromCache(listOf(song))
            }
        } else {
            null
        },
        onRemoveFromHistory = song.feedbackToken?.takeIf { env.fromHistory }?.let { token ->
            {
                env.close()
                viewModel.removeFromHistory(token, onFailure = {
                    Toast.makeText(context, R.string.remove_from_history_failed, Toast.LENGTH_SHORT).show()
                })
            }
        },
        onOpenAlbum = { id ->
            openPage(
                id,
                song.albumName ?: song.title,
                song.artist,
                BrowseType.ALBUM,
            )
        },
        onOpenArtist = { id ->
            openPage(id, song.artist, context.getString(R.string.artist), BrowseType.ARTIST)
        },
        // The player backfills its own track; any other row is
        // looked up as the menu opens — see `needsLinks` above.
        resolvingLinks = if (fromPlayer) env.playerLinksLoading else needsLinks && menuLinksLoading,
        showSleepTimer = fromPlayer,
        // Offered for every playing track with a YouTube upload
        // behind it, not only for one an upgrade visibly swapped:
        // a source ranked above YouTube can be playing its own
        // idea of the song from the first second, and a wrong
        // match sounds like a wrong match whether or not anything
        // announced itself. See [Song.hasYouTubeOriginal].
        onRollbackToOriginal = if (fromPlayer &&
            song.hasYouTubeOriginal() &&
            // Nothing to revert *from*: the listener is hearing a
            // file they saved, not a stream anything chose.
            song.localUri == null &&
            // Already there, and the menu says so with the row
            // below instead.
            song.videoId !in env.pinnedTracks &&
            // And the same for a track that got back here without
            // the listener asking: an upgrade that failed to prove
            // itself is reverted automatically and pins nothing, so
            // this row was being offered for a track already on
            // YouTube's own stream, where it does nothing.
            !playingYouTubesOwn(song.videoId, env.mediaController) &&
            env.mediaController?.currentMediaItem?.mediaId == song.videoId
        ) {
            {
                env.mediaController?.revertToOriginal()
                env.close()
            }
        } else {
            null
        },
        // The way back, and for a pinned track the only one: it is
        // held off the automatic search on purpose, so nothing but
        // this will ever offer it a better copy again. Also shown
        // for a track whose upgrade failed and was reverted, which
        // is likewise sitting on YouTube's own stream with nothing
        // due to look at it again — [QualityUpgrade.refuseUpgrades]
        // takes a broken track off the automatic path for the rest
        // of the session, and `askByHand` is what clears that.
        onUpgradeQuality = if (fromPlayer &&
            (
                song.videoId in env.pinnedTracks ||
                    playingYouTubesOwn(song.videoId, env.mediaController)
                ) &&
            // A track playing off a file the listener saved is not
            // playing a stream anything could upgrade — the pin on
            // it is only waiting for the day it is streamed again.
            song.localUri == null &&
            env.mediaController?.currentMediaItem?.mediaId == song.videoId
        ) {
            {
                env.mediaController?.upgradeQuality()
                env.close()
            }
        } else {
            null
        },
        upgradeQualityInProgress = fromPlayer && song.videoId in env.upgradesInFlight,
        onToggleAudioVersion = onToggleVersion,
        isAudioVersion = menuIsAudioVersion,
        audioVersionMissing = song.videoId in env.versionsNotFound,
        // Hidden outright when there's no real YouTube id behind
        // this row to build a link from — SongActionsMenu already
        // drops it for a local file via `isOffline`, this catches
        // the rest.
        onShare = share.takeIf { song.videoId.isNotBlank() },
        onSaveArtwork = {
            env.close()
            env.coroutineScope.launch {
                com.music.bitchord.data.artwork.ArtworkDownload.save(context, song).fold(
                    onSuccess = { name ->
                        Toast.makeText(
                            context,
                            context.getString(R.string.cover_saved, name),
                            Toast.LENGTH_SHORT,
                        ).show()
                    },
                    onFailure = {
                        Toast.makeText(
                            context,
                            context.getString(R.string.cover_save_failed),
                            Toast.LENGTH_SHORT,
                        ).show()
                    },
                )
            }
        },
        onCopyLog = if (fromPlayer) {
            {
                env.close()
                env.coroutineScope.launch {
                    val text = TrackLog.forTrack(song, NerdStats.current.value)
                    clipboard.setText(AnnotatedString(text))
                    // The line count, not just "copied": it is the
                    // one thing the system's own paste confirmation
                    // doesn't say, and an empty log is a real
                    // outcome worth seeing rather than a silent one.
                    Toast.makeText(
                        context,
                        context.resources.getQuantityString(
                            R.plurals.log_copied_line_count,
                            text.lineSequence().count(),
                            text.lineSequence().count(),
                        ),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        } else {
            null
        },
        onLyricsOffset = if (fromPlayer) {
            {
                env.close()
                env.openLyricsOffset()
            }
        } else {
            null
        },
        onDismissPlayback = if (fromPlayer || env.playingVideoId == song.videoId) {
            {
                env.close()
                env.endPlayback()
            }
        } else {
            null
        },
        // The popup lives in the player, so the menu only has to close
        // and ask for it; it lays the next transition out on its own.
        onAutomixOverview = if (fromPlayer && automixOn) {
            {
                env.close()
                MixViewRequest.open = true
            }
        } else {
            null
        },
    )
}
