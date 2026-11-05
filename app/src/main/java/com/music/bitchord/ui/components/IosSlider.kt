package com.music.bitchord.ui.components

import androidx.compose.animation.core.Spring
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Authentic Apple iOS Human Interface Guidelines slider.
 *
 * Consists of:
 * 1. A sleek rounded capsule track (5dp height) with leading active fill and trailing inactive fill.
 * 2. A circular white thumb knob (26dp) with subtle elevation drop shadow, delicate hairline border,
 *    and smooth spring-based scaling on touch.
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
    inactiveColor: Color = Color.White.copy(alpha = 0.18f),
    thumbColor: Color = Color.White,
    trackHeight: Dp = 5.dp,
    thumbSize: Dp = 26.dp,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
) {
    val isPressed by interactionSource.collectIsPressedAsState()
    val thumbScale by animateFloatAsState(
        targetValue = if (isPressed) 1.08f else 1.0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "iosSliderThumbScale",
    )

    Slider(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        valueRange = valueRange,
        steps = steps,
        onValueChangeFinished = onValueChangeFinished,
        interactionSource = interactionSource,
        thumb = {
            IosSliderThumb(
                size = thumbSize,
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
    size: Dp = 26.dp,
    scale: Float = 1.0f,
    thumbColor: Color = Color.White,
    modifier: Modifier = Modifier,
) {
    Spacer(
        modifier = modifier
            .size(size)
            .scale(scale)
            .shadow(elevation = 3.dp, shape = CircleShape, clip = false)
            .clip(CircleShape)
            .background(thumbColor)
            .border(width = 0.5.dp, color = Color.Black.copy(alpha = 0.08f), shape = CircleShape),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IosSliderTrack(
    sliderState: SliderState,
    trackHeight: Dp = 5.dp,
    activeColor: Color = Color.White.copy(alpha = 0.95f),
    inactiveColor: Color = Color.White.copy(alpha = 0.18f),
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

        // Inactive track (full width)
        drawRoundRect(
            color = inactiveColor,
            topLeft = Offset.Zero,
            size = size,
            cornerRadius = cornerRadius,
        )

        // Active track (up to current fraction)
        if (activeWidth > 0f) {
            drawRoundRect(
                color = activeColor,
                topLeft = Offset.Zero,
                size = Size(activeWidth, size.height),
                cornerRadius = cornerRadius,
            )
        }
    }
}
