package com.music.bitchord

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.spring
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Upgrade
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.music.bitchord.auth.DiscordLoginScreen
import com.music.bitchord.auth.SpotifyLoginScreen
import com.music.bitchord.auth.WebSessionMode
import com.music.bitchord.auth.YtMusicLoginScreen
import com.music.bitchord.data.AppUpdateChecker
import com.music.bitchord.data.LocalMediaRepository
import com.music.bitchord.data.listentogether.JamInviteLink
import com.music.bitchord.data.listentogether.ListenTogether
import com.music.bitchord.data.NerdStats
import com.music.bitchord.data.innertube.InnertubeParser
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.LikeStatus
import com.music.bitchord.data.model.PlaybackSourceType
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.UiState
import com.music.bitchord.data.model.withoutVideos
import com.music.bitchord.data.model.UserPlaylist
import kotlinx.coroutines.Dispatchers
import com.music.bitchord.data.model.durationMillis
import com.music.bitchord.data.scrobbling.LastFM
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.LibrarySort
import com.music.bitchord.data.settings.ThemeMode
import com.music.bitchord.ui.components.AccountProfileSelector
import com.music.bitchord.ui.components.IosModalContainer
import com.music.bitchord.ui.components.IosMotion
import com.music.bitchord.ui.components.LocalIosModalScope
import com.music.bitchord.playback.ACTION_DISMISS_PLAYBACK
import com.music.bitchord.ui.components.DacVolumeKeys
import com.music.bitchord.ui.components.LocalPlayingBarsMotion
import com.music.bitchord.ui.components.handleDacVolumeKey
import com.music.bitchord.ui.components.VolumeHud
import com.music.bitchord.ui.navigation.AppDestination
import com.music.bitchord.ui.navigation.AppRoute
import com.music.bitchord.ui.components.SpotifyImportAlert
import com.music.bitchord.ui.screens.SPOTIFY_BROWSE_ID
import com.music.bitchord.ui.screens.CACHE_FOLDER_BROWSE_ID
import com.music.bitchord.data.sources.TrackMatcher
import com.music.bitchord.ui.screens.SpotifyCanvasAuthScreen
import com.music.bitchord.playback.AudioCache
import com.music.bitchord.playback.LinkRequest
import com.music.bitchord.playback.MusicLink
import com.music.bitchord.playback.OriginalVersion
import com.music.bitchord.playback.PlayerDeepLink
import com.music.bitchord.playback.QueueBuilder
import com.music.bitchord.playback.QueueCoordinator
import com.music.bitchord.playback.PlayRequests
import com.music.bitchord.playback.QueueController
import com.music.bitchord.playback.VersionState
import com.music.bitchord.playback.VersionSwitcher
import com.music.bitchord.playback.QueueCoordinator.asQueueEntry
import com.music.bitchord.playback.QueueShuffle
import com.music.bitchord.playback.QueueSource
import com.music.bitchord.data.model.QueueTier
import com.music.bitchord.playback.autoplayEnabledFor
import com.music.bitchord.playback.autoplaySectionStart
import com.music.bitchord.playback.beginRadioQueue
import com.music.bitchord.playback.commitRadioQueue
import com.music.bitchord.playback.playSongs
import com.music.bitchord.playback.toMediaItem
import com.music.bitchord.playback.toSong
import com.music.bitchord.playback.toDirectYouTubeMediaItem
import com.music.bitchord.playback.toggleAutoplay
import com.music.bitchord.playback.toggleShuffle
import com.music.bitchord.download.DownloadSession
import com.music.bitchord.download.DownloadStore
import com.music.bitchord.download.MediaTagger
import com.music.bitchord.download.DownloadTarget
import com.music.bitchord.download.Downloads
import com.music.bitchord.ui.components.BrowseTarget
import com.music.bitchord.ui.components.PlaylistPickerSheet
import com.music.bitchord.ui.components.LongPressOrigin
import com.music.bitchord.ui.components.MenuAnchor
import com.music.bitchord.ui.components.menuAnchor
import com.music.bitchord.ui.components.IosMenuPopover
import com.music.bitchord.ui.components.HeldContextMenu
import com.music.bitchord.ui.components.HeldItem
import androidx.media3.session.MediaController
import com.music.bitchord.playback.QualityUpgrade
import com.music.bitchord.playback.rememberMediaController
import com.music.bitchord.playback.rememberPlayerState
import com.music.bitchord.playback.setQueueDragActive
import com.music.bitchord.ui.MainViewModel
import com.music.bitchord.ui.SearchSource
import com.music.bitchord.ui.components.BottomFadeScrim
import com.music.bitchord.ui.components.TopFadeBlur
import com.music.bitchord.ui.components.FloatingBarsTapGuard
import com.music.bitchord.ui.components.BottomTab
import com.music.bitchord.ui.components.FLOATING_BAR_MAX_WIDTH
import com.music.bitchord.ui.components.FloatingBottomBar
import com.music.bitchord.ui.components.GlassNavBar
import com.music.bitchord.ui.components.floatingtabbar.rememberFloatingTabBarScrollConnection
import com.music.bitchord.ui.components.FrostedTopBar
import com.music.bitchord.ui.components.LastfmLoginAlert
import com.music.bitchord.ui.components.LocalAppBackdrop
import com.music.bitchord.ui.components.LocalLiquidGlassEnabled
import com.music.bitchord.ui.components.LocalUiMotion
import com.music.bitchord.ui.components.backdrop.backdrops.LayerBackdrop
import com.music.bitchord.ui.components.backdrop.backdrops.layerBackdrop
import com.music.bitchord.ui.components.backdrop.backdrops.rememberLayerBackdrop
import com.music.bitchord.ui.components.backdrop.backdrops.rememberSnapshotBackdrop
import com.music.bitchord.ui.components.backdrop.backdrops.snapshotBackdropSource
import com.music.bitchord.ui.components.isGlassSupported
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.ui.components.ListenBrainzTokenAlert
import com.music.bitchord.ui.components.MiniPlayer
import com.music.bitchord.ui.components.QueueActionNotice
import com.music.bitchord.ui.components.QueueActionNoticeHost
import com.music.bitchord.ui.components.SearchField
import com.music.bitchord.sharedui.resources.Res as SharedRes
import com.music.bitchord.sharedui.resources.search_hint
import com.music.bitchord.sharedui.resources.search_addons_hint
import com.music.bitchord.sharedui.resources.search_library_hint
import com.music.bitchord.ui.components.optimizedHazeEffect
import com.music.bitchord.ui.components.topBarContentPadding
import com.music.bitchord.ui.components.AppLanguageDialog
import com.music.bitchord.ui.components.TranslationLanguageDialog
import com.music.bitchord.ui.components.LyricsSourcesDialog
import com.music.bitchord.ui.components.ServerEditorHost
import com.music.bitchord.ui.components.UpdateAvailableDialog
import com.music.bitchord.ui.navigation.AppAlertHost
import com.music.bitchord.ui.navigation.BrowseMenuActions
import com.music.bitchord.ui.navigation.BrowseMenuHost
import com.music.bitchord.ui.navigation.BrowseMenuState
import com.music.bitchord.ui.navigation.NowPlayingSheetHost
import com.music.bitchord.ui.navigation.PlaylistReorderState
import com.music.bitchord.ui.navigation.ReleasePage
import com.music.bitchord.ui.navigation.ReleasePageActions
import com.music.bitchord.ui.navigation.AppOverlayState
import com.music.bitchord.ui.navigation.RouteActions
import com.music.bitchord.ui.navigation.RoutePage
import com.music.bitchord.ui.navigation.handlesRoute
import com.music.bitchord.ui.navigation.ExploreTab
import com.music.bitchord.ui.navigation.HomeTab
import com.music.bitchord.ui.navigation.LibraryTab
import com.music.bitchord.ui.navigation.SearchTab
import com.music.bitchord.ui.navigation.TabActions
import com.music.bitchord.ui.navigation.TopBarActions
import com.music.bitchord.ui.navigation.SongMenuBody
import com.music.bitchord.ui.navigation.SongMenuEnv
import com.music.bitchord.ui.navigation.AppAlertState
import com.music.bitchord.ui.navigation.WebDavConflictHost
import com.music.bitchord.ui.components.FieldConfig
import com.music.bitchord.ui.icons.BitChordIcons
import androidx.media3.common.Player
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.TopPicks
import com.music.bitchord.data.model.TopPick
import com.music.bitchord.data.model.TopPickKind
import com.music.bitchord.data.model.TopPickMood
import com.music.bitchord.ui.player.NowPlayingScreen
import com.music.bitchord.ui.player.MusicVideoEngine
import com.music.bitchord.ui.player.PlayerSheetMotion
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.layout.offset
import com.music.bitchord.ui.components.MiniPlayerPull
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.layout.onSizeChanged
import com.music.bitchord.ui.player.PlayerDock
import com.music.bitchord.ui.screens.LocalFolderState
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.graphics.vector.ImageVector
import com.music.bitchord.ui.screens.localizedLabel
import com.music.bitchord.data.LocalGroups
import com.music.bitchord.data.OfflinePlaylists
import com.music.bitchord.data.toGroup
import com.music.bitchord.data.settings.LibraryViewType
import com.music.bitchord.data.settings.LocalMusicSort
import com.music.bitchord.ui.screens.SearchScreen
import com.music.bitchord.data.settings.SongSort
import com.music.bitchord.ui.onboarding.OnboardingScreen
import com.music.bitchord.ui.festive.DiwaliGate
import com.music.bitchord.ui.festive.DiwaliGreetingScreen
import com.music.bitchord.ui.replay.cards
import com.music.bitchord.ui.replay.ReplayShareSheet
import com.music.bitchord.ui.replay.ReplayStories
import com.music.bitchord.ui.replay.ReplayStoryPage
import com.music.bitchord.ui.replay.rememberReplayState
import com.music.bitchord.ui.theme.BitChordTheme
import com.music.bitchord.ui.theme.rememberArtworkPalette
import com.music.bitchord.data.canvas.AppleArtistArtRepository
import com.music.bitchord.data.canvas.keyColors
import com.music.bitchord.ui.theme.SystemBarIcons
import com.music.bitchord.ui.utils.rememberIosOverscrollFactory
import com.music.bitchord.ui.performance.resolvePerformanceRefreshRate
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.util.Locale

/** A full first screen of a native YouTube Music radio before AutoPlay tops it up. */
/** How much of an artist page's header scrolls away before the top fade begins. */
private const val TOP_FADE_START = 0.55f

/** How much of the header's height the fade takes to reach full strength. */
private const val TOP_FADE_RAMP = 0.3f

internal fun shouldSkipAfterDislike(
    previousStatus: LikeStatus,
    targetVideoId: String,
    currentVideoId: String?,
): Boolean = previousStatus != LikeStatus.DISLIKE && targetVideoId == currentVideoId


