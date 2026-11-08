package com.music.bitchord.playback

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.guava.future

/**
 * The session's callback: who may connect, the app's own commands
 * ([perform]), and browsing and content resolution for Android Auto and
 * other MediaBrowser clients, which are [LibraryBrowser]'s.
 */
internal class SessionCallback(
    private val scope: CoroutineScope,
    private val library: LibraryBrowser,
    private val buttons: SessionButtons,
    private val player: () -> ExoPlayer?,
    /** Carries out an app command; false for one the service doesn't know. */
    private val perform: (session: MediaSession, action: String, args: Bundle) -> Boolean,
) : MediaLibrarySession.Callback {

    override fun onConnect(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): MediaSession.ConnectionResult {
        val sessionCommands = SessionCommands.available()
        return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
            .setAvailablePlayerCommands(MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS)
            .setAvailableSessionCommands(sessionCommands)
            .build()
    }

    override fun onPostConnect(session: MediaSession, controller: MediaSession.ControllerInfo) {
        buttons.onConnected(session, controller)
    }

    override fun onDisconnected(session: MediaSession, controller: MediaSession.ControllerInfo) {
        buttons.onDisconnected(controller)
    }

    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle,
    ): ListenableFuture<SessionResult> {
        if (!perform(session, customCommand.customAction, args)) {
            return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
        }
        // The actual YouTube rating is asynchronous. The command itself has been accepted;
        // the notification is refreshed when the network write completes.
        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
    }

    // The browse tree, its search and the item lookups are [library]'s;
    // see [LibraryBrowser].
    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<MediaItem>> = library.onGetLibraryRoot(params)

    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
        library.onGetChildren(session, browser, parentId, page, pageSize, params)

    override fun onGetItem(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String,
    ): ListenableFuture<LibraryResult<MediaItem>> = library.onGetItem(mediaId)

    override fun onPlaybackResumption(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future(Dispatchers.IO) {
        // 1. If player currently holds items in its queue, resume from it
        val activePlayer = player()
        if (activePlayer != null && activePlayer.mediaItemCount > 0) {
            val currentItems = (0 until activePlayer.mediaItemCount).map { activePlayer.getMediaItemAt(it) }
            val startIndex = activePlayer.currentMediaItemIndex.coerceIn(0, currentItems.size - 1)
            val startPositionMs = activePlayer.currentPosition.coerceAtLeast(0L)
            return@future MediaSession.MediaItemsWithStartPosition(
                ImmutableList.copyOf(currentItems),
                startIndex,
                startPositionMs,
            )
        }

        // 2. Nothing queued: what the library can offer a headset's play
        // button — see [LibraryBrowser.resumptionFallback].
        library.resumptionFallback()
    }

    override fun onSearch(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<Void>> = library.onSearch(session, browser, query, params)

    override fun onGetSearchResult(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
        library.onGetSearchResult(query, page, pageSize, params)

    override fun onSetMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future(Dispatchers.IO) {
        val resolved = library.resolveMediaItems(mediaItems)
        val validIndex = startIndex.coerceIn(0, (resolved.size - 1).coerceAtLeast(0))
        MediaSession.MediaItemsWithStartPosition(
            ImmutableList.copyOf(resolved),
            validIndex,
            startPositionMs.coerceAtLeast(0L),
        )
    }

    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>,
    ): ListenableFuture<List<MediaItem>> = scope.future(Dispatchers.IO) {
        library.resolveMediaItems(mediaItems)
    }
}
