package com.music.bitchord.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.music.bitchord.ui.utils.RestingSheetSettle
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.DialogWindowProvider
import com.music.bitchord.SNAPSHOT_RELEASE_DELAY_MS
import com.music.bitchord.data.model.Song
import com.music.bitchord.ui.components.DacVolumeKeys
import com.music.bitchord.ui.components.IosMenuPopover
import com.music.bitchord.ui.components.backdrop.backdrops.LayerBackdrop
import com.music.bitchord.ui.components.backdrop.backdrops.SnapshotBackdrop
import com.music.bitchord.ui.components.backdrop.backdrops.layerBackdrop
import com.music.bitchord.ui.components.backdrop.backdrops.snapshotBackdropSource
import com.music.bitchord.ui.player.LocalPlayerDock
import com.music.bitchord.ui.player.PlayerDock
import com.music.bitchord.ui.player.PlayerSheetMotion
import com.music.bitchord.ui.utils.guardSheetFromContentTouches
import kotlinx.coroutines.delay

/**
 * The full-screen Now Playing take-over: M3's [ModalBottomSheet] as the window,
 * but with every open and close carried out by [motion] — a drag released low,
 * a back press, a tap on the scrim — so the artwork can fly between the sheet
 * and the mini player along [dock]. With [reduceMotion] it is M3's own sheet:
 * its slide, its scrim, its back handling.
 *
 * State the app holds and only the sheet reads — the player's ⋯ menu, whether
 * the canvas is live — comes in as lambdas, so it is read in the sheet's own
 * scope and a change to it never recomposes the app around it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingSheetHost(
    motion: PlayerSheetMotion,
    dock: PlayerDock,
    /** The dock the player's content may fly along; null under reduced motion. */
    activeDock: PlayerDock?,
    reduceMotion: Boolean,
    reduceDynamicBlur: Boolean,
    playerBackdrop: SnapshotBackdrop,
    livePlayerBackdrop: LayerBackdrop,
    /** Whether an animated canvas is playing, which the ⋯ menu then samples live. */
    liveCanvas: () -> Boolean,
    onDismissRequest: () -> Unit,
    /** The track the player's own ⋯ menu is open on, or null. */
    menuItem: () -> Song?,
    menuAnchor: () -> Rect?,
    onMenuDismiss: () -> Unit,
    menuBody: @Composable (Song) -> Unit,
    player: @Composable () -> Unit,
) {
    // What rememberModalBottomSheetState builds, but able to start open,
    // and with every hide routed through [motion] — a drag released low,
    // a back press, a tap on the scrim. Refused here and carried out there,
    // on a curve slow enough to watch the artwork fly home on, rather than
    // on the sheet's own.
    val sheetDensity = LocalDensity.current
    // A scroll ending inside the player has the sheet settle onto the anchor
    // it is already on, deaf to taps while it does: called off here, by
    // cancelling the coroutine that asked. See [RestingSheetSettle].
    val restingSettle = remember { RestingSheetSettle() }
    val sheetAsked = remember { arrayOfNulls<SheetState>(1) }
    val confirmSheetValue: (SheetValue) -> Boolean = { value ->
        if (value == SheetValue.Expanded) {
            val sheet = sheetAsked[0]
            val atRest = sheet != null &&
                sheet.currentValue == SheetValue.Expanded &&
                sheet.targetValue == SheetValue.Expanded &&
                runCatching { abs(sheet.requireOffset()) <= 0.5f }.getOrDefault(false)
            if (restingSettle.nothingToSettle(atRest)) {
                throw CancellationException("The sheet is where it would settle")
            }
        }
        // Read when asked, not when the sheet was made: the setting
        // can change while the player is up.
        if (value == SheetValue.Hidden && !reduceMotion) {
            motion.close()
            false
        } else {
            true
        }
    }
    val sheetState = rememberSaveable(
        saver = SheetState.Saver(
            skipPartiallyExpanded = true,
            confirmValueChange = confirmSheetValue,
            density = sheetDensity,
            skipHiddenState = false,
        ),
    ) {
        SheetState(
            skipPartiallyExpanded = true,
            density = sheetDensity,
            // Already open when the app is the one raising it: its
            // content is held down, and slid up, by the motion.
            initialValue = if (motion.holding) SheetValue.Expanded else SheetValue.Hidden,
            confirmValueChange = confirmSheetValue,
        )
    }
    sheetAsked[0] = sheetState
    DisposableEffect(sheetState) {
        motion.sheet = sheetState
        dock.sheetOffset = motion::position
        onDispose {
            dock.sheetOffset = null
            motion.onSheetGone()
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        // None of M3's: it follows the sheet's own animation, which is
        // no longer what moves the player. The player draws its own,
        // off where the player actually is — see the content below.
        scrimColor = if (reduceMotion) BottomSheetDefaults.ScrimColor else Color.Transparent,
        // The player fills the screen and paints its own background to
        // the very top, so the sheet's default 28.dp top corners would
        // only cut two notches out of the artwork behind the status bar.
        // Square, and not clipped along its top — see [PlayerSheetShape].
        shape = PlayerSheetShape,
        containerColor = Color.Transparent,
        dragHandle = null,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
        // M3 caps a bottom sheet at [BottomSheetDefaults.SheetMaxWidth]
        // (640.dp) and centres it once the window is wider than that —
        // built for a sheet that's meant to look like a sheet next to
        // visible content either side. This one is the whole player;
        // capped at 640dp on a tablet it renders as a narrow card with
        // the page it's supposed to be covering visible down both
        // sides. Unspecified opts out of the cap entirely, so the
        // sheet always spans the full window this app draws it for.
        sheetMaxWidth = Dp.Unspecified,
        // Back is the app's to carry out, like every other close — see
        // the handler below. Left to M3, a back press is the one close
        // that never asks `confirmValueChange`: the sheet hides itself
        // straight away on its own curve, then the window leaves on its
        // exit animation with the artwork still drawn in it.
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = reduceMotion),
    ) {
        // With M3's own back off, the dialog's dispatcher ends here.
        // Composed ahead of the player, so the player's own handlers —
        // the lyrics and the queue putting themselves away first — are
        // newer and are asked before this one.
        BackHandler(enabled = !reduceMotion) { motion.close() }
        // Keeps a sheet still "settling" after a lyrics or queue
        // scroll from taking the next touch meant for that list.
        // See [PlayerSheetMotion.attachWindow].
        val sheetWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        // The player's window holds focus while it is up, so the volume
        // keys reach it rather than the activity.
        DacVolumeKeys()
        DisposableEffect(sheetWindow, reduceMotion) {
            if (!reduceMotion) motion.attachWindow(sheetWindow)
            onDispose { }
        }
        Box(
            Modifier
                // Where the app is holding the player, if it is — see
                // [PlayerSheetMotion.contentOffsetPx]. Placement only.
                .offset { IntOffset(0, motion.contentOffsetPx()) }
                // The dim behind the player, from the window's top to
                // its bottom whatever the player's offset, and as deep
                // as the player is open.
                .drawBehind {
                    val open = dock.openFraction()
                    // Not at all under a portrait player that is fully up:
                    // its own background is opaque, and a full-screen
                    // blend behind it is paid on every frame the window
                    // draws for nothing anyone can see.
                    if (!reduceMotion && open > 0f && !(open >= 1f && dock.attached)) {
                        val top = motion.position().takeUnless { it.isNaN() } ?: 0f
                        drawRect(
                            color = Color.Black.copy(alpha = PLAYER_SCRIM_ALPHA * open),
                            topLeft = Offset(0f, -top.toInt().toFloat()),
                            size = Size(size.width, size.height),
                        )
                    }
                }
                .guardSheetFromContentTouches(sheetState)
                .nestedScroll(restingSettle.connection)
                // The sheet fills the window, so its height is the
                // whole of its travel: hidden sits that far down.
                .onSizeChanged {
                    dock.sheetTravel = it.height.toFloat()
                    motion.laidOut = true
                },
        ) {
            // The still the ⋯ menu below blurs under its panel — taken
            // by the tap that opens it (see onOpenMenu), not the player
            // live, which re-rendered all of it under the panel on every
            // frame anything in it moved (see [SnapshotBackdrop]). Let
            // go once the menu has played its way out. Drawn in this
            // sheet's own window, as the menu has to be to sit above
            // the player. Not under "reduce dynamic blur": the panel
            // is opaque then.
            val menuOpenOn = menuItem()
            val playerMenuUp = menuOpenOn != null
            LaunchedEffect(playerMenuUp, reduceDynamicBlur) {
                if (!playerMenuUp || reduceDynamicBlur) {
                    delay(SNAPSHOT_RELEASE_DELAY_MS)
                    playerBackdrop.release()
                }
            }
            val live = liveCanvas()
            Box(
                when {
                    reduceDynamicBlur -> Modifier
                    live -> Modifier.layerBackdrop(livePlayerBackdrop)
                    else -> Modifier.snapshotBackdropSource(playerBackdrop)
                },
            ) {
                CompositionLocalProvider(LocalPlayerDock provides activeDock) {
                    player()
                }
            }
            IosMenuPopover(
                item = menuOpenOn,
                anchor = menuAnchor(),
                onDismiss = onMenuDismiss,
                backdrop = if (live) livePlayerBackdrop else playerBackdrop,
            ) { song ->
                menuBody(song)
            }
        }
    }
}

/** M3's own scrim strength, which the player's dim stands in for. */
private const val PLAYER_SCRIM_ALPHA = 0.32f

/**
 * The player sheet's shape: square, as a plain rectangle would be, but reaching
 * a whole sheet's height above the sheet's top edge.
 *
 * The sheet's Surface clips its content to this shape, and on the way down to
 * the mini player the artwork travels ahead of the sheet carrying it — up
 * above the sheet's top edge, where a plain rectangle cut it off mid-air,
 * leaving only its lower part to arrive in the bar. Nothing else is drawn
 * outside the sheet (its background is transparent and it casts no shadow),
 * so at rest this is the rectangle it replaces.
 */
private object PlayerSheetShape : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rectangle(Rect(0f, -size.height, size.width, size.height))
}
