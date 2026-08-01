package com.mikmy.chromacore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

class RulesTest {

    private val a = 0xFF00E5FF.toInt()   // cyan
    private val b = 0xFFFF2E88.toInt()   // magenta
    private val halfSpan = (58f * PI / 180f).toFloat()
    private val window = (13f * PI / 180f).toFloat()

    private fun deg(d: Float) = (d * PI / 180f).toFloat()

    // ------------------------------------------------------------ angDiff

    @Test
    fun angDiffWrapsAcrossTheSeam() {
        // 350° and 10° are 20° apart, not 340°.
        assertEquals(deg(20f), Rules.angDiff(deg(10f), deg(350f)), 1e-4f)
        assertEquals(deg(-20f), Rules.angDiff(deg(350f), deg(10f)), 1e-4f)
    }

    @Test
    fun angDiffIsZeroForIdenticalAngles() {
        assertEquals(0f, Rules.angDiff(1.234f, 1.234f), 1e-6f)
    }

    // ------------------------------------------------------------ sides

    @Test
    fun flippingSwapsTheHalves() {
        assertEquals(b, Rules.sideColor(rightSide = true, flipped = false, colA = a, colB = b))
        assertEquals(a, Rules.sideColor(rightSide = false, flipped = false, colA = a, colB = b))
        assertEquals(a, Rules.sideColor(rightSide = true, flipped = true, colA = a, colB = b))
        assertEquals(b, Rules.sideColor(rightSide = false, flipped = true, colA = a, colB = b))
    }

    // ---------------------------------------------------------- outcomes

    private fun outcome(
        diffDeg: Float,
        orb: Int,
        anyColour: Boolean = false,
        overdrive: Boolean = false,
        flipped: Boolean = false
    ) = Rules.outcome(deg(diffDeg), halfSpan, orb, anyColour, overdrive, flipped, a, b)

    @Test
    fun orbOutsideTheShieldArcIsAMiss() {
        assertEquals(Rules.OUTCOME_MISS, outcome(75f, b))
        assertEquals(Rules.OUTCOME_MISS, outcome(-75f, a))
        assertEquals(Rules.OUTCOME_MISS, outcome(180f, a))
    }

    @Test
    fun matchingColourBlocks() {
        // right half is magenta when not flipped
        assertEquals(Rules.OUTCOME_BLOCK, outcome(30f, b))
        // left half is cyan when not flipped
        assertEquals(Rules.OUTCOME_BLOCK, outcome(-30f, a))
    }

    @Test
    fun wrongColourIsPunished() {
        assertEquals(Rules.OUTCOME_WRONG, outcome(30f, a))
        assertEquals(Rules.OUTCOME_WRONG, outcome(-30f, b))
    }

    @Test
    fun flippingChangesTheVerdictForTheSameOrb() {
        assertEquals(Rules.OUTCOME_WRONG, outcome(30f, a, flipped = false))
        assertEquals(Rules.OUTCOME_BLOCK, outcome(30f, a, flipped = true))
    }

    @Test
    fun wildAndGoldOrbsAreBlockedByEitherHalf() {
        assertEquals(Rules.OUTCOME_BLOCK, outcome(30f, a, anyColour = true))
        assertEquals(Rules.OUTCOME_BLOCK, outcome(-30f, b, anyColour = true))
    }

    @Test
    fun wildOrbStillMissesWhenTheShieldIsNotThere() {
        assertEquals(Rules.OUTCOME_MISS, outcome(120f, a, anyColour = true))
    }

    @Test
    fun overdriveIgnoresColourButNotPosition() {
        assertEquals(Rules.OUTCOME_BLOCK, outcome(30f, a, overdrive = true))
        assertEquals(Rules.OUTCOME_MISS, outcome(120f, a, overdrive = true))
    }

    @Test
    fun theArcEdgeIsInclusive() {
        // exactly on the boundary still counts as a hit, not a miss
        assertEquals(Rules.OUTCOME_BLOCK, Rules.outcome(halfSpan, halfSpan, b, false, false, false, a, b))
    }

