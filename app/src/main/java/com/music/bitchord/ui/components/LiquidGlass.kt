/*
 * The rim highlight used by [lightweightLiquidGlass] and [liquidGlassRim] is
 * Kyant0/backdrop's (Apache-2.0), vendored at
 * [com.music.bitchord.ui.components.backdrop]. The glass surface itself,
 * [liquidGlass], is drawn with Haze, the blur the rest of the app's bars use.
 */
package com.music.bitchord.ui.components

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.ui.components.backdrop.highlight.Highlight
import com.music.bitchord.ui.components.backdrop.highlight.HighlightElement
import com.music.bitchord.ui.components.backdrop.internal.ShapeProvider
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint

/** Whether Liquid Glass is turned on — see [AppSettings.liquidGlass]. */
val LocalLiquidGlassEnabled = staticCompositionLocalOf { false }

/**
 * The app's pages as a blur source, for a [liquidGlass] surface that was not
 * handed one of its own. Null outside the main window's content.
 */
val LocalGlassHaze = staticCompositionLocalOf<HazeState?> { null }

/** Blurring what is behind a surface needs [android.graphics.RenderEffect], Android 12 (API 31). */
fun isGlassSupported(sdkInt: Int = Build.VERSION.SDK_INT): Boolean = sdkInt >= Build.VERSION_CODES.S

/**
 * How far the glass blurs what is behind it at full intensity. Less than the
 * frosted bars' material, so a page reads through it as shapes and colour.
 */
private const val BLUR_RADIUS_DP = 14f

/** How much of the theme's tint lies over the glass: well under the frost's. */
private const val SURFACE_OPACITY = 0.4f

/**
 * The hairline along a bar's edge, and what stands in for the glass rim
 * wherever the glass itself is not drawn.
 *
 * A surface filled with the theme's own `surface` colour has no edge of its own
 * against a dark page — it is the same near-black the page is.
 */
internal val GLASS_EDGE_WIDTH = 0.5.dp
internal val GLASS_EDGE_COLOR = Color.White.copy(alpha = 0.10f)

/**
 * The rim of a [liquidGlass] surface: lit along the top, nearly gone at the
 * sides and picked up again faintly along the foot, as a thick pane catches
 * light from above. A gradient stroke, with no shader behind it.
 */
private val GLASS_RIM_WIDTH = 1.dp
private val GLASS_RIM = Brush.verticalGradient(
    0f to Color.White.copy(alpha = 0.42f),
    0.45f to Color.White.copy(alpha = 0.07f),
    1f to Color.White.copy(alpha = 0.20f),
)

/**
 * Icon and label colour for content sitting on a glass surface.
 *
 * Glass shows whatever is behind it rather than the theme's surface colour, so
 * the usual onSurface greys have nothing dependable to sit against. Pure black
 * or white off the theme's luminance is the only tint that holds against
 * arbitrary artwork.
 */
@Composable
fun glassContentColor(): Color =
    if (MaterialTheme.colorScheme.surface.luminance() > 0.5f) Color.Black else Color.White

/**
 * Selected-tab indicator colour for a glass surface: white in both themes.
 *
 * In light theme that is the inverse of [glassContentColor], a bright lift
 * behind a dark glyph. In dark theme it is a faint white lift over the bar's
 * dark glass rather than a black scrim pressed into it — the selected tab is
 * raised out of the bar, as on iOS, not sunk.
 */
@Composable
fun glassIndicatorColor(): Color =
    if (MaterialTheme.colorScheme.surface.luminance() > 0.5f) {
        Color.White.copy(alpha = 0.5f)
    } else {
        Color.White.copy(alpha = DARK_INDICATOR_OPACITY)
    }

/** The selected tab's pill in dark theme, a step whiter than the bar. */
private const val DARK_INDICATOR_OPACITY = 0.14f

