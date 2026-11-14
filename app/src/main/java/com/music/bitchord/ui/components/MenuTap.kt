package com.music.bitchord.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize

/**
 * A tap on something in a menu, which counts whether or not anything above the
 * menu laid claim to the finger first.
 *
 * `clickable` only answers a touch nobody has consumed, and a menu is drawn
 * inside things that consume one before its rows are asked: the sheet the
 * player lives in takes a touch-down outright while it believes it is moving,
 * and so does a scrolling column while it believes it is. Either belief can be
 * left standing when a menu opens, and the next finger to land clears it — so
 * the first tap on a freshly opened menu did nothing and every one after it
 * worked. A row has no way to argue with what was decided above it; it can only
 * not depend on it.
 *
 * So this watches the finger itself: down on the row, up on the row, without
 * having travelled as far as a scroll would take. A finger that does travel is
 * the list's, and the row lets go of it.
 *
 * [interactionSource] is told about the press, for the fill a row paints under
 * a finger.
 */
@Composable
internal fun Modifier.menuTap(
    interactionSource: MutableInteractionSource? = null,
    enabled: Boolean = true,
    role: Role? = Role.Button,
    onClick: () -> Unit,
): Modifier {
    val currentOnClick by rememberUpdatedState(onClick)
    return this
        .pointerInput(interactionSource, enabled) {
            if (!enabled) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val press = PressInteraction.Press(down.position)
                interactionSource?.tryEmit(press)
                var tapped = false
                try {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        val holds = tapStillHolds(down.position, change.position, viewConfiguration.touchSlop, size)
                        if (!change.pressed) {
                            tapped = holds
                            if (tapped) change.consume()
                            break
                        }
                        if (!holds) break
                    }
                } finally {
                    interactionSource?.tryEmit(
                        if (tapped) PressInteraction.Release(press) else PressInteraction.Cancel(press),
                    )
                }
                if (tapped) currentOnClick()
            }
        }
        .semantics {
            if (role != null) this.role = role
            if (enabled) {
                onClick {
                    currentOnClick()
                    true
                }
            } else {
                disabled()
            }
        }
}

/**
 * Whether a finger that came down at [start] and is now at [now] is still a
 * tap on a target of [size]: inside it, and no further from where it landed
 * than [touchSlop], past which the movement is a drag.
 */
internal fun tapStillHolds(start: Offset, now: Offset, touchSlop: Float, size: IntSize): Boolean =
    now.x >= 0f && now.y >= 0f && now.x <= size.width && now.y <= size.height &&
        (now - start).getDistance() <= touchSlop