    // -------------------------------------------------------- seam grace

    /**
     * Centring the shield on an orb is the intuitive play, and it lands the orb
     * exactly where the two halves meet. Without a neutral band there, which
     * colour catches it is a coin flip, and a player who aims the way the game
     * visually suggests dies in about nine seconds.
     */
    @Test
    fun anOrbArrivingOnTheSeamIsNeverAWrongColour() {
        for (orb in listOf(a, b)) {
            for (flip in listOf(false, true)) {
                assertEquals(
                    "an orb dead on the seam was punished",
                    Rules.OUTCOME_BLOCK, outcome(0f, orb, flipped = flip)
                )
                assertEquals(Rules.OUTCOME_BLOCK, outcome(4f, orb, flipped = flip))
                assertEquals(Rules.OUTCOME_BLOCK, outcome(-4f, orb, flipped = flip))
            }
        }
    }

    @Test
    fun outsideTheSeamBandTheColourRuleStillBites() {
        val justOutside = (Rules.SEAM_GRACE * 180f / PI).toFloat() + 3f
        assertEquals(Rules.OUTCOME_WRONG, outcome(justOutside, a))
        assertEquals(Rules.OUTCOME_WRONG, outcome(-justOutside, b))
        assertEquals(Rules.OUTCOME_BLOCK, outcome(justOutside, b))
        assertEquals(Rules.OUTCOME_BLOCK, outcome(-justOutside, a))
    }

    @Test
    fun seamGraceDoesNotRescueAnOrbTheShieldIsNotCovering() {
        assertEquals(Rules.OUTCOME_MISS, outcome(150f, a))
        assertEquals(Rules.OUTCOME_MISS, outcome(-150f, b))
    }

    @Test
    fun theSeamBandIsNarrowerThanAHalfSoAimingStillMatters() {
        assertTrue("the neutral band swallowed the whole shield", Rules.SEAM_GRACE < halfSpan * 0.5f)
        assertTrue(Rules.SEAM_GRACE > 0f)
    }

    // ----------------------------------------------------------- perfect

    @Test
    fun perfectIsTheMidpointOfTheCoveringHalf() {
        val mid = halfSpan * 0.5f
        assertTrue(Rules.isPerfect(mid, halfSpan, window))
        assertTrue(Rules.isPerfect(mid + window * 0.9f, halfSpan, window))
        assertFalse(Rules.isPerfect(mid + window * 1.1f, halfSpan, window))
        assertFalse(Rules.isPerfect(0f, halfSpan, window))
        assertFalse(Rules.isPerfect(halfSpan, halfSpan, window))
    }

    // ------------------------------------------------------------ scoring

    @Test
    fun multiplierClimbsEveryEightBlocksAndCapsAtTen() {
        assertEquals(1, Rules.multiplier(0))
        assertEquals(1, Rules.multiplier(7))
        assertEquals(2, Rules.multiplier(8))
        assertEquals(3, Rules.multiplier(16))
        assertEquals(Rules.MAX_MULTIPLIER, Rules.multiplier(500))
    }

    @Test
    fun perfectAndOverdriveEachDoubleTheScore() {
        assertEquals(10, Rules.points(10, 0, perfect = false, overdrive = false))
        assertEquals(20, Rules.points(10, 0, perfect = true, overdrive = false))
        assertEquals(20, Rules.points(10, 0, perfect = false, overdrive = true))
        assertEquals(40, Rules.points(10, 0, perfect = true, overdrive = true))
    }

    @Test
    fun goldOrbsAreWorthTenNormalBlocks() {
        assertEquals(
            10 * Rules.points(10, 24, perfect = false, overdrive = false),
            Rules.points(100, 24, perfect = false, overdrive = false)
        )
    }

    @Test
    fun aLongStreakAtFullOverdriveHitsTheDesignedCeiling() {
        // best case single normal orb: x10 multiplier, perfect, overdrive
        assertEquals(400, Rules.points(10, 200, perfect = true, overdrive = true))
    }
}