class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Before the composition, so a cold launch from a widget's artwork has
        // the request already standing by the time BitChordApp first reads it.
        PlayerDeepLink.consume(intent)
        JamInviteLink.consume(intent)
        // Likewise for a link tapped or shared from another app — see [MusicLink].
        MusicLink.consume(intent)
        setContent {
            val theme by AppSettings.themeMode.collectAsStateWithLifecycle()
            val appFont by AppSettings.appFont.collectAsStateWithLifecycle()
            val highPerformance by AppSettings.highPerformanceMode.collectAsStateWithLifecycle()
            val liquidGlassEnabled by AppSettings.liquidGlass.collectAsStateWithLifecycle()
            val reduceAnimationRoot by AppSettings.reduceAnimation.collectAsStateWithLifecycle()
            val iosOverscrollFactory = rememberIosOverscrollFactory()
            val performanceRefreshRate by AppSettings.performanceRefreshRate.collectAsStateWithLifecycle()
            val composeView = LocalView.current
            LaunchedEffect(highPerformance, performanceRefreshRate, composeView) {
                applyPerformanceMode(highPerformance, performanceRefreshRate, composeView)
            }
            val darkTheme = when (theme) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            BitChordTheme(darkTheme = darkTheme, font = appFont) {
                // The glass surfaces sample this layer, and a layer records only
                // what is drawn into it — which, for BitChord, is a page that
                // paints no background of its own. Everywhere a page is not
                // showing artwork the recording is transparent, so the glass had
                // nothing to blur there and you saw straight through it to the
                // sharp page underneath: album art came through the bar blurred
                // and text came through it untouched. The window's background is
                // the floor the pages have always been drawn against, so it is
                // laid down here too and the recording is opaque like the screen.
                val windowBackground = MaterialTheme.colorScheme.background
                val paintBackdrop: ContentDrawScope.() -> Unit = remember(windowBackground) {
                    {
                        drawRect(windowBackground)
                        drawContent()
                    }
                }
                val appBackdrop = rememberLayerBackdrop(onDraw = paintBackdrop)
                CompositionLocalProvider(
                    LocalOverscrollFactory provides iosOverscrollFactory,
                    LocalLiquidGlassEnabled provides liquidGlassEnabled,
                    LocalUiMotion provides !reduceAnimationRoot,
                    LocalAppBackdrop provides appBackdrop,
                ) {
                // The window's width, measured rather than asked for.
                //
                // `Configuration.screenWidthDp` is the wrong question here: in a
                // freeform or desktop window it can report the display rather
                // than the window it is actually in, and it lands a beat late
                // when that window is dragged. The layout downstream splits in
                // two on the strength of this number and sizes both halves from
                // it, so a stale one is a player pane sized for a window that no
                // longer exists and a page squeezed to a sliver to pay for it.
                // A measured constraint cannot be stale — it is the very width
                // the split is about to be laid out in.
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    BitChordApp(
                        darkTheme = darkTheme,
                        windowWidth = maxWidth,
                        windowHeight = maxHeight,
                        appBackdrop = appBackdrop,
                    )
                }
                }
            }
        }
    }

    /**
     * The volume keys move the DAC's own volume while direct USB output owns
     * it: Android's music stream no longer reaches that DAC, so the system
     * volume would change nothing anyone hears. Handled here rather than left
     * to the media session so the app's own HUD answers instead of Android's
     * remote-volume panel; in the background the session takes them. A sheet
     * in a window of its own asks the same through [DacVolumeKeys].
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        handleDacVolumeKey(event) || super.dispatchKeyEvent(event)

    /**
     * Requests a window refresh rate without forcing a display mode or
     * resolution. Android may still lower it for temperature, battery state or
     * hardware limits, which is why Settings describes this as a preference.
     */
    private fun applyPerformanceMode(enabled: Boolean, refreshRate: Int, composeView: View) {
        val supportedRefreshRate = composeView.display.resolvePerformanceRefreshRate(refreshRate)
        window.attributes = window.attributes.apply {
            preferredRefreshRate = if (enabled) supportedRefreshRate.toFloat() else 0f
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            window.setFrameRatePowerSavingsBalanced(!enabled)
            composeView.requestedFrameRate = if (enabled) {
                supportedRefreshRate.toFloat()
            } else {
                View.REQUESTED_FRAME_RATE_CATEGORY_DEFAULT
            }
        }
    }

    /**
     * The other half of the relay. This activity is `singleTask`, so once it is
     * running a second tap on the widget does not rebuild anything — it arrives
     * here, and [onCreate] never runs again.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Replaces what getIntent() returns, so the extra this consumes is the
        // one that just arrived and not the one the task was started with.
        setIntent(intent)
        PlayerDeepLink.consume(intent)
        JamInviteLink.consume(intent)
        MusicLink.consume(intent)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BitChordApp(
    darkTheme: Boolean,
    /** The width of the window this is laid out in — see the call site. */
    windowWidth: Dp,
    /**
     * The window's height, measured the same way and for the same reason as
     * [windowWidth] — and needed alongside it for exactly one thing: telling
     * a portrait window apart from a landscape one. Width alone can't; a
     * big tablet's portrait width comfortably clears a phone's landscape
     * width, so the two-column player (see [landscapePlayerAvailable])
     * would fire in portrait too if it only ever asked about width.
     */
    windowHeight: Dp,
    appBackdrop: LayerBackdrop,
    viewModel: MainViewModel = viewModel(),
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val hazeState = remember { HazeState() }
    // Recording the backdrop layer costs a draw pass, so it only runs when a
    // liquid-glass surface (the nav bar or artwork-page back button) can sample it.
    val glassActive = LocalLiquidGlassEnabled.current && isGlassSupported()
    // "Reduce dynamic blur" keeps the glass bar's *shape* — the folding
    // now-playing-and-tabs component is a layout, not an effect, and dropping
    // back to the two stacked bars would be answering a question about material
    // with a different screen. What it drops is the sampling: the surfaces fill
    // solid (see [Modifier.liquidGlass]) and the whole-page layer recording
    // below goes with them, which is the part that costs a draw pass.
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    val progressiveBlur by AppSettings.progressiveBlur.collectAsStateWithLifecycle()
    val glassSamplesBackdrop = glassActive && !reduceDynamicBlur
    // What folds [GlassNavBar] between its expanded and inline shapes. Held here
    // rather than inside the bar because the page's scroll is what drives it,
    // and the page is a sibling of the bar rather than a child.
    val navBarScroll = rememberFloatingTabBarScrollConnection()
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    // Explore loads the first time it's shown, not at launch beside Home.
    LaunchedEffect(selectedTab) {
        if (selectedTab == TAB_EXPLORE) viewModel.onExploreShown()
    }
    /**
     * Whether the player's sheet is up. The player is always a full-screen
     * take-over raised over the page, on every window size — the library
     * stays full-screen behind a mini player rather than losing a lane to a
     * permanent pane.
     */
    var showNowPlaying by remember { mutableStateOf(false) }
    // The DAC's own volume while direct USB output owns it; the full player
    // has its own bar for that, so the HUD stays out of its way.
    androidx.compose.runtime.SideEffect { VolumeHud.suppressed = showNowPlaying }
    // The Top Picks and mood artwork loop forever; nobody sees it with the
    // app in the background or Now Playing pulled up over the page.
    val appForeground = com.music.bitchord.ui.rememberIsForeground()
    val artworkMotion = appForeground && !showNowPlaying
    // Top Picks turns over each hour; coming back to the app after a
    // turn-over is an open too.
    LaunchedEffect(appForeground) {
        if (appForeground) viewModel.refreshTopPicksIfStale()
    }
    // Where the two ends of opening and closing the player meet: the mini
    // player's cover reports itself here, the sheet its offset, and the player
    // flies its artwork between them — see [PlayerDock].
    val playerDock = remember { PlayerDock() }
    // Who moves the player sheet when the app does rather than M3: a tap's
    // open, the pull up from the mini player, and every close — see
    // [PlayerSheetMotion].
    val playerSheetScope = rememberCoroutineScope()
    val playerSheetMotion = remember {
        PlayerSheetMotion(
            scope = playerSheetScope,
            dock = playerDock,
            shown = { showNowPlaying },
            setShown = { showNowPlaying = it },
        )
    }
    playerSheetMotion.windowInfo = LocalWindowInfo.current
    // Whether a portrait player fully up has the page covered. Derived, so the
    // page's draw below hears when that flips rather than on every frame of the
    // sheet's travel: read straight off the fraction, each of those frames
    // redrew the page's whole window behind the player, glass and blurs and all.
    val coveredByPlayer by remember {
        derivedStateOf { showNowPlaying && playerDock.attached && playerDock.openFraction() >= 1f }
    }
    // "Reduce animation" keeps the player as it always was: M3's own slide up
    // and down, its own scrim and window animations, no pull from the mini
    // player and no artwork flying between the two. Everything below that
    // takes part in the docking asks for [activeDock] / this, never for the
    // dock or the motion directly.
    val reducePlayerMotion by AppSettings.reduceAnimation.collectAsStateWithLifecycle()
    val activeDock = playerDock.takeIf { !reducePlayerMotion }
    playerSheetMotion.flingVelocity = with(LocalDensity.current) { PLAYER_PULL_FLING_VELOCITY.toPx() }
    // How the app takes the player down, whatever for: a back press, or a page
    // opened from inside it. Set straight to false, the sheet left in one frame
    // on its window's own exit animation — the old fast drop, with a copy of the
    // artwork slipping down out of the mini player's cover after it.
    val dismissPlayer: () -> Unit = {
        if (reducePlayerMotion) showNowPlaying = false else playerSheetMotion.close()
    }
    // The far end of the relay from a widget's artwork. Cleared here rather than
    // where it was set, so the request is spent by being served — see
    // [PlayerDeepLink.handled]. The sheet itself is gated on there being a track,
    // so on a cold launch this simply arms it and it opens as the controller
    // connects.
    val openPlayerRequested by PlayerDeepLink.pending.collectAsStateWithLifecycle()
    LaunchedEffect(openPlayerRequested) {
        if (openPlayerRequested) {
            showNowPlaying = true
            PlayerDeepLink.handled()
        }
    }
    /**
     * What the in-app browser is open for, or null while it is closed —
     * signing in, or picking a channel in YouTube Music's own Accounts list.
     */
    // The sheets and sign-ins pushed pages ask for, held in [AppOverlayState];
    // the locals delegate to it so every caller keeps the same names.
    val overlays = remember { AppOverlayState() }
    var webSession by overlays::webSession
    // Replay: the page (a route on the back stack — see [AppNavigator]), the
    // stories over it, and the share sheet over those. Closing one has to
    // reveal what it was opened from.
    // Library cards use the same Replay page as every other entry point, but
    // category cards ask it to start at their matching ranked section.
    var replayLandingPage by rememberSaveable { mutableStateOf(ReplayStoryPage.INTRO) }
    var replayStory by rememberSaveable { mutableStateOf<ReplayStoryPage?>(null) }
    var showReplayShare by rememberSaveable { mutableStateOf(false) }
    /** Which story card the share sheet is for, or null for the whole Replay. */
    var replaySharePage by rememberSaveable { mutableStateOf<ReplayStoryPage?>(null) }
    var showSpotifyCanvasAuth by overlays::showSpotifyCanvasAuth

    // Root-level alerts (sources, Discord, bit-perfect, download notices):
    // their state and drawing live in [AppAlertHost] — see there for why they
    // are hosted at the root. The locals delegate to it so every page callback
    // keeps writing the same names.
    val alerts = remember { AppAlertState() }
    var editingSource by alerts::editingSource
    var choosingSourceQuality by alerts::choosingSourceQuality
    var explainingOutdatedAddon by alerts::explainingOutdatedAddon
    var confirmJioSaavn by alerts::confirmJioSaavn
    var confirmBitPerfect by alerts::confirmBitPerfect
    var confirmFixedVolume by alerts::confirmFixedVolume
    var blockedAddonSong by alerts::blockedAddonSong
    var addonLeftOutOfDownload by alerts::addonLeftOutOfDownload
    var editingPartyServer by alerts::editingPartyServer
    var editingSignatureApi by alerts::editingSignatureApi
    var librarySortMenuOpen by remember { mutableStateOf(false) }
    var showLyricsSources by overlays::showLyricsSources
    var showAppLanguage by overlays::showAppLanguage
    var showTranslationLanguage by overlays::showTranslationLanguage
    var showAccountSelector by overlays::showAccountSelector
    var showListenBrainzLogin by overlays::showListenBrainzLogin
    var showLastfmLogin by overlays::showLastfmLogin
    var showWebDavEditor by overlays::showWebDavEditor
    var showSmbEditor by overlays::showSmbEditor
    // Discord Rich Presence: its own page under Account & integrations (a
    // route), its own full-screen sign-in, and one slot for whichever of its
    // alerts is open. The alerts live out here rather than on the page because
    // their scrim has to cover the tab bar and mini player, which are drawn
    // after it.
    var showDiscordLogin by overlays::showDiscordLogin
    var showSpotifyLogin by overlays::showSpotifyLogin
    var discordDialog by alerts::discordDialog
    var songActions by remember { mutableStateOf<Song?>(null) }
    var showLyricsOffset by remember { mutableStateOf(false) }
    /**
     * Whether the track menu that is up was opened from the player.
     *
     * Its copy of the menu carries rows nothing else offers — a sleep timer,
     * the track log, share — and until now "opened from the player" and "the
     * player is on screen" were the same sentence, because the player was a
     * sheet and nothing else could be up behind it. On a tablet the player is
     * never *the* thing on screen: it is always beside whatever is, so the
     * question has to be answered by whoever opened the menu.
     */
    var menuFromPlayer by remember { mutableStateOf(false) }
    /** Whether the track menu was opened from the History screen. */
    var menuFromHistory by remember { mutableStateOf(false) }
    /**
     * The row or card that was held to open the track menu, or null when it
     * was opened any other way. Non-null lifts that item into
     * [HeldContextMenu]; null hangs the menu off [songMenuAnchor] instead —
     * which is what the ⋮ on the very same row opens.
     */
    var songMenuOrigin by remember { mutableStateOf<HeldItem?>(null) }
    /** The ⋮ or ⋯ the track menu was opened from, for [IosMenuPopover] to hang off. */
    var songMenuAnchor by remember { mutableStateOf<Rect?>(null) }
    /**
     * A still of the player for its ⋯ menu to blur — see the player's sheet
     * below. Up here so the tap that opens the menu can ask for it: asked any
     * later, the picture is read back on a frame of the menu's spring.
     */
    val playerBackdrop = rememberSnapshotBackdrop()
    /**
     * The player live instead, while it is a full-screen Spotify Canvas: that
     * keeps playing under the ⋯ menu, and a still of it would freeze under the
     * panel. Recorded only while that is the case — see the player's sheet.
     */
    val livePlayerBackdrop = rememberLayerBackdrop()
    var playerLiveCanvas by remember { mutableStateOf(false) }
    /**
     * The same for a ⋮ or ⋯ menu over a page: a still of everything under it —
     * the page, its bars, the mini player — blurred under the panel. The
     * page's live haze, under a panel growing on a spring, was re-cut and
     * rescaled with it on every frame: the blur swam against the panel's
     * growth, and the whole page was rendered again for it each time.
     */
    val pageBackdrop = rememberSnapshotBackdrop()
    /** Holding a row anywhere but the player — the menu without the player's rows. */
    val openSongMenu: (Song) -> Unit = { song ->
        menuFromPlayer = false
        songMenuOrigin = LongPressOrigin.consume()
        songMenuAnchor = MenuAnchor.consume()
        songActions = song
    }
    // Whether the player's album/artist lookup (below, for the current track)
    // is still in flight — read by the long-press sheet so it can show a
    // loading row instead of the two just being absent while it waits.
    var linksLoading by remember { mutableStateOf(false) }
    // Which track the playlist picker is adding, or null when it's closed.
    // Separate from [songActions] so the menu can close behind it — the picker
    // is the next step, not a second sheet stacked on the first.
    var playlistTarget by remember { mutableStateOf<Song?>(null) }
    // The picker opened from the Library tab, where there is no track and
    // creating the playlist is the whole errand.
    var creatingPlaylist by remember { mutableStateOf(false) }
    // The same two, over the device's own playlists — for a track that is a
    // file on it, and for "New playlist" on Local Music's Playlists tab.
    var offlinePlaylistTarget by remember { mutableStateOf<Song?>(null) }
    var creatingOfflinePlaylist by remember { mutableStateOf(false) }
    var showSpotifyImportDialog by remember { mutableStateOf(false) }
    // Which album or playlist the collection menu is open on, or null when it
    // is shut. One slot for every surface that can open it — the shelves on
    // three tabs, the search rows, the artist page's carousels, the release
    // page's own overflow — because only one of them can be held at a time.
    // Held in [BrowseMenuState] and drawn by [BrowseMenuHost]; the locals
    // delegate to it so every surface that opens the menu is unchanged.
    val browseMenu = remember { BrowseMenuState() }
    var browseActions by browseMenu::target
    /** The card held to open [browseActions] — see [songMenuOrigin]. */
    var browseMenuOrigin by browseMenu::origin
    /** The ⋯ [browseActions] was opened from — see [songMenuAnchor]. */
    var browseMenuAnchor by browseMenu::anchor
    /** Rename asked for from the menu, which hands it on to a sheet holding only the form. */
    var browseRenameInSheet by browseMenu::renameInSheet
    /** The playlist being rearranged, or null when the reorder page is shut. */
    // Held in [PlaylistReorderState]; the locals delegate to it.
    val playlistReorder = remember { PlaylistReorderState() }
    var reorderTarget by playlistReorder::target
    /** Its entries as YouTube has them now — fetched fresh when the sheet opens. */
    var reorderEntries by playlistReorder::entries
    var reorderSaving by playlistReorder::saving
    /** An album or playlist: lifted when it was a hold, else hung off the ⋯ that was tapped. */
    val openBrowseMenu: (BrowseTarget) -> Unit = { target ->
        browseMenuOrigin = LongPressOrigin.consume()
        browseMenuAnchor = MenuAnchor.consume()
        browseRenameInSheet = false
        browseActions = target
    }
    val autoplay by AppSettings.autoplay.collectAsStateWithLifecycle()
    val partyState by ListenTogether.state.collectAsStateWithLifecycle()
    // The same answer the playback service acts on, rather than a second one
    // derived here — see [autoplayEnabledFor]. Drawing the local preference in
    // a party made the toggle lie in both directions: a listener whose own
    // switch was on sat under "AutoPlay on" in a party that had it off, got no
    // suggestions, and pressing the button appeared to do nothing, because it
    // turned the party's setting on while the label already said so.
    val autoplayEnabled = autoplayEnabledFor(partyState, autoplay)
    val partyServerStatus by ListenTogether.serverConnectionState.collectAsStateWithLifecycle()
    val listenBrainzToken by AppSettings.listenBrainzToken.collectAsStateWithLifecycle()
    // Set each time the search tab is tapped, which SearchScreen uses as a
    // signal to focus the input field.
    var searchFocusRequested by remember { mutableStateOf(false) }
    val searchFieldFocus = remember { FocusRequester() }
    // Which play request is the latest, and the station label for a radio
    // started off the playing track — see [PlayRequests].
    val playRequests = remember { PlayRequests() }
    val activeRadioSeed by playRequests::radioSeed

    // The modal player owns light status glyphs and its own contrast scrim.
    // Every other surface follows the theme; Replay's page and stories remain
    // dark artwork either way. So is the welcome, drawn over all of it.
    val onboardingComplete by AppSettings.onboardingComplete.collectAsStateWithLifecycle()
    // Held through the slide away as well, so the glyphs don't turn dark over
    // the mesh while it is still on its way off the screen.
    val onboardingShown = remember { MutableTransitionState(!onboardingComplete) }
    onboardingShown.targetState = !onboardingComplete
    val onboardingVisible = onboardingShown.currentState || onboardingShown.targetState

    // ---- Diwali (greeting once after the welcome, golden home wordmark) ----
    // Indian users only, by the device timezone alone, and only on Diwali
    // itself (see DiwaliGate). Asked again each time the app comes to the
    // front, so one left open overnight into the day still greets — and one
    // left open past it lets the gold go.
    val diwaliFestive = remember(appForeground) {
        DiwaliGate.isIndianTimezone() && DiwaliGate.isDiwaliDay()
    }
    var showDiwali by rememberSaveable { mutableStateOf(false) }
    var diwaliAutoShown by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(diwaliFestive, onboardingComplete) {
        if (diwaliFestive && onboardingComplete && !diwaliAutoShown) {
            diwaliAutoShown = true
            if (!AppSettings.diwaliGreetingShown.value) showDiwali = true
        }
    }

    // ---- Navigation ----
    // One back stack for every page: the routes pushed over the tabs, and the
    // album / artist / playlist pages interleaved with them — see
    // [AppNavigator]. Whatever was opened last is what is on screen, and back
    // (the gesture or the bar's button alike) undoes exactly that.
    val navigator = viewModel.navigator
    val detailStack by viewModel.detailStack.collectAsStateWithLifecycle()
    val topEntry = navigator.visibleEntry(detailStack.size)
    // An artist's open shelf is drawn by the artist page itself, so it is not a
    // route the page area switches to — only the shelf the page is showing.
    val artistShelfRoute = topEntry?.route as? AppRoute.ArtistShelf
    val visibleEntry = topEntry?.takeIf { artistShelfRoute == null }
    val detailActiveShelf = artistShelfRoute?.shelf
    /** The route on screen, or null when a release page or a tab is. */
    val route = visibleEntry?.route
    /**
     * The release page at the top of its stack, whether or not a route
     * covers it. Most things want [detail] — the one actually on screen.
     */
    val topDetail = detailStack.lastOrNull()
    /** The release page on screen, or null when a route or a tab is. */
    val detail = topDetail?.takeIf { visibleEntry == null }
    // The reorder list lives on its playlist's page; leaving that page by any
    // route — Back, a tab, another page over it — puts the list away with it.
    LaunchedEffect(detail?.browseId) {
        if (reorderTarget?.browseId != detail?.browseId) reorderTarget = null
    }

    SystemBarIcons(
        dark = !darkTheme && !showNowPlaying && route != AppRoute.Replay && replayStory == null && !onboardingVisible && !showDiwali,
    )

    val homeState by viewModel.home.collectAsStateWithLifecycle()
    val hideRecents by AppSettings.hideRecents.collectAsStateWithLifecycle()
    val hideHomeVideos by AppSettings.hideHomeVideos.collectAsStateWithLifecycle()
    // Only what Home draws: Top Picks reads the unfiltered feed from the view model.
    val visibleHomeState = remember(homeState, hideHomeVideos, hideRecents) {
        val loaded = homeState as? UiState.Success
        if (loaded != null && (hideHomeVideos || hideRecents)) {
            var shelves = loaded.data
            if (hideRecents) shelves = shelves.filterNot { it.title == YtMusicRepository.RECENT_TITLE }
            if (hideHomeVideos) shelves = shelves.withoutVideos()
            UiState.Success(shelves)
        } else {
            homeState
        }
    }
    val topPicks by viewModel.topPicks.collectAsStateWithLifecycle()

    // The top bar's icon is the quiet, always-there nudge; this is the
    // once-per-launch popup version of the same news. `updateDialogShown`
    // rides out configuration changes on rememberSaveable so a rotation
    // doesn't bring it back — only a fresh launch does.
    var updateDialogShown by rememberSaveable { mutableStateOf(false) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    val updateAvailable by viewModel.updateAvailable.collectAsStateWithLifecycle()

    /**
     * The single gate both surfaces read, so the icon can't announce the update
     * a beat before the popup does — they're one piece of news, and staggering
     * them made the top bar look like it had caught something the app hadn't.
     */
    val updateNotice = updateAvailable

    LaunchedEffect(updateNotice) {
        if (updateNotice != null && !updateDialogShown) {
            updateDialogShown = true
            showUpdateDialog = true
        }
    }
    val query by viewModel.query.collectAsStateWithLifecycle()
    val selectedMoodGenre by viewModel.selectedMoodGenre.collectAsStateWithLifecycle()
    val searchSource by viewModel.searchSource.collectAsStateWithLifecycle()
    // Collected rather than read once, so addon rows in search re-dim the
    // moment the quality setting, the connection or the watch's cap moves.
    val addonCeiling = AppSettings.ceilingFor(
        wifi = AppSettings.audioQualityWifi.collectAsStateWithLifecycle().value,
        cellular = AppSettings.audioQualityCellular.collectAsStateWithLifecycle().value,
        metered = AppSettings.meteredConnection.collectAsStateWithLifecycle().value,
        override = AppSettings.streamCeilingOverride.collectAsStateWithLifecycle().value,
    )
    val signedIn by viewModel.signedIn.collectAsStateWithLifecycle()
    val incomingJamInvite by JamInviteLink.pending.collectAsStateWithLifecycle()
    var activeJamInviteCode by rememberSaveable { mutableStateOf<String?>(null) }
    var activeJamInviteServer by rememberSaveable { mutableStateOf<String?>(null) }

    // An invite is navigation and an action: reveal the Jam settings page now,
    // then let that page join once an account is available. Keeping the code
    // here lets a sign-in round trip return to the invite it started from.
    LaunchedEffect(incomingJamInvite) {
        val invite = incomingJamInvite ?: return@LaunchedEffect
        activeJamInviteCode = invite.code
        activeJamInviteServer = invite.serverUrl
        dismissPlayer()
        replayStory = null
        showReplayShare = false
        webSession = null
        navigator.reset(AppRoute.Settings, AppRoute.ListenTogether)
        JamInviteLink.handled()
    }
    LaunchedEffect(signedIn, activeJamInviteCode) {
        if (signedIn && activeJamInviteCode != null) {
            navigator.pushAll(AppRoute.Settings, AppRoute.ListenTogether)
        }
    }
    val account by viewModel.account.collectAsStateWithLifecycle()
    val selectedChannelName by viewModel.selectedChannelName.collectAsStateWithLifecycle()
    val googleAccounts by viewModel.googleAccounts.collectAsStateWithLifecycle()
    val activeAccountId by viewModel.activeAccountId.collectAsStateWithLifecycle()
    val activeProfileId by viewModel.activeProfileId.collectAsStateWithLifecycle()
    val historyState by viewModel.history.collectAsStateWithLifecycle()
    val lyrics by viewModel.lyrics.collectAsStateWithLifecycle()
    val lyricsSource by viewModel.lyricsSource.collectAsStateWithLifecycle()
    val lyricsChecked by viewModel.lyricsChecked.collectAsStateWithLifecycle()
    val lyricsProviderStates by viewModel.lyricsProviderStates.collectAsStateWithLifecycle()
    val releaseLibrary by viewModel.releaseLibrary.collectAsStateWithLifecycle()
    // Local Music, Downloads and the other device folders draw as release
    // pages, but they have no artwork to wash the top inset in, so they keep
    // the plain bar; their top bar trades the overflow for a sort-and-view
    // menu, and keeps the account photo.
    //
    // A downloaded playlist's page is under `local:` too and is none of that: it
    // has a cover and a track list, so it takes the bar every other release page
    // takes — as does an album or artist opened out of a folder. Hence the
    // folder question rather than the prefix.
    val isLocalDetail = detail?.browseId.isDeviceFolder()
    // An album or playlist page: the one whose top bar carries its search,
    // sort and overflow controls.
    val isReleaseDetail = detail != null && !isLocalDetail && detail.type != BrowseType.ARTIST
    // Not keyed on the browse id and not remembered here: each page's choice
    // lives in AppSettings keyed by that page — Spotify-style, one playlist's
    // order never imposes itself on another, and every page keeps its own
    // across visits.
    val detailSongSorts by AppSettings.detailSongSorts.collectAsStateWithLifecycle()
    val songSort = topDetail?.browseId?.let { detailSongSorts[it] } ?: SongSort.DEFAULT
    var songSortMenuOpen by remember { mutableStateOf(false) }
    // A device folder's equivalents — its sort and whether it is a grid —
    // kept apart for Downloads and shared by every other folder, as they
    // always were. Up here because both the page and the top bar's menu read
    // them.
    val downloadedMusicSort by AppSettings.downloadedMusicSort.collectAsStateWithLifecycle()
    val localMusicSort by AppSettings.localMusicSort.collectAsStateWithLifecycle()
    val downloadedMusicViewType by AppSettings.downloadedMusicViewType.collectAsStateWithLifecycle()
    val localMusicViewType by AppSettings.localMusicViewType.collectAsStateWithLifecycle()
    val folderSortOf: (String) -> LocalMusicSort = { id ->
        if (id == "local:downloads") downloadedMusicSort else localMusicSort
    }
    val folderViewTypeOf: (String) -> LibraryViewType = { id ->
        if (id == "local:downloads") downloadedMusicViewType else localMusicViewType
    }
    var folderMenuOpen by remember { mutableStateOf(false) }
    // Whether the open release page's filter box is up. Held out here with the
    // sort control because the button that opens it sits in the top bar beside
    // it, and reset with the page so a filter never follows you to the next one.
    var detailSearching by rememberSaveable(topDetail?.browseId) { mutableStateOf(false) }
    val likeStatuses by viewModel.likeStatuses.collectAsStateWithLifecycle()
    // A device file's heart is the device's Liked playlist, not a YouTube
    // rating: the file has no identity there to rate. One rule for every
    // surface that draws a heart — the player and the track menu alike.
    val offlineLiked by OfflinePlaylists.liked.collectAsStateWithLifecycle()
    val offlinePlaylists by OfflinePlaylists.playlists.collectAsStateWithLifecycle()
    val offlinePlaylistGroups = remember(offlinePlaylists) { offlinePlaylists.map { it.toGroup() } }
    val likeStatusOf: (Song) -> LikeStatus = { song ->
        val uri = song.localUri
        when {
            uri != null -> if (uri in offlineLiked) LikeStatus.LIKE else LikeStatus.INDIFFERENT
            else -> likeStatuses[song.videoId] ?: LikeStatus.INDIFFERENT
        }
    }
    val toggleLikeOf: (Song) -> Unit = { song ->
        if (song.localUri != null) OfflinePlaylists.toggleLike(song) else viewModel.toggleLike(song.videoId)
    }
    // Which tracks are being held on YouTube's own upload, so the player's menu
    // offers the way back out of a revert rather than the revert again.
    val pinnedToOriginal by OriginalVersion.pinned.collectAsStateWithLifecycle()
    val qualityUpgradesInFlight by NerdStats.racingLossless.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val playlistsLoading by viewModel.playlistsLoading.collectAsStateWithLifecycle()

    // The Downloads page is a snapshot of the folder, taken when it was opened.
    // Saving a track or deleting one while it is on screen changes what belongs
    // on it — and now that the page groups by artist and album, a stale list is
    // stale counts and a missing row in three places rather than one. So it is
    // taken again whenever the record of what's on disk changes.
    val savedDownloads by Downloads.saved.collectAsStateWithLifecycle()
    val localMusicFolderUri by AppSettings.localMusicFolderUri.collectAsStateWithLifecycle()
    val filterNonMusicAudio by AppSettings.filterNonMusicAudio.collectAsStateWithLifecycle()
    val webdavUrl by AppSettings.webdavUrl.collectAsStateWithLifecycle()
    val smbHost by AppSettings.smbHost.collectAsStateWithLifecycle()
    val librarySort by AppSettings.librarySort.collectAsStateWithLifecycle()
    // The releases those files were asked for as — read here rather than in the
    // page so the Downloads folder recomposes when one is added, the same way it
    // does when a file is.
    val savedCollections by Downloads.collections.collectAsStateWithLifecycle()
    // The playlists and albums among them, for the Library page's On Device
    // shelf. Read off both records: the collection record is what says a
    // release was downloaded whole, and what is on disk is what says it still
    // has anything left to open.
    val downloadedReleases = remember(savedCollections, savedDownloads) {
        Downloads.savedReleases()
    }
    // Playlists imported without (or instead of) a YouTube Music account live
    // in the app's own store, and sit on the same shelf as the downloaded ones.
    val localPlaylists by com.music.bitchord.data.spotify.LocalPlaylistStore.playlists.collectAsStateWithLifecycle()
    val localPlaylistItems = localPlaylists.map { playlist ->
        ShelfItem(
            title = playlist.title,
            subtitle = stringResource(R.string.local_playlist_subtitle, playlist.songs.size),
            thumbnailUrl = playlist.songs.firstOrNull { !it.thumbnailUrl.isNullOrBlank() }?.thumbnailUrl,
            videoId = null,
            browseId = playlist.browseId,
        )
    }
    // What a browse id is recorded under in Downloads.collections, when it names
    // a release downloaded whole — see BrowseTarget.downloadId. A downloaded
    // playlist's own page and its card both carry the id under the
    // `local:playlist:` prefix; a release still reachable by its real id (an
    // album's own page, a search hit) is looked up directly under that instead.
    val downloadIdFor: (String?) -> String? = { id ->
        id?.let { Downloads.recordIdOf(it) ?: it }?.takeIf { it in savedCollections }
    }
    LaunchedEffect(savedDownloads, savedCollections, topDetail?.browseId) {
        val openPage = topDetail ?: return@LaunchedEffect
        val open = openPage.browseId
        // A downloaded playlist's page is a snapshot of the same folder and goes
        // stale for the same reasons — and it is the one page a delete can empty
        // out entirely, which is worth saying rather than leaving rows behind
        // that play nothing.
        // An album or artist out of one of those folders goes stale with it.
        val folder = LocalGroups.folderOf(open) ?: open
        if (openPage.songs !is UiState.Loading &&
            (folder == "local:downloads" || folder == CACHE_FOLDER_BROWSE_ID || Downloads.recordIdOf(open) != null ||
                com.music.bitchord.data.spotify.LocalPlaylistStore.getPlaylist(open) != null)
        ) {
            viewModel.reloadLocalDetail(open)
        }
    }
    // An offline playlist's page is the store's, and follows it: a track
    // removed from its menu, or a like from the player, leaves no stale row.
    // Apart from the effect above so a like never re-lists the Downloads folder.
    LaunchedEffect(topDetail?.browseId, offlinePlaylists) {
        val openPage = topDetail ?: return@LaunchedEffect
        if (openPage.songs !is UiState.Loading && OfflinePlaylists.isOfflinePlaylist(openPage.browseId)) {
            viewModel.reloadLocalDetail(openPage.browseId)
        }
    }
    LaunchedEffect(localMusicFolderUri, filterNonMusicAudio) {
        val open = topDetail?.browseId
        if (open == "local:all" || LocalGroups.folderOf(open) == "local:all") {
            viewModel.reloadLocalDetail(open!!)
        }
    }
    LaunchedEffect(webdavUrl) {
        if (topDetail?.browseId == com.music.bitchord.data.webdav.WebDavConfig.BROWSE_ID) {
            viewModel.reloadLocalDetail(com.music.bitchord.data.webdav.WebDavConfig.BROWSE_ID)
        }
    }
    LaunchedEffect(smbHost) {
        if (topDetail?.browseId == com.music.bitchord.data.smb.SmbConfig.BROWSE_ID) {
            viewModel.reloadLocalDetail(com.music.bitchord.data.smb.SmbConfig.BROWSE_ID)
        }
    }
    val controller = rememberMediaController()
    val player = rememberPlayerState(controller)
    val dismissPlayback: () -> Unit = {
        if (showNowPlaying) {
            showNowPlaying = false
            dismissPlayer()
        }
        controller?.let { c ->
            c.stop()
            c.clearMediaItems()
            c.sendCustomCommand(
                androidx.media3.session.SessionCommand(ACTION_DISMISS_PLAYBACK, android.os.Bundle.EMPTY),
                android.os.Bundle.EMPTY,
            )
        }
    }
    // A music video's picture follows this controller's clock — see [MusicVideoEngine].
    DisposableEffect(controller) {
        MusicVideoEngine.attach(controller)
        onDispose { MusicVideoEngine.attach(null) }
    }
    // A resume in a party is performed on the instant the server schedules, not
    // when it was pressed, and nothing about the player moves in between — so
    // the transport spends that round trip drawn as though the tap never landed.
    // Folded into the buffering flag every play button already answers to, since
    // to a listener the two are the same fact: it is coming, wait.
    val awaitingPartyStart by ListenTogether.awaitingStart.collectAsStateWithLifecycle()
    val playPauseBusy = player.isLoading || awaitingPartyStart
    // Listening in a party whose host has taken the controls. Read once here
    // and handed to every surface, so the player, the mini player and the glass
    // bar can never disagree about whether this device may drive the music.
    val controlsLocked = partyState.controlsLocked
    var queueNotice by remember { mutableStateOf<QueueActionNotice?>(null) }
    var queueNoticeId by remember { mutableIntStateOf(0) }
    val showQueueNotice: (String) -> Unit = { message ->
        queueNoticeId += 1
        queueNotice = QueueActionNotice(queueNoticeId, message)
    }
    LaunchedEffect(queueNotice?.id) {
        val shown = queueNotice ?: return@LaunchedEffect
        delay(3_000)
        if (queueNotice?.id == shown.id) queueNotice = null
    }
    // Why a control did nothing, on the same strip above the mini player that
    // already answers "added to queue". Reached from every surface that had a
    // control taken away — see [ListenTogether.State.controlsLocked].
    val hostOnlyMessage = stringResource(R.string.listen_together_host_only_notice)
    val showHostOnlyNotice: () -> Unit = { showQueueNotice(hostOnlyMessage) }

    /**
     * Whether the host has taken the music, and say so if they have.
     *
     * Every way the app starts or reorders playback funnels through one of the
     * lambdas below, and each asks this first. Checked here rather than left to
     * the player: the service refuses these actions anyway, but by then the tap
     * has already been half-applied — a queue swapped with nothing to play it,
     * or a resume of whatever the party was on — which is what a listener saw
     * as the music flickering on and off.
     */
    val refusedByHost: () -> Boolean = {
        val locked = ListenTogether.state.value.controlsLocked
        if (locked) showHostOnlyNotice()
        locked
    }

    /**
     * The one play/pause every surface presses.
     *
     * In a locked party this still works — it stops and starts *this* device
     * without touching the party, which is the whole of what a listener is
     * left with. The exception is a party that is itself paused: there is
     * nothing to join and nothing to hold out of, so the tap says why instead
     * of starting a second of audio that [PartySync] then has to stop.
     */
    val togglePlayPause: () -> Unit = {
        controller?.let { c ->
            val party = ListenTogether.state.value
            if (party.controlsLocked && !party.playback.isPlaying && !c.isPlaying) {
                showHostOnlyNotice()
            } else if (c.isPlaying) {
                c.pause()
            } else {
                c.play()
            }
        }
    }
    val shuffleEnabled by QueueShuffle.enabled.collectAsStateWithLifecycle()

    // Lyrics follow whatever is playing; duration lands a beat after the track.
    // Keyed on the lyric settings too, so turning a source on or off, reordering
    // them or changing the syllable preference applies to the track already
    // playing rather than only the next one.
    val syncedLyricsEnabled by AppSettings.syncedLyrics.collectAsStateWithLifecycle()
    val lyricsSources by AppSettings.lyricsSources.collectAsStateWithLifecycle()
    val lyricsSourceOrder by AppSettings.lyricsSourceOrder.collectAsStateWithLifecycle()
    val prioritizeSyllableSync by AppSettings.prioritizeSyllableSync.collectAsStateWithLifecycle()
    LaunchedEffect(
        player.song?.videoId, player.durationMs, syncedLyricsEnabled, lyricsSources,
        lyricsSourceOrder, prioritizeSyllableSync,
    ) {
        player.song?.let {
            viewModel.loadLyrics(
                it.videoId,
                it.title,
                it.artist,
                // The player's own length, and the catalogue's where it has
                // none yet. Paused, ExoPlayer never finishes preparing the
                // track it was skipped to, so it reports no duration at all —
                // and a lookup that waits for one waits for ever, which left
                // the lyrics of a paused track loading until it was played.
                player.durationMs.takeIf { ms -> ms > 0L } ?: it.durationMillis(),
                it.albumName,
                it.localUri,
            )
        }
    }

    val homeListState = rememberLazyListState()
    val exploreListState = rememberLazyListState()
    val moodGenreListState = rememberLazyListState()
    val libraryListState = rememberLazyListState()
    val historyListState = rememberLazyListState()
    val libraryShowAllGridState = rememberLazyGridState()
    val moodsGridState = rememberLazyGridState()
    val searchListState = rememberLazyListState()
    val currentListState = when (selectedTab) {
        TAB_HOME -> homeListState
        TAB_EXPLORE -> if (selectedMoodGenre == null) exploreListState else moodGenreListState
        TAB_LIBRARY -> libraryListState
        else -> searchListState
    }

    // Pull-to-refresh: the drag lives with the feed, but the indicator is the
    // line under the top bar, so the state has to be visible to both.
    val homePull = rememberPullToRefreshState()
    val explorePull = rememberPullToRefreshState()
    val libraryPull = rememberPullToRefreshState()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val currentFeed = when {
        route != null || detail != null -> null
        selectedTab == TAB_HOME -> MainViewModel.Feed.HOME
        selectedTab == TAB_EXPLORE -> MainViewModel.Feed.EXPLORE
        selectedTab == TAB_LIBRARY -> MainViewModel.Feed.LIBRARY
        else -> null
    }
    // The lead shelf is listening history, so opening Home after playing
    // something is exactly when it needs re-fetching.
    LaunchedEffect(currentFeed) {
        if (currentFeed == MainViewModel.Feed.HOME) viewModel.onHomeShown()
        // Likewise for Library: a playlist created or a song liked since it
        // was last fetched is a change to exactly this page.
        if (currentFeed == MainViewModel.Feed.LIBRARY) viewModel.onLibraryShown()
    }

    val currentPull = when (currentFeed) {
        MainViewModel.Feed.HOME -> homePull
        MainViewModel.Feed.EXPLORE -> explorePull
        MainViewModel.Feed.LIBRARY -> libraryPull
        null -> null
    }
    val scrolled by remember(currentListState) {
        derivedStateOf {
            currentListState.firstVisibleItemIndex > 0 ||
                currentListState.firstVisibleItemScrollOffset > 24
        }
    }

    // A pushed album/artist/playlist page has a large header of its own — the
    // sleeve, or an artist's photo running edge to edge — which owns the title
    // until it is scrolled away, exactly as a tab's big heading does. The state
    // is hoisted because the bar lives beside that page rather than inside it,
    // and is rebuilt per page: pushing a second one must not inherit the
    // first's scroll offset.
    // As [detailListState], for Replay: its own large heading owns the title
    // until it is scrolled away, and the bar lives out here rather than on the
    // page. Rebuilt per opening so reopening starts at the top, but kept while
    // that opening is on the stack, so back from a page opened off it returns
    // to where it was.
    val replayEntryId = navigator.entries.lastOrNull { it.route == AppRoute.Replay }?.id
    val replayListState = remember(replayEntryId, replayLandingPage) { LazyListState() }
    val replayScrolled by remember(replayListState) {
        derivedStateOf {
            replayListState.firstVisibleItemIndex > 0 ||
                replayListState.firstVisibleItemScrollOffset > 24
        }
    }

    // AnimatedContent keeps the outgoing page composed during its fade. A
    // single state remembered from only the *current* detail id is therefore
    // handed to both the outgoing and incoming LazyColumns for that interval.
    // Compose lazy state is one-layout state: sharing it between those lists
    // can leave the incoming album attached to the disappearing artist/grid
    // layout and unable to consume scroll gestures. Keep one state per page
    // while it is on the navigation stack instead.
    val detailListStates = remember { mutableMapOf<String, LazyListState>() }
    val detailListState = topDetail?.browseId?.let { browseId ->
        detailListStates.getOrPut(browseId) { LazyListState() }
    } ?: remember { LazyListState() }
    // A device folder's tab and selection, kept the same way and for the same
    // reason: an album opened from the folder replaces its page until Back.
    val localFolderStates = remember { mutableMapOf<String, LocalFolderState>() }
    LaunchedEffect(detailStack.map { it.browseId }) {
        val open = detailStack.mapTo(HashSet()) { it.browseId }
        detailListStates.keys.retainAll(open)
        localFolderStates.keys.retainAll(open)
    }
    // Each pushed page's saved state (see the page switcher below), held while
    // the page is on the stack and dropped as it leaves it, so the next visit
    // to the same route starts fresh.
    val pageStates = rememberSaveableStateHolder()
    val pageKeysSeen = remember { mutableSetOf<String>() }
    val livePageKeys = navigator.entries.map { AppDestination.Route(it).key } +
        detailStack.map { AppDestination.Release(it.browseId).key }
    LaunchedEffect(livePageKeys) {
        val gone = pageKeysSeen.filter { it !in livePageKeys }
        gone.forEach(pageStates::removeState)
        pageKeysSeen.removeAll(gone.toSet())
    }
    val detailTitleDrop = with(LocalDensity.current) { DETAIL_TITLE_DROP.toPx() }
    val detailScrolled by remember(detailListState, detailTitleDrop) {
        derivedStateOf {
            detailListState.firstVisibleItemIndex > 0 ||
                detailListState.firstVisibleItemScrollOffset > detailTitleDrop
        }
    }

    // Held, not rebuilt. `listOf` hands back a new instance on every pass, and a
    // List is not a type the compiler can call stable, so under strong skipping
    // the bar this is handed to compares it by identity, never matches, and so
    // can never skip. This composable re-runs on every frame of a scroll — it
    // reads [scrolled] — which made the whole floating bar, both of its states
    // and every glass surface on them recompose once per frame for the length of
    // a fold. Keyed on the labels so a locale change still rebuilds it.
    val homeLabel = stringResource(R.string.home)
    val playLabel = stringResource(R.string.play)
    val exploreLabel = stringResource(R.string.explore)
    val libraryLabel = stringResource(R.string.library)
    val searchLabel = stringResource(R.string.search)
    val queueLabel = stringResource(R.string.queue)
    val sharedLinkLabel = stringResource(R.string.shared_link)
    val openedFileLabel = stringResource(R.string.opened_file)
    val tabs = remember(homeLabel, exploreLabel, libraryLabel, searchLabel) {
        listOf(
            BottomTab(homeLabel, BitChordIcons.Home),
            BottomTab(exploreLabel, BitChordIcons.TabExplore),
            BottomTab(libraryLabel, BitChordIcons.TabLibrary),
            BottomTab(searchLabel, BitChordIcons.TabSearch),
        )
    }

    val scope = rememberCoroutineScope()

    // Copies tracks to the WebDAV server, leaving the local files alone.
    // A clash suspends the batch on WebDavUploads.conflict until the dialog
    // above answers it, so this needs nothing more than the summary.
    fun uploadToWebDav(songs: List<Song>) {
        scope.launch {
            val summary = com.music.bitchord.data.webdav.WebDavUploads.upload(context, songs)
            if (summary.total > 0) {
                showQueueNotice(
                    context.getString(
                        R.string.webdav_upload_summary,
                        summary.uploaded,
                        summary.skipped,
                        summary.failed,
                    ),
                )
            }
            if (summary.uploaded > 0 &&
                topDetail?.browseId == com.music.bitchord.data.webdav.WebDavConfig.BROWSE_ID
            ) {
                viewModel.reloadLocalDetail(com.music.bitchord.data.webdav.WebDavConfig.BROWSE_ID)
            }
        }
    }

    // Switching the playing track between its video upload and its catalogue
    // release lives in [VersionSwitcher]; [versions] is what the player and the
    // track menu read. The controller and the notice follow this composition.
    val versions = remember { VersionState() }
    val versionSwitcher = remember { VersionSwitcher(context, scope, versions) }
    versionSwitcher.controller = controller
    versionSwitcher.showNotice = showQueueNotice

    // Building and starting queues lives in [QueueController]; it follows the
    // connection, the queue the UI shows and the party lock, handed to it on
    // every pass because a remembered object can't capture them once.
    val queueController = remember { QueueController(context, scope, playRequests) }
    queueController.controller = controller
    queueController.currentQueue = player.queue
    queueController.refusedByHost = refusedByHost
    fun playFrom(
        allSongs: List<Song>,
        allIndex: Int,
        source: QueueSource,
        boundedContext: Boolean = false,
        continuationToken: String? = null,
    ) = queueController.playFrom(allSongs, allIndex, source, boundedContext, continuationToken)
    // Kept for entry points whose rows already carry their origin (notably a
    // collection action fetched before this callback). The explicit wrappers
    // below are preferred because a track's album is not necessarily where it
    // was played from.
    val play: (List<Song>, Int) -> Unit = { songs, index ->
        val first = songs.getOrNull(index)
        val source = QueueSource(
            title = first?.playbackSource ?: first?.albumName ?: queueLabel,
            type = first?.playbackSourceType ?: PlaybackSourceType.QUEUE,
            id = first?.playbackSourceId,
        )
        playFrom(songs, index, source)
    }
    LaunchedEffect(player.song?.videoId) {
        versions.onTrackChanged(player.song?.videoId)
        playRequests.onTrackChanged(player.song?.videoId)
    }

    /** A song picked on its own starts a station — see [QueueController.playRadio]. */
    val playRadio: (Song, QueueSource) -> Unit = queueController::playRadio

    /** The overflow menu's Start radio — see [QueueController.startRadio]. */
    val startRadio: (Song) -> Unit = queueController::startRadio
    /**
     * A Top Picks card: opens the release, or starts the station behind it.
     *
     * A station is YouTube Music's own queue for it — the Supermix, the
     * artist's Mix, a mood playlist's radio — fetched only now, on the tap,
     * and loaded before the player is touched so a failed request leaves the
     * current queue playing. It then replaces the queue the way [startRadio]
     * does, and AutoPlay carries it on past its last track.
     */
    val openTopPick: (TopPick, String) -> Unit = { pick, label ->
        val browseId = pick.browseId
        if (pick.kind == TopPickKind.LATEST_RELEASE && browseId != null) {
            viewModel.openDetail(
                browseId = browseId,
                title = pick.title,
                subtitle = pick.artistName.orEmpty(),
                thumbnailUrl = pick.images.firstOrNull(),
            )
        } else if (controller != null && !refusedByHost()) {
            val request = playRequests.begin()
            scope.launch {
                val songs = TopPicks.queue(pick).getOrNull().orEmpty()
                if (!playRequests.isCurrent(request)) return@launch
                val activeController = controller ?: return@launch
                if (songs.isEmpty()) {
                    Toast.makeText(context, R.string.couldnt_load_tracks, Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val sourceId = pick.watch?.playlistId ?: pick.mood?.name
                val station = songs.map {
                    it.copy(
                        radioName = label,
                        playbackSource = label,
                        playbackSourceType = PlaybackSourceType.HOME,
                        playbackSourceId = sourceId,
                    )
                }
                playRequests.radioSeed = null
                activeController.beginRadioQueue()
                activeController.playSongs(station, 0)
                activeController.commitRadioQueue()
            }
        }
    }
    /**
     * A Find Your Mood tile, on Home or on its Show all grid: that mood's
     * station, steered by the same scene words the Mood for You card was
     * built with.
     */
    val playMood: (TopPickMood, String) -> Unit = { mood, title ->
        val hints = topPicks?.firstOrNull { it.kind == TopPickKind.MOOD }?.hints
        openTopPick(
            if (hints != null) TopPicks.moodStation(mood, hints) else TopPicks.moodStation(mood),
            title,
        )
    }
    val addToQueue: (Song) -> Unit = { song ->
        scope.launch {
            if (refusedByHost()) return@launch
            // The end of what the user queued, not the end of the queue: a song
            // asked for by name outranks whatever AutoPlay lined up behind it.
            controller?.let {
                if (ListenTogether.state.value.inParty) {
                    val upcoming = (it.mediaItemCount - (it.currentMediaItemIndex + 1)).coerceAtLeast(0)
                    if (upcoming >= 25) {
                        showQueueNotice(context.getString(R.string.party_queue_full, 25))
                        return@launch
                    }
                }
                val current = it.currentMediaItem?.toSong()
                val timeline = player.queue.takeIf { q -> q.size == it.mediaItemCount }
                    ?: (0 until it.mediaItemCount).map { idx -> it.getMediaItemAt(idx).toSong() }
                val at = QueueCoordinator.findUserQueueInsertionIndex(
                    timeline = timeline,
                    currentIndex = it.currentMediaItemIndex,
                    isNext = false,
                )
                val queued = song.copy(
                    radioName = current?.radioName,
                    playbackSource = current?.playbackSource ?: queueLabel,
                    playbackSourceType = current?.playbackSourceType ?: PlaybackSourceType.QUEUE,
                    playbackSourceId = current?.playbackSourceId,
                ).asQueueEntry(QueueTier.USER_QUEUE)
                it.addMediaItem(at, queued.toMediaItem())
                showQueueNotice(context.getString(R.string.song_added_to_queue))
            }
        }
    }
    val playNext: (Song) -> Unit = { song ->
        scope.launch {
            if (refusedByHost()) return@launch
            controller?.let {
                if (ListenTogether.state.value.inParty) {
                    val upcoming = (it.mediaItemCount - (it.currentMediaItemIndex + 1)).coerceAtLeast(0)
                    if (upcoming >= 25) {
                        showQueueNotice(context.getString(R.string.party_queue_full, 25))
                        return@launch
                    }
                }
                val current = it.currentMediaItem?.toSong()
                val timeline = player.queue.takeIf { q -> q.size == it.mediaItemCount }
                    ?: (0 until it.mediaItemCount).map { idx -> it.getMediaItemAt(idx).toSong() }
                val at = QueueCoordinator.findUserQueueInsertionIndex(
                    timeline = timeline,
                    currentIndex = it.currentMediaItemIndex,
                    isNext = true,
                )
                val queued = song.copy(
                    radioName = current?.radioName,
                    playbackSource = current?.playbackSource ?: queueLabel,
                    playbackSourceType = current?.playbackSourceType ?: PlaybackSourceType.QUEUE,
                    playbackSourceId = current?.playbackSourceId,
                ).asQueueEntry(QueueTier.USER_QUEUE)
                it.addMediaItem(at, queued.toMediaItem())
                showQueueNotice(context.getString(R.string.song_will_play_next))
            }
        }
    }
    val onSongSwipe: (Song) -> Unit = { song ->
        if (AppSettings.swipeToPlayNext.value) playNext(song) else addToQueue(song)
    }

    /**
     * Opens an artist or release page given its browse id — or, failing that,
     * its name.
     *
     * Replay's charts are the reason this exists. An artist there is counted by
     * *name*, because a name is the only thing every track carries: a browse id
     * rides along only when the row that queued the track happened to have one,
     * which for a home-feed card or an AutoPlay suggestion it does not. So half
     * the rows on a chart would have nothing to open, and a row that does
     * nothing when tapped is worse than a row that isn't tappable — it reads as
     * the app having failed rather than as the app not offering.
     *
     * Searching for the name is what the user would do next anyway, and it is
     * what the app already does to find a video's catalogue release (see
     * [YtMusicRepository.resolveAudio]). A search that finds nothing says so,
     * which is at least an answer.
     */
    fun openByName(
        browseId: String?,
        name: String,
        subtitle: String?,
        type: BrowseType,
        artwork: String? = null,
    ) {
        if (browseId != null) {
            val credit = subtitle ?: context.getString(
                if (type == BrowseType.ARTIST) R.string.artist else R.string.album,
            )
            viewModel.openDetail(browseId, name, credit, artwork, type)
            return
        }
        scope.launch {
            val filter = if (type == BrowseType.ARTIST) {
                SearchFilter.ARTISTS
            } else {
                SearchFilter.ALBUMS
            }
            val query = listOfNotNull(name, subtitle).joinToString(" ")
            val hit = YtMusicRepository.search(query, filter).getOrNull()
                ?.filterIsInstance<SearchResult.Browse>()
                ?.firstOrNull()
                ?.item
            if (hit == null) {
                Toast.makeText(
                    context,
                    context.getString(R.string.couldnt_find, name),
                    Toast.LENGTH_SHORT,
                ).show()
            } else {
                viewModel.openDetail(
                    hit.browseId,
                    hit.title,
                    hit.subtitle,
                    hit.thumbnailUrl ?: artwork,
                    hit.type,
                )
            }
        }
    }

    /**
     * A whole album, playlist or library list onto the queue in one go.
     *
     * [next] picks which of the two positions the single-track menu already
     * offers it lands in — straight after the current track, or behind
     * everything else the user queued but still ahead of AutoPlay (see
     * [addToQueue]). The list keeps its own running order either way: this
     * *adds* a release, it doesn't start one, so [QueueShuffle] has no say here.
     *
     * There is usually nothing on screen to show for it — the queue panel is
     * shut, the current track carries on — so the count is said out loud, the
     * same way a batch download is.
     */
    val queueSongs: (List<Song>, Boolean) -> Unit = { songs, next ->
        if (songs.isNotEmpty()) {
            scope.launch {
                if (refusedByHost()) return@launch
                val c = controller
                if (c == null || c.mediaItemCount == 0) {
                    // Nothing to queue behind. "Add to queue" on a silent
                    // player can only mean start here — and adding without
                    // preparing would leave the list sitting in a player that
                    // never gets round to it.
                    play(songs, 0)
                } else {
                    val toAdd = if (ListenTogether.state.value.inParty) {
                        val upcoming = (c.mediaItemCount - (c.currentMediaItemIndex + 1)).coerceAtLeast(0)
                        val slotsLeft = (25 - upcoming).coerceAtLeast(0)
                        if (slotsLeft <= 0) {
                            showQueueNotice(context.getString(R.string.party_queue_full, 25))
                            return@launch
                        }
                        songs.take(slotsLeft)
                    } else {
                        songs
                    }
                    val timeline = player.queue.takeIf { q -> q.size == c.mediaItemCount }
                        ?: (0 until c.mediaItemCount).map { idx -> c.getMediaItemAt(idx).toSong() }
                    val at = QueueCoordinator.findUserQueueInsertionIndex(
                        timeline = timeline,
                        currentIndex = c.currentMediaItemIndex,
                        isNext = next,
                    )
                    val current = c.currentMediaItem?.toSong()
                    c.addMediaItems(
                        at,
                        toAdd.map {
                            it.copy(
                                radioName = current?.radioName,
                                playbackSource = current?.playbackSource ?: queueLabel,
                                playbackSourceType = current?.playbackSourceType
                                    ?: PlaybackSourceType.QUEUE,
                                playbackSourceId = current?.playbackSourceId,
                            ).asQueueEntry(QueueTier.USER_QUEUE).toMediaItem()
                        },
                    )
                    val message = context.resources.getQuantityString(
                        if (next) R.plurals.songs_will_play_next else R.plurals.songs_added_to_queue,
                        toAdd.size,
                        toAdd.size,
                    )
                    showQueueNotice(message)
                }
            }
        }
    }
    val addSongsToQueue: (List<Song>) -> Unit = { songs -> queueSongs(songs, false) }
    val playSongsNext: (List<Song>) -> Unit = { songs -> queueSongs(songs, true) }

    // ---- Links from outside the app ----

    /**
     * A YouTube Music link tapped elsewhere on the device, a link shared into
     * BitChord, or "play something" said to the assistant — see [MusicLink].
     *
     * Keyed on the controller as well as the request, because a link is as
     * often as not what cold-starts the app: the session it has to play into is
     * still connecting the first time this runs, and returning empty-handed
     * without spending the request is what lets the second run serve it.
     */
    val linkRequest by MusicLink.pending.collectAsStateWithLifecycle()
    LaunchedEffect(linkRequest, controller) {
        val request = linkRequest ?: return@LaunchedEffect
        // Nothing here can be served without somewhere to play it — even the
        // branches that only push a page are a beat away from a tap on one of
        // its rows, and half-serving a request would spend it.
        val session = controller ?: return@LaunchedEffect
        when (request) {
            is LinkRequest.Track -> {
                val song = YtMusicRepository.trackLinks(request.videoId).getOrNull()
                if (song == null) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.couldnt_open_link),
                        Toast.LENGTH_SHORT,
                    ).show()
                } else {
                    // A link is one song named on purpose, which is exactly the
                    // case [playRadio] exists for: play it and let AutoPlay
                    // carry on, rather than queueing something around it.
                    playRadio(
                        song,
                        QueueSource(sharedLinkLabel, PlaybackSourceType.SHARED_LINK, song.videoId),
                    )
                }
            }
            is LinkRequest.Page -> {
                dismissPlayer()
                // Titled by the page itself once it lands — a link carries a
                // browse id and nothing else. See MainViewModel.openDetail.
                viewModel.openDetail(request.browseId, title = "")
            }
            is LinkRequest.Search -> {
                val songs = if (!request.play) null else {
                    YtMusicRepository.search(request.query, SearchFilter.SONGS).getOrNull()
                        ?.filterIsInstance<SearchResult.Track>()
                }
                val top = songs?.firstOrNull()?.song
                if (top != null) {
                    playRadio(top, QueueSource(searchLabel, PlaybackSourceType.SEARCH))
                } else {
                    // Either the link was a search to look at, or "play X"
                    // found nothing to start — and the results are a better
                    // answer to a spoken request than silence is.
                    dismissPlayer()
                    navigator.clear()
                    selectedTab = TAB_SEARCH
                    viewModel.searchFor(request.query)
                }
            }
            // "Play music", nothing named. The playback service restores its
            // bounded queue before the controller connects, so this resumes
            // both a live session and one recovered after process death.
            LinkRequest.Resume -> if (session.mediaItemCount > 0) session.play()
            // A file from a file manager. The read grant that came with the
            // intent lasts as long as this task; a persistable one, when the
            // sender offered it, keeps the track playable after a restart.
            is LinkRequest.LocalFile -> {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        request.uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
                val song = LocalMediaRepository.songFromUri(context, request.uri)
                playFrom(
                    listOf(song),
                    0,
                    QueueSource(openedFileLabel, PlaybackSourceType.SHARED_LINK, song.localUri),
                )
                showNowPlaying = true
            }
        }
        MusicLink.handled()
    }

    // ---- Album / playlist menu ----

    /**
     * Holding an album or playlist card, wherever one is drawn.
     *
     * Artists are left out. An artist page is a selection of their work rather
     * than a running order, and "add Radiohead to the queue" has no answer that
     * isn't a guess — so holding an artist card does nothing, as it did before.
     */
    val onBrowseLongPress: (ShelfItem) -> Unit = { item ->
        val id = item.browseId
        val type = id?.let { viewModel.browseTypeOf(it) }
        if (id != null && type != BrowseType.ARTIST) {
            openBrowseMenu(
                BrowseTarget(
                    browseId = id,
                    title = item.title,
                    subtitle = item.subtitle,
                    thumbnailUrl = item.thumbnailUrl,
                    type = type ?: BrowseType.OTHER,
                    downloadId = downloadIdFor(id),
                ),
            )
        }
    }

    /**
     * The track a song card stands for, or null if the card is a collection.
     *
     * The card's own subtitle is billed as "Song • Chelsea Wolfe"; only the
     * credit belongs in the field the player, mini player and everything
     * downstream read.
     */
    val shelfSong: (ShelfItem) -> Song? = { item ->
        item.videoId?.let { videoId ->
            Song(
                videoId = videoId,
                title = item.title,
                artist = InnertubeParser.artistFromSubtitle(item.subtitle),
                thumbnailUrl = item.thumbnailUrl,
                isVideo = item.isVideo,
            )
        }
    }

    /**
     * Holding a card on a feed whose shelves mix tracks with collections —
     * Quick picks and Recently played are songs, Listen again is either.
     *
     * [onBrowseLongPress] alone answered only half of them: a track card
     * carries a videoId and no browse id, so holding one fell through its
     * null check and nothing opened. Dispatched on the same test as the tap
     * below, so a card that plays a song offers the track menu and a card that
     * opens a page offers the album / playlist one.
     */
    val onShelfLongPress: (ShelfItem) -> Unit = { item ->
        val song = shelfSong(item)
        if (song != null) openSongMenu(song) else onBrowseLongPress(item)
    }

    /**
     * Hands [action] the target's whole track list.
     *
     * A card has no tracks behind it — its page was never opened — so the
     * listing is fetched first, all of it, and the menu that asked has already
     * closed by the time it lands. A release page's overflow passes the rows it
     * is already showing and this is immediate.
     *
     * Album rows arrive with no album name of their own, the same way they do on
     * the page (see `withAlbum` below), so the title is stamped on here too —
     * otherwise a track queued from an album card reaches the player and the
     * download folder with nothing to file it under.
     */
    val withBrowseSongs: (BrowseTarget, (List<Song>) -> Unit) -> Unit = { target, action ->
        val stamp: (List<Song>) -> Unit = { songs ->
            action(
                songs.map { song ->
                    song.copy(
                        albumName = if (target.type == BrowseType.ALBUM) {
                            song.albumName ?: target.title
                        } else {
                            song.albumName
                        },
                        playbackSource = target.title,
                        playbackSourceType = PlaybackSourceType.BROWSE,
                        playbackSourceId = target.browseId,
                    )
                },
            )
        }
        when {
            target.songs.isNotEmpty() -> stamp(target.songs)
            target.browseId == null ->
                Toast.makeText(context, context.getString(R.string.no_tracks_here), Toast.LENGTH_SHORT).show()
            else -> viewModel.collectSongs(target.browseId, target.thumbnailUrl) { result ->
                result.fold(
                    onSuccess = stamp,
                    onFailure = {
                        val message = it.message ?: context.getString(R.string.couldnt_load_tracks)
                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                    },
                )
            }
        }
    }

    // ---- Downloads ----
    // Two permissions, and never both on one device: writing to the shared
    // Music folder needs storage access below API 29 and none at all from
    // 29 on, where MediaStore grants an app its own rows; notifications are
    // only asked for from API 33. So the branches below are mutually exclusive
    // by SDK level, and nothing here can stack two dialogs on each other.
    var downloadPending by remember { mutableStateOf<List<Song>>(emptyList()) }
    /**
     * What the pending batch was asked for as, held alongside it for the same
     * reason: the storage-permission dialog is a round trip through another
     * process, and the release has to survive it or a whole album granted
     * permission arrives as forty loose tracks.
     */
    var downloadPendingFrom by remember { mutableStateOf<DownloadTarget?>(null) }
    val notifyPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* Refusing costs the progress notification, not the download. */ }
    val storagePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val songs = downloadPending
        val from = downloadPendingFrom
        downloadPending = emptyList()
        downloadPendingFrom = null
        when {
            songs.isEmpty() -> Unit
            granted -> {
                songs.forEach { Downloads.enqueue(context, it, from?.title) }
                if (from != null) Downloads.markRequested(from.id, songs.map { it.videoId })
            }
            // The one case where refusing is fatal: below API 29 there is no
            // other way to reach the Music folder.
            else -> Toast
                .makeText(context, context.getString(R.string.storage_required_save), Toast.LENGTH_SHORT)
                .show()
        }
    }
    val mediaPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            viewModel.reloadLocalDetail("local:all")
            viewModel.reloadLocalDetail("local:downloads")
            viewModel.loadLibrarySongs()
        } else {
            Toast.makeText(context, context.getString(R.string.storage_required_read), Toast.LENGTH_SHORT).show()
        }
    }
    val mediaPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    // The Search tab's Library source reads the Local Music folder, so it asks
    // for the same permission opening that folder does.
    val onSearchSourceChange: (SearchSource) -> Unit = { source ->
        if (source == SearchSource.LIBRARY && !LocalMediaRepository.hasStoragePermission(context)) {
            mediaPermissionLauncher.launch(mediaPermission)
        }
        viewModel.setSearchSource(source)
    }
    // Shared by the Library tab itself and by a shelf's "Show all" page, so a
    // card opens the same way from either.
    val onLibraryItemClick: (ShelfItem) -> Unit = { item ->
        item.browseId?.let { id ->
            if (id == SPOTIFY_BROWSE_ID) {
                navigator.push(AppRoute.Spotify)
                return@let
            }
            if ((id == "local:all" || id == "local:downloads") && !LocalMediaRepository.hasStoragePermission(context)) {
                mediaPermissionLauncher.launch(mediaPermission)
            }
            // Opened from a shelf's "Show all" page, it stacks over the grid
            // exactly as one opened from the Library tab stacks over that, so
            // back from the release lands on the grid rather than skipping
            // past it.
            viewModel.openDetail(
                browseId = id,
                title = item.title,
                subtitle = item.subtitle,
                thumbnailUrl = item.thumbnailUrl,
            )
        }
    }
    // Takes a list so a single tap on an album/playlist header can queue the
    // whole thing — the permission dance only needs to happen once for the
    // batch, not once per track.
    //
    // [from] is what the list *is*, when it is a release rather than a
    // selection: an album or a playlist. It is recorded whole, so the Downloads
    // page can offer the thing that was tapped back rather than the forty rows
    // it decomposed into — see [Downloads.rememberCollection]. Null for a single
    // track, which is not a release however many of them are asked for one at a
    // time.
    val startDownload: (List<Song>, DownloadTarget?) -> Unit = { requested, from ->
        val saved = Downloads.saved.value
        // Already on disk, and already queued or running: neither needs asking
        // again. What's left is what a tap on "Download" actually means.
        //
        // The release's cover is stamped onto any row that hasn't got one, as a
        // last check before the tap becomes a file.
        //
        // An album page bills its artwork once, in the header — its track rows
        // carry no thumbnail at all, see [InnertubeParser.parseResponsiveListItem]
        // — and a row that reaches [MediaTagger.artworkFor] with a null url is a
        // track saved with no cover in the file and none in [SavedSongMetadata]
        // either, so nothing downstream can draw one afterwards.
        // `MainViewModel.withArtwork` normally fills those in as a page loads and
        // covers the usual route here; this is the backstop for a list that
        // reached this function some other way, and it is worth having precisely
        // because the failure is silent and permanent — the file is written
        // without a cover, and re-downloading adopts the untagged copy rather
        // than replacing it.
        val songs = requested
            .filter { it.videoId !in saved }
            .map { song ->
                val cover = from?.thumbnailUrl
                if (song.thumbnailUrl.isNullOrBlank() && !cover.isNullOrBlank()) {
                    song.copy(thumbnailUrl = cover)
                } else {
                    song
                }
            }
        // Asked here as well as inside [Downloads.enqueue] — not instead of it.
        // Enqueue is the invariant and has to refuse whoever calls it, including
        // the storage-permission continuation below, which resumes long enough
        // after this check for the connection to have changed under it. This is
        // the one place that knows the tap was for forty tracks and can say so
        // once, rather than leaving forty identical failed rows to be read.
        // A copy out of the song cache spends no data, so Wi-Fi only does not
        // refuse a batch that has one — [Downloads.enqueue] still refuses the
        // tracks in it that would need the network.
        val blocked = songs.isNotEmpty() && !AppSettings.downloadsAllowedNow &&
            songs.none { Downloads.servableFromCache(it.videoId) }
        if (songs.isNotEmpty() && !blocked) {
            val needsStorage = AppSettings.exportDownloads.value && DownloadStore.needsLegacyPermission() &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                ) != PackageManager.PERMISSION_GRANTED

            // Asked for here rather than at launch because here is where it means
            // something: a download is the first thing this app does that the user
            // is expected to walk away from.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }

            if (needsStorage) {
                downloadPending = songs
                downloadPendingFrom = from
                storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            } else {
                songs.forEach { Downloads.enqueue(context, it, from?.title) }
                if (from != null) Downloads.markRequested(from.id, songs.map { it.videoId })
            }
            // Said once per addon and reason per session, and only for a batch
            // that will reach the sources at all — a copy out of the song cache
            // never asks them. Without it the addon was passed over in silence
            // and the file simply arrived from JioSaavn or YouTube.
            if (!songs.all { Downloads.servableFromCache(it.videoId) }) {
                SourceRegistry.addonsLeftOutOfDownloads()
                    .firstOrNull { (config, why) -> explainedDownloadExclusions.add("${config.id}:$why") }
                    ?.let { (config, why) -> addonLeftOutOfDownload = config.displayName to why }
            }
        }
        // Recorded for everything that was asked for, not just what still has to
        // be fetched: a release whose tracks are already on the device is still
        // that release, and the point of the record is to group them. Skipped
        // only when the whole batch was refused, since then there will be
        // nothing on disk for it to group.
        if (from != null && !blocked) {
            Downloads.rememberCollection(from, requested)
            // A collection cover is not part of any audio file. Cache the
            // header image separately so its Downloads card still has artwork
            // with no connection, then atomically replace the remote URL in
            // the persisted collection record.
            if (!from.thumbnailUrl.isNullOrBlank()) {
                scope.launch(Dispatchers.IO) {
                    MediaTagger.cacheArtwork(context.applicationContext, from.thumbnailUrl)
                        ?.let { Downloads.rememberCollectionArtwork(from.id, it) }
                }
            }
        }
        when {
            // The row's own icon reports a queued download, so a single tap
            // normally needs no toast — but a refused one leaves the row exactly
            // as it was, and a button that visibly does nothing is worse than a
            // long message. So this one is said whatever the count.
            blocked -> Toast.makeText(
                context,
                context.getString(R.string.wifi_only_download_refusal),
                Toast.LENGTH_LONG,
            ).show()
            requested.size > 1 -> {
                val message = if (songs.isEmpty()) {
                    context.getString(R.string.already_downloaded)
                } else {
                    context.resources.getQuantityString(
                        R.plurals.downloading_song_count,
                        songs.size,
                        songs.size,
                    )
                }
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }
    }
    // The Cached songs folder's download buttons. The song cache already holds
    // these tracks, so they are saved from its copy — no data spent, no
    // connection needed — rather than fetched afresh at the Download quality
    // setting. See [Downloads.preferCachedCopy]. The folder's own second line
    // ("YouTube · 4.2 MB") is the cache's, not the file's, and stays behind.
    val downloadFromCache: (List<Song>) -> Unit = { songs ->
        val cleaned = songs.map { it.copy(downloadFormat = null, localDateAddedSeconds = null) }
        Downloads.preferCachedCopy(cleaned.map { it.videoId })
        startDownload(cleaned, null)
    }
    val removedFromCacheMessage = stringResource(R.string.removed_from_cache)
    // Takes tracks out of the song cache — the folder's selection, or one
    // song's menu. The open folder re-reads itself off [AudioCache.contentsChanged].
    val removeFromCache: (List<Song>) -> Unit = { songs ->
        scope.launch {
            val removed = songs.sumOf { AudioCache.remove(it.videoId) }
            if (removed > 0) showQueueNotice(removedFromCacheMessage)
        }
    }
    /**
     * One track on its own, which is never a release.
     *
     * A track reached through AutoPlay or a radio queue often has no duration
     * string at all — YouTube's watch-queue rows don't always send
     * `lengthText` — while the player itself knows exactly how long the same
     * track runs once it has loaded. Backfilled from there when it's the song
     * on screen, so downloading it isn't handed a duration of zero and
     * silently skipped for lyrics — see [LyricsTag.forTrack].
     */
    val downloadSong: (Song) -> Unit = { song ->
        val withDuration = if (song.durationMillis() <= 0L &&
            player.song?.videoId == song.videoId && player.durationMs > 0L
        ) {
            song.copy(durationText = formatDurationText(player.durationMs))
        } else {
            song
        }
        startDownload(listOf(withDuration), null)
    }

    // Content padding leaves room for the frosted bar above and the tab bar
    // (plus mini player) below, so nothing is ever trapped under the glass.
    //
    // The top is measured off the bar rather than guessed at: the bar is pinned
    // under the status bar inset, and that inset varies by device and by window,
    // so a fixed number only ever lines up on the one device it was picked on.
    // See [topBarContentPadding].
    val listPadding = PaddingValues(
        top = topBarContentPadding(),
        bottom = if (player.song != null) 210.dp else 140.dp,
    )

    // What colour the page currently under the bars is. The fades either end
    // of the screen are flat colour wherever their blur has least to say, so
    // handing them the theme's background puts a black band on a page that is
    // washed in an artwork's colour instead. Off a detail page this resolves
    // to the theme's background anyway, which is exactly right there.
    //
    // An artist page is coloured from Apple's art when it has some, so this has
    // to read the same source the page does or the bars stay the YouTube photo's
    // colour while the page beneath them has moved on.
    val appleArtVersion by AppleArtistArtRepository.updates.collectAsStateWithLifecycle()
    val detailApple = remember(topDetail?.browseId, topDetail?.type, appleArtVersion) {
        topDetail?.takeIf { it.type == BrowseType.ARTIST }
            ?.let { AppleArtistArtRepository.cached(it.browseId) }
    }
    val detailPalette = rememberArtworkPalette(
        imageUrl = detailApple?.heroUrl ?: topDetail?.thumbnailUrl,
        keyColors = detailApple?.keyColors(),
    )

    // One set of numbers for the cards, the page, the stories and the shared
    // picture, so they cannot disagree. Read while any of them is on screen —
    // which includes the Library tab, since the cards live at the top of it.
    // See [rememberReplayState].
    val replayOpen = route == AppRoute.Replay || replayStory != null || showReplayShare ||
        (selectedTab == TAB_LIBRARY && detail == null && route == null)
    val (replay, setReplayPeriod) = rememberReplayState(replayOpen)
    val replayCards = remember(replay.summary) {
        replay.summary?.takeUnless { it.isEmpty }?.cards(context).orEmpty()
    }

    // ---- The track in the player ----
    // Whatever started this track knew its title and its artwork, but rarely
    // which album or artist page it belongs to. Fill that in while the player
    // is actually up — when the sheet is raised, so playing an album from the
    // mini player still costs nothing.
    val playerShowing = showNowPlaying
    var links by remember { mutableStateOf<Song?>(null) }
    LaunchedEffect(player.song?.videoId, playerShowing) {
        links = null
        linksLoading = false
        if (!playerShowing) return@LaunchedEffect
        val current = player.song ?: return@LaunchedEffect
        if (current.albumId != null && current.artistId != null) return@LaunchedEffect
        linksLoading = true
        links = YtMusicRepository.trackLinks(current.videoId).getOrNull()
        linksLoading = false
    }
    val playerSong = player.song?.let { current ->
        val extra = links?.takeIf { it.videoId == current.videoId } ?: return@let current
        current.copy(
            artistId = current.artistId ?: extra.artistId,
            albumId = current.albumId ?: extra.albumId,
            albumName = current.albumName ?: extra.albumName,
        )
    }
    // The three-dot menu snapshots the track into songActions when it's opened,
    // so a menu opened before the lookup above resolves would otherwise be
    // stuck without album/artist rows even after the ids come in. Keep it in
    // sync while it's showing this track.
    LaunchedEffect(playerSong) {
        if (playerSong != null && songActions?.videoId == playerSong.videoId) {
            songActions = playerSong
        }
    }

    // The player's whole parameter list, kept apart from the sheet that
    // mounts it so the sheet's own setup reads on its own.
    val nowPlaying: @Composable (Song) -> Unit = { song ->
        val effectiveSong = versions.effectiveSong(song)
        val displayedSong = activeRadioSeed
            ?.takeIf { (videoId, _) -> effectiveSong.radioName == null && videoId == effectiveSong.videoId }
            ?.let { (videoId, name) ->
                effectiveSong.copy(
                    radioName = name,
                    playbackSource = name,
                    playbackSourceType = PlaybackSourceType.SHARED_LINK,
                    playbackSourceId = videoId,
                )
            }
            ?: effectiveSong
        val playedBy = partyState
            .takeIf {
                it.inParty && it.playback.track?.videoId == displayedSong.videoId
            }
            ?.playback
            ?.let { playback ->
                playback.startedByName?.takeIf(String::isNotBlank)
                    ?: partyState.members.firstOrNull {
                        it.memberId == playback.startedBy
                    }?.displayName?.takeIf(String::isNotBlank)
            }
        NowPlayingScreen(
            song = displayedSong,
            playedBy = playedBy,
            accountName = account?.name,
            windowWidth = windowWidth,
            windowHeight = windowHeight,
            // A canvas under the ⋯ menu holds still. Asked, not passed — see
            // the parameter.
            menuOpen = { songActions != null && menuFromPlayer && songMenuOrigin == null },
            onLiveCanvasChanged = { playerLiveCanvas = it },
            isPlaying = player.isPlaying,
            isLoading = playPauseBusy,
            position = player.position,
            durationMs = player.durationMs,
            audioVersionSwitching = versions.switching,
            qualityUpgraded = player.isQualityUpgraded,
            onPlayPause = {
                togglePlayPause()
            },
            onNext = { controller?.seekToNextMediaItem() },
            onPrevious = { controller?.seekToPrevious() },
            onBlockedControl = showHostOnlyNotice,
            onSeekFraction = { fraction ->
                controller?.let { player ->
                    // Read at the moment of the seek, not from the
                    // polled snapshot the screen draws with: a track
                    // change updates the current item before it updates
                    // the duration, so a fraction dropped seconds after
                    // a transition would otherwise be scaled by the
                    // previous song's length.
                    val duration = player.duration
                    if (duration > 0) {
                        player.seekTo(
                            (fraction * duration).toLong()
                                .coerceIn(0L, (duration - SEEK_END_GUARD_MS).coerceAtLeast(0L)),
                        )
                    }
                }
            },
            onSeek = { target ->
                controller?.let { player ->
                    // Clamped here rather than at each caller because
                    // not every caller can clamp. The scrubber's target
                    // is a fraction of the duration and cannot overrun,
                    // but a tapped lyric line seeks to a timestamp from
                    // whichever transcription matched on title, artist
                    // and duration — and a match against a slightly
                    // longer master puts every line late, so a tap near
                    // the end asks for a position past the end of this
                    // stream. Media3 answers that by clamping to the
                    // final millisecond, which ends the track and starts
                    // the next one: tapping the last line of a song
                    // skipped it.
                    val duration = player.duration
                    player.seekTo(
                        if (duration > 0) {
                            target.coerceIn(0L, (duration - SEEK_END_GUARD_MS).coerceAtLeast(0L))
                        } else {
                            target.coerceAtLeast(0L)
                        },
                    )
                }
            },
            queue = player.queue,
            queueIndex = player.queueIndex,
            hasPrevious = player.hasPrevious,
            hasNext = player.hasNext,
            repeatMode = player.repeatMode,
            shuffleEnabled = shuffleEnabled,
            autoplayEnabled = autoplayEnabled,
            signedIn = signedIn,
            likeStatus = likeStatusOf(song),
            onToggleLike = { toggleLikeOf(song) },
            // The service owns both the queue and the Shuffle state. Keeping
            // the toggle on that side prevents the UI from changing the icon
            // before its asynchronous reorder command has actually landed.
            onToggleShuffle = { controller?.toggleShuffle() },
            onCycleRepeat = {
                controller?.let {
                    val next = when (it.repeatMode) {
                        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                        else -> Player.REPEAT_MODE_OFF
                    }
                    // Persist the new repeat mode so it survives app restarts.
                    AppSettings.setRepeatMode(next)
                    // Nothing else to do here: PlaybackService watches the
                    // repeat mode itself and takes AutoPlay's tracks out of
                    // the queue for the duration of repeat-all — and, unlike
                    // this screen, is still around to put them back when the
                    // loop ends.
                    it.repeatMode = next
                }
            },
            onToggleAutoplay = {
                // PlaybackService owns the setting and queue extension so
                // this path and the notification use exactly one loader.
                controller?.toggleAutoplay()
            },
            onJumpTo = { index ->
                controller?.let { c ->
                    // The session applies the jump plan to its own player (see
                    // its seekTo override); editing the queue from here would
                    // round-trip every item through the controller and lose
                    // their bounded-window metadata.
                    c.seekTo(index, 0L)
                    c.play()
                }
            },
            onRemoveFromQueue = { controller?.removeMediaItem(it) },
            onMoveInQueue = { from, to -> controller?.moveMediaItem(from, to) },
            onQueueDragActiveChange = { active -> controller?.setQueueDragActive(active) },
            // The enriched copy, not player.song — otherwise the menu
            // hides the album and artist rows even once their browse
            // ids have been resolved.
            onOpenMenu = {
                menuFromPlayer = true
                songMenuOrigin = null
                songMenuAnchor = MenuAnchor.consume()
                songActions = song
                // Taken on the frame the menu opens on, which is long anyway,
                // rather than on one of its spring's.
                if (!AppSettings.reduceDynamicBlur.value && !playerLiveCanvas) playerBackdrop.capture()
            },
            // Pushed over whatever page is under the player, so back from it
            // returns there.
            onOpenAlbum = { id ->
                dismissPlayer()
                viewModel.openDetail(
                    id,
                    song.albumName ?: song.title,
                    song.artist,
                    song.thumbnailUrl,
                    BrowseType.ALBUM,
                )
            },
            // A credit with a channel id opens that channel straight away.
            // YouTube does not give one to every credited artist - on a two-artist
            // track it linked only the first, and neither the byline nor the
            // album header had the second - so the rest go by name, through a
            // lookup that opens a page only when the answer is that name
            // exactly. Otherwise nothing opens, which beats opening a stranger.
            //
            // No artwork: this track's cover isn't the artist's
            // picture, and the page fills its own in once loaded.
            onOpenArtist = { id, name ->
                dismissPlayer()
                if (id != null) {
                    viewModel.openDetail(
                        id,
                        name,
                        context.getString(R.string.artist),
                        null,
                        BrowseType.ARTIST,
                    )
                } else {
                    scope.launch {
                        val found = YtMusicRepository.findArtistPageId(name).getOrNull()
                        if (found != null) {
                            viewModel.openDetail(
                                found,
                                name,
                                context.getString(R.string.artist),
                                null,
                                BrowseType.ARTIST,
                            )
                        } else {
                            Toast.makeText(
                                context,
                                context.getString(R.string.couldnt_find, name),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                }
            },
            onOpenPlaybackSource = openSource@{
                val sourceType = displayedSong.playbackSourceType ?: PlaybackSourceType.QUEUE
                val sourceTitle = displayedSong.playbackSource
                    ?: displayedSong.albumName
                    ?: queueLabel
                val sourceId = displayedSong.playbackSourceId

                // A context link is navigation, not another page stacked over
                // Now Playing. Clear the current route before restoring it.
                dismissPlayer()
                navigator.clear()

                when (sourceType) {
                    PlaybackSourceType.BROWSE -> {
                        val id = sourceId ?: return@openSource
                        viewModel.closeMoodGenre()
                        viewModel.openDetail(id, sourceTitle)
                    }
                    PlaybackSourceType.HOME -> {
                        viewModel.closeMoodGenre()
                        selectedTab = TAB_HOME
                    }
                    PlaybackSourceType.SEARCH -> {
                        viewModel.closeMoodGenre()
                        selectedTab = TAB_SEARCH
                    }
                    PlaybackSourceType.HISTORY -> {
                        navigator.push(AppRoute.History)
                        viewModel.loadHistory()
                    }
                    PlaybackSourceType.REPLAY -> {
                        replayLandingPage = ReplayStoryPage.INTRO
                        navigator.push(AppRoute.Replay)
                    }
                    PlaybackSourceType.EXPLORE -> selectedTab = TAB_EXPLORE
                    PlaybackSourceType.SHARED_LINK -> {
                        val id = sourceId ?: return@openSource
                        context.startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("https://music.youtube.com/watch?v=$id"),
                            ),
                        )
                    }
                    // Handled inside NowPlayingScreen by opening its queue.
                    PlaybackSourceType.QUEUE -> Unit
                }
            },
            lyrics = lyrics,
            lyricsSource = lyricsSource,
            lyricsProviderStates = lyricsProviderStates,
            onSelectLyricsProvider = viewModel::selectLyricsProvider,
            lyricsUnavailable = lyricsChecked && lyrics.isNullOrEmpty(),
            lyricsOffsetOpen = showLyricsOffset,
            onDismissLyricsOffset = { showLyricsOffset = false },
            onListenTogether = {
                // The player is a sheet over the page, so it has to come down
                // for the page to be read at all.
                dismissPlayer()
                navigator.pushAll(AppRoute.Settings, AppRoute.ListenTogether)
            },
            onClearQueue = {
                // Keep what's playing and context/autoplay; drop user-queued tracks.
                controller?.let { c ->
                    QueueCoordinator.clearUserQueue(c)
                }
            },
        )
    }

    // One step back, from anywhere: the page on top of the stack, then a mood
    // or genre opened inside Explore, then any tab but Home back to Home. Only
    // Home itself hands back to the system, which is what actually closes or
    // minimizes the app. The gesture and the bar's back button both come here,
    // so they can never disagree about what is on top.
    val canGoBack = navigator.entries.isNotEmpty() || detailStack.isNotEmpty() ||
        selectedMoodGenre != null || selectedTab != TAB_HOME
    // What the four tab pages reach that lives here — see [TabActions].
    val tabActions = TabActions(
        shelfSong = shelfSong,
        playRadio = playRadio,
        playFrom = { songs, index, source -> playFrom(songs, index, source) },
        onShelfLongPress = onShelfLongPress,
        openTopPick = openTopPick,
        playMood = playMood,
        openSongMenu = openSongMenu,
        onSongSwipe = onSongSwipe,
        openBrowseMenu = openBrowseMenu,
        downloadIdFor = downloadIdFor,
        onSearchSourceChange = onSearchSourceChange,
        onLibraryItemClick = onLibraryItemClick,
        onBrowseLongPress = onBrowseLongPress,
        openDiwali = { showDiwali = true },
        searchFocusHandled = { searchFocusRequested = false },
        pickPlaylistFor = { song -> playlistTarget = song },
        createPlaylist = { creatingPlaylist = true },
        importSpotifyPlaylist = { showSpotifyImportDialog = true },
        openReplayAt = { page ->
            replayLandingPage = page
            navigator.push(AppRoute.Replay)
        },
    )
    val goBack: () -> Unit = {
        if (!navigator.pop() && !viewModel.closeMoodGenre()) selectedTab = TAB_HOME
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // Nothing of the page under a portrait player that is fully up:
            // that player is its own window, opaque from edge to edge, and
            // this one drawing on behind it — the page, the glass bar and the
            // blurs that sample it, a now-playing row's bars — is GPU time
            // taken from the player on every frame for nothing anyone can see.
            // The same test the player's own dim skips itself on. Composition
            // and layout carry on, so the page is current the moment the
            // player starts to come down and this draws again.
            .drawWithContent {
                if (!coveredByPlayer) drawContent()
            },
    ) {
        // A pushed page replaces the tab content but leaves the tab bar and
        // mini player in place.
        //
        // Back handlers answer newest-registered first, so the order below is
        // the order of precedence, lowest first: the page stack, then what is
        // drawn over a page, then the alerts drawn over everything. Each sheet
        // and dialog further down registers its own handler as it opens, and
        // so outranks all of these while it is up.
        BackHandler(enabled = canGoBack, onBack = goBack)
        // Replay's stories and its share sheet are drawn over everything, and
        // unwind in the order they were opened. Only while there is a summary
        // to draw them from — otherwise back would spend a step closing
        // something that never appeared.
        val replayDrawable = replay.summary?.isEmpty == false
        BackHandler(enabled = replayDrawable && replayStory != null && !showReplayShare) { replayStory = null }
        BackHandler(enabled = replayDrawable && showReplayShare) { showReplayShare = false }
        // Note: Modal dialogs (update, ListenBrainz, Last.fm) are wrapped in
        // IosModalContainer, which registers their own BackHandlers that trigger
        // animated exit transitions.
        BackHandler(enabled = discordDialog != null) { discordDialog = null }
        BackHandler(enabled = editingSource != null) { editingSource = null }
        BackHandler(enabled = choosingSourceQuality != null) { choosingSourceQuality = null }
        BackHandler(enabled = explainingOutdatedAddon != null) { explainingOutdatedAddon = null }
        BackHandler(enabled = editingPartyServer) { editingPartyServer = false }
        BackHandler(enabled = editingSignatureApi) { editingSignatureApi = false }

        // On a tablet the page and the player stand side by side rather than
        // one over the other: everything a phone stacks in a single column —
        // the feed, the frosted bars, the tab row — becomes the left half of
        // a row, and the player is the right. Off a tablet the row has the
        // one child it always had and changes nothing.
        //
        // Everything a page's menu floats over, and so what [pageBackdrop]
        // takes its still of; the menus themselves are drawn outside it. On the
        // window's background, which the pages don't paint: see `paintBackdrop`.
        Row(
            Modifier
                .fillMaxSize()
                .snapshotBackdropSource(pageBackdrop, floor = MaterialTheme.colorScheme.background),
        ) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                AnimatedContent(
                    // Whatever is on top of the back stack — see [AppNavigator].
                    targetState = when {
                        visibleEntry != null -> AppDestination.Route(visibleEntry)
                        detail != null -> AppDestination.Release(detail.browseId)
                        else -> AppDestination.Tab(selectedTab)
                    },
                    // A route's entry is copied when the release pages under it
                    // change, which is not a different page.
                    contentKey = { it.key },
                    // Tabs swap outright; everything else crossfades.
                    //
                    // A tab is not a place you travel to — the bar is the whole
                    // navigation and it carries its own movement — so a fade
                    // between two of them only ever reads as a stutter. And it
                    // cannot read as anything else: neither page paints a
                    // background, so a crossfade dissolves both through to the
                    // window and the switch dips through a dimmer frame in the
                    // middle. Pushing a page or raising Settings is a real
                    // change of context and keeps the fade.
                    //
                    // "Show all" swapping with the Library tab underneath it is
                    // the same case as a tab swap, not a pushed page: it's still
                    // that tab, just laid out as a grid instead of a row, sharing
                    // its background rather than painting its own — so this one
                    // pair gets the tab's no-fade swap too, in both directions, or
                    // the dip through a dim frame shows up on every hold of a
                    // card there. A card opened *from* the grid is a real page
                    // and keeps the fade, same as one opened from the row.
                    transitionSpec = {
                        val initial = initialState
                        val target = targetState
                        val tabSwap = initial is AppDestination.Tab && target is AppDestination.Tab
                        val libraryTab = AppDestination.Tab(TAB_LIBRARY)
                        fun AppDestination.isShowAll() =
                            (this as? AppDestination.Route)?.entry?.route is AppRoute.LibraryShowAll
                        val libraryShowAllSwap = (initial.isShowAll() && target == libraryTab) ||
                            (target.isShowAll() && initial == libraryTab)

                        fun destinationDepth(dest: AppDestination): Int = when (dest) {
                            is AppDestination.Tab -> 0
                            is AppDestination.Release -> {
                                val detailIndex = detailStack.indexOfFirst { it.browseId == dest.browseId }
                                val d = if (detailIndex >= 0) detailIndex + 1 else detailStack.size.coerceAtLeast(1)
                                val routesUnder = navigator.entries.count { it.detailDepth < d }
                                d + routesUnder
                            }
                            is AppDestination.Route -> {
                                val routeIndex = navigator.entries.indexOfFirst { it.id == dest.entry.id }
                                if (routeIndex >= 0) {
                                    val entry = navigator.entries[routeIndex]
                                    entry.detailDepth + routeIndex + 1
                                } else {
                                    detailStack.size + navigator.entries.size + 1
                                }
                            }
                        }

                        val initialDepth = destinationDepth(initial)
                        val targetDepth = destinationDepth(target)
                        val isPush = targetDepth > initialDepth
                        val isPop = targetDepth < initialDepth

                        if (tabSwap || libraryShowAllSwap) {
                            EnterTransition.None togetherWith ExitTransition.None
                        } else if (reducePlayerMotion) {
                            IosMotion.navCrossfade()
                        } else if (isPush) {
                            // Forward: in from the right, the old page shifts back with parallax.
                            IosMotion.navPush()
                        } else if (isPop) {
                            // Back: out to the right, the previous page slides back in.
                            IosMotion.navPop()
                        } else {
                            IosMotion.navCrossfade()
                        }
                    },
                    modifier = Modifier
                        .hazeSource(hazeState)
                        .then(
                            if (glassActive) {
                                Modifier
                                    // Not under "reduce dynamic blur": nothing
                                    // samples the layer then, and recording a
                                    // whole page into one for no reader is the
                                    // cost that setting exists to remove.
                                    .then(
                                        if (glassSamplesBackdrop) {
                                            Modifier.layerBackdrop(appBackdrop)
                                        } else {
                                            Modifier
                                        },
                                    )
                                    // Every page's scroll passes through here, so
                                    // the glass bar collapses on all of them
                                    // without each one having to know about it.
                                    .nestedScroll(navBarScroll)
                            } else {
                                Modifier
                            },
                        ),
                    label = "content",
                ) { destination ->
                    // Every branch below reads `destination` rather than the
                    // stack that produced it. The two are the same thing only
                    // for the page being entered: the one on its way out is
                    // still composed, and asking it what is on top *now* has it
                    // redraw itself as its own replacement — which then fades
                    // out from under the identical copy fading in behind it.
                    val key = destination.key
                    val pageRoute = (destination as? AppDestination.Route)?.entry?.route
                    val live = (destination as? AppDestination.Release)?.let { release ->
                        detailStack.lastOrNull()?.takeIf { it.browseId == release.browseId }
                    }
                    // Held for the same reason, one step further on: a popped
                    // page is off the stack before it has finished animating
                    // out, so `live` goes null under it and it would spend its
                    // exit drawing whatever is underneath instead of itself.
                    // Per slot, since each is remembered against its own key.
                    val held = remember(key) { mutableStateOf(live) }
                    if (live != null) held.value = live
                    val page = held.value
                    // This state belongs to this AnimatedContent slot, not to
                    // whichever detail happens to be at the top of the stack
                    // while the slot is fading out.
                    val pageDetailListState = remember(key) {
                        detailListStates.getOrPut(key) { LazyListState() }
                    }
                    val pageContent: @Composable () -> Unit = {
                        if (pageRoute != null && handlesRoute(pageRoute)) {
                            RoutePage(
                                pageRoute = pageRoute,
                                viewModel = viewModel,
                                navigator = navigator,
                                alerts = alerts,
                                overlays = overlays,
                                actions = RouteActions(
                                    playFrom = { songs, index, source -> playFrom(songs, index, source) },
                                    openSongMenu = openSongMenu,
                                    openSongMenuFromHistory = { song ->
                                        menuFromHistory = true
                                        openSongMenu(song)
                                    },
                                    onSongSwipe = onSongSwipe,
                                    onLibraryItemClick = onLibraryItemClick,
                                    onBrowseLongPress = onBrowseLongPress,
                                    playMood = playMood,
                                    playRadio = playRadio,
                                    openByName = { id, name, subtitle, type, art -> openByName(id, name, subtitle, type, art) },
                                    setReplayPeriod = setReplayPeriod,
                                    openReplayStory = { replayStory = it },
                                    shareReplay = {
                                        replaySharePage = null
                                        showReplayShare = true
                                    },
                                    openReplay = {
                                        replayLandingPage = ReplayStoryPage.INTRO
                                        navigator.push(AppRoute.Replay)
                                    },
                                    createPlaylist = { creatingPlaylist = true },
                                    inviteHandled = {
                                        activeJamInviteCode = null
                                        activeJamInviteServer = null
                                    },
                                ),
                                player = player,
                                historyState = historyState,
                                historyListState = historyListState,
                                libraryShowAllGridState = libraryShowAllGridState,
                                moodsGridState = moodsGridState,
                                replay = replay,
                                replayListState = replayListState,
                                replayLandingPage = replayLandingPage,
                                account = account,
                                selectedChannelName = selectedChannelName,
                                signedIn = signedIn,
                                activeJamInviteCode = activeJamInviteCode,
                                activeJamInviteServer = activeJamInviteServer,
                                windowWidth = windowWidth,
                                listPadding = listPadding,
                            )
                        } else if (page != null) {
                            // Local Music, Downloads and the other device folders are
                            // this same release page: the header any playlist has, then
                            // Songs / Artists / Albums under it. An album or artist
                            // picked there opens as a page of its own — see LocalGroups.
                            ReleasePage(
                                page = page,
                                viewModel = viewModel,
                                actions = ReleasePageActions(
                                    scope = scope,
                                    openSongMenu = openSongMenu,
                                    openBrowseMenu = openBrowseMenu,
                                    onShelfLongPress = onShelfLongPress,
                                    onSongSwipe = onSongSwipe,
                                    playFrom = { songs, index, source, bounded, continuation ->
                                        playFrom(songs, index, source, boundedContext = bounded, continuationToken = continuation)
                                    },
                                    playRadio = playRadio,
                                    shelfSong = shelfSong,
                                    refusedByHost = refusedByHost,
                                    uploadToWebDav = ::uploadToWebDav,
                                    downloadFromCache = downloadFromCache,
                                    removeFromCache = removeFromCache,
                                    createOfflinePlaylist = { creatingOfflinePlaylist = true },
                                    showQueueNotice = showQueueNotice,
                                    setSearching = { detailSearching = it },
                                ),
                                navigator = navigator,
                                reorder = playlistReorder,
                                currentSong = player.song,
                                isPlaying = player.isPlaying,
                                listState = pageDetailListState,
                                activeShelf = detailActiveShelf,
                                // Only the page on its way in answers to the bar's
                                // search button; one fading out keeps its list whole.
                                searching = detailSearching && page.browseId == detail?.browseId,
                                signedIn = signedIn,
                                releaseLibrary = releaseLibrary,
                                songSort = songSort,
                                listPadding = listPadding,
                                webdavUrl = webdavUrl,
                                savedCollections = savedCollections,
                                localFolderStates = localFolderStates,
                                folderSortOf = folderSortOf,
                                folderViewTypeOf = folderViewTypeOf,
                                offlinePlaylistGroups = offlinePlaylistGroups,
                            )
                        } else when ((destination as? AppDestination.Tab)?.index ?: selectedTab) {
                            TAB_HOME -> HomeTab(
                                viewModel = viewModel, navigator = navigator, alerts = alerts, overlays = overlays, actions = tabActions,
                                visibleHomeState = visibleHomeState, homeListState = homeListState, homePull = homePull,
                                player = player, signedIn = signedIn, diwaliFestive = diwaliFestive, showDiwali = showDiwali,
                                artworkMotion = artworkMotion, listPadding = listPadding,
                            )
                            TAB_EXPLORE -> ExploreTab(
                                viewModel = viewModel, navigator = navigator, alerts = alerts, overlays = overlays, actions = tabActions,
                                exploreListState = exploreListState, moodGenreListState = moodGenreListState,
                                explorePull = explorePull, listPadding = listPadding,
                            )
                            TAB_SEARCH -> SearchTab(
                                viewModel = viewModel, navigator = navigator, alerts = alerts, overlays = overlays, actions = tabActions,
                                searchListState = searchListState, player = player,
                                searchFocusRequested = searchFocusRequested, addonCeiling = addonCeiling,
                                listPadding = listPadding,
                            )
                            else -> LibraryTab(
                                viewModel = viewModel, navigator = navigator, alerts = alerts, overlays = overlays, actions = tabActions,
                                libraryListState = libraryListState, libraryPull = libraryPull, signedIn = signedIn,
                                replay = replay, replayCards = replayCards, downloadedReleases = downloadedReleases,
                                localPlaylistItems = localPlaylistItems, listPadding = listPadding,
                            )
                        }
                    }
                    // A pushed page keeps its saved state — a scroll position,
                    // a search typed into Settings — for as long as it is on
                    // the stack, so coming back to it finds it as it was left.
                    // Tabs keep theirs hoisted above, as they always have.
                    //
                    // The playing bars hold still with the player up, the app in
                    // the background, or this page on its way out: each frame of
                    // them redraws the page under the glass, for nobody.
                    val barsMotion = artworkMotion && transition.targetState == EnterExitState.Visible
                    CompositionLocalProvider(LocalPlayingBarsMotion provides barsMotion) {
                        if (destination is AppDestination.Tab) {
                            pageContent()
                        } else {
                            pageKeysSeen += key
                            pageStates.SaveableStateProvider(key, pageContent)
                        }
                    }
                }

                // The top-bar footprint is transparent on every page so the
                // shared app-level gradient is continuous; its controls float
                // as separate circles in both the glass and blur materials.
                val isDetailVisible = detail != null &&
                    (detail.type == BrowseType.ALBUM ||
                        detail.type == BrowseType.PLAYLIST ||
                        detail.type == BrowseType.ARTIST) &&
                    !isLocalDetail
                val chromePageColor = if (isDetailVisible) {
                    detailPalette.background
                } else {
                    MaterialTheme.colorScheme.background
                }
                // Apple's scroll edge: what passes up under the status bar and the
                // controls is blurred — hardest at the top, none just below the
                // controls — rather than faded out to the page colour. It is a
                // Liquid Glass option: with glass off, the Progressive Blur setting
                // off, or under reduce dynamic blur, the bottom floor's own fade
                // stands in, turned upside down, so both edges share its curve
                // and colour.
                // On an artist page the photograph and logo run up under the status
                // bar, and either across them would only muddy them. It comes in as
                // the page scrolls past that header, when content starts passing
                // under the bar and needs the cover.
                //
                // Home's wordmark sits up there too, lifted under the bar by
                // HOME_TITLE_LIFT, and at rest the fade washed it out. It is held
                // off the same way, from the first pixel of scroll rather than
                // partway through, since the wordmark is the top of its heading.
                val artistListState = detail
                    ?.takeIf { isDetailVisible && it.type == BrowseType.ARTIST }
                    ?.let { detailListStates.getOrPut(it.browseId) { LazyListState() } }
                val homeHeadingVisible = route == null && detail == null && selectedTab == TAB_HOME
                val headerListState = artistListState ?: homeListState.takeIf { homeHeadingVisible }
                val fadeStart = if (artistListState != null) TOP_FADE_START else 0f
                val fadeRamp = if (artistListState != null) TOP_FADE_RAMP else 1f
                val topFadeAlpha by remember(headerListState, fadeStart, fadeRamp) {
                    derivedStateOf {
                        val list = headerListState ?: return@derivedStateOf 1f
                        if (list.firstVisibleItemIndex > 0) return@derivedStateOf 1f
                        val header = list.layoutInfo.visibleItemsInfo
                            .firstOrNull { it.index == 0 }?.size?.toFloat()
                            ?: return@derivedStateOf 0f
                        ((list.firstVisibleItemScrollOffset - header * fadeStart) /
                            (header * fadeRamp)).coerceIn(0f, 1f)
                    }
                }
                if (reduceDynamicBlur || !glassActive || !progressiveBlur) {
                    BottomFadeScrim(
                        pageColor = chromePageColor,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .rotate(180f)
                            .graphicsLayer { alpha = topFadeAlpha },
                    )
                } else {
                    TopFadeBlur(
                        hazeState = hazeState,
                        pageColor = chromePageColor,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .graphicsLayer { alpha = topFadeAlpha },
                    )
                }

                FrostedTopBar(
                    title = when (route) {
                        AppRoute.Spotify -> stringResource(R.string.spotify)
                        AppRoute.Downloads -> stringResource(R.string.downloads)
                        AppRoute.Discord -> "Discord"
                        AppRoute.History -> stringResource(R.string.history)
                        is AppRoute.LibraryShowAll -> route.shelf.title
                        is AppRoute.ArtistShelf -> route.shelf.title
                        AppRoute.Moods -> stringResource(R.string.find_your_mood)
                        AppRoute.AccountScrobbling -> stringResource(R.string.account_scrobbling)
                        AppRoute.Sources -> stringResource(R.string.sources)
                        AppRoute.ListenTogether -> stringResource(R.string.listen_together)
                        AppRoute.WearOs -> stringResource(R.string.wear_os_page_title)
                        AppRoute.Equalizer -> stringResource(R.string.equalizer)
                        AppRoute.Settings -> stringResource(R.string.settings)
                        is AppRoute.SettingsSection -> stringResource(route.category.title)
                        is AppRoute.SettingsChoice -> stringResource(route.choice.title)
                        AppRoute.EqualizerPresets -> stringResource(R.string.equalizer_preset)
                        AppRoute.Replay -> stringResource(R.string.replay)
                        null -> when {
                            detail != null && detailActiveShelf != null -> detailActiveShelf?.title.orEmpty()
                            detail != null -> detail.title
                            selectedMoodGenre != null -> selectedMoodGenre?.title.orEmpty()
                            else -> tabs[selectedTab].let {
                                if (it.label == "Play") stringResource(R.string.listen_now) else it.label
                            }
                        }
                    },
                    // Every page, in either material, uses separated floating
                    // circles over the shared gradient — no full-width pane.
                    transparentBackdrop = true,
                    artworkPageChrome = true,
                    backButtonHazeState = hazeState,
                    trailingTitle = if (detail != null && detailActiveShelf != null) detail.title else null,
                    // Search has no large in-list header to hand the title back to —
                    // the field takes that space — so its bar title is always up.
                    scrolled = {
                        when {
                            // The page leads with its own large "Replay", so the bar
                            // stays out of the way until that has been scrolled off.
                            route == AppRoute.Replay -> replayScrolled
                            route != null -> true
                            detail != null && detailActiveShelf != null -> true
                            detail != null -> detailScrolled
                            selectedMoodGenre != null -> true
                            else -> scrolled || selectedTab == TAB_SEARCH
                        }
                    },
                    refreshing = currentFeed != null && currentFeed in refreshing,
                    pullFraction = { currentPull?.distanceFraction ?: 0f },
                    // The same step the back gesture takes — see [goBack] —
                    // offered on anything pushed over a tab.
                    onBack = when {
                        route != null || detail != null || selectedMoodGenre != null -> goBack
                        else -> null
                    },
                    modifier = Modifier.align(Alignment.TopCenter),
                    // Only on the Search tab itself, not on anything pushed over it.
                    accessory = if (
                        selectedTab == TAB_SEARCH && route == null && detail == null && selectedMoodGenre == null
                    ) {
                        {
                            // The search field lives up here in the bar, beside the
                            // account photo, so it answers the nav bar's Search tap
                            // itself: focus and keyboard, the way the page used to.
                            val keyboard = LocalSoftwareKeyboardController.current
                            LaunchedEffect(searchFocusRequested) {
                                if (searchFocusRequested) {
                                    searchFieldFocus.requestFocus()
                                    keyboard?.show()
                                    searchFocusRequested = false
                                }
                            }
                            SearchField(
                                query = query,
                                onQueryChange = viewModel::onQueryChange,
                                onSubmit = viewModel::submitSearch,
                                focusRequester = searchFieldFocus,
                                placeholder = org.jetbrains.compose.resources.stringResource(
                                    if (searchSource == SearchSource.LIBRARY) {
                                        SharedRes.string.search_library_hint
                                    } else if (searchSource == SearchSource.ADDONS) {
                                        SharedRes.string.search_addons_hint
                                    } else {
                                        SharedRes.string.search_hint
                                    },
                                ),
                            )
                        }
                    } else null,
                    actions = {
                        TopBarActions(
                            viewModel = viewModel,
                            navigator = navigator,
                            route = route,
                            detail = detail,
                            detailActiveShelf = detailActiveShelf,
                            isReleaseDetail = isReleaseDetail,
                            isLocalDetail = isLocalDetail,
                            selectedTab = selectedTab,
                            partyServerStatus = partyServerStatus,
                            updateNotice = updateNotice,
                            onShowUpdate = { showUpdateDialog = true },
                            librarySort = librarySort,
                            librarySortMenuOpen = librarySortMenuOpen,
                            onLibrarySortMenuOpenChange = { librarySortMenuOpen = it },
                            detailSearching = detailSearching,
                            onToggleDetailSearching = { detailSearching = !detailSearching },
                            onOpenSortMenu = { if (isLocalDetail) folderMenuOpen = true else songSortMenuOpen = true },
                            songSort = songSort,
                            openBrowseMenu = openBrowseMenu,
                            downloadIdFor = downloadIdFor,
                            account = account,
                            signedIn = signedIn,
                            onOpenAccountSelector = { showAccountSelector = true },
                        )
                    },
                )

                // Drawn before the bars so their own glass reads on top of it.
                BottomFadeScrim(
                    withMiniPlayer = player.song != null,
                    // Not the wash: by the foot of the screen the page has finished
                    // easing out of it and into this, so this is what is actually
                    // under the tab bar.
                    pageColor = chromePageColor,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )

                // Whichever bar is drawing reports its height here, for the
                // guard to read at layout — never in composition, see the guard.
                val floatingBarsHeight = remember { mutableIntStateOf(0) }
                FloatingBarsTapGuard(
                    barsHeight = { floatingBarsHeight.intValue },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )

                // One tab handler, whichever bar is drawing it.
                // A tab is a fresh start: everything pushed over the old one —
                // routes and release pages alike — goes with it.
                val onTabSelected: (Int) -> Unit = { index ->
                    navigator.clear()
                    viewModel.closeMoodGenre()
                    selectedTab = index

                    // Every search tab tap resets the field, focuses it, and opens
                    // the keyboard through SearchScreen's focus request.
                    if (index == TAB_SEARCH) {
                        viewModel.onQueryChange("")
                        searchFocusRequested = true
                    }
                }

                if (glassActive) Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .widthIn(max = FLOATING_BAR_MAX_WIDTH)
                        .fillMaxWidth()
                        .onSizeChanged { floatingBarsHeight.intValue = it.height },
                ) {
                    QueueActionNoticeHost(queueNotice)
                    // Liquid glass replaces the two stacked bars with the single
                    // component they are stacked to imitate: the now playing
                    // controls dock into the tab bar rather than riding above it,
                    // and the pair folds together on scroll. See [GlassNavBar].
                    GlassNavBar(
                        tabs = tabs,
                        selectedIndex = selectedTab,
                        onTabSelected = onTabSelected,
                        scrollConnection = navBarScroll,
                        song = player.song,
                        isPlaying = player.isPlaying,
                        isLoading = playPauseBusy,
                        onPlayPause = {
                            togglePlayPause()
                        },
                        onNext = { controller?.seekToNextMediaItem() },
                        onPrevious = { controller?.seekToPrevious() },
                        onExpand = if (reducePlayerMotion) {
                            { showNowPlaying = true }
                        } else {
                            playerSheetMotion::open
                        },
                        onDismissPlayback = dismissPlayback,
                        controlsLocked = controlsLocked,
                        onBlockedControl = showHostOnlyNotice,
                        dock = activeDock,
                        pull = playerSheetMotion.takeIf { !reducePlayerMotion },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        // Capped and centred rather than run to the page's edges
                        // — see [FLOATING_BAR_MAX_WIDTH]. It sits on the Column
                        // rather than on each bar so the two are held to the same
                        // width and keep the shared left and right edge they have
                        // on a phone. Before fillMaxWidth, so the fill has
                        // already been bounded by the time it is applied.
                        .widthIn(max = FLOATING_BAR_MAX_WIDTH)
                        .fillMaxWidth()
                        .onSizeChanged { floatingBarsHeight.intValue = it.height },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    QueueActionNoticeHost(queueNotice)
                    AnimatedVisibility(
                        visible = player.song != null,
                        enter = slideInVertically(
                            initialOffsetY = { it },
                            animationSpec = spring(dampingRatio = 0.82f, stiffness = 380f),
                        ) + fadeIn(tween(200)),
                        exit = slideOutVertically(
                            targetOffsetY = { it },
                            animationSpec = spring(dampingRatio = 0.85f, stiffness = 420f),
                        ) + fadeOut(tween(200)),
                    ) {
                        player.song?.let { song ->
                            Column {
                                MiniPlayer(
                                    song = song,
                                    isPlaying = player.isPlaying,
                                    isLoading = playPauseBusy,
                                    hazeState = hazeState,
                                    onPlayPause = {
                                        togglePlayPause()
                                    },
                                    onNext = { controller?.seekToNextMediaItem() },
                                    onPrevious = { controller?.seekToPrevious() },
                                    onExpand = if (reducePlayerMotion) {
                                        { showNowPlaying = true }
                                    } else {
                                        playerSheetMotion::open
                                    },
                                    onDismissPlayback = dismissPlayback,
                                    controlsLocked = controlsLocked,
                                    onBlockedControl = showHostOnlyNotice,
                                    dock = activeDock,
                                    pull = playerSheetMotion.takeIf { !reducePlayerMotion },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Spacer(Modifier.height(8.dp))
                            }
                        }
                    }
                    FloatingBottomBar(
                        tabs = tabs,
                        selectedIndex = selectedTab,
                        hazeState = hazeState,
                        onTabSelected = onTabSelected,
                    )
                }
            }

        }

        val playerRaised = showNowPlaying && playerSong != null

        // ---- The page's still, for a menu hung over it ----
        // Taken as a ⋮ or ⋯ menu opens over a page, let go once it has played
        // its way out — see [pageBackdrop]. Read in a scope of its own, as
        // the menus are below, so a menu opening recomposes only this.
        Isolated {
            val pageMenuUp =
                (songActions != null && songMenuOrigin == null && !(menuFromPlayer && playerRaised)) ||
                    (browseActions != null && browseMenuOrigin == null && !browseRenameInSheet)
            LaunchedEffect(pageMenuUp, reduceDynamicBlur) {
                if (pageMenuUp && !reduceDynamicBlur && isGlassSupported()) {
                    pageBackdrop.capture()
                } else {
                    delay(SNAPSHOT_RELEASE_DELAY_MS)
                    pageBackdrop.release()
                }
            }
        }

        // ---- Long-press track actions ----
        // One body for every frame the track menu comes in: the panel hung off
        // a ⋮ or the player's ⋯, and the one a held row lifts into. Every
        // callback below is the same in each; only the frame differs.
        // Member names differ from the locals they read on purpose: inside the
        // object an unqualified name resolves to the member first, so a
        // same-named override would call itself.
        val songMenuEnv = object : SongMenuEnv {
            override val fromPlayer get() = menuFromPlayer
            override val fromHistory get() = menuFromHistory
            override val isSignedIn get() = signedIn
            override val openDetailId get() = detail?.browseId
            override val webDavServerUrl get() = webdavUrl
            override val playerLinksLoading get() = linksLoading
            override val pinnedTracks get() = pinnedToOriginal
            override val upgradesInFlight get() = qualityUpgradesInFlight
            override val versionsNotFound: Collection<String> get() = versions.notFound
            override val mediaController get() = controller
            override val playingVideoId get() = player.song?.videoId
            override val coroutineScope get() = scope
            override fun close() { songActions = null }
            override fun closePlayer() = dismissPlayer()
            override fun endPlayback() = dismissPlayback()
            override fun likeStatus(song: Song) = likeStatusOf(song)
            override fun toggleLike(song: Song) = toggleLikeOf(song)
            override fun queueNext(song: Song) = playNext(song)
            override fun queueLast(song: Song) = addToQueue(song)
            override fun radioFrom(song: Song) = startRadio(song)
            override fun download(song: Song) = downloadSong(song)
            override fun downloadCached(songs: List<Song>) = downloadFromCache(songs)
            override fun evictFromCache(songs: List<Song>) = removeFromCache(songs)
            override fun uploadToServer(songs: List<Song>) = uploadToWebDav(songs)
            override fun addToPlaylist(song: Song) {
                if (song.localUri != null && (song.videoId.startsWith("content://") || song.videoId.startsWith("file://"))) {
                    offlinePlaylistTarget = song
                } else {
                    viewModel.loadPlaylists()
                    playlistTarget = song
                }
            }
            override fun openLyricsOffset() { showLyricsOffset = true }
            override fun versionRow(song: Song) = versionSwitcher.menuRow(song) { songActions = null }
        }
        val songMenuBody: @Composable (Song) -> Unit = { song ->
            SongMenuBody(heldSong = song, env = songMenuEnv, viewModel = viewModel)
        }
        // ---- Now Playing ----
        if (playerRaised) {
            NowPlayingSheetHost(
                motion = playerSheetMotion,
                dock = playerDock,
                activeDock = activeDock,
                reduceMotion = reducePlayerMotion,
                reduceDynamicBlur = reduceDynamicBlur,
                playerBackdrop = playerBackdrop,
                livePlayerBackdrop = livePlayerBackdrop,
                liveCanvas = { playerLiveCanvas },
                onDismissRequest = { showNowPlaying = false },
                menuItem = { songActions?.takeIf { songMenuOrigin == null && menuFromPlayer } },
                menuAnchor = { songMenuAnchor },
                onMenuDismiss = { songActions = null },
                menuBody = songMenuBody,
                player = { nowPlaying(playerSong) },
            )
        }

        // ---- Replay stories ----
        // Mounted out here rather than inside the pane above, because a story
        // covers the window: on a tablet the page is only the left half of the
        // row, and a story laid out inside it would run alongside the player
        // instead of over it.
        replayStory?.let { start ->
            replay.summary?.takeUnless { it.isEmpty }?.let { summary ->
                ReplayStories(
                    summary = summary,
                    start = start,
                    onClose = { replayStory = null },
                    onShare = { card ->
                        replaySharePage = card
                        showReplayShare = true
                    },
                    paused = showReplayShare,
                )
            }
        }

        // ---- Share the Replay ----
        if (showReplayShare) {
            replay.summary?.takeUnless { it.isEmpty }?.let { summary ->
                ModalBottomSheet(
                    onDismissRequest = { showReplayShare = false },
                    // Straight to full height. The sheet is a picture and two
                    // buttons, and half-open it showed the picture with both
                    // buttons below the fold — a sheet whose only two controls
                    // need a drag to reach.
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                    containerColor = MaterialTheme.colorScheme.background,
                ) {
                    ReplayShareSheet(
                        summary = summary,
                        holder = account?.name.orEmpty(),
                        memberSince = replay.memberSince,
                        page = replaySharePage,
                        onDismiss = { showReplayShare = false },
                    )
                }
            }
        }

        // ---- Track menu ----
        // In a recompose scope of its own: whether a menu is up is read in
        // here, and read in the app's own body it recomposed all of the app
        // on the frame the menu starts to grow — the stutter at the start of
        // its spring, blur or no blur.
        Isolated {
            // Drawn over everything, tab bar and mini player included, and kept
            // composed through its own exit — so it is not gated on songActions.
            HeldContextMenu(
                item = songActions,
                held = songMenuOrigin,
                hazeState = hazeState,
                onDismiss = { songActions = null },
                // Tapping the lifted track opens its album, as Apple Music's does.
                onPreviewClick = { song ->
                    song.albumId?.let { id ->
                        songActions = null
                        viewModel.openDetail(id, song.albumName ?: song.title, song.artist, song.thumbnailUrl, BrowseType.ALBUM)
                    }
                },
            ) { song ->
                songMenuBody(song)
            }
            // A tap on a ⋮ — or on the player's ⋯ while the player is part of the
            // page, as on a tablet — hangs the same menu off that button. While the
            // player is a sheet the sheet draws its own (above), so it can sit
            // over the player and blur it.
            // A player menu goes with the player: left up, it would carry on here,
            // over the page.
            LaunchedEffect(playerRaised) {
                if (!playerRaised && menuFromPlayer) songActions = null
            }
            IosMenuPopover(
                item = songActions?.takeIf { songMenuOrigin == null && !(menuFromPlayer && playerRaised) },
                anchor = songMenuAnchor,
                onDismiss = { songActions = null },
                backdrop = pageBackdrop,
                hazeState = hazeState,
            ) { song ->
                songMenuBody(song)
            }
            // A closed menu forgets where it was held and what it hung off, so
            // whatever opens it next starts from nothing.
            LaunchedEffect(songActions == null) {
                if (songActions == null) {
                    songMenuOrigin = null
                    songMenuAnchor = null
                    menuFromHistory = false
                }
            }
        }

        // ---- Add to playlist / new playlist ----
        // One sheet for both, because they are one decision: the list of
        // playlists with a way to make another. `creatingPlaylist` opens it
        // straight onto the form, which is what the Library tile means.
        if (playlistTarget != null || creatingPlaylist) {
            val target = playlistTarget
            val dismiss = {
                playlistTarget = null
                creatingPlaylist = false
            }
            PlaylistPickerSheet(
                playlists = playlists,
                loading = playlistsLoading,
                song = target,
                startCreating = target == null,
                onDismiss = dismiss,
                onAdd = { picked ->
                    target?.let { song ->
                        viewModel.addToPlaylists(picked, song) { added, alreadyThere, failed ->
                            // One line for the whole batch, saying the outcome
                            // that matters most: what went in, else that it was
                            // all there already, else that it didn't work.
                            showQueueNotice(
                                when {
                                    added > 1 -> context.resources.getQuantityString(
                                        R.plurals.added_to_playlists_notice, added, added,
                                    )
                                    added == 1 -> context.getString(R.string.song_added_to_playlist)
                                    alreadyThere > 0 && failed == 0 ->
                                        context.getString(R.string.song_already_in_playlist)
                                    else -> context.getString(R.string.failed)
                                },
                            )
                        }
                    }
                },
                onCreate = { title, privacy ->
                    viewModel.createPlaylist(title, privacy, target)
                },
            )
        }

        // ---- Add to a device playlist / new device playlist ----
        // The sheet above over the offline player's own playlists, for a track
        // that is a file on this device. Nothing here waits on a network, so
        // there is no loading state and the outcome is known on the tap.
        if (offlinePlaylistTarget != null || creatingOfflinePlaylist) {
            val target = offlinePlaylistTarget
            val dismiss = {
                offlinePlaylistTarget = null
                creatingOfflinePlaylist = false
            }
            val choices = remember(offlinePlaylists) {
                offlinePlaylists.map { playlist ->
                    UserPlaylist(
                        playlistId = playlist.id,
                        title = playlist.title,
                        subtitle = context.resources.getQuantityString(
                            R.plurals.song_count_plural,
                            playlist.songs.size,
                            playlist.songs.size,
                        ),
                        thumbnailUrl = playlist.songs.firstNotNullOfOrNull { it.thumbnailUrl }
                            .takeUnless { playlist.isLiked },
                    )
                }
            }
            PlaylistPickerSheet(
                playlists = choices,
                loading = false,
                song = target,
                startCreating = target == null,
                offline = true,
                onDismiss = dismiss,
                onAdd = { picked ->
                    target?.let { song ->
                        val result = OfflinePlaylists.add(picked.mapTo(HashSet()) { it.playlistId }, listOf(song))
                        showQueueNotice(
                            when {
                                result.added > 1 -> context.resources.getQuantityString(
                                    R.plurals.added_to_playlists_notice, result.added, result.added,
                                )
                                result.added == 1 -> context.getString(R.string.song_added_to_playlist)
                                result.alreadyThere > 0 -> context.getString(R.string.song_already_in_playlist)
                                else -> context.getString(R.string.failed)
                            },
                        )
                    }
                },
                onCreate = { title, _ ->
                    OfflinePlaylists.create(title, listOfNotNull(target))
                    if (target != null) showQueueNotice(context.getString(R.string.song_added_to_playlist))
                },
            )
        }

        IosModalContainer(
            visible = showSpotifyImportDialog,
            onDismiss = { showSpotifyImportDialog = false },
        ) {
            SpotifyImportAlert(
                hazeState = hazeState,
                signedIn = signedIn,
                onImported = { title, privacy, songs ->
                    viewModel.createPlaylistWithVideoIds(
                        title,
                        privacy,
                        songs.map { it.videoId },
                        songs,
                    ) { browseId, pTitle, savedLocally ->
                        showQueueNotice(
                            context.getString(
                                if (savedLocally && signedIn) R.string.spotify_import_local_fallback
                                else R.string.spotify_import_done,
                                pTitle,
                            ),
                        )
                        browseId?.let { id ->
                            viewModel.openDetail(id, pTitle, "${songs.size} songs", songs.firstOrNull()?.thumbnailUrl)
                        }
                    }
                },
                onDismiss = { showSpotifyImportDialog = false },
            )
        }

        // ---- Album / playlist actions ----
        // Opened by holding a card on any tab, or from the release page's own
        // overflow. What a track's long-press menu is to one song, this is to
        // the whole release — the queue rows above all.
        BrowseMenuHost(
            state = browseMenu,
            viewModel = viewModel,
            actions = BrowseMenuActions(
                withSongs = withBrowseSongs,
                playNext = playSongsNext,
                addToQueue = addSongsToQueue,
                play = play,
                startDownload = startDownload,
                reorder = { p ->
                    // Rearranged on the playlist's own page: open it first
                    // when the menu was raised from anywhere else.
                    if (detail?.browseId != p.browseId) {
                        viewModel.openDetail(
                            browseId = p.browseId,
                            title = p.title,
                            subtitle = p.subtitle,
                            thumbnailUrl = p.thumbnailUrl,
                            type = BrowseType.PLAYLIST,
                        )
                    }
                    detailSearching = false
                    reorderTarget = p
                    reorderEntries = UiState.Loading
                    reorderSaving = false
                    viewModel.loadPlaylistEntries(p) { result ->
                        if (reorderTarget != p) return@loadPlaylistEntries
                        reorderEntries = result.fold(
                            onSuccess = { UiState.Success(it) },
                            onFailure = { UiState.Error(context.getString(R.string.failed)) },
                        )
                    }
                },
            ),
            signedIn = signedIn,
            playlists = playlists,
            openDetailId = detail?.browseId,
            hazeState = hazeState,
            pageBackdrop = pageBackdrop,
            scope = scope,
        )

        // ---- Welcome (once; under the sign-in WebView it can open) ----
        // There from the first frame, so no way in; on the way out it drops
        // like a dismissed sheet onto the app it was standing in front of.
        AnimatedVisibility(
            visibleState = onboardingShown,
            enter = EnterTransition.None,
            exit = slideOutVertically(
                tween<IntOffset>(durationMillis = 560, easing = OnboardingDismissEasing),
            ) { it },
        ) {
            OnboardingScreen(
                signedIn = signedIn,
                initialName = remember {
                    ListenTogether.nickname().ifBlank { account?.name.orEmpty() }
                },
                onNameChosen = ListenTogether::setNickname,
                onSignIn = { webSession = WebSessionMode.SIGN_IN },
                onFinish = AppSettings::setOnboardingComplete,
            )
        }

        // ---- Google sign-in (full screen WebView) ----
        webSession?.let { mode ->
            BackHandler { webSession = null }
            // Raised by "Use this channel", read by the browser as "take the
            // session from the page as it now stands". A counter rather than a
            // flag so a second tap, after a failed first one, is still a new
            // request rather than a value that was already true.
            var captureRequest by remember(mode) { mutableIntStateOf(0) }
            var captureFailed by remember(mode) { mutableStateOf(false) }
            var pageReady by remember(mode) { mutableStateOf(false) }
            var confirmingProfile by remember(mode) { mutableStateOf(false) }
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = { webSession = null }) {
                            Icon(
                                Icons.Rounded.Close,
                                contentDescription = stringResource(R.string.close),
                                tint = MaterialTheme.colorScheme.onBackground,
                            )
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = when (mode) {
                                    WebSessionMode.SIGN_IN -> stringResource(R.string.sign_in_youtube_music)
                                    WebSessionMode.SWITCH_CHANNEL -> stringResource(R.string.choose_profile)
                                },
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onBackground,
                            )
                            if (pageReady) {
                                Text(
                                    text = if (captureFailed) {
                                         stringResource(R.string.profile_unavailable)
                                     } else {
                                         stringResource(R.string.switch_profile_hint)
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                )
                            }
                        }
                        if (pageReady) {
                            TextButton(onClick = {
                                captureFailed = false
                                confirmingProfile = true
                                captureRequest++
                            }, enabled = !confirmingProfile) {
                                Text(
                                    if (confirmingProfile) stringResource(R.string.checking)
                                    else stringResource(R.string.use_this_profile),
                                )
                            }
                        }
                    }
                    YtMusicLoginScreen(
                        mode = mode,
                        initialCookie = if (mode == WebSessionMode.SWITCH_CHANNEL) {
                            googleAccounts.firstOrNull { it.accountId == activeAccountId }?.cookie
                        } else null,
                        captureRequest = captureRequest,
                        onPageReady = { pageReady = it },
                        onCaptureUnavailable = {
                            confirmingProfile = false
                            captureFailed = true
                        },
                        onCaptured = { session ->
                            viewModel.onWebSession(session, mode) { accepted ->
                                confirmingProfile = false
                                if (accepted) {
                                    webSession = null
                                    if (mode == WebSessionMode.SIGN_IN) {
                                        navigator.clear()
                                        selectedTab = TAB_LIBRARY
                                    }
                                } else {
                                    captureFailed = true
                                }
                            }
                        },
                    )
                }
            }
        }

        // ---- Diwali greeting ----
        AnimatedVisibility(
            visible = showDiwali,
            enter = fadeIn(tween(400)),
            exit = fadeOut(tween(300)),
        ) {
            val dismissDiwali = {
                showDiwali = false
                AppSettings.setDiwaliGreetingShown()
            }
            BackHandler(onBack = dismissDiwali)
            DiwaliGreetingScreen(onDismiss = dismissDiwali)
        }

        // ---- Update available (once per launch; held until the welcome and Diwali greeting are done) ----
        IosModalContainer(
            visible = showUpdateDialog && onboardingComplete && !showDiwali && updateNotice != null,
            onDismiss = { showUpdateDialog = false },
        ) {
            updateNotice?.let { update ->
                UpdateAvailableDialog(
                    version = update.version,
                    notes = update.notes,
                    hazeState = hazeState,
                    // A download in progress keeps running behind the closed
                    // sheet — only the sheet itself goes away. The top bar's
                    // update icon reopens it onto whatever state it reached.
                    onDismiss = { showUpdateDialog = false },
                    onDownload = {
                        if (update.apkUrl != null) {
                            scope.launch { AppUpdateChecker.downloadApk(context) }
                        } else {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(update.releaseUrl)))
                            showUpdateDialog = false
                        }
                    },
                    onCancelDownload = {
                        AppUpdateChecker.cancelDownload()
                    },
                    onInstall = {
                        val ready = AppUpdateChecker.download.value as? AppUpdateChecker.DownloadState.Ready
                        ready?.let { AppUpdateChecker.installApk(context, it.file) }
                    },
                    onOpenReleasePage = {
                        AppUpdateChecker.resetDownload()
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(update.releaseUrl)))
                        showUpdateDialog = false
                    },
                    animatedScope = this,
                )
            }
        }

        IosModalContainer(
            visible = showLyricsSources,
            onDismiss = { showLyricsSources = false },
        ) {
            LyricsSourcesDialog(
                hazeState = hazeState,
                onDismiss = { showLyricsSources = false },
                animatedScope = this,
            )
        }

        IosModalContainer(
            visible = songSortMenuOpen,
            onDismiss = { songSortMenuOpen = false },
        ) {
            FrostedSortMenu(
                hazeState = hazeState,
                selected = songSort,
                onSelect = { option ->
                    detail?.browseId?.let { AppSettings.setDetailSongSort(it, option) }
                    songSortMenuOpen = false
                },
                // Flipping the date direction deliberately leaves the menu up:
                // closing it here would cut the arrow's rotation animation off
                // before it played, and the open menu lets the direction flip
                // read against the list reordering behind the frost.
                onFlipDateDirection = {
                    detail?.browseId?.let { browseId ->
                        val next = when (songSort) {
                            SongSort.DATE_ADDED_DESC -> SongSort.DATE_ADDED_ASC
                            SongSort.DATE_ADDED_ASC -> SongSort.DATE_ADDED_DESC
                            else -> SongSort.DATE_ADDED_DESC
                        }
                        AppSettings.setDetailSongSort(browseId, next)
                    }
                },
                onDismiss = { songSortMenuOpen = false },
                animatedScope = this,
            )
        }

        val menuFolder = detail?.browseId?.takeIf { it.isDeviceFolder() }
        IosModalContainer(
            visible = folderMenuOpen && menuFolder != null,
            onDismiss = { folderMenuOpen = false },
        ) {
            val downloadsFolder = menuFolder == "local:downloads"
            val viewType = folderViewTypeOf(menuFolder ?: "")
            FrostedFolderMenu(
                hazeState = hazeState,
                sort = folderSortOf(menuFolder ?: ""),
                viewType = viewType,
                onSort = { option ->
                    if (downloadsFolder) AppSettings.setDownloadedMusicSort(option)
                    else AppSettings.setLocalMusicSort(option)
                    folderMenuOpen = false
                },
                onToggleViewType = {
                    val next = if (viewType == LibraryViewType.GRID) LibraryViewType.LIST else LibraryViewType.GRID
                    if (downloadsFolder) AppSettings.setDownloadedMusicViewType(next)
                    else AppSettings.setLocalMusicViewType(next)
                    folderMenuOpen = false
                },
                onDismiss = { folderMenuOpen = false },
                animatedScope = this,
            )
        }

        IosModalContainer(
            visible = showAccountSelector,
            onDismiss = { showAccountSelector = false },
        ) {
            AccountProfileSelector(
                accounts = googleAccounts,
                activeAccountId = activeAccountId,
                activeProfileId = activeProfileId,
                hazeState = hazeState,
                animatedScope = this,
                onSelect = { selected, profile -> viewModel.selectProfile(selected.accountId, profile.profileId) },
                onAddAccount = {
                    showAccountSelector = false
                    webSession = WebSessionMode.SIGN_IN
                },
                onRemoveAccount = { selected ->
                    viewModel.removeAccount(selected.accountId)
                    showAccountSelector = false
                },
                onOpenSettings = {
                    showAccountSelector = false
                    navigator.push(AppRoute.Settings)
                },
                onDismiss = { showAccountSelector = false },
            )
        }

        IosModalContainer(
            visible = showAppLanguage,
            onDismiss = { showAppLanguage = false },
        ) {
            AppLanguageDialog(
                hazeState = hazeState,
                onDismiss = { showAppLanguage = false },
                animatedScope = this,
            )
        }

        IosModalContainer(
            visible = showTranslationLanguage,
            onDismiss = { showTranslationLanguage = false },
        ) {
            TranslationLanguageDialog(
                hazeState = hazeState,
                onDismiss = { showTranslationLanguage = false },
                animatedScope = this,
            )
        }

        IosModalContainer(
            visible = showListenBrainzLogin,
            onDismiss = { showListenBrainzLogin = false },
        ) {
            var tokenInput by remember { mutableStateOf(listenBrainzToken) }
            ListenBrainzTokenAlert(
                hazeState = hazeState,
                tokenInput = tokenInput,
                onTokenInputChange = { tokenInput = it },
                onSave = {
                    AppSettings.setListenBrainzToken(tokenInput.trim())
                    showListenBrainzLogin = false
                },
                onDismiss = { showListenBrainzLogin = false },
                animatedScope = this,
            )
        }

        IosModalContainer(
            visible = showLastfmLogin,
            onDismiss = { showLastfmLogin = false },
        ) {
            var usernameInput by remember { mutableStateOf("") }
            var passwordInput by remember { mutableStateOf("") }
            var lastfmError by remember { mutableStateOf<String?>(null) }
            var lastfmLoading by remember { mutableStateOf(false) }
            LastfmLoginAlert(
                hazeState = hazeState,
                usernameInput = usernameInput,
                onUsernameInputChange = { usernameInput = it },
                passwordInput = passwordInput,
                onPasswordInputChange = { passwordInput = it },
                error = lastfmError,
                loading = lastfmLoading,
                animatedScope = this,
                onSignIn = {
                    lastfmLoading = true
                    lastfmError = null
                    scope.launch {
                        try {
                            LastFM.initialize(
                                apiKey = AppSettings.lastfmApiKey.value,
                                secret = AppSettings.lastfmSecret.value,
                            )
                            LastFM.getMobileSession(usernameInput.trim(), passwordInput)
                                .onSuccess { auth ->
                                    AppSettings.setLastfmSessionKey(auth.session.key)
                                    AppSettings.setLastfmUsername(auth.session.name)
                                    AppSettings.setLastfmEnabled(true)
                                    showLastfmLogin = false
                                }
                                .onFailure { e ->
                                    lastfmError = e.message ?: context.getString(R.string.login_failed)
                                }
                        } catch (e: Exception) {
                            lastfmError = e.message ?: context.getString(R.string.login_failed)
                        } finally {
                            lastfmLoading = false
                        }
                    }
                },
                onDismiss = { if (!lastfmLoading) showLastfmLogin = false },
            )
        }

        IosModalContainer(
            visible = showWebDavEditor,
            onDismiss = { showWebDavEditor = false },
        ) {
            ServerEditorHost(
                hazeState = hazeState,
                title = stringResource(R.string.webdav),
                description = stringResource(R.string.webdav_description),
                fields = listOf(
                    FieldConfig(
                        initial = AppSettings.webdavUrl.value,
                        placeholder = stringResource(R.string.webdav_server_url_hint),
                        keyboardType = KeyboardType.Uri,
                    ),
                    FieldConfig(
                        initial = AppSettings.webdavUsername.value,
                        placeholder = stringResource(R.string.username),
                    ),
                    FieldConfig(
                        initial = AppSettings.webdavPassword.value,
                        placeholder = stringResource(R.string.password),
                        keyboardType = KeyboardType.Password,
                        isPassword = true,
                    ),
                ),
                canSubmit = { it[0].isNotBlank() },
                testFailedRes = R.string.webdav_test_failed,
                onTest = { (url, username, password) ->
                    com.music.bitchord.data.webdav.WebDavRepository.testConnection(
                        url.trim(),
                        username.trim(),
                        password,
                    )
                },
                onSave = { (url, username, password) ->
                    AppSettings.setWebDavUrl(url.trim())
                    AppSettings.setWebDavUsername(username.trim())
                    AppSettings.setWebDavPassword(password)
                    showWebDavEditor = false
                },
                onDismiss = { showWebDavEditor = false },
                animatedScope = this,
            )
        }

        if (showSmbEditor) {
            BackHandler { showSmbEditor = false }
            ServerEditorHost(
                hazeState = hazeState,
                title = stringResource(R.string.smb),
                description = stringResource(R.string.smb_description),
                fields = listOf(
                    FieldConfig(
                        initial = AppSettings.smbHost.value,
                        placeholder = stringResource(R.string.smb_server_hint),
                        keyboardType = KeyboardType.Uri,
                    ),
                    FieldConfig(
                        initial = AppSettings.smbShare.value,
                        placeholder = stringResource(R.string.smb_share_hint),
                    ),
                    FieldConfig(
                        initial = AppSettings.smbBasePath.value,
                        placeholder = stringResource(R.string.smb_folder_hint),
                    ),
                    FieldConfig(
                        initial = AppSettings.smbUsername.value,
                        placeholder = stringResource(R.string.username),
                    ),
                    FieldConfig(
                        initial = AppSettings.smbPassword.value,
                        placeholder = stringResource(R.string.password),
                        keyboardType = KeyboardType.Password,
                        isPassword = true,
                    ),
                ),
                canSubmit = { it[0].isNotBlank() && it[1].isNotBlank() },
                testFailedRes = R.string.smb_test_failed,
                onTest = { (host, share, folder, username, password) ->
                    com.music.bitchord.data.smb.SmbRepository.testConnection(
                        host.trim(),
                        share.trim(),
                        folder.trim(),
                        username.trim(),
                        password,
                    )
                },
                onSave = { (host, share, folder, username, password) ->
                    AppSettings.setSmbHost(host.trim())
                    AppSettings.setSmbShare(share.trim())
                    AppSettings.setSmbBasePath(folder.trim())
                    AppSettings.setSmbUsername(username.trim())
                    AppSettings.setSmbPassword(password)
                    showSmbEditor = false
                },
                onDismiss = { showSmbEditor = false },
            )
        }

        WebDavConflictHost(hazeState)

        // ---- Discord sign-in (full screen WebView) ----
        if (showDiscordLogin) {
            BackHandler { showDiscordLogin = false }
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = { showDiscordLogin = false }) {
                            Icon(
                                Icons.Rounded.Close,
                                contentDescription = stringResource(R.string.close),
                                tint = MaterialTheme.colorScheme.onBackground,
                            )
                        }
                        Text(
                            "Sign in to Discord",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                    DiscordLoginScreen(
                        onTokenCaptured = { token ->
                            AppSettings.setDiscordToken(token)
                            showDiscordLogin = false
                        },
                    )
                }
            }
        }

        // ---- Spotify sign-in (full screen WebView) ----
        // Out here with Discord's rather than on the Spotify page itself: see
        // SpotifyLoginScreen for what the blurred content tree does to a WebView.
        if (showSpotifyLogin) {
            BackHandler { showSpotifyLogin = false }
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = { showSpotifyLogin = false }) {
                            Icon(
                                Icons.Rounded.Close,
                                contentDescription = stringResource(R.string.close),
                                tint = MaterialTheme.colorScheme.onBackground,
                            )
                        }
                        Text(
                            stringResource(R.string.spotify_sign_in),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                    SpotifyLoginScreen(
                        modifier = Modifier.navigationBarsPadding(),
                        onConnected = { token ->
                            AppSettings.setSpotifySpdcToken(token)
                            showSpotifyLogin = false
                        },
                    )
                }
            }
        }

        if (showSpotifyCanvasAuth) {
            BackHandler { showSpotifyCanvasAuth = false }
            SpotifyCanvasAuthScreen(
                onNavigateUp = { showSpotifyCanvasAuth = false }
            )
        }

        AppAlertHost(
            state = alerts,
            hazeState = hazeState,
            scope = scope,
            downloadNoticeInOwnWindow = playerRaised || songActions != null || browseActions != null,
        )

    }
}

