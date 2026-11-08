package com.music.bitchord.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.music.bitchord.sharedui.resources.*
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.collectLatest

/**
 * How often the legacy mesh re-reads the colours of a playing Canvas clip.
 *
 * Every three seconds, with the mesh's own fade easing each read into the last
 * so the backdrop arrives at its new colour rather than cutting to it. The read is
 * the expensive half — a texture readback off the GPU — and this is the number
 * that decides how many of them there are; the fade is the cheap half and is
 * over well inside the gap, which leaves the backdrop still for most of it.
 */
internal const val MESH_REFRESH_MS = 3_000L

/** How long the mesh's seam takes to reach a clip's edge, in step with the clip's own fade. */
internal const val MESH_SEAM_MOVE_MS = 320

/**
 * The widest portrait clip that still gets [PortraitClipScrim]. Narrower than a
 * 3:4 cover, which stops above the controls and has the mesh to stand on there.
 */
internal const val PORTRAIT_SCRIM_MAX_ASPECT = 0.7f

/**
 * The default theme's backdrop — see [ArtworkMeshBackdrop] — in a scope of its
 * own: a clip's new frame every few seconds, and the seam moving, reach only
 * this rather than the whole of the player.
 */
@Composable
internal fun PlayerMeshBackdrop(
    trackKey: String,
    imageUrl: String?,
    canvasFrame: () -> ImageBitmap?,
    seamTarget: () -> Dp,
    alpha: () -> Float,
) {
    val mesh = key(trackKey) { rememberArtworkMesh(imageUrl, canvasFrame(), ART_PX) }
    val currentSeamTarget by rememberUpdatedState(seamTarget)
    val seam = remember { Animatable(Snapshot.withoutReadObservation { seamTarget() }, Dp.VectorConverter) }
    // Glides to a clip's edge as the clip fades in, and is put straight where
    // the first measure says: nothing should be seen settling as the player opens.
    LaunchedEffect(seam) {
        snapshotFlow { currentSeamTarget() }.collectLatest { target ->
            if (seam.value == 0.dp || target == 0.dp) {
                seam.snapTo(target)
            } else {
                seam.animateTo(target, tween(MESH_SEAM_MOVE_MS, easing = FastOutSlowInEasing))
            }
        }
    }
    ArtworkMeshBackdrop(
        mesh = mesh,
        seam = { seam.value },
        modifier = Modifier.graphicsLayer { this.alpha = alpha() },
    )
}

/** The legacy theme's backdrop, in a scope of its own for the same reason. */
@Composable
internal fun LegacyMeshBackdrop(
    imageUrl: String?,
    canvasFrame: () -> ImageBitmap?,
    trackKey: String,
    modifier: Modifier,
) {
    MeshGradientBackground(
        palette = rememberArtworkColors(imageUrl, canvasFrame()),
        trackKey = trackKey,
        modifier = modifier,
    )
}

/** [image] filling this scope, centred and cropped as [ContentScale.Crop] would. */
internal fun DrawScope.drawCropped(image: ImageBitmap) {
    if (size.width <= 0f || size.height <= 0f) return
    val scale = maxOf(size.width / image.width, size.height / image.height)
    val srcWidth = (size.width / scale).roundToInt().coerceIn(1, image.width)
    val srcHeight = (size.height / scale).roundToInt().coerceIn(1, image.height)
    drawImage(
        image = image,
        srcOffset = IntOffset((image.width - srcWidth) / 2, (image.height - srcHeight) / 2),
        srcSize = IntSize(srcWidth, srcHeight),
        dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
    )
}

/** An animated cover's frame, and where in its clip it was taken. */
@Immutable
internal class CanvasStill(val url: String, val image: ImageBitmap, val positionMs: Long)

/**
 * The animated cover's frame from where it last held still for the player
 * moving, kept outside the player so it outlives the player closing. One at a
 * time: it is only ever wanted for the clip that is on screen.
 */
internal object CanvasStills {
    var current by mutableStateOf<CanvasStill?>(null)

    /** Whether [current] is still the clip's frame, rather than one it has played on from. */
    var fresh by mutableStateOf(false)
}
