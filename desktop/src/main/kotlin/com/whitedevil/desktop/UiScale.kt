package com.whitedevil.desktop

import kotlin.math.roundToInt

/**
 * How big the whole interface is. It scales the density, so text, buttons and spacing grow together (like zoom in
 * a browser) and nothing ends up clipped in a box that stayed the same size. Saved with the settings.
 *
 * Ctrl + / Ctrl - / Ctrl 0 and the A- / A+ buttons in the sidebar change it. 100% is the design size; up to 200%
 * because bigger is the direction people usually need.
 */
object UiScale {
    const val MIN = 0.8f
    const val MAX = 2.0f
    const val STEP = 0.1f
    const val DEFAULT = 1.0f

    /** Keeps a value in range and on a 10% step, so repeated presses never drift (1.0000001) or leave the range. */
    fun clamp(v: Float): Float {
        if (v.isNaN()) return DEFAULT
        val stepped = (v / STEP).roundToInt() * STEP
        return (Math.round(stepped.coerceIn(MIN, MAX) * 100) / 100f)
    }

    /** One step bigger (+1) or smaller (-1). */
    fun step(current: Float, direction: Int): Float = clamp(clamp(current) + direction * STEP)

    fun percent(v: Float): String = "${(clamp(v) * 100).roundToInt()}%"
}
