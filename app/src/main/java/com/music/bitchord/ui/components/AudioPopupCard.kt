package com.music.bitchord.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.ui.utils.containSheetGestures
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val POPUP_CARD_SHAPE = RoundedCornerShape(ALERT_CORNER)
private val POPUP_SCRIM_COLOR = Color.Black.copy(alpha = 0.4f)
private val POPUP_WIDTH = 320.dp
private val POPUP_CONTENT_MAX_HEIGHT = 420.dp

/**
 * The frosted card the output drawer's popups open into — the one
 * [AudioPipelineDialog] was drawn in, lifted out so the Cast picker is the same
 * object rather than a second one made to look like it.
 *
 * A scrim that dismisses, a card that swallows the tap so touching it does not,
 * a centred title and subtitle, a scrolling body, and a full-width closing
 * action. Fixed to the player's own dark palette rather than the theme-adaptive
 * one the settings dialogs use, since everything on the player is white alphas
 * whatever the app's light/dark theme is.
 *
 * [content] is the scrolling body, handed a [BoxScope] so a body can lay
 * something behind its rows — the pipeline draws its signal line there.
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
internal fun AudioPopupCard(
    hazeState: HazeState,
    title: String,
    subtitle: String,
    doneLabel: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    animatedScope: AnimatedVisibilityScope? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    val reduceAnimation by AppSettings.reduceAnimation.collectAsStateWithLifecycle()

    var shown by remember { mutableStateOf(false) }
    var isDismissing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }

    val coroutineScope = rememberCoroutineScope()
    val handleDismiss: () -> Unit = {
        if (animatedScope != null) {
            onDismiss()
        } else if (!isDismissing) {
            isDismissing = true
            shown = false
            coroutineScope.launch {
                delay(200)
                onDismiss()
            }
        }
    }

    if (animatedScope == null) {
        BackHandler(enabled = shown && !isDismissing, onBack = handleDismiss)
    }

    val selfScale by animateFloatAsState(
        targetValue = if (shown) 1f else 0.88f,
        animationSpec = spring(
            dampingRatio = IosMotion.SPRING_DAMPING,
            stiffness = IosMotion.SPRING_STIFFNESS,
        ),
        label = "audioPopupCardScale",
    )
    val selfAlpha by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(if (shown) 220 else 180),
        label = "audioPopupCardAlpha",
    )
    val selfScrimAlpha by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(if (shown) 220 else 180),
        label = "audioPopupScrimAlpha",
    )

    val animModifier = if (animatedScope != null) {
        with(animatedScope) {
            Modifier.animateEnterExit(
                enter = IosMotion.modalCardEnter(reduceAnimation),
                exit = IosMotion.modalCardExit(reduceAnimation),
            )
        }
    } else {
        Modifier.graphicsLayer {
            scaleX = selfScale
            scaleY = selfScale
            alpha = selfAlpha
        }
    }

    val scrimColor = if (animatedScope != null) {
        POPUP_SCRIM_COLOR
    } else {
        POPUP_SCRIM_COLOR.copy(alpha = POPUP_SCRIM_COLOR.alpha * selfScrimAlpha)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .containSheetGestures()
            .background(scrimColor)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = handleDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(POPUP_WIDTH)
                .then(animModifier)
                .clip(POPUP_CARD_SHAPE)
                .then(
                    if (reduceDynamicBlur) {
                        Modifier.background(Color(0xFF121212))
                    } else {
                        Modifier
                            .optimizedHazeEffect(
                                state = hazeState,
                                style = HazeMaterials.regular(Color(0xFF141414)),
                            )
                            .background(Color(0xFF121212).copy(alpha = 0.9f))
                    }
                )
                // Swallows the tap before it reaches the scrim behind, so
                // touching the card itself never dismisses it.
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = {},
                ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 19.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = 17.sp,
                        fontWeight = FontWeight.W600,
                    ),
                    color = Color.White,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = subtitle,
                    modifier = Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 13.sp,
                        lineHeight = 17.sp,
                    ),
                    color = Color.White.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                )
            }

            Box(
                modifier = Modifier
                    .heightIn(max = POPUP_CONTENT_MAX_HEIGHT)
                    .verticalScroll(rememberScrollState()),
                content = content,
            )

            // Full width: the default rule is indented to clear the pipeline's
            // icon lane, which left this one sitting right of centre.
            PipelineRule(Modifier)
            PipelineDoneAction(label = doneLabel, onClick = handleDismiss)
        }
    }
}
