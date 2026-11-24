package com.music.bitchord.ui.replay

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.music.bitchord.R
import com.music.bitchord.data.stats.RankedEntry
import java.util.Locale

/**
 * Genre Spectrum visualizer card: displays top genre strength and animated proportional
 * frequency bars across up to 18 listening genres with RTL awareness and waterfall contrast.
 */
@Composable
fun GenreSpectrumCard(
    genres: List<RankedEntry>,
    totalMs: Long,
    dark: Boolean,
    modifier: Modifier = Modifier,
) {
    val leader = genres.firstOrNull() ?: return
    val visible = genres.take(18)
    var revealed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { revealed = true }

    val topMs = leader.ms.coerceAtLeast(1L).toFloat()
    val heights = List(18) { index ->
        animateFloatAsState(
            targetValue = if (revealed) {
                (visible.getOrNull(index)?.ms?.toFloat() ?: 0f) / topMs
            } else 0f,
            animationSpec = tween(durationMillis = 650, delayMillis = index * 20),
            label = "genreSpectrumHeight$index",
        )
    }
    val cellWidth = animateFloatAsState(
        targetValue = 1f / visible.size.coerceAtLeast(1),
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "genreSpectrumSpacing",
    )

    val surfaceColor = if (dark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.04f)
    val cardBorder = if (dark) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.08f)
    val textColor = if (dark) Color.White else Color.Black
    val subtextColor = if (dark) Color.White.copy(alpha = 0.65f) else Color.Black.copy(alpha = 0.65f)
    val primaryColor = MaterialTheme.colorScheme.primary

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = surfaceColor,
        border = BorderStroke(1.dp, cardBorder),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.top_genre),
                        style = MaterialTheme.typography.labelMedium,
                        color = subtextColor,
                    )
                    Text(
                        text = leader.title.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() },
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = textColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val pct = if (totalMs > 0) ((leader.ms.toFloat() / totalMs) * 100f).toInt().coerceIn(1, 100) else 100
                Text(
                    text = "$pct%",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.W800,
                    color = primaryColor,
                )
            }

            GenreSpectrumCanvas(
                heights = heights,
                cellWidth = cellWidth.value,
                count = visible.size,
                primaryColor = primaryColor,
                secondaryColor = subtextColor.copy(alpha = 0.45f),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
            )
        }
    }
}

/**
 * Proportional rounded waterfall bars for genre distributions.
 */
@Composable
fun GenreSpectrumCanvas(
    heights: List<State<Float>>,
    cellWidth: Float,
    count: Int,
    primaryColor: Color,
    secondaryColor: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        clipRect {
            val cell = size.width * cellWidth
            val barWidth = (cell - 5.dp.toPx()).coerceAtLeast(3.dp.toPx())
            val cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)

            for (i in 0 until count) {
                val h = size.height * (heights.getOrNull(i)?.value ?: 0f).coerceIn(0.04f, 1f)
                val left = i * cell + (cell - barWidth) / 2f
                val drawLeft = if (layoutDirection == LayoutDirection.Rtl) size.width - left - barWidth else left
                val color = if (i == 0) primaryColor else secondaryColor.copy(
                    alpha = (0.65f - (i * 0.025f)).coerceAtLeast(0.18f)
                )

                drawRoundRect(
                    color = color,
                    topLeft = Offset(drawLeft, size.height - h),
                    size = Size(barWidth, h),
                    cornerRadius = cornerRadius,
                )
            }
        }
    }
}
