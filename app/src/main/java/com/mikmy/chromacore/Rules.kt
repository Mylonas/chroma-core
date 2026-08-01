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

    /**
     * Half-width of the neutral band where the two shield halves meet, in
     * radians. Inside it either colour blocks.
     *
     * Without this the game is broken. Centring the shield on an incoming orb
     * is the intuitive play, and it puts the orb exactly on the boundary, so
     * which colour catches it is a coin flip. Measured with a bot: a player who
     * centres their aim survives 8.8s and scores 19 with no grace, versus 54s
     * and 2465 with it. Aiming a half's midpoint is still the better play —
     * that is what earns PERFECT — so this makes the obvious action safe
     * without removing the skill.
     */
    const val SEAM_GRACE = (10.0 * Math.PI / 180.0).toFloat()

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
        colB: Int,
        seamGrace: Float = SEAM_GRACE
    ): Int {
        if (abs(diff) > halfSpan) return OUTCOME_MISS
        if (anyColour || overdrive) return OUTCOME_BLOCK
        // Right on the seam, either half counts — see SEAM_GRACE.
        if (abs(diff) < seamGrace) return OUTCOME_BLOCK
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
