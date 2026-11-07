package com.music.bitchord.ui.graphics

/**
 * Smootherstep, `6t⁵ − 15t⁴ + 10t³`, for a fade across `t` in 0..1.
 *
 * Its slope and its curvature are both zero at either end, so a fade drawn
 * along it eases in from nothing and settles into nothing: there is no line
 * where it starts or where it finishes. A straight ramp turns a corner at both,
 * and the eye picks those corners out as edges across whatever is fading.
 */
fun smootherStep(t: Float): Float {
    val x = t.coerceIn(0f, 1f)
    return x * x * x * (x * (x * 6f - 15f) + 10f)
}

/**
 * How many stops a gradient following [smootherStep] is cut into: enough that
 * the straight spans between them neither band nor show the curve as facets
 * over a tall fade.
 */
const val FADE_CURVE_STOPS = 16

/** Precomputed smootherstep lookup table across [0, FADE_CURVE_STOPS]. */
val SMOOTHER_STEP_TABLE = FloatArray(FADE_CURVE_STOPS + 1) { i ->
    smootherStep(i / FADE_CURVE_STOPS.toFloat())
}

/** Precomputed t fraction table across [0, FADE_CURVE_STOPS]. */
val FADE_CURVE_T_TABLE = FloatArray(FADE_CURVE_STOPS + 1) { i ->
    i / FADE_CURVE_STOPS.toFloat()
}