/**
 * The addon exclusions already explained this session, as `configId:reason` —
 * see [SourceRegistry.addonsLeftOutOfDownloads]. Process-wide rather than
 * remembered by the composition, so turning the phone doesn't say it again.
 */
private val explainedDownloadExclusions = java.util.Collections.synchronizedSet(mutableSetOf<String>())

/** UIKit's sheet curve: leaves at once, settles long — a sheet let go of, not pushed. */
private val OnboardingDismissEasing = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)

private fun tween(durationMillis: Int) =
    androidx.compose.animation.core.tween<Float>(durationMillis)

@Composable
internal fun LibrarySort.localizedLabel(): String = when (this) {
    LibrarySort.DEFAULT -> stringResource(R.string.sort_default)
    LibrarySort.TITLE_ASC -> stringResource(R.string.sort_title_ascending)
    LibrarySort.TITLE_DESC -> stringResource(R.string.sort_title_descending)
}

/**
 * The track-list sort menu, styled after the account switcher: a full-screen
 * scrim to catch the dismissal tap, and the options on a frosted panel that
 * blurs the page behind it. Composed here in the main hierarchy rather than
 * as a popup window — which is exactly what lets the haze see the content it
 * is blurring.
 */
@Composable
private fun FrostedSortMenu(
    hazeState: HazeState,
    selected: SongSort,
    onSelect: (SongSort) -> Unit,
    onFlipDateDirection: () -> Unit,
    onDismiss: () -> Unit,
    animatedScope: AnimatedVisibilityScope? = null,
) {
    FrostedMenuPanel(hazeState = hazeState, onDismiss = onDismiss, animatedScope = animatedScope) {
        // Date added is one row, Spotify-style: the arrow on it shows
        // the direction — up for newest first, down for oldest — and
        // tapping flips it, the rotation animating the flip. Up is
        // also where a fresh activation lands, newest first being the
        // point of the feature.
        val dateActive = selected == SongSort.DATE_ADDED_ASC ||
            selected == SongSort.DATE_ADDED_DESC
        val arrowRotation by animateFloatAsState(
            targetValue = if (selected == SongSort.DATE_ADDED_ASC) 180f else 0f,
            animationSpec = tween(durationMillis = 200),
            label = "dateAddedArrow",
        )
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(role = Role.Button) { onFlipDateDirection() }
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.sort_date_added_toggle),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            if (dateActive) {
                Icon(
                    Icons.Rounded.ArrowUpward,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.rotate(arrowRotation),
                )
            }
        }
        SongSort.entries
            .filter { it != SongSort.DATE_ADDED_ASC && it != SongSort.DATE_ADDED_DESC }
            .forEach { option ->
                FrostedMenuRow(
                    label = option.localizedLabel(),
                    checked = option == selected,
                    onClick = { onSelect(option) },
                )
            }
    }
}

