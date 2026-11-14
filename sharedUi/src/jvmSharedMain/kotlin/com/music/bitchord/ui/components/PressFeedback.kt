package com.music.bitchord.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/** How far a pressed tile or pill gives under the finger. */
const val TILE_PRESS_SCALE = 0.96f

/** The same give for a panel that spans the page, where 4% of its width would lurch. */
const val WIDE_PRESS_SCALE = 0.98f

/**
 * A press's scale: [pressedScale] while [pressed], back to 1 when it lets go,
 * on the card spring. Held at 1 under Reduce animation (see [LocalUiMotion]).
 */
@Composable
fun animatePressScale(pressed: Boolean, pressedScale: Float = TILE_PRESS_SCALE): State<Float> =
    animateFloatAsState(
        targetValue = if (pressed && LocalUiMotion.current) pressedScale else 1f,
        animationSpec = spring(dampingRatio = CardSpring.DAMPING, stiffness = CardSpring.STIFFNESS),
        label = "pressScale",
    )

/**
 * Shrinks this a touch while [interaction] is pressed, as a UIKit cell or
 * button gives under the finger, and springs back on release.
 *
 * It only watches: whatever clickable shares [interaction] still takes the tap.
 * A clickable inside a scrolling list reports its press only after a short
 * wait, so a fling never makes the tiles it passes over flinch. Put this
 * ahead of the surface's clip and background, so the whole surface gives
 * rather than its contents inside a fixed frame. The scale is read on the
 * draw phase alone.
 */
@Composable
fun Modifier.pressBounce(
    interaction: InteractionSource,
    pressedScale: Float = TILE_PRESS_SCALE,
): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val scale by animatePressScale(pressed, pressedScale)
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/**
 * How far a bare glyph gives under the finger. More than a tile's: a glyph has
 * no surface of its own to show the press on, so its size is all that says it.
 */
const val GLYPH_PRESS_SCALE = 0.86f

/** The glyph spring, shared with whatever swaps one glyph for another. */
const val GLYPH_SPRING_DAMPING = 0.6f
const val GLYPH_SPRING_STIFFNESS = 500f

/**
 * [pressBounce] for the player's glyphs: a deeper give, and a spring loose
 * enough to overshoot once on the way back, so a tap reads as a tap even
 * though the finger covers most of it.
 */
@Composable
fun Modifier.glyphPressBounce(
    interaction: InteractionSource,
    pressedScale: Float = GLYPH_PRESS_SCALE,
): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && LocalUiMotion.current) pressedScale else 1f,
        animationSpec = spring(dampingRatio = GLYPH_SPRING_DAMPING, stiffness = GLYPH_SPRING_STIFFNESS),
        label = "glyphPressScale",
    )
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}
