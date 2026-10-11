package io.github.towkirahmedrony.salah.core.domain.qibla

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Heading smoothing.
 *
 * Two properties matter, and they pull in opposite directions:
 *
 *  * jitter must not reach the needle, and
 *  * a real movement must not be delayed — the user turns to align and needs the reading to be
 *    right *then*, not a third of a second later.
 *
 * The tests below pin both, and the wraparound case pins the reason the averaging happens on the
 * unit circle rather than on the numbers.
 */
class HeadingSmootherTest {

    @Test
    fun `the first sample is adopted exactly`() {
        val smoother = HeadingSmoother()

        // No history to average against, so filtering here could only introduce error.
        assertEquals(137.0, smoother.update(137.0), 1e-9)
    }

    @Test
    fun `an unchanging heading passes through unchanged`() {
        val smoother = HeadingSmoother()

        repeat(20) {
            assertEquals(42.0, smoother.update(42.0), 1e-6)
        }
    }

    @Test
    fun `smoothing across the wrap stays near the wrap instead of flipping to the opposite side`() {
        // The failure this test exists for: the arithmetic mean of 359 and 1 is 180, which is the
        // exact opposite direction. Averaging on the unit circle makes it 0.
        val smoother = HeadingSmoother()
        smoother.update(359.0)

        val smoothed = smoother.update(1.0)

        val distanceFromWrap = minOf(abs(smoothed - 0.0), abs(smoothed - 360.0))
        assertTrue("smoothed to $smoothed, which is nowhere near 0/360", distanceFromWrap < 2.0)
    }

    @Test
    fun `repeated samples either side of the wrap converge to the wrap`() {
        val smoother = HeadingSmoother()

        var smoothed = smoother.update(0.0)
        repeat(40) { index ->
            smoothed = smoother.update(if (index % 2 == 0) 359.0 else 1.0)
        }

        val distanceFromWrap = minOf(abs(smoothed - 0.0), abs(smoothed - 360.0))
        assertTrue("converged to $smoothed", distanceFromWrap < 1.0)
    }

    @Test
    fun `a movement at or beyond the full-adoption threshold is adopted exactly`() {
        val smoother = HeadingSmoother(fullAdoptionDegrees = 10.0)
        smoother.update(10.0)

        // Thirty degrees is a deliberate movement, not noise. Averaging it in would leave the
        // needle lagging for several samples exactly when the user is turning to align.
        assertEquals(40.0, smoother.update(40.0), 1e-9)
    }

    @Test
    fun `a movement inside the threshold is partly adopted`() {
        val smoother = HeadingSmoother(minimumWeight = 0.25, fullAdoptionDegrees = 10.0)
        smoother.update(10.0)

        val smoothed = smoother.update(15.0)

        // Weight 0.5 at a five-degree deviation, so the needle moves halfway: inside the movement,
        // and strictly between the two samples.
        assertTrue("expected a partial move, got $smoothed", smoothed > 10.0 && smoothed < 15.0)
        assertEquals(12.5, smoothed, 0.15)
    }

    @Test
    fun `a slow steady rotation is tracked with bounded lag`() {
        // 1.8 degrees per sample is a 10 Hz sensor while the user turns at roughly 18 degrees per
        // second — a normal speed for bringing a compass onto a bearing.
        val smoother = HeadingSmoother()
        var heading = 0.0
        var smoothed = smoother.update(heading)

        repeat(200) {
            heading = HeadingMath.normalize(heading + 1.8)
            smoothed = smoother.update(heading)
        }

        val lag = abs(HeadingMath.shortestSignedDelta(heading, smoothed))
        assertTrue("lag of $lag degrees is excessive for a slow turn", lag < 3.5)
        assertTrue("lag of $lag degrees suggests no smoothing is happening", lag > 0.5)
    }

    @Test
    fun `a fast rotation is tracked with essentially no lag`() {
        // 10 degrees per sample: beyond the adoption threshold, so the filter gets out of the way.
        val smoother = HeadingSmoother()
        var heading = 0.0
        var smoothed = smoother.update(heading)

        repeat(60) {
            heading = HeadingMath.normalize(heading + 10.0)
            smoothed = smoother.update(heading)
        }

        val lag = abs(HeadingMath.shortestSignedDelta(heading, smoothed))
        assertTrue("lag of $lag degrees while turning quickly", lag < 0.5)
    }

    @Test
    fun `reset forgets the history so the next sample is adopted unfiltered`() {
        val smoother = HeadingSmoother()
        smoother.update(100.0)
        smoother.update(100.0)

        smoother.reset()

        assertEquals(250.0, smoother.update(250.0), 1e-9)
    }

    @Test
    fun `sample-to-sample jitter is reduced`() {
        val smoother = HeadingSmoother()
        val jitter = listOf(100.0, 102.0, 98.0, 102.0, 98.0, 102.0, 98.0, 102.0, 98.0, 102.0)

        val outputs = jitter.map { smoother.update(it) }

        fun spread(values: List<Double>): Double = values.max() - values.min()

        // The raw sequence spans four degrees. A ±2 degree wobble sits below the minimum weight's
        // reach, so the filtered output must be visibly tighter while staying inside the raw range.
        assertTrue("filtered spread ${spread(outputs)} is not tighter than the raw spread", spread(outputs) < 1.5)
        assertTrue(outputs.all { it in 97.0..103.0 })
    }

    @Test
    fun `a non-finite sample is folded rather than poisoning the filter`() {
        val smoother = HeadingSmoother()
        smoother.update(10.0)

        val smoothed = smoother.update(Double.NaN)

        assertTrue("got $smoothed", smoothed.isFinite())
        assertTrue(smoothed >= 0.0 && smoothed < 360.0)
    }
}
