package io.github.towkirahmedrony.salah.core.domain.qibla

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Angle arithmetic and the north-reference discipline.
 *
 * The single most consequential rule here is that declination is applied exactly once. That is
 * enforced by types rather than by convention — [HeadingMath.toTrueHeading] consumes a
 * [MagneticHeading] and produces a [TrueHeading], and nothing accepts a [TrueHeading] and adds
 * declination — so these tests check the arithmetic that the types allow, and the tests that follow
 * check that the consequence holds across a range of declinations.
 */
class HeadingMathTest {

    // --- Normalization ---------------------------------------------------------

    @Test
    fun `normalize folds any angle into zero inclusive to 360 exclusive`() {
        assertEquals(0.0, HeadingMath.normalize(0.0), 0.0)
        assertEquals(90.0, HeadingMath.normalize(90.0), 0.0)
        assertEquals(0.0, HeadingMath.normalize(360.0), 0.0)
        assertEquals(10.0, HeadingMath.normalize(370.0), 0.0)
        assertEquals(350.0, HeadingMath.normalize(-10.0), 0.0)
        assertEquals(180.0, HeadingMath.normalize(-180.0), 0.0)
        assertEquals(0.0, HeadingMath.normalize(-720.0), 0.0)
        assertEquals(359.5, HeadingMath.normalize(-0.5), 0.0)
    }

    @Test
    fun `normalize folds not-a-number to zero rather than propagating it`() {
        // A NaN reaching the dial would rotate it to an arbitrary place. A visible zero is a bug
        // report; a silently wrong needle is not.
        assertEquals(0.0, HeadingMath.normalize(Double.NaN), 0.0)
    }

    @Test
    fun `normalize keeps positive and negative infinity inside the range`() {
        val positive = HeadingMath.normalize(Double.POSITIVE_INFINITY)
        val negative = HeadingMath.normalize(Double.NEGATIVE_INFINITY)

        // Infinity modulo 360 is NaN in IEEE arithmetic, so both fold to zero by the NaN rule.
        assertEquals(0.0, positive, 0.0)
        assertEquals(0.0, negative, 0.0)
    }

    @Test
    fun `the heading builders normalize what they are given`() {
        assertEquals(350.0, HeadingMath.magnetic(-10.0).degrees, 0.0)
        assertEquals(10.0, HeadingMath.trueHeading(370.0).degrees, 0.0)
    }

    // --- Signed shortest difference -------------------------------------------

    @Test
    fun `the signed difference takes the short way around the circle`() {
        // The case the whole design exists for: a bearing just past north and a heading just before
        // it are two degrees apart, not 358.
        assertEquals(2.0, HeadingMath.shortestSignedDelta(359.0, 1.0), 1e-9)
        assertEquals(-2.0, HeadingMath.shortestSignedDelta(1.0, 359.0), 1e-9)

        assertEquals(10.0, HeadingMath.shortestSignedDelta(100.0, 110.0), 1e-9)
        assertEquals(-10.0, HeadingMath.shortestSignedDelta(110.0, 100.0), 1e-9)
        assertEquals(0.0, HeadingMath.shortestSignedDelta(45.0, 45.0), 1e-9)
    }

    @Test
    fun `the signed difference is within the half-open interval from minus 180 to 180`() {
        for (from in 0 until 360 step 7) {
            for (to in 0 until 360 step 11) {
                val delta = HeadingMath.shortestSignedDelta(from.toDouble(), to.toDouble())
                assertTrue("delta $delta out of range", delta > -180.0 && delta <= 180.0)
            }
        }
    }

    @Test
    fun `a half turn is reported as positive 180 whichever way it is approached`() {
        // Exactly opposite has no shorter direction, so a single convention is fixed and tested:
        // positive, meaning clockwise. Without a decision here the sign would flip with rounding.
        assertEquals(180.0, HeadingMath.shortestSignedDelta(0.0, 180.0), 1e-9)
        assertEquals(180.0, HeadingMath.shortestSignedDelta(180.0, 0.0), 1e-9)
    }

