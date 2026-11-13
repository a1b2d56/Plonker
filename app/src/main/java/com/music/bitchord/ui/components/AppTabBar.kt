package com.music.bitchord.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.ui.haptics.Haptic
import com.music.bitchord.ui.haptics.rememberHaptics
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import kotlin.math.abs
import kotlin.math.roundToInt

data class BottomTab(
    val label: String,
    val icon: ImageVector,
)

/**
 * The gap between the pill's edge and the tabs inside it.
 *
 * Apple's selection sits about 4pt in from its tab bar's edge, measured off
 * the WWDC Liquid Glass frames.
 */
internal val PILL_INSET = 4.dp

/**
 * The tab bar's margin at either side of the screen: Apple's is 19pt, wider
 * than the page's own [BAR_GUTTER].
 */
internal val NAV_BAR_GUTTER = 19.dp

/**
 * How far above [floatingBarInsets] the bar sits: on a gesture inset of 15dp
 * this puts its foot 21dp off the screen's edge, where Apple's is.
 */
internal val NAV_BAR_LIFT = 6.dp

/** The gap between the tab pill and the Search circle beside it. */
private val TAB_SEARCH_GAP = 10.dp

/**
 * How far the bars stand off the bottom edge: the system navigation bar's
 * inset, but never less than a gesture bar's.
 *
 * With the gesture hint (or the navigation bar itself) switched off that inset
 * is zero, and the tab bar dropped onto the screen's very edge. It keeps the
 * height it has with the hint showing instead. Three-button navigation is
 * taller than the floor, so it still wins there.
 */
internal val floatingBarInsets: WindowInsets
    @Composable get() = WindowInsets.navigationBars.union(WindowInsets(bottom = GESTURE_BAR_FLOOR))

/** A gesture bar's inset — 45px at the test phone's 3x density. */
private val GESTURE_BAR_FLOOR = 15.dp

/**
 * Each tab's own vertical padding. With [PILL_INSET] and [TAB_ICON_SIZE] it
 * brings the bar to 62dp; the pill has no height of its own.
 */
internal val TAB_VERTICAL_PADDING = 6.5.dp

/** The gap between a tab's glyph and its label. */
internal val TAB_ICON_LABEL_GAP = 1.dp

/** A tab's glyph box: Apple's glyphs stand 21 to 24pt in a bar 61pt tall. */
internal val TAB_ICON_SIZE = 28.dp

/** A tab's label: Apple's 10pt, letter-spaced as SF Pro Text sets it. */
@Composable
internal fun tabLabelStyle(): TextStyle =
    MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, letterSpacing = 0.5.sp)

/**
 * The spring the selection capsule and the tab glyphs both travel on: one soft
 * pass beyond the mark and done, in about a third of a second.
 */
internal val GlassSpring = spring<Float>(dampingRatio = 0.72f, stiffness = 320f)

/**
 * How far the capsule draws out along its travel at full stride, and how much
 * of that is taken back out of its height. Driven off how far it still has to
 * go, so it is widest mid-trip and exactly itself once it arrives.
 */
internal const val STRETCH = 0.16f
internal const val SQUASH = 0.5f

/**
 * The app's tab bar: a pill holding every tab but the last, and the last —
 * Search — in a circle of its own beside it, as iOS 26 lays it out.
 *
 * The selected tab sits on a capsule that slides between tabs on a spring and
 * follows a finger dragged along the pill. On Search the capsule fades out of
 * the pill and the circle fills instead.
 *
 * This is the bar while Liquid Glass is off: each surface is frosted off
 * [hazeState], or filled solid under "reduce dynamic blur", and nothing here
 * moves with the page's scroll. With Liquid Glass on, MainActivity draws
 * [GlassNavBar] instead, which has the same split and folds as the page scrolls.
 */
