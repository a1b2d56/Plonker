package com.music.bitchord.playback

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.music.bitchord.R
import com.music.bitchord.data.LikeState
import com.music.bitchord.data.LocalMediaRepository
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.innertube.InnertubeParser
import com.music.bitchord.data.model.BrowseItem
import com.music.bitchord.data.model.NOTIFICATION_ART_PX
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.artworkAt
import com.music.bitchord.download.Downloads
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The media library Android Auto and every other media browser sees: the root
 * and its folders (Quick Picks, Recents, Playlists, Liked, and under More the
 * downloads and the device's own music), a playlist or album's tracks, item
 * lookups, search, and what a browser's request to play something resolves to.
 *
 * The feeds behind the folders are cached briefly and revalidated in the
 * background (see [refreshFolderInBackground]): Auto re-asks for a folder
 * every time it is opened, including just backing out and back in.
 *
 * [PlaybackService]'s session callback hands every browse call here; what a
 * session does with the player (custom commands, resuming the queue it holds)
 * stays there.
 */
@UnstableApi
class LibraryBrowser(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private val searchResults = ConcurrentHashMap<String, List<Song>>()
    private val searchBrowseResults = ConcurrentHashMap<String, List<BrowseItem>>()
    private val songCache = ConcurrentHashMap<String, Song>()

    /**
     * [YtMusicRepository.home] cached briefly for Android Auto's browse tree.
     *
     * Recents and Quick Picks each read the same home feed, and Auto re-runs
     * `onGetChildren` every time either folder is opened — including just
     * backing out and back in. Without this, one glance at Quick Picks after
     * Recents paid for the full three-way home fetch twice in a row, which is
     * most of what read as lag browsing the car UI. The window is short
     * enough that a pull-to-refresh-style wait for genuinely new content
     * never has to wait this long for it.
     */
    /**
     * [YtMusicRepository.recents] cached for Android Auto's browse tree.
     */
    private var cachedRecents: Pair<Long, List<Song>>? = null
    private val recentsMutex = Mutex()
    private val RECENTS_CACHE_TTL_MS = 30_000L

    suspend fun cachedRecentsSongs(): List<Song> = recentsMutex.withLock {
        cachedRecents?.let { (at, songs) ->
            if (SystemClock.elapsedRealtime() - at < RECENTS_CACHE_TTL_MS && songs.isNotEmpty()) return songs
        }
        val songs = try {
            withTimeoutOrNull(4000L) {
                YtMusicRepository.recents().getOrNull()
            } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        if (songs.isNotEmpty()) {
            cachedRecents = SystemClock.elapsedRealtime() to songs
            songs.forEach { songCache[it.videoId] = it }
        }
        songs
    }

    /**
     * [YtMusicRepository.quickPicks] cached for Android Auto's browse tree.
     * Excludes listening history songs to guarantee strictly unique data.
     */
    private var cachedQuickPicks: Pair<Long, List<Song>>? = null
    private val quickPicksMutex = Mutex()
    private val QUICK_PICKS_CACHE_TTL_MS = 60_000L

    private suspend fun cachedQuickPicksSongs(): List<Song> = quickPicksMutex.withLock {
        cachedQuickPicks?.let { (at, songs) ->
            if (SystemClock.elapsedRealtime() - at < QUICK_PICKS_CACHE_TTL_MS && songs.isNotEmpty()) return songs
        }
        val recentsIds = cachedRecents?.second?.mapTo(HashSet()) { it.videoId } ?: emptySet()
        val songs = try {
            withTimeoutOrNull(4000L) {
                YtMusicRepository.quickPicks(excludeSongIds = recentsIds).getOrNull()
            } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        if (songs.isNotEmpty()) {
            cachedQuickPicks = SystemClock.elapsedRealtime() to songs
            songs.forEach { songCache[it.videoId] = it }
        }
        songs
    }

    /**
     * [YtMusicRepository.browseSongs] for Liked Music cached for Android Auto's browse tree.
     */
    private var cachedLiked: Pair<Long, List<Song>>? = null
    private var cachedLikedCookie: String? = null
    private val likedMutex = Mutex()
    private val LIKED_CACHE_TTL_MS = 60_000L

    private suspend fun cachedLikedSongs(): List<Song> = likedMutex.withLock {
        val cookie = com.music.bitchord.data.innertube.Innertube.cookie
        if (cookie == null) {
            cachedLiked = null
            cachedLikedCookie = null
            return emptyList()
        }
        if (cachedLikedCookie != null && cachedLikedCookie != cookie) {
            cachedLiked = null
        }
        cachedLiked?.let { (at, songs) ->
            if (SystemClock.elapsedRealtime() - at < LIKED_CACHE_TTL_MS && songs.isNotEmpty()) return songs
        }
        val songs = try {
            withTimeoutOrNull(4000L) {
                YtMusicRepository.browseSongs(YtMusicRepository.LIKED_MUSIC).getOrNull()?.songs?.ifEmpty { null }
                    ?: YtMusicRepository.browseSongs("LM").getOrNull()?.songs?.ifEmpty { null }
                    ?: run {
                        val libPlaylists = YtMusicRepository.libraryPlaylists().getOrNull()
                        val likedCard = libPlaylists?.firstOrNull {
                            it.browseId == "VLLM" || it.title.contains("liked", ignoreCase = true)
                        }
                        val likedBrowseId = likedCard?.browseId
                        if (likedBrowseId != null) {
                            YtMusicRepository.browseSongs(likedBrowseId).getOrNull()?.songs
                        } else null
                    }
                    ?: YtMusicRepository.browseSongs("FEmusic_liked_videos").getOrNull()?.songs
            } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        if (songs.isNotEmpty()) {
            cachedLiked = SystemClock.elapsedRealtime() to songs
            cachedLikedCookie = cookie
            songs.forEach { songCache[it.videoId] = it }
            LikeState.seedLiked(songs.mapTo(HashSet()) { it.videoId })
        }
        songs
    }

    private var cachedHome: Pair<Long, com.music.bitchord.data.model.HomeFeed>? = null
    private val homeMutex = Mutex()
    private val HOME_CACHE_TTL_MS = 60_000L
    private val HOME_NETWORK_TIMEOUT_MS = 2_500L

    private suspend fun cachedHomeFeed(): com.music.bitchord.data.model.HomeFeed? = homeMutex.withLock {
        cachedHome?.let { (at, feed) ->
            if (SystemClock.elapsedRealtime() - at < HOME_CACHE_TTL_MS) return feed
        }
        val feed = try {
            withTimeoutOrNull(HOME_NETWORK_TIMEOUT_MS) {
                YtMusicRepository.home().getOrNull()
            }
        } catch (_: Exception) {
            null
        } ?: return null
        cachedHome = SystemClock.elapsedRealtime() to feed
        feed
    }

    private var cachedPlaylistCards: Pair<Long, List<ShelfItem>>? = null
    private var cachedPlaylistCookie: String? = null
    private val playlistsMutex = Mutex()
    private val PLAYLISTS_CACHE_TTL_MS = 60_000L

    private suspend fun fetchPlaylistCards(): List<ShelfItem> = try {
        withTimeoutOrNull(3_000L) {
            YtMusicRepository.libraryPlaylists().getOrNull()
                ?: YtMusicRepository.userPlaylists().getOrNull()?.map {
                    ShelfItem(
                        title = it.title,
                        subtitle = it.subtitle,
                        thumbnailUrl = it.thumbnailUrl,
                        videoId = null,
                        browseId = "VL${it.playlistId}",
                    )
                }
        } ?: emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    private suspend fun cachedPlaylistCards(): List<ShelfItem> = playlistsMutex.withLock {
        val cookie = com.music.bitchord.data.innertube.Innertube.cookie
        if (cookie == null) {
            cachedPlaylistCards = null
            cachedPlaylistCookie = null
            return emptyList()
        }
        if (cachedPlaylistCookie != null && cachedPlaylistCookie != cookie) {
            cachedPlaylistCards = null
        }
        cachedPlaylistCards?.let { (at, cards) ->
            if (SystemClock.elapsedRealtime() - at < PLAYLISTS_CACHE_TTL_MS && cards.isNotEmpty()) return cards
        }
        val cards = fetchPlaylistCards()
        if (cards.isNotEmpty()) {
            cachedPlaylistCards = SystemClock.elapsedRealtime() to cards
            cachedPlaylistCookie = cookie
        }
        cards
    }

    private fun snapshotRecents(): List<Song> = cachedRecents?.second.orEmpty()
    private fun snapshotQuickPicks(): List<Song> = cachedQuickPicks?.second.orEmpty()
    private fun snapshotLiked(): List<Song> {
        val cookie = com.music.bitchord.data.innertube.Innertube.cookie
        return if (cookie != null && cookie == cachedLikedCookie) cachedLiked?.second.orEmpty() else emptyList()
    }
    private fun snapshotPlaylistCards(): List<ShelfItem> {
        val cookie = com.music.bitchord.data.innertube.Innertube.cookie
        return if (cookie != null && cookie == cachedPlaylistCookie) cachedPlaylistCards?.second.orEmpty() else emptyList()
    }
    private fun snapshotHome(): com.music.bitchord.data.model.HomeFeed? = cachedHome?.second

    private fun isFresh(at: Long, ttlMs: Long): Boolean =
        SystemClock.elapsedRealtime() - at < ttlMs

    private fun isRecentsFresh(): Boolean =
        cachedRecents?.let { (at, songs) -> songs.isNotEmpty() && isFresh(at, RECENTS_CACHE_TTL_MS) } ?: false

    private fun isQuickPicksFresh(): Boolean =
        cachedQuickPicks?.let { (at, songs) -> songs.isNotEmpty() && isFresh(at, QUICK_PICKS_CACHE_TTL_MS) } ?: false

    private fun isLikedFresh(): Boolean =
        snapshotLiked().isNotEmpty() && cachedLiked?.let { (at, _) -> isFresh(at, LIKED_CACHE_TTL_MS) } == true

    private fun isPlaylistsFresh(): Boolean =
        snapshotPlaylistCards().isNotEmpty() && cachedPlaylistCards?.let { (at, _) -> isFresh(at, PLAYLISTS_CACHE_TTL_MS) } == true

    private fun refreshFolderInBackground(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        params: LibraryParams?,
        fetch: suspend () -> Int,
    ) {
        scope.launch(Dispatchers.IO) {
            try {
                val freshCount = fetch()
                if (freshCount > 0) {
                    session.notifyChildrenChanged(browser, parentId, freshCount, params)
                }
            } catch (_: Exception) {
                // The existing snapshot remains usable when revalidation fails.
            }
        }
    }

    fun onGetLibraryRoot(
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<MediaItem>> {
        val isRecent = params?.isRecent == true ||
            params?.extras?.getBoolean("android.service.media.extra.RECENT") == true ||
            params?.extras?.getBoolean("androidx.media.MediaBrowserCompat.EXTRA_RECENT") == true

        val rootId = if (isRecent) MEDIA_RECENTS_ID else MEDIA_ROOT_ID
        val rootExtras = buildContentStyleBundle(
            CONTENT_STYLE_GRID_ITEM_HINT_VALUE,
            CONTENT_STYLE_LIST_ITEM_HINT_VALUE,
        )
        val rootItem = MediaItem.Builder()
            .setMediaId(rootId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(context.getString(R.string.app_name))
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setFolderType(MediaMetadata.FOLDER_TYPE_MIXED)
                    .setExtras(rootExtras)
                    .build(),
            )
            .build()
        val rootParams = LibraryParams.Builder()
            .setExtras(rootExtras)
            .build()
        scope.launch(Dispatchers.IO) {
            try {
                cachedHomeFeed()
            } catch (_: Exception) {}
        }

        return Futures.immediateFuture(LibraryResult.ofItem(rootItem, rootParams))
    }

    fun onGetChildren(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future(Dispatchers.IO) {
        val isGridFolder = parentId == MEDIA_ROOT_ID || parentId == MEDIA_PLAYLISTS_ID
        val items: List<MediaItem> = when (parentId) {
            MEDIA_ROOT_ID -> listOf(
                createFolderItem(MEDIA_QUICK_PICKS_ID, context.getString(R.string.auto_quick_picks), folderType = MediaMetadata.FOLDER_TYPE_PLAYLISTS),
                createFolderItem(MEDIA_RECENTS_ID, context.getString(R.string.auto_recents), folderType = MediaMetadata.FOLDER_TYPE_PLAYLISTS),
                createFolderItem(MEDIA_PLAYLISTS_ID, context.getString(R.string.playlists), folderType = MediaMetadata.FOLDER_TYPE_PLAYLISTS, isGrid = true),
                createFolderItem(MEDIA_LIKED_ID, context.getString(R.string.auto_liked), folderType = MediaMetadata.FOLDER_TYPE_PLAYLISTS),
                createFolderItem(MEDIA_MORE_ID, context.getString(R.string.more), folderType = MediaMetadata.FOLDER_TYPE_MIXED),
            )
            MEDIA_MORE_ID -> listOf(
                createFolderItem(MEDIA_DOWNLOADS_ID, context.getString(R.string.downloads), folderType = MediaMetadata.FOLDER_TYPE_MIXED),
                createFolderItem(MEDIA_LOCAL_MUSIC_ID, context.getString(R.string.local_music), folderType = MediaMetadata.FOLDER_TYPE_MIXED),
            )
            MEDIA_RECENTS_ID -> {
                val snapshot = snapshotRecents()
                val songs = if (snapshot.isNotEmpty()) {
                    if (!isRecentsFresh()) {
                        refreshFolderInBackground(session, browser, parentId, params) {
                            cachedRecentsSongs().size
                        }
                    }
                    snapshot
                } else {
                    cachedRecentsSongs()
                }
                songs.forEach { songCache[it.videoId] = it }
                if (songs.isNotEmpty()) songs.map { it.toMediaItem() } else listOf(
                    createInfoItem(
                        "msg:empty_recents",
                        context.getString(R.string.auto_empty_recents_title),
                        context.getString(R.string.auto_empty_recents_subtitle),
                    ),
                )
            }
            MEDIA_QUICK_PICKS_ID -> {
                val snapshot = snapshotQuickPicks()
                val songs = if (snapshot.isNotEmpty()) {
                    if (!isQuickPicksFresh()) {
                        refreshFolderInBackground(session, browser, parentId, params) {
                            cachedQuickPicksSongs().size
                        }
                    }
                    snapshot
                } else {
                    cachedQuickPicksSongs().ifEmpty {
                        snapshotHome()?.shelves?.flatMap { it.items }
                            ?.mapNotNull { it.toSongOrNull() }
                            ?.distinctBy { it.videoId }
                            .orEmpty()
                    }
                }
                songs.forEach { songCache[it.videoId] = it }
                if (songs.isNotEmpty()) songs.map { it.toMediaItem() } else listOf(
                    createInfoItem(
                        "msg:empty_quick_picks",
                        context.getString(R.string.auto_empty_quick_picks_title),
                        context.getString(R.string.auto_empty_quick_picks_subtitle),
                    ),
                )
            }
            MEDIA_LIKED_ID -> {
                if (com.music.bitchord.data.innertube.Innertube.cookie == null) {
                    listOf(createLoginPromptItem())
                } else {
                    val snapshot = snapshotLiked()
                    val songs = if (snapshot.isNotEmpty()) {
                        if (!isLikedFresh()) {
                            refreshFolderInBackground(session, browser, parentId, params) {
                                cachedLikedSongs().size
                            }
                        }
                        snapshot
                    } else {
                        cachedLikedSongs()
                    }
                    songs.forEach { songCache[it.videoId] = it }
                    if (songs.isNotEmpty()) {
                        songs.map { it.toMediaItem() }
                    } else {
                        listOf(
                            createInfoItem(
                                "msg:empty_liked",
                                context.getString(R.string.auto_empty_liked_title),
                                context.getString(R.string.auto_empty_liked_subtitle),
                            ),
                        )
                    }
                }
            }
            MEDIA_DOWNLOADS_ID -> {
                val downloaded = Downloads.getDownloadedSongs(context)
                downloaded.forEach { songCache[it.videoId] = it }
                if (downloaded.isNotEmpty()) downloaded.map { it.toMediaItem() } else listOf(
                    createInfoItem(
                        "msg:empty_downloads",
                        context.getString(R.string.auto_empty_offline_title),
                        context.getString(R.string.auto_empty_offline_subtitle),
                    ),
                )
            }
            MEDIA_PLAYLISTS_ID -> {
                if (com.music.bitchord.data.innertube.Innertube.cookie == null) {
                    listOf(createLoginPromptItem())
                } else {
                    val snapshot = snapshotPlaylistCards()
                    val playlists = if (snapshot.isNotEmpty()) {
                        if (!isPlaylistsFresh()) {
                            refreshFolderInBackground(session, browser, parentId, params) {
                                cachedPlaylistCards().size
                            }
                        }
                        snapshot
                    } else {
                        cachedPlaylistCards()
                    }
                    if (playlists.isNotEmpty()) playlists.map(::shelfToGridPlaylistItem) else listOf(
                        createInfoItem(
                            "msg:empty_playlists",
                            context.getString(R.string.auto_empty_playlists_title),
                            context.getString(R.string.auto_empty_playlists_subtitle),
                        ),
                    )
                }
            }
            MEDIA_LOCAL_MUSIC_ID -> {
                val local = LocalMediaRepository.getLocalMusic(context)
                local.forEach { songCache[it.videoId] = it }
                if (local.isNotEmpty()) local.map { it.toMediaItem() } else listOf(
                    createInfoItem(
                        "msg:empty_local",
                        context.getString(R.string.auto_empty_offline_title),
                        context.getString(R.string.auto_empty_offline_subtitle),
                    ),
                )
            }
            else -> {
                when {
                    parentId.startsWith("playlist:") -> {
                        val playlistId = parentId.removePrefix("playlist:")
                        val songs = try {
                            withTimeoutOrNull(2500L) {
                                YtMusicRepository.browseSongs("VL$playlistId").getOrNull()?.songs
                                    ?: YtMusicRepository.browseSongs(playlistId).getOrNull()?.songs
                            } ?: emptyList()
                        } catch (_: Exception) {
                            emptyList()
                        }
                        songs.forEach { songCache[it.videoId] = it }
                        songs.map { it.toMediaItem() }
                    }
                    parentId.startsWith("browse:") -> {
                        val browseId = parentId.removePrefix("browse:")
                        val songs = try {
                            withTimeoutOrNull(2500L) {
                                YtMusicRepository.browseSongs(browseId).getOrNull()?.songs
                            } ?: emptyList()
                        } catch (_: Exception) {
                            emptyList()
                        }
                        songs.forEach { songCache[it.videoId] = it }
                        songs.map { it.toMediaItem() }
                    }
                    else -> {
                        val cachedSong = songCache[parentId]
                        if (cachedSong != null) {
                            listOf(cachedSong.toMediaItem())
                        } else {
                            emptyList()
                        }
                    }
                }
            }
        }
        val returnParams = LibraryParams.Builder().setExtras(
            buildContentStyleBundle(
                if (isGridFolder) CONTENT_STYLE_GRID_ITEM_HINT_VALUE else CONTENT_STYLE_LIST_ITEM_HINT_VALUE,
                CONTENT_STYLE_LIST_ITEM_HINT_VALUE,
            ),
        ).build()
        LibraryResult.ofItemList(ImmutableList.copyOf(paginate(items, page, pageSize)), returnParams)
    }

    fun onGetItem(
        mediaId: String,
    ): ListenableFuture<LibraryResult<MediaItem>> = scope.future(Dispatchers.IO) {
        val item = when (mediaId) {
            MEDIA_ROOT_ID -> MediaItem.Builder()
                .setMediaId(MEDIA_ROOT_ID)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(context.getString(R.string.app_name))
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .setFolderType(MediaMetadata.FOLDER_TYPE_MIXED)
                        .build(),
                ).build()
            MEDIA_RECENTS_ID -> createFolderItem(MEDIA_RECENTS_ID, context.getString(R.string.auto_recents), folderType = MediaMetadata.FOLDER_TYPE_PLAYLISTS)
            MEDIA_QUICK_PICKS_ID -> createFolderItem(MEDIA_QUICK_PICKS_ID, context.getString(R.string.auto_quick_picks), folderType = MediaMetadata.FOLDER_TYPE_PLAYLISTS)
            MEDIA_PLAYLISTS_ID -> createFolderItem(MEDIA_PLAYLISTS_ID, context.getString(R.string.playlists), folderType = MediaMetadata.FOLDER_TYPE_PLAYLISTS, isGrid = true)
            MEDIA_MORE_ID -> createFolderItem(MEDIA_MORE_ID, context.getString(R.string.more), folderType = MediaMetadata.FOLDER_TYPE_MIXED)
            MEDIA_LIKED_ID -> createFolderItem(MEDIA_LIKED_ID, context.getString(R.string.auto_liked), folderType = MediaMetadata.FOLDER_TYPE_PLAYLISTS)
            MEDIA_DOWNLOADS_ID -> createFolderItem(MEDIA_DOWNLOADS_ID, context.getString(R.string.downloads))
            MEDIA_LOCAL_MUSIC_ID -> createFolderItem(MEDIA_LOCAL_MUSIC_ID, context.getString(R.string.local_music))
            "msg:login_required" -> createLoginPromptItem()
            else -> {
                if (mediaId.startsWith("msg:")) {
                    createInfoItem(
                        mediaId,
                        context.getString(R.string.auto_unavailable_title),
                        context.getString(R.string.auto_unavailable_subtitle),
                    )
                } else {
                    val cached = songCache[mediaId]
                    if (cached != null) {
                        cached.toMediaItem()
                    } else if (mediaId.startsWith("playlist:") || mediaId.startsWith("browse:")) {
                        createGridPlaylistItem(
                            mediaId = mediaId,
                            title = if (mediaId.contains("MPREb_")) "Album" else context.getString(R.string.playlists),
                            folderType = if (mediaId.contains("MPREb_")) MediaMetadata.FOLDER_TYPE_ALBUMS else MediaMetadata.FOLDER_TYPE_PLAYLISTS,
                        )
                    } else {
                        createInfoItem(
                            "msg:unavailable|$mediaId",
                            context.getString(R.string.auto_unavailable_title),
                            context.getString(R.string.auto_unavailable_subtitle),
                        )
                    }
                }
            }
        }
        LibraryResult.ofItem(item, null)
    }

    fun onSearch(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<Void>> = scope.future(Dispatchers.IO) {
        val (songs, browses) = searchAll(query)
        storeSearchResults(query, songs, browses)
        session.notifySearchResultChanged(browser, query, songs.size + browses.size, params)
        LibraryResult.ofVoid(params)
    }

    fun onGetSearchResult(
        query: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future(Dispatchers.IO) {
        val cachedSongs = searchResults[query]
        val cachedBrowses = searchBrowseResults[query]
        val (songs, browses) = if (cachedSongs != null || cachedBrowses != null) {
            cachedSongs.orEmpty() to cachedBrowses.orEmpty()
        } else {
            searchAll(query).also { storeSearchResults(query, it.first, it.second) }
        }
        songs.forEach { songCache[it.videoId] = it }
        val items = songs.map { it.toMediaItem() } + browses.map(::browseItemToGridItem)
        LibraryResult.ofItemList(ImmutableList.copyOf(paginate(items, page, pageSize)), params)
    }

    suspend fun resolveMediaItems(mediaItems: List<MediaItem>): List<MediaItem> {
        val resolved = mutableListOf<MediaItem>()
        for (item in mediaItems) {
            val id = item.mediaId
            val searchQuery = item.requestMetadata.searchQuery
            when {
                !searchQuery.isNullOrBlank() -> {
                    val cached = searchResults[searchQuery]
                    val songs = if (!cached.isNullOrEmpty()) {
                        cached
                    } else {
                        try {
                            withTimeoutOrNull(3000L) {
                                YtMusicRepository.search(searchQuery, SearchFilter.SONGS).getOrNull()
                                    ?.mapNotNull { if (it is SearchResult.Track) it.song else null }
                            } ?: emptyList()
                        } catch (_: Exception) {
                            emptyList()
                        }
                    }
                    songs.forEach { songCache[it.videoId] = it }
                    if (songs.isNotEmpty()) {
                        resolved.addAll(songs.map { it.toMediaItem() })
                    }
                }
                id.startsWith("playlist:") -> {
                    val playlistId = id.removePrefix("playlist:")
                    val songs = try {
                        withTimeoutOrNull(3000L) {
                            YtMusicRepository.browseSongs("VL$playlistId").getOrNull()?.songs
                                ?: YtMusicRepository.browseSongs(playlistId).getOrNull()?.songs
                        } ?: emptyList()
                    } catch (_: Exception) {
                        emptyList()
                    }
                    songs.forEach { songCache[it.videoId] = it }
                    resolved.addAll(songs.map { it.toMediaItem() })
                }
                id.startsWith("browse:") -> {
                    val browseId = id.removePrefix("browse:")
                    val songs = try {
                        withTimeoutOrNull(3000L) {
                            YtMusicRepository.browseSongs(browseId).getOrNull()?.songs
                        } ?: emptyList()
                    } catch (_: Exception) {
                        emptyList()
                    }
                    songs.forEach { songCache[it.videoId] = it }
                    resolved.addAll(songs.map { it.toMediaItem() })
                }
                id == MEDIA_ROOT_ID || id == MEDIA_RECENTS_ID -> {
                    val songs = cachedRecentsSongs()
                    resolved.addAll(songs.map { it.toMediaItem() })
                }
                id == MEDIA_QUICK_PICKS_ID -> {
                    val songs = cachedQuickPicksSongs()
                    resolved.addAll(songs.map { it.toMediaItem() })
                }
                id == MEDIA_LIKED_ID -> {
                    val songs = cachedLikedSongs()
                    if (songs.isNotEmpty()) {
                        resolved.addAll(songs.map { it.toMediaItem() })
                    }
                }
                id == MEDIA_DOWNLOADS_ID -> {
                    val downloaded = Downloads.getDownloadedSongs(context)
                    downloaded.forEach { songCache[it.videoId] = it }
                    resolved.addAll(downloaded.map { it.toMediaItem() })
                }
                id == MEDIA_PLAYLISTS_ID -> {
                    val songs = try {
                        withTimeoutOrNull(2500L) {
                            val playlists = YtMusicRepository.userPlaylists().getOrNull() ?: emptyList()
                            val first = playlists.firstOrNull()
                            if (first != null) {
                                YtMusicRepository.browseSongs("VL${first.playlistId}").getOrNull()?.songs
                                    ?: YtMusicRepository.browseSongs(first.playlistId).getOrNull()?.songs
                            } else null
                        } ?: emptyList()
                    } catch (_: Exception) {
                        emptyList()
                    }
                    songs.forEach { songCache[it.videoId] = it }
                    resolved.addAll(songs.map { it.toMediaItem() })
                }
                id == MEDIA_LOCAL_MUSIC_ID -> {
                    val local = LocalMediaRepository.getLocalMusic(context)
                    local.forEach { songCache[it.videoId] = it }
                    resolved.addAll(local.map { it.toMediaItem() })
                }
                songCache.containsKey(id) -> {
                    resolved.add(songCache[id]!!.toMediaItem())
                }
                item.localConfiguration != null -> {
                    resolved.add(item)
                }
                item.requestMetadata.mediaUri != null -> {
                    val uri = item.requestMetadata.mediaUri!!
                    val videoId = uri.getQueryParameter("v") ?: uri.lastPathSegment.orEmpty()
                    if (videoId.isNotEmpty() && !videoId.startsWith("http")) {
                        val cached = songCache[videoId]
                        if (cached != null) {
                            resolved.add(cached.toMediaItem())
                        } else {
                            resolved.add(Song(videoId = videoId, title = item.mediaMetadata.title?.toString() ?: "Track", artist = item.mediaMetadata.artist?.toString() ?: "Artist", thumbnailUrl = null).toMediaItem())
                        }
                    } else {
                        resolved.add(item.buildUpon().setUri(uri).build())
                    }
                }
                else -> {
                    val cached = songCache[id]
                    if (cached != null) {
                        resolved.add(cached.toMediaItem())
                    } else {
                        val song = item.toSong()
                        resolved.add(song.toMediaItem())
                    }
                }
            }
        }
        return if (resolved.isEmpty()) mediaItems else resolved
    }

    /**
     * What a media button can start with nothing queued (a headset's play, a
     * car reconnecting): downloads first, being playable offline, then the
     * device's own music, then Quick Picks off the home feed. Each is capped:
     * a play button has no use for the whole collection.
     */
    suspend fun resumptionFallback(): MediaSession.MediaItemsWithStartPosition {
        // 2. Fallback to local downloaded songs (offline-ready). Capped, as is
        // the device library below: this answers a headset's play button
        // with nothing queued, which has no use for the whole collection.
        val downloaded = Downloads.getDownloadedSongs(context)
        if (downloaded.isNotEmpty()) {
            val items = downloaded.take(BoundedQueuePlanner.MAX_PHYSICAL_CONTEXT).map { it.toMediaItem() }
            return MediaSession.MediaItemsWithStartPosition(
                ImmutableList.copyOf(items),
                0,
                0L,
            )
        }

        // 3. Fallback to device local music
        val localSongs = LocalMediaRepository.getLocalMusic(context)
        if (localSongs.isNotEmpty()) {
            val items = localSongs.take(BoundedQueuePlanner.MAX_PHYSICAL_CONTEXT).map { it.toMediaItem() }
            return MediaSession.MediaItemsWithStartPosition(
                ImmutableList.copyOf(items),
                0,
                0L,
            )
        }

        // 5. Fallback to Quick Picks / Home shelves
        val fallbackSongs = try {
            withTimeoutOrNull(2500L) {
                val home = YtMusicRepository.home().getOrNull()
                home?.shelves?.flatMap { shelf ->
                    shelf.items.mapNotNull { it.toMediaItemOrNull() }
                }?.distinctBy { it.mediaId }
            } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }

        if (fallbackSongs.isNotEmpty()) {
            return MediaSession.MediaItemsWithStartPosition(
                ImmutableList.copyOf(fallbackSongs),
                0,
                0L,
            )
        }

        // Return empty instead of throwing an unhandled exception to prevent crash
        return MediaSession.MediaItemsWithStartPosition(
            ImmutableList.of(),
            0,
            0L,
        )
    }

    private fun buildContentStyleBundle(browsableHint: Int, playableHint: Int): Bundle =
        Bundle().apply {
            putInt(EXTRA_CONTENT_STYLE_BROWSABLE_HINT, browsableHint)
            putInt(EXTRA_CONTENT_STYLE_PLAYABLE_HINT, playableHint)
            putInt(EXTRA_CONTENT_STYLE_BROWSABLE_HINT_AX, browsableHint)
            putInt(EXTRA_CONTENT_STYLE_PLAYABLE_HINT_AX, playableHint)
            putInt(EXTRA_CONTENT_STYLE_BROWSABLE_HINT_LEGACY, browsableHint)
            putInt(EXTRA_CONTENT_STYLE_PLAYABLE_HINT_LEGACY, playableHint)
            putInt("android.media.extras.CONTENT_STYLE_BROWSABLE_HINT", browsableHint)
            putInt("android.media.extras.CONTENT_STYLE_PLAYABLE_HINT", playableHint)
            putInt("android.media.browse.CONTENT_STYLE_BROWSABLE_HINT", browsableHint)
            putInt("android.media.browse.CONTENT_STYLE_PLAYABLE_HINT", playableHint)
            putBoolean(EXTRA_CONTENT_STYLE_SUPPORTED, true)
            putBoolean(EXTRA_CONTENT_STYLE_SUPPORTED_AX, true)
        }

    private fun createGridPlaylistItem(
        mediaId: String,
        title: String,
        subtitle: String? = null,
        artworkUrl: String? = null,
        folderType: Int = MediaMetadata.FOLDER_TYPE_PLAYLISTS,
    ): MediaItem = MediaItem.Builder()
        .setMediaId(mediaId)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setArtworkUri(artworkUrl?.artworkAt(NOTIFICATION_ART_PX)?.let(Uri::parse))
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setFolderType(folderType)
                .setExtras(buildContentStyleBundle(CONTENT_STYLE_GRID_ITEM_HINT_VALUE, CONTENT_STYLE_LIST_ITEM_HINT_VALUE))
                .build(),
        )
        .build()

    private fun shelfToGridPlaylistItem(playlist: ShelfItem): MediaItem {
        val browseId = playlist.browseId ?: "VL${playlist.videoId}"
        val mediaId = if (browseId.startsWith("VL")) "playlist:${browseId.removePrefix("VL")}" else "browse:$browseId"
        return createGridPlaylistItem(
            mediaId = mediaId,
            title = playlist.title,
            subtitle = playlist.subtitle,
            artworkUrl = playlist.thumbnailUrl,
        )
    }

    private suspend fun searchAll(query: String): Pair<List<Song>, List<BrowseItem>> {
        val safeQuery = query.trim().take(MAX_AUTO_SEARCH_QUERY_CHARS)
        if (safeQuery.isEmpty()) return emptyList<Song>() to emptyList()
        val results = try {
            withTimeoutOrNull(3_500L) {
                YtMusicRepository.search(safeQuery, SearchFilter.ALL).getOrNull()
            } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        val songs = results.mapNotNull {
            when (it) {
                is SearchResult.Track -> it.song
                is SearchResult.TopTrack -> it.song
                else -> null
            }
        }.distinctBy { it.videoId }
        val browses = results.mapNotNull { (it as? SearchResult.Browse)?.item }
            .distinctBy { it.browseId }
        return songs to browses
    }

    private fun storeSearchResults(query: String, songs: List<Song>, browses: List<BrowseItem>) {
        while (searchResults.size >= MAX_AUTO_SEARCH_CACHE_ENTRIES) {
            val key = searchResults.keys.firstOrNull() ?: break
            searchResults.remove(key)
            searchBrowseResults.remove(key)
        }
        searchResults[query] = songs
        searchBrowseResults[query] = browses
        songs.forEach { songCache[it.videoId] = it }
    }

    private fun browseItemToGridItem(item: BrowseItem): MediaItem = createGridPlaylistItem(
        mediaId = "browse:${item.browseId}",
        title = item.title,
        subtitle = item.subtitle,
        artworkUrl = item.thumbnailUrl,
        folderType = if (item.browseId.startsWith("MPREb_")) MediaMetadata.FOLDER_TYPE_ALBUMS else MediaMetadata.FOLDER_TYPE_PLAYLISTS,
    )

    private fun createFolderItem(
        mediaId: String,
        title: String,
        subtitle: String? = null,
        folderType: Int = MediaMetadata.FOLDER_TYPE_MIXED,
        isGrid: Boolean = false,
    ): MediaItem {
        val style = if (isGrid) CONTENT_STYLE_GRID_ITEM_HINT_VALUE else CONTENT_STYLE_LIST_ITEM_HINT_VALUE
        val extras = buildContentStyleBundle(style, CONTENT_STYLE_LIST_ITEM_HINT_VALUE)
        return MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setFolderType(if (isGrid && folderType == MediaMetadata.FOLDER_TYPE_MIXED) MediaMetadata.FOLDER_TYPE_ALBUMS else folderType)
                    .setExtras(extras)
                    .build(),
            )
            .build()
    }

    private fun ShelfItem.toMediaItemOrNull(): MediaItem? {
        val vid = videoId
        if (vid != null) {
            val song = Song(
                videoId = vid,
                title = title,
                artist = InnertubeParser.artistFromSubtitle(subtitle),
                thumbnailUrl = thumbnailUrl,
            )
            songCache[vid] = song
            return song.toMediaItem()
        }
        val bid = browseId
        if (bid != null) {
            return MediaItem.Builder()
                .setMediaId("browse:$bid")
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(title)
                        .setSubtitle(subtitle)
                        .setArtworkUri(thumbnailUrl.artworkAt(NOTIFICATION_ART_PX)?.let { Uri.parse(it) })
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .setFolderType(MediaMetadata.FOLDER_TYPE_ALBUMS)
                        .build(),
                )
                .build()
        }
        return null
    }

    private fun ShelfItem.toSongOrNull(): Song? {
        val id = videoId ?: return null
        return Song(
            videoId = id,
            title = title,
            artist = InnertubeParser.artistFromSubtitle(subtitle),
            thumbnailUrl = thumbnailUrl,
        )
    }

    private fun createLoginPromptItem(): MediaItem =
        MediaItem.Builder()
            .setMediaId("msg:login_required")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(context.getString(R.string.auto_login_title))
                    .setSubtitle(context.getString(R.string.auto_login_subtitle))
                    .setIsBrowsable(false)
                    .setIsPlayable(false)
                    .build(),
            )
            .build()

    private fun createInfoItem(mediaId: String, title: String, subtitle: String? = null): MediaItem =
        MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setIsBrowsable(false)
                    .setIsPlayable(false)
                    .setExtras(buildContentStyleBundle(CONTENT_STYLE_LIST_ITEM_HINT_VALUE, CONTENT_STYLE_LIST_ITEM_HINT_VALUE))
                    .build(),
            )
            .build()

    companion object {
        /**
         * One page of a browse list as a media browser asks for it: [page] counts
         * from zero, a page past the end is empty, and no page is longer than
         * [MAX_AUTO_PAGE_SIZE] whatever the browser asks for.
         */
        fun <T> paginate(items: List<T>, page: Int, pageSize: Int): List<T> {
            if (page < 0 || pageSize <= 0 || items.isEmpty()) return emptyList()
            val safePageSize = pageSize.coerceAtMost(MAX_AUTO_PAGE_SIZE)
            val fromLong = page.toLong() * safePageSize.toLong()
            if (fromLong >= items.size.toLong()) return emptyList()
            val from = fromLong.toInt()
            val to = (fromLong + safePageSize).coerceAtMost(items.size.toLong()).toInt()
            return items.subList(from, to)
        }

        const val MEDIA_ROOT_ID = "root"
        const val MEDIA_RECENTS_ID = "recents"
        const val MEDIA_QUICK_PICKS_ID = "quick_picks"
        const val MEDIA_PLAYLISTS_ID = "playlists"
        const val MEDIA_MORE_ID = "more"
        const val MEDIA_LIKED_ID = "liked"
        const val MEDIA_DOWNLOADS_ID = "downloads"
        const val MEDIA_LOCAL_MUSIC_ID = "local_music"
        const val MAX_AUTO_PAGE_SIZE = 50
        const val MAX_AUTO_SEARCH_QUERY_CHARS = 200
        const val MAX_AUTO_SEARCH_CACHE_ENTRIES = 20

        // Android Auto Content Style Hints
        const val EXTRA_CONTENT_STYLE_SUPPORTED = "android.media.browse.extra.CONTENT_STYLE_SUPPORTED"
        const val EXTRA_CONTENT_STYLE_SUPPORTED_AX = "androidx.media.contentstyle.CONTENT_STYLE_SUPPORTED"
        const val EXTRA_CONTENT_STYLE_BROWSABLE_HINT = "android.media.browse.extra.CONTENT_STYLE_BROWSABLE_HINT"
        const val EXTRA_CONTENT_STYLE_PLAYABLE_HINT = "android.media.browse.extra.CONTENT_STYLE_PLAYABLE_HINT"
        const val EXTRA_CONTENT_STYLE_BROWSABLE_HINT_AX = "androidx.media.contentstyle.CONTENT_STYLE_BROWSABLE_HINT"
        const val EXTRA_CONTENT_STYLE_PLAYABLE_HINT_AX = "androidx.media.contentstyle.CONTENT_STYLE_PLAYABLE_HINT"
        const val EXTRA_CONTENT_STYLE_BROWSABLE_HINT_LEGACY = "androidx.media.utils.CONTENT_STYLE_BROWSABLE_HINT"
        const val EXTRA_CONTENT_STYLE_PLAYABLE_HINT_LEGACY = "androidx.media.utils.CONTENT_STYLE_PLAYABLE_HINT"
        const val CONTENT_STYLE_LIST_ITEM_HINT_VALUE = 1
        const val CONTENT_STYLE_GRID_ITEM_HINT_VALUE = 4
    }
}
