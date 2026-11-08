package com.music.bitchord.ui.player.lyrics

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.bitchord.sharedui.resources.*
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign
import org.jetbrains.compose.resources.stringResource

/**
 * How long the lyric panel waits, after the reader's hand leaves it, before it
 * goes back to following the song. The pill's ring counts the same time down.
 */
internal const val LYRICS_RESUME_MS = 4_000

/**
 * The spring a jump back to the sung line travels on: a seek, a long
 * instrumental scrolled past, or the reader tapping "Jump to current line".
 *
 * Only jumps. The ordinary handover from one line to the next keeps its tween,
 * timed to the run-up to the line, because each row trails the list on that
 * same clock — a spring there would leave the rows behind it.
 */
internal val LyricJumpSpring: AnimationSpec<Float> = spring(dampingRatio = 0.85f, stiffness = 350f)

/** The most a jump springs through; anything further is cut to this first. */
private const val JUMP_SPRING_VIEWPORTS = 1.5f

/**
 * Springs the list until item [index] sits where [targetOffset] says a row
 * of its size should, in the list's own item offsets.
 *
 * How far that is can only be estimated until the row is laid out, so the
 * travel is judged off the rows on screen and the landing is put right once
 * the row is there. A jump of more than [JUMP_SPRING_VIEWPORTS] screens is
 * cut to that distance first: springing through a whole song is a blur, and
 * a long one is still settling after the singer has moved on.
 */
internal suspend fun LazyListState.springToItem(index: Int, targetOffset: (size: Int) -> Int) {
    val seen = layoutInfo.visibleItemsInfo
    if (seen.size < 2) {
        animateScrollToItem(index, scrollOffset = -targetOffset(0))
    } else {
        val first = seen.first()
        val last = seen.last()
        val pitch = (last.offset - first.offset).toFloat() / (last.index - first.index).coerceAtLeast(1)
        var travel = first.offset + (index - first.index) * pitch - targetOffset(pitch.roundToInt())
        val reach = layoutInfo.viewportSize.height * JUMP_SPRING_VIEWPORTS
        if (abs(travel) > reach && pitch > 0f) {
            val rowsLeft = (reach / pitch).roundToInt()
            scrollToItem((index - sign(travel).toInt() * rowsLeft).coerceAtLeast(0), scrollOffset = -targetOffset(0))
            val now = layoutInfo.visibleItemsInfo.firstOrNull() ?: return
            travel = now.offset + (index - now.index) * pitch - targetOffset(pitch.roundToInt())
        }
        animateScrollBy(travel, LyricJumpSpring)
    }
    // Put right now that the row's real size is known.
    val landed = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
    if (landed == null) {
        animateScrollToItem(index, scrollOffset = -targetOffset(0))
    } else {
        val miss = (landed.offset - targetOffset(landed.size)).toFloat()
        if (abs(miss) >= 1f) animateScrollBy(miss, LyricJumpSpring)
    }
}

/**
 * The pill that floats over the lyrics while the reader is browsing them by
 * hand: a tap goes straight back to the sung line, and the ring around its
 * dot runs out over [LYRICS_RESUME_MS] to show when the panel will go back on
 * its own.
 *
 * [remaining] is read only while drawing the ring, so the countdown repaints
 * the ring and nothing else.
 */
@Composable
internal fun LyricsJumpPill(
    visible: Boolean,
    remaining: () -> Float,
    onJump: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn() + scaleIn(initialScale = 0.9f),
        exit = fadeOut() + scaleOut(targetScale = 0.9f),
    ) {
        val ringWidth = with(LocalDensity.current) { 1.6.dp.toPx() }
        val ring = remember(ringWidth) { Stroke(width = ringWidth, cap = StrokeCap.Round) }
        Row(
            modifier = Modifier
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.32f))
                .background(Color.White.copy(alpha = 0.12f))
                .clickable(onClick = onJump)
                .padding(start = 10.dp, end = 14.dp, top = 7.dp, bottom = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Canvas(Modifier.size(13.dp)) {
                drawCircle(color = Color.White.copy(alpha = 0.22f), style = ring)
                drawArc(
                    color = Color.White.copy(alpha = 0.9f),
                    startAngle = -90f,
                    sweepAngle = 360f * remaining().coerceIn(0f, 1f),
                    useCenter = false,
                    style = ring,
                )
            }
            Text(
                text = stringResource(Res.string.jump_to_current_line),
                color = Color.White.copy(alpha = 0.92f),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