/**
 * A device folder's menu, off the same button: list or grid for its tabs, then
 * its sort orders — the Files app's arrangement, view first and order under it.
 */
@Composable
private fun FrostedFolderMenu(
    hazeState: HazeState,
    sort: LocalMusicSort,
    viewType: LibraryViewType,
    onSort: (LocalMusicSort) -> Unit,
    onToggleViewType: () -> Unit,
    onDismiss: () -> Unit,
    animatedScope: AnimatedVisibilityScope? = null,
) {
    FrostedMenuPanel(hazeState = hazeState, onDismiss = onDismiss, animatedScope = animatedScope) {
        val grid = viewType == LibraryViewType.GRID
        FrostedMenuRow(
            label = stringResource(if (grid) R.string.switch_to_list_view else R.string.switch_to_grid_view),
            checked = false,
            onClick = onToggleViewType,
            icon = if (grid) BitChordIcons.ListView else BitChordIcons.GridView,
        )
        HorizontalDivider(
            modifier = Modifier.padding(vertical = 4.dp),
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        )
        LocalMusicSort.entries.forEach { option ->
            FrostedMenuRow(
                label = option.localizedLabel(),
                checked = option == sort,
                onClick = { onSort(option) },
            )
        }
    }
}

/** The scrim and frosted panel both top-bar menus are drawn on. */
@OptIn(ExperimentalHazeMaterialsApi::class, ExperimentalAnimationApi::class)
@Composable
private fun FrostedMenuPanel(
    hazeState: HazeState,
    onDismiss: () -> Unit,
    animatedScope: AnimatedVisibilityScope? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    val reduceAnimation by AppSettings.reduceAnimation.collectAsStateWithLifecycle()
    val shape = MaterialTheme.shapes.extraLarge
    val effectiveScope = animatedScope ?: LocalIosModalScope.current
    val animModifier = if (effectiveScope != null) {
        with(effectiveScope) {
            Modifier.animateEnterExit(
                enter = IosMotion.modalCardEnter(reduceAnimation),
                exit = IosMotion.modalCardExit(reduceAnimation),
            )
        }
    } else Modifier

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = .48f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.TopEnd,
    ) {
        Surface(
            color = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = shape,
            modifier = Modifier
                .padding(top = 56.dp, end = 20.dp)
                .width(IntrinsicSize.Max)
                .then(animModifier)
                .clip(shape)
                .then(
                    if (reduceDynamicBlur) {
                        Modifier.background(MaterialTheme.colorScheme.surface)
                    } else {
                        Modifier.optimizedHazeEffect(
                            state = hazeState,
                            style = HazeMaterials.thin(MaterialTheme.colorScheme.surface),
                        )
                    },
                )
                .clickable(onClick = {}),
        ) {
            Column(Modifier.padding(vertical = 8.dp), content = content)
        }
    }
}

