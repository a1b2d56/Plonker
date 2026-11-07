package com.music.bitchord.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.music.bitchord.ui.haptics.Haptic
import com.music.bitchord.ui.haptics.rememberHaptics
import kotlin.math.abs

/**
 * Authentic Apple iOS Human Interface Guidelines slider.
 *
 * Implements:
 * 1. A rounded capsule track (6dp height) with leading active fill and trailing inactive fill.
 * 2. An authentic horizontal capsule/pill thumb (36dp x 24dp) with elevation drop shadow,
 *    delicate hairline border, and interactive elastic expansion on drag/press.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IosSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
    activeColor: Color = Color.White.copy(alpha = 0.95f),
    inactiveColor: Color = Color.White.copy(alpha = 0.20f),
    thumbColor: Color = Color.White,
    trackHeight: Dp = 6.dp,
    thumbWidth: Dp = 36.dp,
    thumbHeight: Dp = 24.dp,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
) {
    val haptics = rememberHaptics()
    var lastNotifiedStep by remember(valueRange, steps) { mutableStateOf(value) }
    val isPressed by interactionSource.collectIsPressedAsState()
    val animatedWidth by animateDpAsState(
        targetValue = if (isPressed) thumbWidth + 4.dp else thumbWidth,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "iosSliderThumbWidth",
    )
    val thumbScale by animateFloatAsState(
        targetValue = if (isPressed) 1.04f else 1.0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "iosSliderThumbScale",
    )

    val handleValueChange: (Float) -> Unit = { newValue ->
        if (steps > 0) {
            val stepSpan = (valueRange.endInclusive - valueRange.start) / (steps + 1)
            if (abs(newValue - lastNotifiedStep) >= stepSpan * 0.7f) {
                haptics.play(Haptic.Tick)
                lastNotifiedStep = newValue
            }
        }
        onValueChange(newValue)
    }

    Slider(
        value = value,
        onValueChange = handleValueChange,
        modifier = modifier,
        enabled = enabled,
        valueRange = valueRange,
        steps = steps,
        onValueChangeFinished = {
            haptics.play(Haptic.Select)
            onValueChangeFinished?.invoke()
        },
        interactionSource = interactionSource,
        thumb = {
            IosSliderThumb(
                width = animatedWidth,
                height = thumbHeight,
                scale = thumbScale,
                thumbColor = thumbColor,
            )
        },
        track = { sliderState ->
            IosSliderTrack(
                sliderState = sliderState,
                trackHeight = trackHeight,
                activeColor = activeColor,
                inactiveColor = inactiveColor,
            )
        },
    )
}

@Composable
fun IosSliderThumb(
    width: Dp = 36.dp,
    height: Dp = 24.dp,
    scale: Float = 1.0f,
    thumbColor: Color = Color.White,
    modifier: Modifier = Modifier,
) {
    val capsuleShape = RoundedCornerShape(percent = 50)
    Spacer(
        modifier = modifier
            .size(width = width, height = height)
            .scale(scale)
            .shadow(
                elevation = 3.dp,
                shape = capsuleShape,
                clip = false,
                ambientColor = Color.Black.copy(alpha = 0.35f),
                spotColor = Color.Black.copy(alpha = 0.45f),
            )
            .clip(capsuleShape)
            .background(thumbColor)
            .border(
                width = 0.5.dp,
                color = Color.Black.copy(alpha = 0.08f),
                shape = capsuleShape,
            ),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IosSliderTrack(
    sliderState: SliderState,
    trackHeight: Dp = 6.dp,
    activeColor: Color = Color.White.copy(alpha = 0.95f),
    inactiveColor: Color = Color.White.copy(alpha = 0.20f),
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(trackHeight),
    ) {
        val cornerRadius = CornerRadius(size.height / 2f, size.height / 2f)
        val fraction = ((sliderState.value - sliderState.valueRange.start) /
            (sliderState.valueRange.endInclusive - sliderState.valueRange.start)).coerceIn(0f, 1f)
        val activeWidth = size.width * fraction

        // Inactive track (full width pill capsule)
        drawRoundRect(
            color = inactiveColor,
            topLeft = Offset.Zero,
            size = size,
            cornerRadius = cornerRadius,
        )

        // Active track (clipped capsule fill matching the outer track's curve)
        if (activeWidth > 0f) {
            clipRect(left = 0f, top = 0f, right = activeWidth, bottom = size.height) {
                drawRoundRect(
                    color = activeColor,
                    topLeft = Offset.Zero,
                    size = size,
                    cornerRadius = cornerRadius,
                )
            }
        }
    }
}
