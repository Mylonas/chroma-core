package com.mikmy.chromacore

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min

/**
 * The scoring and collision rules, with no Android types in sight, so they can
 * be unit tested on the JVM. [Game] owns presentation and state; this owns the
 * decisions that determine whether a run was fair.
 */
object Rules {

    const val OUTCOME_BLOCK = 0
    const val OUTCOME_WRONG = 1
    const val OUTCOME_MISS = 2

    const val MAX_MULTIPLIER = 10

    /** Signed smallest angle from [b] to [a], in (-PI, PI]. */
    fun angDiff(a: Float, b: Float): Float {
        var d = a - b
        val tau = (PI * 2).toFloat()
        while (d > PI) d -= tau
        while (d < -PI) d += tau
        return d
    }

    /** Which half of the shield an orb arrives on. */
    fun onRightHalf(diff: Float): Boolean = diff > 0f

    /**
     * Colour of a shield half. [flipped] swaps the two halves, which is what a
     * tap does.
     */
    fun sideColor(rightSide: Boolean, flipped: Boolean, colA: Int, colB: Int): Int =
        if (rightSide != flipped) colB else colA

    /**
     * What happens when an orb reaches the shield radius.
     *
     * @param diff signed angle between the orb and the shield centre
     * @param anyColour true for wild/gold orbs, which either half stops
     */
    fun outcome(
        diff: Float,
        halfSpan: Float,
        orbColor: Int,
        anyColour: Boolean,
        overdrive: Boolean,
        flipped: Boolean,
        colA: Int,
        colB: Int
    ): Int {
        if (abs(diff) > halfSpan) return OUTCOME_MISS
        if (anyColour || overdrive) return OUTCOME_BLOCK
        val side = sideColor(onRightHalf(diff), flipped, colA, colB)
        return if (orbColor == side) OUTCOME_BLOCK else OUTCOME_WRONG
    }

    /** A block is PERFECT when it lands near the midpoint of the covering half. */
    fun isPerfect(absDiff: Float, halfSpan: Float, window: Float): Boolean =
        abs(absDiff - halfSpan * 0.5f) < window

    fun multiplier(combo: Int): Int = min(MAX_MULTIPLIER, 1 + combo / 8)

    fun points(base: Int, combo: Int, perfect: Boolean, overdrive: Boolean): Int {
        var g = base * multiplier(combo)
        if (perfect) g *= 2
        if (overdrive) g *= 2
        return g
    }
}