    @Test
    fun `the signed difference is antisymmetric`() {
        for (from in 0 until 360 step 13) {
            for (to in 0 until 360 step 17) {
                val forward = HeadingMath.shortestSignedDelta(from.toDouble(), to.toDouble())
                val backward = HeadingMath.shortestSignedDelta(to.toDouble(), from.toDouble())

                // Exactly half a turn is excluded on purpose. The documented range is `(-180, 180]`,
                // so a half turn is reported as `+180` in both directions — which is the right
                // instruction ("turn 180 either way") but is not antisymmetric. That case has its
                // own test immediately above.
                if (abs(forward) == 180.0 && abs(backward) == 180.0) continue

                assertEquals("asymmetry at $from -> $to", 0.0, forward + backward, 1e-9)
            }
        }
    }

    // --- Declination -----------------------------------------------------------

    @Test
    fun `true heading is the magnetic heading plus declination, applied once`() {
        val heading = HeadingMath.magnetic(100.0)

        assertEquals(105.0, HeadingMath.toTrueHeading(heading, 5.0).degrees, 1e-9)
        assertEquals(95.0, HeadingMath.toTrueHeading(heading, -5.0).degrees, 1e-9)
    }

    @Test
    fun `applying declination twice is not expressible`() {
        // The behavioural consequence of the value-class split: a TrueHeading cannot be fed back
        // into toTrueHeading, so a double correction cannot be written. This asserts the property
        // that *is* observable — one call shifts the heading by exactly the declination, and the
        // shift does not depend on how the magnetic heading was produced.
        val declination = 12.5
        val first = HeadingMath.toTrueHeading(HeadingMath.magnetic(200.0), declination)

        assertEquals(212.5, first.degrees, 1e-9)

        // A second application is representable only by going through the magnetic domain again,
        // which is a different heading, not a correction of this one.
        val genuinelyDifferent = HeadingMath.toTrueHeading(HeadingMath.magnetic(200.0), declination * 2)
        assertEquals(225.0, genuinelyDifferent.degrees, 1e-9)
        assertTrue(first.degrees != genuinelyDifferent.degrees)
    }

    @Test
    fun `declination is applied exactly once, not twice`() {
        val magneticHeading = 100.0
        val bearing = 277.567140

        // A non-zero declination is essential: at zero, applying it once and applying it twice give
        // the same answer, so a double-correction bug would be invisible.
        listOf(1.0, 12.5, 20.0).forEach { declination ->
            val actual = turn(magneticHeading, bearing, declination)

            val appliedOnce = HeadingMath.shortestSignedDelta(magneticHeading + declination, bearing)
            assertEquals("declination $declination not applied once", appliedOnce, actual, 1e-9)

            val appliedTwice = HeadingMath.shortestSignedDelta(magneticHeading + 2 * declination, bearing)
            assertTrue(
                "declination $declination looks like it was applied twice",
                abs(appliedTwice - actual) > 1e-6,
            )
        }
    }

    @Test
    fun `the turn equals the signed difference between the true heading and the bearing`() {
        val magnetic = HeadingMath.magnetic(100.0)
        val declination = 5.0
        val trueHeading = HeadingMath.toTrueHeading(magnetic, declination)

        val turn = HeadingMath.turnFromTrue(trueHeading, HeadingMath.trueHeading(277.0))

        // 105 from true north, Qibla at 277: 172 degrees clockwise, which is inside the half turn.
        assertEquals(172.0, turn, 1e-9)
    }

    @Test
    fun `the turn wraps correctly when the Qibla is just past north`() {
        // Facing 181 degrees, Qibla at 179: two degrees anticlockwise, not 358 clockwise.
        val turn = HeadingMath.turnFromTrue(HeadingMath.trueHeading(181.0), HeadingMath.trueHeading(179.0))

        assertEquals(-2.0, turn, 1e-9)
    }

    // --- Dial rotation ---------------------------------------------------------

    @Test
    fun `the dial rotates by the negative of the heading`() {
        assertEquals(-90.0, HeadingMath.dialRotationDegrees(HeadingMath.trueHeading(90.0)), 1e-9)
        assertEquals(-270.0, HeadingMath.dialRotationDegrees(HeadingMath.trueHeading(270.0)), 1e-9)
        assertEquals(-359.0, HeadingMath.dialRotationDegrees(HeadingMath.trueHeading(359.0)), 1e-9)
    }

    private fun turn(magneticDegrees: Double, bearingDegrees: Double, declination: Double): Double =
        HeadingMath.turnFromTrue(
            HeadingMath.toTrueHeading(HeadingMath.magnetic(magneticDegrees), declination),
            HeadingMath.trueHeading(bearingDegrees),
        )
}
