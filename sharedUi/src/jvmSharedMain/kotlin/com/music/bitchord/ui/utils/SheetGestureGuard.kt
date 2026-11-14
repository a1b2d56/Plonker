package com.music.bitchord.ui.utils

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.Velocity

/**
 * Stops a resting [androidx.compose.material3.ModalBottomSheet] from stealing a
 * touch that belongs to the content inside it. Goes on the root of the sheet's
 * content.
 *
 * Material3 1.3.1's sheet starts its own drag on touch-down, skipping the touch
 * slop and taking the event in the Initial pass before any content sees it,
 * whenever it thinks it is animating. And it is "animating" for 300 ms after
 * every scroll that ends inside it: its nested-scroll connection calls settle()
 * on every post-fling, even one with zero velocity left, and settle runs the
 * sheet's fixed 300 ms tween from the anchor it is already on to that same
 * anchor. So a finger that lands within 300 ms of the last scroll ending drags
 * the sheet. The sheet carries the content along with it, so the list under the
 * finger sees no movement and never takes the gesture back. The player collapsing
 * mid-way through scrolling the lyrics or queue was exactly that.
 *
 * The fix: if the sheet grabbed the down while it was fully open (so there was
 * no real motion to catch), consume the first move in the Main pass. The sheet's
 * drag runs after its content in that pass, sees the move consumed, and cancels,
 * and the content's own drag carries on from its touch slop as usual. A sheet
 * that is really moving (opening, or springing back from a partial drag) is left
 * alone, so a finger can still catch it.
 */
@OptIn(ExperimentalMaterial3Api::class)
fun Modifier.guardSheetFromContentTouches(sheetState: SheetState): Modifier =
    pointerInput(sheetState) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            // Unconsumed means the sheet was not animating, so it waits for its
            // slop like any other ancestor and the content wins as normal.
            if (!down.isConsumed) return@awaitEachGesture
            val offset = runCatching { sheetState.requireOffset() }.getOrNull()
                ?: return@awaitEachGesture
            if (offset > 1f) return@awaitEachGesture
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                if (change.positionChange() != Offset.Zero) {
                    change.consume()
                    break
                }
            }
        }
    }

/**
 * Spots the settle a resting sheet is asked for when a scroll inside it ends,
 * so that it can be called off.
 *
 * Material3 1.3.1's sheet answers the end of every fling under it by settling,
 * and for a sheet lying open at rest that is 300 ms of animating from where it
 * is to where it is — during which it takes the next touch-down for itself
 * ([guardSheetFromContentTouches] gives a drag back to the content; a tap it
 * cannot give back, and the tap is lost). On a phone, a tap on anything in the
 * player up to a quarter of a second after the lyrics came to rest did nothing.
 *
 * The sheet cannot be kept from hearing of the fling, and its animation cannot
 * be stopped from outside. But before it animates it asks the state's
 * `confirmValueChange`, and that is the app's. So: [connection], on the root of
 * the sheet's content, is told of a fling's end just before the sheet is, and
 * notes when; and `confirmValueChange`, asked about "expanded" straight after
 * by a sheet that is expanded and not moving, asks [nothingToSettle] and
 * cancels the settle rather than answer. Only that one: a sheet off its anchor
 * has somewhere to go, and any later question is somebody else's.
 */
class RestingSheetSettle(private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private var flingEnded = false
    private var flingEndedAtMs = 0L

    val connection: NestedScrollConnection = object : NestedScrollConnection {
        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            flingEnded = true
            flingEndedAtMs = nowMs()
            return Velocity.Zero
        }
    }

    /**
     * Whether the settle now being asked about is the one a fling's end has
     * just set off in a sheet that is [atRest]. Answers yes once per fling.
     */
    fun nothingToSettle(atRest: Boolean): Boolean {
        val justEnded = flingEnded && nowMs() - flingEndedAtMs <= SETTLE_FOLLOWS_WITHIN_MS
        flingEnded = false
        return justEnded && atRest
    }

    private companion object {
        /** The sheet launches its settle on the frame the fling ends; this is slack. */
        const val SETTLE_FOLLOWS_WITHIN_MS = 200L
    }
}

/**
 * Keeps every drag that starts inside an overlay — a drawer or dialog drawn
 * over the player — from reaching the sheet the player lives in. Goes on the
 * overlay's full-screen root.
 *
 * Two routes leak otherwise. A scrollable inside the overlay hands whatever it
 * can't use (a pull down while already at the top) up the nested-scroll chain,
 * where the sheet's own connection takes it and moves the player. And a drag
 * that nothing inside claims — on the scrim, or a row with no scroll of its
 * own — reaches the sheet's draggable as an unconsumed drag. The connection
 * swallows the first; consuming every move past touch slop stops the second,
 * while taps, which never cross the slop, still reach the scrim's dismiss.
 */
fun Modifier.containSheetGestures(): Modifier =
    nestedScroll(SwallowNestedScroll).pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val start = awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                ?: return@awaitEachGesture
            drag(start.id) { it.consume() }
        }
    }

private object SwallowNestedScroll : NestedScrollConnection {
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource) =
        sheetBoundLeftover(available, source)

    override suspend fun onPostFling(consumed: Velocity, available: Velocity) = available
}

/**
 * The part of a scroll's leftover that has to be kept from the player's sheet:
 * a finger's, and only a finger's.
 *
 * The sheet follows what a drag leaves over and ignores what a fling does, so
 * a connection that is only there to keep the sheet still has no reason to
 * take a fling's — and a good one not to. A fling reads "consumed" as "still
 * moving". With its leftover taken, one that has run into the end of its list
 * is never told so, and carries on for its whole length with nothing moving:
 * up to a second and a half after a hard flick, measured on a phone. The list
 * counts as scrolling all that while, and a list that is scrolling takes the
 * next touch as the one that stops it. So a tap on a row did nothing straight
 * after a flick to the end of a menu, and worked the second time.
 *
 * Handed back, the leftover ends the fling where the list ends. What the fling
 * had left in speed is still taken, in `onPostFling`: that the sheet would act
 * on, and a downward one would close the player.
 */
internal fun sheetBoundLeftover(available: Offset, source: NestedScrollSource): Offset =
    if (source == NestedScrollSource.UserInput) available else Offset.Zero
