package com.music.bitchord.ui.components

import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset

/**
 * The card spring — settled with a hint of overshoot — kept here so both
 * modules move on the same one. `IosMotion` in :app reads these numbers.
 */
object CardSpring {
    const val DAMPING = 0.82f
    const val STIFFNESS = 380f
}

/**
 * Whether list rows animate as they move, arrive and leave. The app provides
 * it from Reduce animation; left unprovided, rows move.
 */
val LocalListRowMotion = compositionLocalOf { true }

/**
 * A track row's motion in a lazy list: it slides to its new place when a
 * sort, a filter or a removal moves it, and fades as it arrives or leaves.
 *
 * Put it on the item's root, around the row and its divider, so the two move
 * as one. The item's key has to follow the row for any of this to show — see
 * [stableRowKeys].
 */
@Composable
fun LazyItemScope.trackRowMotion(): Modifier =
    if (LocalListRowMotion.current) {
        Modifier.animateItem(
            // Opacity has nothing to overshoot, so the fades are undamped
            // springs at the card's stiffness: the same pace, no bounce.
            fadeInSpec = spring(stiffness = CardSpring.STIFFNESS),
            placementSpec = spring(
                dampingRatio = CardSpring.DAMPING,
                stiffness = CardSpring.STIFFNESS,
                visibilityThreshold = IntOffset.VisibilityThreshold,
            ),
            fadeOutSpec = spring(stiffness = CardSpring.STIFFNESS),
        )
    } else {
        Modifier
    }

/**
 * One lazy-list key per row of [ids], unique even when an id repeats (the same
 * song twice on a playlist) and unchanged when other rows come and go.
 *
 * A key with the row's index in it is unique too, but every row after a
 * removed one gets a new key, so the list fades those rows out and in again
 * instead of sliding them up. Here a row's key is its id, with how many times
 * that id appeared before it: only another copy of the same song can change it.
 */
fun stableRowKeys(ids: List<String>): List<String> {
    val seen = HashMap<String, Int>(ids.size)
    return ids.map { id ->
        val before = seen[id] ?: 0
        seen[id] = before + 1
        if (before == 0) id else "$id#$before"
    }
}