@Composable
fun AppTabBar(
    tabs: List<BottomTab>,
    selectedIndex: Int,
    onTabSelected: (Int) -> Unit,
    hazeState: HazeState,
    modifier: Modifier = Modifier,
) {
    if (tabs.isEmpty()) return
    val pillShape = remember { RoundedCornerShape(percent = 50) }
    val useGlass = LocalLiquidGlassEnabled.current && isGlassSupported()
    val reduceAnimation by AppSettings.reduceAnimation.collectAsStateWithLifecycle()
    val motion: AnimationSpec<Float> = if (reduceAnimation) snap() else GlassSpring
    val haptics = rememberHaptics()
    val glassHaptics = rememberLiquidGlassHaptics()
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl

    val searchIndex = tabs.lastIndex
    val pillCount = searchIndex
    val searchSelected = selectedIndex == searchIndex

    // Where the capsule rests while Search holds the selection: on the tab it
    // left, so it fades out and back in place rather than sliding off the end.
    var lastPillIndex by remember { mutableIntStateOf(selectedIndex.coerceIn(0, (pillCount - 1).coerceAtLeast(0))) }
    if (!searchSelected && selectedIndex in 0 until pillCount) lastPillIndex = selectedIndex
    val anchorIndex = if (searchSelected) lastPillIndex else selectedIndex

    // A drag's offset from the anchor, in tabs.
    var dragTabs by remember { mutableFloatStateOf(0f) }
    var lastHapticTab by remember { mutableIntStateOf(anchorIndex) }
    LaunchedEffect(selectedIndex) {
        dragTabs = 0f
        lastHapticTab = anchorIndex
    }

    // Read in the draw pass only, so the slide recomposes nothing.
    val target = rememberUpdatedState(anchorIndex + dragTabs)
    val position = animateFloatAsState(target.value, motion, label = "tabCapsule")
    val capsuleAlpha = animateFloatAsState(
        targetValue = if (searchSelected) 0f else 1f,
        animationSpec = if (reduceAnimation) snap() else tween(180),
        label = "tabCapsuleAlpha",
    )

    val capsuleColor = if (useGlass) {
        glassIndicatorColor()
    } else {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
    }
    // Glass shows whatever is behind it rather than the theme's surface, so it
    // takes pure black or white; the frosted bar keeps the theme's own tints.
    val glassTint = glassContentColor()
    val selectedTint = if (useGlass) glassTint else MaterialTheme.colorScheme.primary
    val unselectedTint = if (useGlass) glassTint.copy(alpha = 0.65f) else MaterialTheme.colorScheme.onSurfaceVariant

    val currentAnchor by rememberUpdatedState(anchorIndex)
    val currentSelected by rememberUpdatedState(selectedIndex)
    val currentOnTabSelected by rememberUpdatedState(onTabSelected)
    val select: (Int) -> Unit = { index ->
        if (index != selectedIndex) {
            haptics.play(Haptic.Select)
            if (useGlass) glassHaptics()
        }
        onTabSelected(index)
    }

    Layout(
        modifier = modifier
            .windowInsetsPadding(floatingBarInsets)
            .padding(horizontal = NAV_BAR_GUTTER)
            .padding(bottom = NAV_BAR_LIFT)
            .fillMaxWidth(),
        content = {
            Box(
                Modifier
                    .tabBarSurface(pillShape, hazeState)
                    .padding(PILL_INSET),
            ) {
                Box(
                    Modifier
                        .matchParentSize()
                        .drawBehind {
                            if (pillCount <= 0) return@drawBehind
                            val alpha = capsuleAlpha.value
                            if (alpha <= 0f) return@drawBehind
                            val tabWidth = size.width / pillCount
                            val at = position.value
                            // 0 at rest, toward 1 mid-move: how far there is still to go.
                            val lag = abs(target.value - at).coerceIn(0f, 1f)
                            val width = tabWidth * (1f + lag * STRETCH)
                            val height = size.height * (1f - lag * STRETCH * SQUASH)
                            val centre = (at + 0.5f) * tabWidth
                            drawRoundRect(
                                color = capsuleColor.copy(alpha = capsuleColor.alpha * alpha),
                                topLeft = Offset(
                                    x = (if (rtl) size.width - centre else centre) - width / 2f,
                                    y = (size.height - height) / 2f,
                                ),
                                size = Size(width, height),
                                cornerRadius = CornerRadius(height / 2f),
                            )
                        },
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .pointerInput(pillCount, rtl) {
                            if (pillCount <= 0) return@pointerInput
                            // Every drag measures from the tab the capsule is
                            // on when it starts.
                            var dragged = 0f
                            var startIndex = currentAnchor
                            fun reached(): Float {
                                val tabWidth = size.width.toFloat() / pillCount
                                return (startIndex + dragged / tabWidth).coerceIn(0f, (pillCount - 1).toFloat())
                            }
                            detectHorizontalDragGestures(
                                onDragStart = {
                                    dragged = 0f
                                    startIndex = currentAnchor
                                },
                                onDragCancel = { dragTabs = 0f },
                                onDragEnd = {
                                    val landed = reached().roundToInt()
                                    if (landed != currentSelected) currentOnTabSelected(landed)
                                    dragTabs = 0f
                                },
                                onHorizontalDrag = { _, delta ->
                                    dragged += if (rtl) -delta else delta
                                    val at = reached()
                                    dragTabs = at - currentAnchor
                                    val over = at.roundToInt()
                                    if (over != lastHapticTab) {
                                        haptics.play(Haptic.Tick)
                                        lastHapticTab = over
                                    }
                                },
                            )
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    for (index in 0 until pillCount) {
                        TabBarItem(
                            tab = tabs[index],
                            selected = index == selectedIndex,
                            motion = motion,
                            selectedTint = selectedTint,
                            unselectedTint = unselectedTint,
                            onClick = { select(index) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            SearchCircle(
                tab = tabs[searchIndex],
                selected = searchSelected,
                motion = motion,
                fill = capsuleColor,
                selectedTint = selectedTint,
                unselectedTint = unselectedTint,
                onClick = { select(searchIndex) },
                modifier = Modifier.tabBarSurface(pillShape, hazeState),
            )
        },
    ) { measurables, constraints ->
        // The pill is as tall as its tabs come to, and the circle is that
        // height square: the pill takes what width the circle leaves.
        val gap = TAB_SEARCH_GAP.roundToPx()
        val height = measurables[0].minIntrinsicHeight(constraints.maxWidth)
        val circle = measurables[1].measure(Constraints.fixed(height, height))
        val pill = measurables[0].measure(
            Constraints.fixed((constraints.maxWidth - gap - height).coerceAtLeast(0), height),
        )
        layout(constraints.maxWidth, height) {
            pill.placeRelative(0, 0)
            circle.placeRelative(pill.width + gap, 0)
        }
    }
}

/**
 * One material for the pill and the circle: glass, frost or a solid fill.
 * Liquid glass brings its own rim; the other two take the plain hairline.
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
internal fun Modifier.tabBarSurface(shape: CornerBasedShape, hazeState: HazeState? = null): Modifier {
    val container = MaterialTheme.colorScheme.surface
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    val useGlass = LocalLiquidGlassEnabled.current && isGlassSupported()
    return clip(shape).then(
        when {
            useGlass -> Modifier.liquidGlass(shape)
            reduceDynamicBlur || hazeState == null -> Modifier
                .background(container)
                .border(GLASS_EDGE_WIDTH, GLASS_EDGE_COLOR, shape)
            else -> Modifier
                .optimizedHazeEffect(state = hazeState, style = HazeMaterials.regular(container))
                .border(GLASS_EDGE_WIDTH, GLASS_EDGE_COLOR, shape)
        },
    )
}

@Composable
private fun TabBarItem(
    tab: BottomTab,
    selected: Boolean,
    motion: AnimationSpec<Float>,
    selectedTint: Color,
    unselectedTint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The same spring the capsule rides, so the glyph arriving and the capsule
    // arriving are one movement.
    val scale = animateFloatAsState(if (selected) 1.08f else 1f, motion, label = "tabScale")
    val tint by animateColorAsState(
        targetValue = if (selected) selectedTint else unselectedTint,
        animationSpec = tween(200),
        label = "tabTint",
    )
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(percent = 50))
            .selectable(
                selected = selected,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Tab,
                onClick = onClick,
            )
            .padding(vertical = TAB_VERTICAL_PADDING),
    ) {
        Icon(
            imageVector = tab.icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier
                .size(TAB_ICON_SIZE)
                .graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                },
        )
        Spacer(Modifier.height(TAB_ICON_LABEL_GAP))
        Text(
            text = tab.label,
            style = tabLabelStyle(),
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The last tab, alone in its circle: a glyph and no label. */
@Composable
private fun SearchCircle(
    tab: BottomTab,
    selected: Boolean,
    motion: AnimationSpec<Float>,
    fill: Color,
    selectedTint: Color,
    unselectedTint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val lit = animateFloatAsState(if (selected) 1f else 0f, motion, label = "searchFill")
    val tint by animateColorAsState(
        targetValue = if (selected) selectedTint else unselectedTint,
        animationSpec = tween(200),
        label = "searchTint",
    )
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .selectable(
                selected = selected,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Tab,
                onClick = onClick,
            )
            .drawBehind {
                val amount = lit.value.coerceIn(0f, 1f)
                if (amount <= 0f) return@drawBehind
                // Grows out from the glyph as it fills, to the pill's own inset.
                val radius = (size.minDimension / 2f - PILL_INSET.toPx()) * (0.8f + 0.2f * amount)
                drawCircle(color = fill.copy(alpha = fill.alpha * amount), radius = radius)
            },
    ) {
        Icon(
            imageVector = tab.icon,
            contentDescription = tab.label,
            tint = tint,
            modifier = Modifier.size(TAB_ICON_SIZE),
        )
    }
}
