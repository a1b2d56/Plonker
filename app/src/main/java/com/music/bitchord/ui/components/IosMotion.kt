package com.music.bitchord.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.settings.AppSettings

/**
 * Provides the parent modal AnimatedVisibilityScope to child alerts and dialogs
 * so they automatically animate their entrance and exit without needing manual wiring.
 */
val LocalIosModalScope = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

/**
 * Authentic Apple iOS motion curves, physics, and transitions.
 */
object IosMotion {
    const val SPRING_DAMPING = 0.82f
    const val SPRING_STIFFNESS = 380f

    /**
     * Navigation push/pop: a touch firmer than the card spring, so a page
     * travelling the full width settles without a visible overshoot.
     */
    const val NAV_DAMPING = 0.88f
    const val NAV_STIFFNESS = 400f
    private const val NAV_FADE_MS = 180

    /** The shared card spring, for any value that should move like a modal card. */
    fun <T> cardSpring(): FiniteAnimationSpec<T> =
        spring(dampingRatio = SPRING_DAMPING, stiffness = SPRING_STIFFNESS)

    /** For [androidx.compose.animation.animateContentSize] on expanding cards and rows. */
    fun contentSizeSpec(): FiniteAnimationSpec<IntSize> =
        spring(dampingRatio = SPRING_DAMPING, stiffness = SPRING_STIFFNESS)

    private fun navOffsetSpec(): FiniteAnimationSpec<IntOffset> =
        spring(dampingRatio = NAV_DAMPING, stiffness = NAV_STIFFNESS)

    /**
     * Forward navigation, UINavigationController-style: the new page slides in
     * from the right while the one it covers drifts a third of the way
     * back (parallax), both fading over [NAV_FADE_MS].
     */
    fun navPush(): ContentTransform =
        (
            slideInHorizontally(navOffsetSpec()) { it } + fadeIn(tween(NAV_FADE_MS))
        ) togetherWith (
            slideOutHorizontally(navOffsetSpec()) { -it / 3 } + fadeOut(tween(NAV_FADE_MS))
        )

    /** The exact reverse of [navPush]: the page leaves to the right. */
    fun navPop(): ContentTransform =
        (
            slideInHorizontally(navOffsetSpec()) { -it / 3 } + fadeIn(tween(NAV_FADE_MS))
        ) togetherWith (
            slideOutHorizontally(navOffsetSpec()) { it } + fadeOut(tween(NAV_FADE_MS))
        )

    /** A change of context with no direction to it, or any change under reduced motion. */
    fun navCrossfade(): ContentTransform =
        fadeIn(tween(NAV_FADE_MS)) togetherWith fadeOut(tween(NAV_FADE_MS))

    /** Modal scrim background enter transition. */
    fun modalScrimEnter(): EnterTransition =
        fadeIn(animationSpec = tween(durationMillis = 240, easing = LinearOutSlowInEasing))

    /** Modal scrim background exit transition. */
    fun modalScrimExit(): ExitTransition =
        fadeOut(animationSpec = tween(durationMillis = 180, easing = FastOutLinearInEasing))

    /**
     * Apple UIAlertController-style spring scale-up on entry.
     */
    fun modalCardEnter(reduceMotion: Boolean = false): EnterTransition {
        return if (reduceMotion) {
            fadeIn(tween(160))
        } else {
            scaleIn(
                initialScale = 0.88f,
                animationSpec = spring(
                    dampingRatio = SPRING_DAMPING,
                    stiffness = SPRING_STIFFNESS,
                ),
            ) + fadeIn(tween(180, easing = LinearOutSlowInEasing))
        }
    }

    /**
     * Apple UIAlertController-style scale-down and fade on exit.
     */
    fun modalCardExit(reduceMotion: Boolean = false): ExitTransition {
        return if (reduceMotion) {
            fadeOut(tween(140))
        } else {
            scaleOut(
                targetScale = 0.86f,
                animationSpec = tween(durationMillis = 180, easing = FastOutLinearInEasing),
            ) + fadeOut(tween(durationMillis = 180, easing = FastOutLinearInEasing))
        }
    }
}

/**
 * Scaffolding that wraps any popup, dialog, or menu in an iOS-native animated container
 * with scrim fade, spring card scaling, and back button handling.
 */
@Composable
fun IosModalContainer(
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable AnimatedVisibilityScope.() -> Unit,
) {
    val reduceAnimation by AppSettings.reduceAnimation.collectAsStateWithLifecycle()
    AnimatedVisibility(
        visible = visible,
        enter = if (reduceAnimation) fadeIn(tween(160)) else IosMotion.modalScrimEnter(),
        exit = if (reduceAnimation) fadeOut(tween(180, easing = FastOutLinearInEasing)) else IosMotion.modalScrimExit(),
        modifier = modifier,
    ) {
        BackHandler(onBack = onDismiss)
        CompositionLocalProvider(LocalIosModalScope provides this) {
            content()
        }
    }
}

/**
 * [IosModalContainer] for a modal that is open *on* something — a config being
 * edited, a mode being confirmed. Shown while [item] is non-null, and keeps
 * drawing the last non-null value through the exit animation, so the card
 * doesn't empty out the moment the caller clears its slot.
 */
@Composable
fun <T : Any> IosModalContainer(
    item: T?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable AnimatedVisibilityScope.(T) -> Unit,
) {
    var retained by remember { mutableStateOf(item) }
    if (item != null && item != retained) retained = item
    IosModalContainer(visible = item != null, onDismiss = onDismiss, modifier = modifier) {
        retained?.let { content(it) }
    }
}

/**
 * Applies iOS subtle button bounce / press scale feedback to any composable.
 *
 * With [onClick] it is the click target itself. Without one it only *watches*
 * the pointer (on the initial pass, consuming nothing), so it can sit on a
 * row or card whose own `clickable` / `combinedClickable` handles the tap —
 * and it lets go as soon as the gesture turns into a scroll, the way a UIKit
 * highlight does. Off under "Reduce animation".
 */
@Composable
fun Modifier.iosTouchFeedback(
    pressedScale: Float = 0.96f,
    onClick: (() -> Unit)? = null,
): Modifier {
    val reduceAnimation by AppSettings.reduceAnimation.collectAsStateWithLifecycle()
    val interactionSource = remember { MutableInteractionSource() }
    val clickPressed by interactionSource.collectIsPressedAsState()
    var watchedPressed by remember { mutableStateOf(false) }
    val pressed = (clickPressed || watchedPressed) && !reduceAnimation
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1.0f,
        animationSpec = spring(
            dampingRatio = 0.70f,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "iosTouchScale",
    )
    // Read inside the layer block so a press animates on the draw phase alone.
    val base = this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
    return if (onClick != null) {
        base.clickable(
            interactionSource = interactionSource,
            indication = null,
            onClick = onClick,
        )
    } else {
        base.pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                watchedPressed = true
                try {
                    while (true) {
                        // Final pass: by now a scrollable parent has had its
                        // say, and a consumed move means the touch became a drag.
                        val event = awaitPointerEvent(PointerEventPass.Final)
                        val scrolled = event.changes.any { it.isConsumed && it.positionChange() != Offset.Zero }
                        if (scrolled || event.changes.none { it.pressed }) break
                    }
                } finally {
                    watchedPressed = false
                }
            }
        }
    }
}
