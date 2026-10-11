package io.github.towkirahmedrony.salah.core.domain.qibla

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Damped heading for display.
 *
 * **Why smoothing on the circle, not on the number.** A magnetometer-fused heading jitters by a
 * degree or two sample to sample, which makes a compass needle visibly twitch. Averaging the
 * *numbers* would be wrong at the wrap: the mean of 359° and 1° is 180°, the exact opposite
 * direction. So the average is taken on the unit circle — the mean of the vectors
 * `(cos θ, sin θ)` — and converted back with `atan2`, which makes 359° and 1° average to 0°.
 *
 * **Why the weight is adaptive.** A single fixed weight cannot satisfy both requirements at once:
 *
 *  * heavy smoothing to kill jitter means a long time constant, and
 *  * a short time constant to track a real movement means jitter passes straight through.
 *
 * A first-order average with weight `w` lags by `(1 − w) / w` samples. At `w = 0.25` that is three
 * samples, which at the 10 Hz the compass is sampled at is 0.3 s — enough that a user turning at a
 * normal speed would watch the needle trail well behind the device *while they were turning to
 * align*, which is precisely when the reading has to be right.
 *
 * So the weight scales with how far the sample moved:
 *
 * ```text
 * weight = clamp(|sample − previous| / fullAdoptionDegrees, minimumWeight, 1)
 * ```
 *
 * A one-degree wobble gets the minimum weight and is smoothed away. A five-degree move gets half
 * weight. A change at or beyond [fullAdoptionDegrees] is adopted exactly, and a genuine turn
 * produces deviations that climb into that region within a sample or two, so the lag self-limits
 * instead of accumulating. There is consequently no separate "snap" rule: full adoption is simply
 * the top of the same curve.
 *
 * Measured behaviour, both of which are asserted in `HeadingSmootherTest`: a steady turn at
 * 18°/s tracks with about 2.4° of lag, and a steady turn at 100°/s tracks with none.
 *
 * Pure, so the timing behaviour is unit-tested rather than judged by eye on a device.
 */
class HeadingSmoother(
    private val minimumWeight: Double = DEFAULT_MINIMUM_WEIGHT,
    private val fullAdoptionDegrees: Double = DEFAULT_FULL_ADOPTION_DEGREES,
) {

    private var accumulatedCos: Double? = null
    private var accumulatedSin: Double? = null
    private var lastReported: Double? = null

    /** Forgets all history, so the next [update] is adopted unfiltered. */
    fun reset() {
        accumulatedCos = null
        accumulatedSin = null
        lastReported = null
    }

    /**
     * Feeds one raw heading and returns the smoothed heading.
     *
     * @param degrees raw heading in degrees; any value outside `[0, 360)` is folded first.
     * @return the smoothed heading, normalized into `[0, 360)`.
     */
    fun update(degrees: Double): Double {
        val normalized = HeadingMath.normalize(degrees)
        val sampleCos = cos(Math.toRadians(normalized))
        val sampleSin = sin(Math.toRadians(normalized))

        val previousCos = accumulatedCos
        val previousSin = accumulatedSin
        val previousReported = lastReported

        if (previousCos == null || previousSin == null || previousReported == null) {
            return adopt(normalized, sampleCos, sampleSin)
        }

        val deviation = abs(HeadingMath.shortestSignedDelta(previousReported, normalized))
        val weight = (deviation / fullAdoptionDegrees).coerceIn(minimumWeight, 1.0)

        // A real movement (or a first sample) is reported exactly. Filtering here could only delay
        // the needle without making it more accurate.
        if (weight >= 1.0) {
            return adopt(normalized, sampleCos, sampleSin)
        }

        val blendedCos = previousCos + weight * (sampleCos - previousCos)
        val blendedSin = previousSin + weight * (sampleSin - previousSin)

        // Two opposing samples can briefly cancel; reporting the arbitrary direction of numerical
        // dust would be worse than holding the previous value.
        if (hypot(blendedCos, blendedSin) < DEGENERATE_VECTOR_MAGNITUDE) return previousReported

        accumulatedCos = blendedCos
        accumulatedSin = blendedSin

        val smoothed = HeadingMath.normalize(Math.toDegrees(atan2(blendedSin, blendedCos)))
        lastReported = smoothed
        return smoothed
    }

    private fun adopt(degrees: Double, cosine: Double, sine: Double): Double {
        accumulatedCos = cosine
        accumulatedSin = sine
        lastReported = degrees
        return degrees
    }

    companion object {
        /**
         * Weight given to a sample that barely moved.
         *
         * `0.25` cuts sample-to-sample jitter by roughly a factor of four while keeping a small
         * residual response, so a slow drift is still followed rather than frozen out.
         */
        const val DEFAULT_MINIMUM_WEIGHT: Double = 0.25

        /**
         * Deviation at which a sample is adopted in full.
         *
         * Ten degrees: comfortably above sensor noise for any of the three sensor families, and
         * comfortably below the per-sample step of a deliberate turn, so a real movement is followed
         * immediately rather than averaged in.
         */
        const val DEFAULT_FULL_ADOPTION_DEGREES: Double = 10.0

        /** Below this the blended vector carries no usable direction. */
        private const val DEGENERATE_VECTOR_MAGNITUDE: Double = 1e-9
    }
}