/** One option on a frosted menu: its label, and a tick or [icon] at the end. */
@Composable
private fun FrostedMenuRow(
    label: String,
    checked: Boolean,
    onClick: () -> Unit,
    icon: ImageVector? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        if (checked) {
            Icon(
                Icons.Rounded.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        } else if (icon != null) {
            Spacer(Modifier.width(16.dp))
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun SongSort.localizedLabel(): String = when (this) {
    SongSort.DEFAULT -> stringResource(R.string.sort_default)
    SongSort.TITLE_ASC -> stringResource(R.string.sort_title_ascending)
    SongSort.TITLE_DESC -> stringResource(R.string.sort_title_descending)
    SongSort.DATE_ADDED_ASC -> stringResource(R.string.sort_date_added_oldest)
    SongSort.DATE_ADDED_DESC -> stringResource(R.string.sort_date_added)
}

/**
 * Whether this page id is one of the device folders — `local:downloads`,
 * `local:all` and the rest, the pages with Songs / Artists / Albums tabs.
 *
 * Asked rather than `startsWith("local:")` because that prefix covers unlike
 * pages: a folder, one downloaded playlist, and an album or artist grouped out
 * of a folder — the last two plain track listings that want the same chrome
 * every other release page gets. See [Downloads.PLAYLIST_PREFIX] and
 * [LocalGroups.PREFIX] for why they share a namespace at all.
 */
internal fun String?.isDeviceFolder(): Boolean =
    this != null && startsWith("local:") &&
        !startsWith(Downloads.PLAYLIST_PREFIX) && !startsWith(LocalGroups.PREFIX) &&
        !startsWith(OfflinePlaylists.PREFIX)

/**
 * Whether [videoId] is the track playing, and is known to be playing YouTube's
 * own copy — which decides whether the player menu leads with "Revert to
 * original" or with "Upgrade quality".
 *
 * See [QualityUpgrade.isKnownToBePlayingYouTubesOwn] for why "known" is doing
 * real work here: an unresolved track playing off the disk cache is judged by
 * the origin its cache entry was tagged with, and keeps the revert on offer
 * only when the entry has none.
 */
internal fun playingYouTubesOwn(videoId: String, controller: MediaController?): Boolean {
    val item = controller?.currentMediaItem?.takeIf { it.mediaId == videoId } ?: return false
    val uri = item.localConfiguration?.uri
    return QualityUpgrade.isKnownToBePlayingYouTubesOwn(
        videoId,
        uri?.let(QualityUpgrade::cacheTag),
        uri?.let(AudioCache::originOf),
    )
}

/** `M:SS`/`H:MM:SS`, the same shape [String?.durationMillis] parses back. */
private fun formatDurationText(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(Locale.ROOT, minutes, seconds)
}

/**
 * How far short of the end a seek is allowed to land.
 *
 * Seeking to the final millisecond is indistinguishable from the track running
 * out, so it starts the next song — which is not what anyone dragging to the end
 * of the bar, or tapping the last line of a lyric, is asking for. A second back
 * from the end plays the outro instead.
 */
private const val SEEK_END_GUARD_MS = 1_000L

/**
 * How far a detail page scrolls before its title moves up into the bar.
 *
 * Roughly the height of the sleeve and the credit stacked above the Play pair,
 * so the two titles hand over as the header one leaves rather than sitting on
 * screen together. The bar cross-fades over 220ms, which absorbs the difference
 * between that estimate and a particular page's real header.
 */
private val DETAIL_TITLE_DROP = 320.dp

/**
 * How fast, a second, a mini-player pull has to be moving when the finger
 * lifts to decide open or closed on its own, wherever it got to — see
 * [MiniPlayerPull]. A flick is a whole gesture; only a slow pull is judged by
 * the distance it covered.
 */
private val PLAYER_PULL_FLING_VELOCITY = 400.dp

/**
 * [content] in a recompose scope of its own, so state read inside it
 * recomposes it alone rather than everything it is written in.
 */
@Composable
private fun Isolated(content: @Composable () -> Unit) = content()

/** Longer than the ⋯ menu's exit, so its panel keeps its frost on the way out. */
internal const val SNAPSHOT_RELEASE_DELAY_MS = 400L

internal const val TAB_HOME = 0
internal const val TAB_EXPLORE = 1
internal const val TAB_LIBRARY = 2
internal const val TAB_SEARCH = 3
