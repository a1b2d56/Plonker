package com.music.bitchord.ui.icons
import kotlin.math.sin
import kotlin.math.cos
import kotlin.math.PI

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.unit.dp

/**
 * Thick-stroke, round-capped icons in the spirit of Telegram's modern icon set.
 * Drawn as strokes so the 2.2px weight + round joins read as a single polished
 * family. Tint is applied by [androidx.compose.material3.Icon].
 *
 * [Home] is the exception and says why it is one.
 */
object BitChordIcons {

    private const val STROKE = 2.2f
    private val stroke = SolidColor(Color.Black)

    /** Compact list and quotation-bubble shapes used by the player controls. */
    val Queue: ImageVector by lazy {
        ImageVector.Builder(
            name = "bc_queue", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round) {
                for (y in listOf(6f, 12f, 18f)) {
                    moveTo(8f, y)
                    lineTo(21f, y)
                }
            }
            path(fill = stroke) {
                for (y in listOf(6f, 12f, 18f)) {
                    moveTo(4.35f, y)
                    arcToRelative(1.25f, 1.25f, 0f, true, true, -2.5f, 0f)
                    arcToRelative(1.25f, 1.25f, 0f, true, true, 2.5f, 0f)
                    close()
                }
            }
        }.build()
    }

    val LyricsQuote: ImageVector get() = PlayerLyrics

    val Play: ImageVector by lazy {
        ImageVector.Builder(
            name = "bc_play",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                stroke = stroke,
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = stroke,
            ) {
                moveTo(6.8f, 4.8f)
                lineTo(19.2f, 12f)
                lineTo(6.8f, 19.2f)
                close()
            }
        }.build()
    }

    /**
     * The one solid icon in the set, and the tab bar is why.
     *
     * Selection here is carried by tint alone — see `GlassNavBar`, where the
     * unselected tabs are the same glyph at 65% — so the home tab is the anchor
     * of the bar rather than a state of it, and a filled silhouette reads as
     * that anchor at 24dp where a four-stroke outline reads as detail.
     *
     * Filled *and* stroked, with the same path. The outline of a house is all
     * corners — an apex, two shoulders, two door jambs — and stroking the fill
     * with a round-joined pen is what rounds every one of them at once, for a
     * geometry that stays nine straight lines. The numbers below are therefore
     * inset by half the stroke: the pen puts back what they give up, and the
     * door notch is drawn oversized for the same reason, since the pen closes
     * in on it from both jambs.
     */
    val Home: ImageVector by lazy {
        ImageVector.Builder(
            name = "bc_home",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                fill = stroke,
                stroke = stroke,
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                // Roof, flush with the walls rather than overhanging them: an
                // eave narrower than the pen it is drawn with comes out as a
                // blob on the corner instead of a ledge.
                moveTo(12f, 4.05f)
                lineTo(19.75f, 11.35f)
                // Right wall, down to the floor
                lineTo(19.75f, 19.65f)
                // Door, cut up out of the bottom edge
                lineTo(14.95f, 19.65f)
                lineTo(14.95f, 15.6f)
                lineTo(9.05f, 15.6f)
                lineTo(9.05f, 19.65f)
                // Left wall, back up to the apex
                lineTo(4.25f, 19.65f)
                lineTo(4.25f, 11.35f)
                close()
            }
        }.build()
    }

    /**
     * The phone tab bar's Explore, Library and Search glyphs: solid-weight
     * iOS shapes rather than this file's stroked family, taken from the
     * outlines of Flutter's cupertino_icons font (MIT) — square_grid_2x2
     * with its four counters dropped so the squares fill solid,
     * square_stack_fill and search.
     *
     * Each is the glyph laid out the way the font sets it: the 512-unit em
     * box is the viewport, the baseline sits at the font's ascent share of
     * it, and the advance is centred. So they keep the font's own optical
     * size and weight against one another at any icon size.
     */
    val TabExplore: ImageVector by lazy {
        fontGlyph(
            "bc_tab_explore",
            "M99.5 230.24Q58.5 230.24 58.5 188.24V93.24Q58.5 51.24 99.5 51.24H197.5Q238.5 51.24 238.5 93.24V188.24Q238.5 230.24 197.5 230.24Z" +
                "M314.5 230.24Q273.5 230.24 273.5 188.24V93.24Q273.5 51.24 314.5 51.24H412.5Q453.5 51.24 453.5 93.24V188.24Q453.5 230.24 412.5 230.24Z" +
                "M99.5 445.24Q58.5 445.24 58.5 403.24V307.24Q58.5 266.24 99.5 266.24H197.5Q238.5 266.24 238.5 307.24V403.24Q238.5 445.24 197.5 445.24Z" +
                "M314.5 445.24Q273.5 445.24 273.5 403.24V307.24Q273.5 266.24 314.5 266.24H412.5Q453.5 266.24 453.5 307.24V403.24Q453.5 445.24 412.5 445.24Z",
        )
    }

    /**
     * Two units lower than the font sets it: the back card's top edge rises
     * just past the em box, and the viewport would clip it flat.
     */
    val TabLibrary: ImageVector by lazy {
        fontGlyph(
            "bc_tab_library",
            "M154 27.24Q156 0.24 185 0.24H322Q352 0.24 353 27.24Z" +
                "M116 87.24Q121 56.24 150 56.24H356Q385 56.24 390 87.24Z" +
                "M133 500.24Q66 500.24 66 434.24V187.24Q66 121.24 133 121.24H378Q446 121.24 446 187.24V434.24Q446 500.24 385 500.24Z",
        )
    }

    val TabSearch: ImageVector by lazy {
        fontGlyph(
            "bc_tab_search",
            "M219 374.24Q151 374.24 102.5 325.74Q54 277.24 54 209.24Q54 141.24 102.5 92.74Q151 44.24 219 44.24Q287 44.24 335.5 92.74Q384 141.24 384 209.24Q384 264.24 350 309.24L451 410.24Q458 417.24 458 427.24Q458 438.24 451 445.24Q444 452.24 434 452.24Q423 452.24 416 445.24L315 343.24Q271 374.24 219 374.24Z" +
                "M128 300.24Q166 338.24 219 338.24Q272 338.24 310 300.24Q348 262.24 348 209.24Q348 156.24 310 118.24Q272 80.24 219 80.24Q166 80.24 128 118.24Q90 156.24 90 209.24Q90 262.24 128 300.24Z",
        )
    }

    /** A filled glyph outline in the 512-unit em box — see [TabExplore]. */
    private fun fontGlyph(name: String, pathData: String): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 512f, viewportHeight = 512f,
        ).addPath(pathData = addPathNodes(pathData), fill = stroke).build()

    val Search: ImageVector by lazy {
        ImageVector.Builder(
            name = "bc_search",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                stroke = stroke,
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                // Lens (full circle from two arcs)
                moveTo(4.6f, 11f)
                arcToRelative(6.4f, 6.4f, 0f, isMoreThanHalf = true, isPositiveArc = true, 12.8f, 0f)
                arcToRelative(6.4f, 6.4f, 0f, isMoreThanHalf = true, isPositiveArc = true, -12.8f, 0f)
                // Handle
                moveTo(15.9f, 15.9f)
                lineTo(20.4f, 20.4f)
            }
        }.build()
    }

    val Explore: ImageVector by lazy {
        ImageVector.Builder(
            name = "bc_explore",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                stroke = stroke,
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                // Compass dial
                moveTo(3.4f, 12f)
                arcToRelative(8.6f, 8.6f, 0f, isMoreThanHalf = true, isPositiveArc = true, 17.2f, 0f)
                arcToRelative(8.6f, 8.6f, 0f, isMoreThanHalf = true, isPositiveArc = true, -17.2f, 0f)
                // Needle
                moveTo(15.4f, 8.6f)
                lineTo(13.6f, 13.6f)
                lineTo(8.6f, 15.4f)
                lineTo(10.4f, 10.4f)
                close()
            }
        }.build()
    }

    val Shuffle: ImageVector by lazy {
        ImageVector.Builder(
            name = "bc_shuffle",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                stroke = stroke,
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                // Strand that crosses downwards, with its arrow head
                moveTo(3.4f, 7.4f); lineTo(7f, 7.4f); lineTo(16.6f, 16.6f); lineTo(20.6f, 16.6f)
                moveTo(18.1f, 14.1f); lineTo(20.6f, 16.6f); lineTo(18.1f, 19.1f)
                // Strand that crosses upwards, broken around the intersection
                moveTo(3.4f, 16.6f); lineTo(7f, 16.6f); lineTo(9.8f, 13.9f)
                moveTo(13.9f, 10.1f); lineTo(16.6f, 7.4f); lineTo(20.6f, 7.4f)
                moveTo(18.1f, 4.9f); lineTo(20.6f, 7.4f); lineTo(18.1f, 9.9f)
            }
        }.build()
    }

    val Repeat: ImageVector by lazy { repeatLoop("bc_repeat") }

    /**
     * Two straight runs joined by semicircles, with the arrow heads lying flat
     * at the ends of the straights. Putting them on the curves instead — as a
     * first pass did — makes the glyph read as a refresh/sync symbol.
     */
    private fun repeatLoop(name: String): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                stroke = stroke,
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(8.6f, 7.6f)
                lineTo(15.4f, 7.6f)
                arcToRelative(4.4f, 4.4f, 0f, isMoreThanHalf = false, isPositiveArc = true, 0f, 8.8f)
                lineTo(8.6f, 16.4f)
                arcToRelative(4.4f, 4.4f, 0f, isMoreThanHalf = false, isPositiveArc = true, 0f, -8.8f)
                close()
                // Direction of travel: right along the top, left along the bottom.
                moveTo(13.5f, 5.7f); lineTo(15.4f, 7.6f); lineTo(13.5f, 9.5f)
                moveTo(10.5f, 14.5f); lineTo(8.6f, 16.4f); lineTo(10.5f, 18.3f)
            }
        }.build()

    val RepeatOne: ImageVector by lazy {
        ImageVector.Builder(
            name = "bc_repeat_one",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                stroke = stroke,
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(8.6f, 7.6f)
                lineTo(15.4f, 7.6f)
                arcToRelative(4.4f, 4.4f, 0f, isMoreThanHalf = false, isPositiveArc = true, 0f, 8.8f)
                lineTo(8.6f, 16.4f)
                arcToRelative(4.4f, 4.4f, 0f, isMoreThanHalf = false, isPositiveArc = true, 0f, -8.8f)
                close()
                moveTo(13.5f, 5.7f); lineTo(15.4f, 7.6f); lineTo(13.5f, 9.5f)
                moveTo(10.5f, 14.5f); lineTo(8.6f, 16.4f); lineTo(10.5f, 18.3f)
                // Number 1 in the middle
                moveTo(11.2f, 10.5f); lineTo(12f, 9.8f); lineTo(12f, 14.2f)
            }
        }.build()
    }

    val Lock: ImageVector by lazy {
        ImageVector.Builder(
            name = "bc_lock", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(stroke = stroke, strokeLineWidth = STROKE, strokeLineCap = StrokeCap.Round) {
                moveTo(6.5f, 11f); lineTo(17.5f, 11f)
                arcToRelative(2f, 2f, 0f, false, true, 2f, 2f)
                lineTo(19.5f, 18f)
                arcToRelative(2f, 2f, 0f, false, true, -2f, 2f)
                lineTo(6.5f, 20f)
                arcToRelative(2f, 2f, 0f, false, true, -2f, -2f)
                lineTo(4.5f, 13f)
                arcToRelative(2f, 2f, 0f, false, true, 2f, -2f)
                close()
                moveTo(8f, 11f); lineTo(8f, 7.5f)
                arcToRelative(4f, 4f, 0f, false, true, 8f, 0f)
                lineTo(16f, 11f)
            }
        }.build()
    }

    val LockOpen: ImageVector by lazy {
        ImageVector.Builder(
            name = "bc_lock_open", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(stroke = stroke, strokeLineWidth = STROKE, strokeLineCap = StrokeCap.Round) {
                moveTo(6.5f, 11f); lineTo(17.5f, 11f)
                arcToRelative(2f, 2f, 0f, false, true, 2f, 2f)
                lineTo(19.5f, 18f)
                arcToRelative(2f, 2f, 0f, false, true, -2f, 2f)
                lineTo(6.5f, 20f)
                arcToRelative(2f, 2f, 0f, false, true, -2f, -2f)
                lineTo(4.5f, 13f)
                arcToRelative(2f, 2f, 0f, false, true, 2f, -2f)
                close()
                moveTo(8f, 11f); lineTo(8f, 7.5f)
                arcToRelative(4f, 4f, 0f, false, true, 8f, 0f)
                lineTo(16f, 5.5f)
            }
        }.build()
    }

    /** AutoPlay's lemniscate. */
    val Infinity: ImageVector by lazy {
        ImageVector.Builder(
            name = "bc_infinity",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                stroke = stroke,
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(12f, 12f)
                curveTo(10.1f, 9.1f, 8.7f, 8f, 7.1f, 8f)
                arcToRelative(4f, 4f, 0f, isMoreThanHalf = false, isPositiveArc = false, 0f, 8f)
                curveTo(8.7f, 16f, 10.1f, 14.9f, 12f, 12f)
                curveTo(13.9f, 9.1f, 15.3f, 8f, 16.9f, 8f)
                arcToRelative(4f, 4f, 0f, isMoreThanHalf = false, isPositiveArc = true, 0f, 8f)
                curveTo(15.3f, 16f, 13.9f, 14.9f, 12f, 12f)
            }
        }.build()
    }

    /** Beamed pair of notes, for instrumental stretches in the lyrics. */
    val MusicNote: ImageVector by lazy {
        ImageVector.Builder(
            name = "bc_music_note",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            // Heads are solid; stems and beam keep the family's stroke weight.
            path(fill = stroke) {
                moveTo(4.2f, 17.7f)
                arcToRelative(2.9f, 2.5f, 0f, isMoreThanHalf = true, isPositiveArc = true, 5.8f, 0f)
                arcToRelative(2.9f, 2.5f, 0f, isMoreThanHalf = true, isPositiveArc = true, -5.8f, 0f)
                close()
                moveTo(14.2f, 15.9f)
                arcToRelative(2.9f, 2.5f, 0f, isMoreThanHalf = true, isPositiveArc = true, 5.8f, 0f)
                arcToRelative(2.9f, 2.5f, 0f, isMoreThanHalf = true, isPositiveArc = true, -5.8f, 0f)
                close()
            }
            path(
                stroke = stroke,
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(10f, 17.7f); lineTo(10f, 6.7f)
                moveTo(20f, 15.9f); lineTo(20f, 4.9f)
                moveTo(10f, 6.7f); lineTo(20f, 4.9f)
            }
        }.build()
    }

    /**
     * Two beamed notes, filled, sitting level — what the lyrics show through an
     * instrumental section. Apart from [MusicNote], which stays the artwork
     * placeholder elsewhere.
     */
    val Instrumental: ImageVector by lazy {
        ImageVector.Builder(
            name = "bc_instrumental",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).addPath(
            pathData = addPathNodes(
                "M19.952 1.651a.75.75 0 0 1 .298.599V16.303a3 3 0 0 1-2.176 2.884l-1.32.377a2.553 2.553 0 1 1-1.403-4.909" +
                    "l2.311-.66a1.5 1.5 0 0 0 1.088-1.442V6.994l-9 2.572v9.737a3 3 0 0 1-2.176 2.884l-1.32.377" +
                    "a2.553 2.553 0 1 1-1.402-4.909l2.31-.66a1.5 1.5 0 0 0 1.088-1.442V5.25a.75.75 0 0 1 .544-.721" +
                    "l10.5-3a.75.75 0 0 1 .658.122Z",
            ),
            pathFillType = PathFillType.EvenOdd,
            fill = stroke,
        ).build()
    }

    /** Speech bubble with two lines of words. */
    val Lyrics: ImageVector by lazy {
        ImageVector.Builder(
            name = "bc_lyrics",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                stroke = stroke,
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(6.2f, 4.6f)
                lineTo(17.8f, 4.6f)
                arcToRelative(2.8f, 2.8f, 0f, isMoreThanHalf = false, isPositiveArc = true, 2.8f, 2.8f)
                lineTo(20.6f, 13.6f)
                arcToRelative(2.8f, 2.8f, 0f, isMoreThanHalf = false, isPositiveArc = true, -2.8f, 2.8f)
                lineTo(10.6f, 16.4f)
                lineTo(6.8f, 19.6f)
                lineTo(6.8f, 16.4f)
                lineTo(6.2f, 16.4f)
                arcToRelative(2.8f, 2.8f, 0f, isMoreThanHalf = false, isPositiveArc = true, -2.8f, -2.8f)
                lineTo(3.4f, 7.4f)
                arcToRelative(2.8f, 2.8f, 0f, isMoreThanHalf = false, isPositiveArc = true, 2.8f, -2.8f)
                close()
                moveTo(7.6f, 9f); lineTo(16.4f, 9f)
                moveTo(7.6f, 12.1f); lineTo(13.2f, 12.1f)
            }
        }.build()
    }

    /** Plain chevron — a disclosure hint, not a directional arrow. */
    val ChevronRight: ImageVector by lazy {
        ImageVector.Builder(
            name = "bc_chevron_right",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                stroke = stroke,
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(9.5f, 6.2f)
                lineTo(15.3f, 12f)
                lineTo(9.5f, 17.8f)
            }
        }.build()
    }

    /**
     * The player's like control, in two weights.
     *
     * Filled rather than merely tinted when set: the player draws every glyph
     * white on artwork, where a colour change alone is the one signal the
     * backdrop can swallow. A shape change survives any album cover.
     */
    val Heart: ImageVector by lazy { heart("bc_heart", filled = false) }

    val HeartFilled: ImageVector by lazy { heart("bc_heart_filled", filled = true) }

    private fun heart(name: String, filled: Boolean): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                stroke = stroke,
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = if (filled) stroke else null,
            ) {
                // Two lobes meeting at the top notch, falling to a single point.
                moveTo(12f, 20f)
                curveTo(12f, 20f, 3.2f, 14.6f, 3.2f, 8.9f)
                arcToRelative(4.5f, 4.5f, 0f, isMoreThanHalf = false, isPositiveArc = true, 8.8f, -1.5f)
                arcToRelative(4.5f, 4.5f, 0f, isMoreThanHalf = false, isPositiveArc = true, 8.8f, 1.5f)
                curveTo(20.8f, 14.6f, 12f, 20f, 12f, 20f)
                close()
            }
        }.build()

    /**
     * Favorite, the way Apple Music draws it: a five-point star whose tips
     * are softened by the round stroke join rather than cut to a point.
     * Filled when set, for the reason [Heart] gives.
     */
    val Star: ImageVector by lazy { star("bc_star", filled = false) }

    val StarFilled: ImageVector by lazy { star("bc_star_filled", filled = true) }

    private fun star(name: String, filled: Boolean): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                stroke = stroke,
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = if (filled) stroke else null,
            ) {
                // Outer radius 9, inner 0.44 of it, centred on its own bounds
                // so the shorter lower half doesn't sit the star high.
                moveTo(12f, 3.86f)
                lineTo(14.33f, 9.66f)
                lineTo(20.56f, 10.08f)
                lineTo(15.77f, 14.08f)
                lineTo(17.29f, 20.14f)
                lineTo(12f, 16.82f)
                lineTo(6.71f, 20.14f)
                lineTo(8.23f, 14.08f)
                lineTo(3.44f, 10.08f)
                lineTo(9.67f, 9.66f)
                close()
            }
        }.build()

    /** Adding something — a new playlist, on the library shelf. */
    val Plus: ImageVector by lazy {
        ImageVector.Builder(
            name = "bc_plus",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                stroke = stroke,
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(12f, 5f); lineTo(12f, 19f)
                moveTo(5f, 12f); lineTo(19f, 12f)
            }
        }.build()
    }

    /**
     * A tick — the other half of [Plus]. Saving something to the library swaps
     * one for the other in place, so the two are drawn on the same 14-unit span
     * and at the same weight; a tick sized to its own bounding box would jump.
     */
    val Check: ImageVector by lazy {
        ImageVector.Builder(
            name = "bc_check",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                stroke = stroke,
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(5f, 12.8f); lineTo(9.6f, 17.4f); lineTo(19f, 6.9f)
            }
        }.build()
    }

    /** Arrow pointing down into a tray — offline download. */
    val Download: ImageVector by lazy {
        ImageVector.Builder(
            name = "bc_download",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                stroke = stroke,
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                // Vertical stem
                moveTo(12f, 4.5f); lineTo(12f, 15.5f)
                // Arrow head
                moveTo(7.5f, 11f); lineTo(12f, 15.5f); lineTo(16.5f, 11f)
                // Tray base
                moveTo(4.5f, 18f); lineTo(19.5f, 18f)
            }
        }.build()
    }

    /**
     * Clock face with two hands — a download asked for but not yet on disk.
     *
     * The dial is the same 8.6 radius as [Explore]'s, so the two sit at the same
     * optical weight when they appear in the same row of header buttons. The
     * hands are one polyline through the centre rather than two strokes: the
     * round join is then the pivot, which is what stops the middle reading as a
     * pair of lines that happen to cross.
     */
    val Clock: ImageVector by lazy {
        ImageVector.Builder(
            name = "bc_clock",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                stroke = stroke,
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                // Dial (full circle from two arcs)
                moveTo(3.4f, 12f)
                arcToRelative(8.6f, 8.6f, 0f, isMoreThanHalf = true, isPositiveArc = true, 17.2f, 0f)
                arcToRelative(8.6f, 8.6f, 0f, isMoreThanHalf = true, isPositiveArc = true, -17.2f, 0f)
                // Minute hand up, hour hand down to the right
                moveTo(12f, 7.4f); lineTo(12f, 12f); lineTo(15.4f, 13.8f)
            }
        }.build()
    }

    /**
     * A pushpin, not a map marker: a round head with a straight needle
     * kicked out to one side, the way it sits once it's stuck into something.
     * A teardrop reads as "location" the moment it's this small — the two
     * disjoint strokes here are what keep it read as "pinned" instead.
     */
    val Pin: ImageVector by lazy {
        ImageVector.Builder(
            name = "bc_pin",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                stroke = stroke,
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                // Round head.
                moveTo(9.6f, 8.4f)
                arcToRelative(3.4f, 3.4f, 0f, isMoreThanHalf = true, isPositiveArc = true, 6.8f, 0f)
                arcToRelative(3.4f, 3.4f, 0f, isMoreThanHalf = true, isPositiveArc = true, -6.8f, 0f)
                // Needle, kicked out from the underside of the head to a point.
                moveTo(10.8f, 10.8f)
                lineTo(5.6f, 19.6f)
            }
        }.build()
    }

    /** Speed gauge used by high-performance display settings. */
    val Performance: ImageVector by lazy {
        ImageVector.Builder(
            name = "bc_performance",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                stroke = stroke,
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                // Open gauge, leaving room for the base line.
                moveTo(4.1f, 16.8f)
                arcToRelative(8.4f, 8.4f, 0f, isMoreThanHalf = true, isPositiveArc = true, 15.8f, 0f)
                // Needle and hub.
                moveTo(12f, 15.8f)
                lineTo(16.7f, 9.3f)
                moveTo(10.9f, 16.9f)
                arcToRelative(1.1f, 1.1f, 0f, isMoreThanHalf = true, isPositiveArc = true, 2.2f, 0f)
                arcToRelative(1.1f, 1.1f, 0f, isMoreThanHalf = true, isPositiveArc = true, -2.2f, 0f)
                // Stable baseline.
                moveTo(5.4f, 19.5f)
                lineTo(18.6f, 19.5f)
            }
        }.build()
    }

    /** Four frame-time bars, used by refresh-rate selection. */
    val FrameRate: ImageVector by lazy {
        ImageVector.Builder(
            name = "bc_frame_rate",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                stroke = stroke,
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
            ) {
                moveTo(5f, 15.5f); lineTo(5f, 8.5f)
                moveTo(9.7f, 18.5f); lineTo(9.7f, 5.5f)
                moveTo(14.3f, 16.5f); lineTo(14.3f, 7.5f)
                moveTo(19f, 14.5f); lineTo(19f, 9.5f)
            }
        }.build()
    }

    val Library: ImageVector by lazy {
        ImageVector.Builder(
            name = "bc_library",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                stroke = stroke,
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                // Three upright spines + one leaning — Apple Music's library glyph, thickened
                moveTo(4.6f, 4.8f); lineTo(4.6f, 19.2f)
                moveTo(9.2f, 4.8f); lineTo(9.2f, 19.2f)
                moveTo(13.8f, 4.8f); lineTo(13.8f, 19.2f)
                moveTo(17.2f, 5.6f); lineTo(20.6f, 18.9f)
            }
        }.build()
    }

    /** 2x2 grid icon (4 rounded squares) for switching layout to grid view. */
    val GridView: ImageVector by lazy {
        ImageVector.Builder(
            name = "bc_grid_view",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                stroke = stroke,
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                // Top-left
                moveTo(6.5f, 4.5f)
                lineTo(8.5f, 4.5f)
                arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, 2f, 2f)
                lineTo(10.5f, 8.5f)
                arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, -2f, 2f)
                lineTo(6.5f, 10.5f)
                arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, -2f, -2f)
                lineTo(4.5f, 6.5f)
                arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, 2f, -2f)
                close()

                // Top-right
                moveTo(15.5f, 4.5f)
                lineTo(17.5f, 4.5f)
                arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, 2f, 2f)
                lineTo(19.5f, 8.5f)
                arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, -2f, 2f)
                lineTo(15.5f, 10.5f)
                arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, -2f, -2f)
                lineTo(13.5f, 6.5f)
                arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, 2f, -2f)
                close()

                // Bottom-left
                moveTo(6.5f, 13.5f)
                lineTo(8.5f, 13.5f)
                arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, 2f, 2f)
                lineTo(10.5f, 17.5f)
                arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, -2f, 2f)
                lineTo(6.5f, 19.5f)
                arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, -2f, -2f)
                lineTo(4.5f, 15.5f)
                arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, 2f, -2f)
                close()

                // Bottom-right
                moveTo(15.5f, 13.5f)
                lineTo(17.5f, 13.5f)
                arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, 2f, 2f)
                lineTo(19.5f, 17.5f)
                arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, -2f, 2f)
                lineTo(15.5f, 19.5f)
                arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, -2f, -2f)
                lineTo(13.5f, 15.5f)
                arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, 2f, -2f)
                close()
            }
        }.build()
    }

    /** List view icon (horizontal rows) for switching layout to list view. */
    val ListView: ImageVector by lazy {
        ImageVector.Builder(
            name = "bc_list_view",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                stroke = stroke,
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                // Three horizontal lines with bullet markers
                moveTo(4.5f, 6.5f); lineTo(5.5f, 6.5f)
                moveTo(9.5f, 6.5f); lineTo(19.5f, 6.5f)

                moveTo(4.5f, 12f); lineTo(5.5f, 12f)
                moveTo(9.5f, 12f); lineTo(19.5f, 12f)

                moveTo(4.5f, 17.5f); lineTo(5.5f, 17.5f)
                moveTo(9.5f, 17.5f); lineTo(19.5f, 17.5f)
            }
        }.build()
    }
    
    /** A filled play triangle, with rounded points. */
    val PlayerPlay: ImageVector by lazy {
        ImageVector.Builder(
            name = "bc_player_play",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                fill = stroke,
                stroke = stroke,
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(4.8f, 1.2f)
                lineTo(21.0f, 12f)
                lineTo(4.8f, 22.8f)
                close()
            }
        }.build()
    }

    /** Two wide, rounded pause bars, matched to [PlayerPlay]. */
    val PlayerPause: ImageVector by lazy {
        ImageVector.Builder(
            name = "bc_player_pause",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                fill = stroke,
                stroke = stroke,
                strokeLineWidth = 1.9f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(3.0f, 0.4f)
                lineTo(9.6f, 0.4f)
                lineTo(9.6f, 23.6f)
                lineTo(3.0f, 23.6f)
                close()
                moveTo(14.4f, 0.4f)
                lineTo(21.0f, 0.4f)
                lineTo(21.0f, 23.6f)
                lineTo(14.4f, 23.6f)
                close()
            }
        }.build()
    }

    /** Three rows of a dot and a line — Apple Music's queue mark. */
    val PlayerQueue: ImageVector by lazy {
        ImageVector.Builder(
            name = "bc_player_queue",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                stroke = stroke,
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
            ) {
                for (y in listOf(6.4f, 12f, 17.6f)) {
                    moveTo(8.7f, y)
                    lineTo(20.5f, y)
                }
            }
            path(fill = stroke) {
                for (y in listOf(6.4f, 12f, 17.6f)) {
                    circle(4.7f, y, 1.0f)
                }
            }
        }.build()
    }

    /** Apple SF / Opaline style rounded quote bubble with twin marks: the lyrics button. */
    val PlayerLyrics: ImageVector by lazy {
        ImageVector.Builder(
            name = "bc_player_lyrics",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                stroke = stroke,
                strokeLineWidth = 2.05f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(7.5f, 4.5f)
                lineTo(16.5f, 4.5f)
                quadTo(20.5f, 4.5f, 20.5f, 8.5f)
                lineTo(20.5f, 13.5f)
                quadTo(20.5f, 17.5f, 16.5f, 17.5f)
                lineTo(10.5f, 17.5f)
                lineTo(6.2f, 20.8f)
                quadTo(5.5f, 21.3f, 5.5f, 20.3f)
                lineTo(5.5f, 17.5f)
                quadTo(3.5f, 17.5f, 3.5f, 13.5f)
                lineTo(3.5f, 8.5f)
                quadTo(3.5f, 4.5f, 7.5f, 4.5f)
                close()
            }
            path(fill = stroke) {
                quoteMark(8.4f)
                quoteMark(13.6f)
            }
        }.build()
    }

    /** Three dots, with no disc of their own — [CircleGlyph] paints that. */
    val PlayerEllipsis: ImageVector by lazy {
        ImageVector.Builder(
            name = "bc_player_ellipsis",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(fill = stroke) {
                circle(5.9f, 12f, 1.75f)
                circle(12f, 12f, 1.75f)
                circle(18.1f, 12f, 1.75f)
            }
        }.build()
    }

    /** Apple's sharp, five-point outline star. */
    val PlayerStar: ImageVector by lazy {
        ImageVector.Builder(
            name = "bc_player_star",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                stroke = stroke,
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                star()
            }
        }.build()
    }

    /** The filled state of [PlayerStar]. */
    val PlayerStarFilled: ImageVector by lazy {
        ImageVector.Builder(
            name = "bc_player_star_filled",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                fill = stroke,
                stroke = stroke,
                strokeLineWidth = 1.2f,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                star()
            }
        }.build()
    }

    /**
     * Where the music is sent: two arcs open at the bottom around a small
     * ring, and a filled wedge in the opening — the glyph the phone's own
     * media player puts on its output button, built here to that one's
     * measurements: rings of radius 10, 6 and 2 about the middle of the box,
     * all 1.4 thick, the arcs ending 47.7 degrees either side of straight
     * down, the wedge's foot on the outer arc's outer edge.
     *
     * The arcs stop short of the wedge rather than run behind it; closed
     * rings with a triangle laid over them read as a target with a bite out.
     */
    val PlayerAirPlay: ImageVector by lazy {
        ImageVector.Builder(
            name = "bc_player_airplay",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                stroke = stroke,
                strokeLineWidth = 1.4f,
                strokeLineCap = StrokeCap.Round,
            ) {
                moveTo(4.60f, 18.73f)
                arcTo(10f, 10f, 0f, true, true, 19.40f, 18.73f)
                moveTo(7.56f, 16.04f)
                arcTo(6f, 6f, 0f, true, true, 16.44f, 16.04f)
                circle(12f, 12f, 2f)
            }
            // The wedge: its point under the ring, its foot on the outer
            // arc's circle. The stroke is only there to round its corners.
            path(
                fill = stroke,
                stroke = stroke,
                strokeLineWidth = 1.4f,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(12f, 16.5f)
                lineTo(16.99f, 20.66f)
                arcTo(10f, 10f, 0f, false, true, 7.01f, 20.66f)
                close()
            }
        }.build()
    }

    /** A small filled speaker cone, the body shared by the volume glyphs. */
    val PlayerVolumeLow: ImageVector by lazy {
        ImageVector.Builder(
            name = "bc_player_volume_low",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                fill = stroke,
                stroke = stroke,
                strokeLineWidth = 1.4f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                speakerBody()
            }
        }.build()
    }

    /** The low speaker plus two sound-wave arcs. */
    val PlayerVolumeHigh: ImageVector by lazy {
        ImageVector.Builder(
            name = "bc_player_volume_high",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                fill = stroke,
                stroke = stroke,
                strokeLineWidth = 1.4f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                speakerBody()
            }
            path(
                stroke = stroke,
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
            ) {
                moveTo(14.4f, 8.9f)
                quadTo(16.5f, 12f, 14.4f, 15.1f)
                moveTo(17.5f, 6.6f)
                quadTo(20.8f, 12f, 17.5f, 17.4f)
            }
        }.build()
    }

    /** The low speaker with a small cross where the waves would be. */
    val PlayerVolumeMuted: ImageVector by lazy {
        ImageVector.Builder(
            name = "bc_player_volume_muted",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                fill = stroke,
                stroke = stroke,
                strokeLineWidth = 1.4f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                speakerBody()
            }
            path(
                stroke = stroke,
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
            ) {
                moveTo(14.9f, 9.8f)
                lineTo(19.1f, 14.2f)
                moveTo(19.1f, 9.8f)
                lineTo(14.9f, 14.2f)
            }
        }.build()
    }

    /** Two crossing arrows, matching the wider, lighter player row. */
    val PlayerShuffle: ImageVector by lazy {
        ImageVector.Builder(
            name = "bc_player_shuffle",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                stroke = stroke,
                strokeLineWidth = 1.9f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(3.3f, 7.2f)
                lineTo(6.7f, 7.2f)
                lineTo(17.3f, 16.8f)
                lineTo(19.2f, 16.8f)
                moveTo(3.3f, 16.8f)
                lineTo(6.7f, 16.8f)
                lineTo(9.4f, 14.4f)
                moveTo(13.9f, 9.6f)
                lineTo(17.3f, 7.2f)
                lineTo(19.2f, 7.2f)
            }
            path(fill = stroke) {
                moveTo(17.7f, 5.0f); lineTo(21.0f, 7.2f); lineTo(17.7f, 9.4f); close()
                moveTo(17.7f, 14.6f); lineTo(21.0f, 16.8f); lineTo(17.7f, 19.0f); close()
            }
        }.build()
    }

    /** A repeat loop with arrowheads on its straight runs. */
    val PlayerRepeat: ImageVector by lazy {
        ImageVector.Builder(
            name = "bc_player_repeat",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                stroke = stroke,
                strokeLineWidth = 1.9f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(8.2f, 7.2f)
                lineTo(16.0f, 7.2f)
                arcToRelative(4.8f, 4.8f, 0f, isMoreThanHalf = false, isPositiveArc = true, 0f, 9.6f)
                lineTo(8.2f, 16.8f)
                arcToRelative(4.8f, 4.8f, 0f, isMoreThanHalf = false, isPositiveArc = true, 0f, -9.6f)
                close()
            }
            path(fill = stroke) {
                moveTo(14.0f, 5.0f); lineTo(17.4f, 7.2f); lineTo(14.0f, 9.4f); close()
                moveTo(10.4f, 14.6f); lineTo(7.0f, 16.8f); lineTo(10.4f, 19.0f); close()
            }
        }.build()
    }

    /** Autoplay's lemniscate, widened to sit with [PlayerRepeat]. */
    val PlayerAutoplay: ImageVector by lazy {
        ImageVector.Builder(
            name = "bc_player_autoplay",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                stroke = stroke,
                strokeLineWidth = 1.9f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(12f, 12f)
                curveTo(10.12f, 9.1f, 8.7f, 8f, 7.1f, 8f)
                arcToRelative(4f, 4f, 0f, isMoreThanHalf = false, isPositiveArc = false, 0f, 8f)
                curveTo(8.7f, 16f, 10.12f, 14.9f, 12f, 12f)
                curveTo(13.88f, 9.1f, 15.3f, 8f, 16.9f, 8f)
                arcToRelative(4f, 4f, 0f, isMoreThanHalf = false, isPositiveArc = true, 0f, 8f)
                curveTo(15.3f, 16f, 13.88f, 14.9f, 12f, 12f)
            }
        }.build()
    }

    private fun PathBuilder.circle(cx: Float, cy: Float, radius: Float) {
        moveTo(cx - radius, cy)
        arcToRelative(radius, radius, 0f, isMoreThanHalf = true, isPositiveArc = true, radius * 2f, 0f)
        arcToRelative(radius, radius, 0f, isMoreThanHalf = true, isPositiveArc = true, -radius * 2f, 0f)
        close()
    }

    private fun PathBuilder.speakerBody() {
        moveTo(3.3f, 9.2f)
        lineTo(5.9f, 9.2f)
        lineTo(10.4f, 5.5f)
        lineTo(10.4f, 18.5f)
        lineTo(5.9f, 14.8f)
        lineTo(3.3f, 14.8f)
        close()
    }

    private fun PathBuilder.quoteMark(x: Float) {
        moveTo(x + 0.6f, 8.4f)
        curveTo(x + 1.8f, 8.4f, x + 2.3f, 9.2f, x + 2.3f, 10.2f)
        curveTo(x + 2.3f, 11.5f, x + 1.4f, 12.8f, x - 0.2f, 13.6f)
        lineTo(x - 0.7f, 12.8f)
        curveTo(x + 0.5f, 12.1f, x + 0.9f, 11.4f, x + 0.9f, 10.7f)
        lineTo(x + 0.5f, 10.7f)
        curveTo(x - 0.4f, 10.7f, x - 0.6f, 9.9f, x - 0.6f, 9.4f)
        curveTo(x - 0.6f, 8.7f, x - 0.1f, 8.4f, x + 0.6f, 8.4f)
        close()
    }

    private fun PathBuilder.star() {
        val cx = 12f
        val cy = 12.3f
        val outer = 10.3f
        val inner = 4.35f
        moveTo(cx, cy - outer)
        for (index in 1..10) {
            val radius = if (index % 2 == 1) inner else outer
            val angle = -PI / 2 + index * PI / 5
            lineTo(
                (cx + radius * cos(angle)).toFloat(),
                (cy + radius * sin(angle)).toFloat(),
            )
        }
        close()
    }

    /**
     * A note beside four lines of text: the Lyrics page in Settings.
     *
     * Traced artwork, drawn on a 512 grid that it fills edge to edge. The
     * symbols it is listed among keep a margin inside their own boxes, so it is
     * set in from each side to stand the same size as they do; its bars and
     * stem are already about their weight.
     */
    val SongLyrics: ImageVector by lazy {
        ImageVector.Builder(
            name = "bc_song_lyrics", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = SONG_LYRICS_GRID + SONG_LYRICS_MARGIN * 2f,
            viewportHeight = SONG_LYRICS_GRID + SONG_LYRICS_MARGIN * 2f,
        ).apply {
            group(translationX = SONG_LYRICS_MARGIN, translationY = SONG_LYRICS_MARGIN) {
                addPath(pathData = addPathNodes("M331.98,1.76c8.98,5.5,14.58,13.45,19.9,22.3c5.84,9.43,11.89,18.46,18.74,27.18c1.38,1.76,2.74,3.54,4.11,5.31c14.95,19.43,31.54,38.63,50.16,54.62c2.29,1.98,4.51,4.03,6.74,6.08C437.82,122.84,444.4,127.9,451,133c5.34,4.13,10.66,8.27,15.98,12.43c1.24,.96,2.48,1.93,3.73,2.89c22.77,17.62,36.42,42.15,40.9,70.45c.68,5.66,.73,11.29,.7,16.98c0,1.23-.01,2.46-.01,3.73c-.41,30.46-13.42,56.6-34.82,78.1c-19.48,18.67-46.09,29.6-72.98,29.92c-.9,.02-1.79,.04-2.71,.06c-7.6-.08-14.13-1.98-20.38-6.38c-7.07-7.37-10.54-14.86-10.41-25.18c.89-9.59,5.6-16.1,12.55-22.46c6.29-3.96,13.44-4.99,20.7-5.91c15.78-2.29,27.49-7.87,37.9-20.13c7.46-10.09,12.57-23.81,10.85-36.5c-3.58-19.45-12.62-30.84-28-43c-1.73-1.41-3.46-2.83-5.19-4.25c-3.19-2.62-6.39-5.22-9.6-7.82c-42.23-34.17-42.23-34.17-57.85-52.08c-.63-.72-1.26-1.44-1.91-2.18c-.48-.55-.96-1.1-1.45-1.67c0,.64,0,1.29,0,1.95c-.08,38.22-.18,76.44-.28,114.67c-.01,4.59-.03,9.18-.04,13.78c0,.91,0,1.82,0,2.77c-.04,14.79-.08,29.57-.11,44.36c-.03,15.19-.07,30.37-.11,45.56c-.03,9.36-.05,18.73-.07,28.09c-.01,6.43-.03,12.86-.05,19.29c-.01,3.7-.02,7.4-.02,11.11c-.01,4.03-.02,8.05-.04,12.08c0,1.16,0,2.31,0,3.5c-.18,27.52-9.12,50.22-28.15,70.03c-19.86,19.28-44.07,25.36-71.06,25.09c-8.23-.22-15.31-1.49-23.07-4.28c-1.06-.37-2.11-.75-3.2-1.13C209.86,501.86,199.72,494.7,190,485c-.82-.8-1.63-1.61-2.47-2.44c-16.67-17.37-24.17-41.5-23.74-65.21c.92-25.41,12.13-48.09,30.46-65.54C214.57,333.98,239.5,327.47,266,329c7.55,.84,14.81,2.56,22,5c0-.65,0-1.3,0-1.98c.04-38.64,.09-77.29,.18-115.93c.01-4.65,.02-9.29,.03-13.93c0-.46,0-.46,0-2.8c.03-14.96,.05-29.91,.06-44.87c.02-15.35,.04-30.7,.08-46.05c.03-9.47,.04-18.94,.04-28.41c0-6.5,.02-13,.04-19.49c.02-3.75,.03-7.5,.02-11.24c-.01-4.07,.01-8.14,.03-12.21c-.01-1.17-.01-2.35-.02-3.56c.1-9.62,1.97-18.82,8.76-26.03c8.96-7.55,23.77-11.18,34.76-5.74Z"), fill = stroke)
                addPath(pathData = addPathNodes("M28.18,178.61c.79-.01,1.59-.02,2.41-.02c2.65-.02,5.3-.01,7.95-.01c1.9-.01,3.81-.02,5.71-.03c5.17-.02,10.34-.02,15.51-.02c4.32,0,8.63-.01,12.95-.02c10.2-.01,20.39-.02,30.58-.01c10.5,.01,21.01-.02,31.51-.05c9.03-.04,18.05-.05,27.08-.04c5.39,0,10.78-.01,16.16-.03c5.07-.02,10.14-.02,15.21,0c1.85,0,3.71,0,5.56-.02c20.02-.14,20.02-.14,28.31,7.63c6.52,6.77,8.45,13.63,8.36,22.94c-.3,8.52-3.2,14.24-9.35,20.01c-5.88,5-11.74,6.51-19.31,6.45c-.79,.01-1.59,.02-2.41,.02c-2.65,.02-5.3,.01-7.95,.01c-1.9,.01-3.81,.02-5.71,.03c-5.17,.02-10.34,.02-15.51,.02c-4.32,0-8.63,.01-12.95,.02c-10.2,.01-20.39,.02-30.58,.01c-10.5-.01-21.01,.02-31.51,.05c-9.03,.04-18.05,.05-27.08,.04c-5.39,0-10.78,.01-16.16,.03c-5.07,.02-10.14,.02-15.21,0c-1.85,0-3.71,0-5.56,.02c-20.02,.14-20.02,.14-28.31-7.63c-6.52-6.77-8.45-13.63-8.36-22.94c.3-8.52,3.2-14.24,9.36-20.01c5.87-5,11.73-6.51,19.3-6.45Z"), fill = stroke)
                addPath(pathData = addPathNodes("M28.18,80.61c.79-.01,1.59-.02,2.41-.02c2.65-.02,5.3-.01,7.95-.01c1.9-.01,3.81-.02,5.71-.03c5.17-.02,10.34-.02,15.51-.02c4.32,0,8.63-.01,12.95-.02c10.2-.01,20.39-.02,30.58-.01c10.5,.01,21.01-.02,31.51-.05c9.03-.04,18.05-.05,27.08-.04c5.39,0,10.78-.01,16.16-.03c5.07-.02,10.14-.02,15.21,0c1.85,0,3.71,0,5.56-.02c20.02-.14,20.02-.14,28.31,7.63c6.52,6.77,8.45,13.63,8.36,22.94c-.3,8.52-3.2,14.24-9.35,20.01c-5.88,5-11.74,6.51-19.31,6.45c-.79,.01-1.59,.02-2.41,.02c-2.65,.02-5.3,.01-7.95,.01c-1.9,.01-3.81,.02-5.71,.03c-5.17,.02-10.34,.02-15.51,.02c-4.32,0-8.63,.01-12.95,.02c-10.2,.01-20.39,.02-30.58,.01c-10.5-.01-21.01,.02-31.51,.05c-9.03,.04-18.05,.05-27.08,.04c-5.39,0-10.78,.01-16.16,.03c-5.07,.02-10.14,.02-15.21,0c-1.85,0-3.71,0-5.56,.02c-20.02,.14-20.02,.14-28.31-7.63c-6.52-6.77-8.45-13.63-8.36-22.94c.3-8.52,3.2-14.24,9.36-20.01c5.87-5,11.73-6.51,19.3-6.45Z"), fill = stroke)
                addPath(pathData = addPathNodes("M30.92,276.58c1.07-.01,2.15-.02,3.26-.03c3.55-.02,7.1-.02,10.65-.02c2.48-.01,4.96-.01,7.43-.02c5.19-.02,10.38-.02,15.57-.01c6.63,0,13.27-.03,19.9-.07c5.12-.02,10.23-.02,15.35-.02c2.44,0,4.89-.01,7.33-.03c3.43-.02,6.86-.01,10.28,0c.5,0,.5,0,3.04-.04c7.91,.1,14.52,2.2,20.42,7.65c6.46,6.82,8.42,13.61,8.33,22.94c-.3,8.52-3.2,14.24-9.35,20.01c-6.87,5.84-13.37,6.51-22.05,6.48c-1.07,.01-2.15,.02-3.26,.03c-3.55,.02-7.1,.02-10.65,.02c-2.48,.01-4.96,.01-7.43,.02c-5.19,.02-10.38,.02-15.57,.01c-6.63,0-13.27,.03-19.9,.07c-5.12,.02-10.23,.02-15.35,.02c-2.44,0-4.89,.01-7.33,.03c-3.43,.02-6.86,.01-10.28,0c-.5,0-.5,0-3.04,.04c-7.91-.1-14.52-2.2-20.42-7.65C1.39,319.19-.57,312.4-.48,303.07c.3-8.52,3.2-14.24,9.36-20.01c6.86-5.84,13.36-6.51,22.04-6.48Z"), fill = stroke)
                addPath(pathData = addPathNodes("M28.53,374.58c.82-.01,1.65-.02,2.5-.03c2.7-.02,5.41-.02,8.11-.02c1.9-.01,3.79-.01,5.69-.02c3.96-.02,7.93-.02,11.89-.01c5.06,0,10.12-.03,15.18-.07c3.91-.02,7.82-.02,11.74-.02c1.86,0,3.73-.01,5.59-.03c20.59-.18,20.59-.18,28.89,7.61c6.52,6.77,8.45,13.63,8.36,22.94c-.3,8.52-3.2,14.24-9.35,20.01c-6.05,5.15-11.97,6.51-19.66,6.48c-.82,.01-1.65,.02-2.5,.03c-2.7,.02-5.41,.02-8.11,.02c-1.9,.01-3.79,.01-5.69,.02c-3.96,.02-7.93,.02-11.89,.01c-5.06,0-10.12,.03-15.18,.07c-3.91,.02-7.82,.02-11.74,.02c-1.86,0-3.73,.01-5.59,.03c-20.59,.18-20.59,.18-28.89-7.61c-6.52-6.77-8.45-13.63-8.36-22.94c.3-8.52,3.2-14.24,9.36-20.01c6.04-5.15,11.96-6.51,19.65-6.48Z"), fill = stroke)
            }
        }.build()
    }

    private const val SONG_LYRICS_GRID = 512f
    private const val SONG_LYRICS_MARGIN = 28f
}