/**
 * A lightweight visual match for liquid glass over a stable background.
 *
 * This keeps the same translucent tint, directional highlight and hairline as
 * [liquidGlass], but samples nothing behind it. When Liquid Glass is disabled
 * or unsupported, [fallbackColor] preserves the control's existing filled
 * appearance.
 */
@Composable
fun Modifier.lightweightLiquidGlass(
    shape: CornerBasedShape,
    fallbackColor: Color,
): Modifier {
    val useGlass = LocalLiquidGlassEnabled.current && isGlassSupported()
    val glassTint = if (MaterialTheme.colorScheme.surface.luminance() > 0.5f) {
        Color(0xFFFAFAFA)
    } else {
        Color(0xFF121212)
    }
    val shapeProvider = ShapeProvider { shape }

    return clip(shape)
        .background(
            color = if (useGlass) glassTint.copy(alpha = SURFACE_OPACITY) else fallbackColor,
            shape = shape,
        )
        .then(
            if (useGlass) {
                HighlightElement(
                    shapeProvider = shapeProvider,
                    highlight = { Highlight.Default },
                )
            } else {
                Modifier
            },
        )
        .border(GLASS_EDGE_WIDTH, GLASS_EDGE_COLOR, shape)
}

/**
 * Just the rim of liquid glass — the directional [Highlight] that catches the
 * light along a glass edge — for a control that keeps its own fill.
 *
 * Nothing at all while Liquid Glass is off or unsupported.
 */
@Composable
fun Modifier.liquidGlassRim(shape: CornerBasedShape): Modifier {
    if (!LocalLiquidGlassEnabled.current || !isGlassSupported()) return this
    val shapeProvider = remember(shape) { ShapeProvider { shape } }
    return then(
        HighlightElement(
            shapeProvider = shapeProvider,
            highlight = { Highlight.Default },
        ),
    )
}

/**
 * Draws this composable as a liquid glass surface: what is behind it blurred
 * lightly off [hazeState], a thin theme tint over that (light glass on a light
 * theme, dark on dark), and a lit rim.
 *
 * It is the frosted bars' own blur with a different style, so a glass surface
 * costs what a frosted one does: one downsampled blur of the page, and no
 * refraction shader, second recording of the page or shadow pass.
 *
 * [AppSettings.liquidGlassIntensity] scales the blur. Under "reduce dynamic
 * blur", with no [hazeState] to sample, or before Android 12 the surface is
 * filled solid instead.
 */
@Composable
fun Modifier.liquidGlass(
    shape: CornerBasedShape,
    hazeState: HazeState? = LocalGlassHaze.current,
    /** Drawn over the glass in place of the theme's default tint, when set. */
    surfaceTint: Color? = null,
): Modifier {
    val surface = MaterialTheme.colorScheme.surface
    val floor = MaterialTheme.colorScheme.background
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    if (!isGlassSupported() || reduceDynamicBlur || hazeState == null) {
        return clip(shape)
            .background(surface)
            .border(GLASS_EDGE_WIDTH, GLASS_EDGE_COLOR, shape)
    }
    val intensity by AppSettings.liquidGlassIntensity.collectAsStateWithLifecycle()
    val themeTint = if (surface.luminance() > 0.5f) Color(0xFFFAFAFA) else Color(0xFF121212)
    val tint = surfaceTint ?: themeTint.copy(alpha = SURFACE_OPACITY)
    val style = remember(surface, floor, tint, intensity) {
        HazeStyle(
            // The pages paint no background of their own, so this is what the
            // blur has under it wherever a page is empty.
            backgroundColor = floor,
            tints = listOf(HazeTint(tint)),
            blurRadius = (BLUR_RADIUS_DP * intensity).dp,
            noiseFactor = 0f,
            fallbackTint = HazeTint(surface),
        )
    }
    return clip(shape)
        .optimizedHazeEffect(state = hazeState, style = style)
        .border(GLASS_RIM_WIDTH, GLASS_RIM, shape)
}
