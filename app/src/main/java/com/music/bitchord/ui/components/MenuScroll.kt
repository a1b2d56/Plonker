package com.music.bitchord.ui.components

import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.DragScope
import androidx.compose.foundation.gestures.DraggableState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.scrollBy
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * A menu's rows in a column that scrolls when there are more of them than the
 * screen has room for — and keeps that scrolling to itself.
 *
 * `verticalScroll` reports every scroll, and the end of every fling, to
 * whatever is above it. Above the player's menu is the sheet the player lives
 * in, and Material's sheet answers the end of any fling under it, a fling of
 * nothing included, by "settling": 300 ms of animating from where it is to
 * where it is. For those 300 ms it takes the next touch-down for itself,
 * before anything inside it is asked. So the tap that followed a scroll of
 * the menu went to the sheet, and the row under the finger never heard of it
 * — on a phone, a tap up to 270 ms after a finger that had only dragged the
 * menu was lifted did nothing, and one 420 ms after worked. Every row that has
 * to be scrolled to was a row whose first tap could be lost.
 *
 * The sheet has no use for what a menu scrolls. So the finger and the fling
 * are run from here, on the column's own [ScrollState] through a `draggable`,
 * which reports to nobody, and `verticalScroll` is left only the laying out
 * and the clipping. A fling ends where the rows do, a finger landing on rows
 * still gliding stops them without being a tap on one, and there is nothing
 * after either for a tap to have to wait out.
 */
@Composable
internal fun MenuScrollColumn(
    state: ScrollState,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val fling = ScrollableDefaults.flingBehavior()
    val finger = remember(state) { ScrollDrag(state) }
    val wheelStep = with(LocalDensity.current) { WHEEL_STEP.toPx() }
    // What tells the rows they are in something that scrolls. A row asked to
    // light up under a finger waits a tenth of a second first if it is — the
    // finger may be the start of a swipe — and it only knows that it is from a
    // `scrollable` above it. This one scrolls nothing and never gets the
    // chance to: the `draggable` under it is asked about every finger first,
    // and takes each one that moves. Without it a row lit up the moment a
    // swipe touched it, and went dark again as the swipe got going.
    val nothingToScroll = rememberScrollableState { 0f }
    Column(
        modifier = modifier
            .scrollable(nothingToScroll, Orientation.Vertical)
            .draggable(
                state = finger,
                orientation = Orientation.Vertical,
                // Rows still gliding are caught by the finger that lands on
                // them, there and then, rather than tapped.
                startDragImmediately = state.isScrollInProgress,
                // The finger's speed is the rows' the other way: a finger
                // going up is the list going forward.
                onDragStopped = { velocity ->
                    state.scroll { with(fling) { performFling(-velocity) } }
                },
            )
            // A mouse wheel, which `verticalScroll` would have answered.
            .pointerInput(state, wheelStep) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type != PointerEventType.Scroll) continue
                        val notches = event.changes.fold(0f) { sum, change -> sum + change.scrollDelta.y }
                        if (notches != 0f && state.dispatchRawDelta(notches * wheelStep) != 0f) {
                            event.changes.forEach { it.consume() }
                        }
                    }
                }
            }
            // And a screen reader's scroll, likewise.
            .semantics {
                scrollBy { _, y -> state.dispatchRawDelta(y) != 0f }
            }
            .verticalScroll(state, overscrollEffect = null, enabled = false),
        content = content,
    )
}

/**
 * A finger's drag as a scroll of [state], held under the state's own lock: a
 * drag that starts while a fling is running takes the lock from it, and that
 * is what stops the fling.
 */
private class ScrollDrag(private val state: ScrollState) : DraggableState {
    override suspend fun drag(dragPriority: MutatePriority, block: suspend DragScope.() -> Unit) {
        state.scroll(dragPriority) {
            val scroll = this
            object : DragScope {
                override fun dragBy(pixels: Float) {
                    scroll.scrollBy(-pixels)
                }
            }.block()
        }
    }

    override fun dispatchRawDelta(delta: Float) {
        state.dispatchRawDelta(-delta)
    }
}

/** How far one notch of a mouse wheel moves the rows. */
private val WHEEL_STEP = 64.dp
