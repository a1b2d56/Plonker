package com.music.bitchord.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.settings.AppSettings

/**
 * Authentic Apple iOS motion curves, physics, and transitions.
 */
object IosMotion {
    const val SPRING_DAMPING = 0.82f
    const val SPRING_STIFFNESS = 380f

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
     * Apple UIAlertController-style scale-down on exit.
     */
    fun modalCardExit(reduceMotion: Boolean = false): ExitTransition {
        return if (reduceMotion) {
            fadeOut(tween(140))
        } else {
            scaleOut(
                targetScale = 0.90f,
                animationSpec = tween(durationMillis = 160, easing = FastOutLinearInEasing),
            ) + fadeOut(tween(durationMillis = 160))
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
        enter = if (reduceAnimation) fadeIn(tween(150)) else IosMotion.modalScrimEnter(),
        exit = if (reduceAnimation) fadeOut(tween(150)) else IosMotion.modalScrimExit(),
        modifier = modifier,
    ) {
        BackHandler(onBack = onDismiss)
        content()
    }
}

/**
 * Applies iOS subtle button bounce / press scale feedback to any composable.
 */
@Composable
fun Modifier.iosTouchFeedback(
    pressedScale: Float = 0.96f,
    onClick: (() -> Unit)? = null,
): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) pressedScale else 1.0f,
        animationSpec = spring(
            dampingRatio = 0.70f,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "iosTouchScale",
    )
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
        base
    }
}
