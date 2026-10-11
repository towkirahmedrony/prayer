package io.github.towkirahmedrony.salah.core.domain.qibla

/**
 * An azimuth measured clockwise from **magnetic** north.
 *
 * A distinct type, not a `Double`, so that a magnetic angle and a true-north angle cannot be
 * swapped by accident. This is the mechanism that makes double declination correction impossible
 * rather than merely discouraged: [HeadingMath.toTrueHeading] accepts a [MagneticHeading] and
 * produces a [TrueHeading], and there is no function anywhere that accepts a [TrueHeading] and
 * applies declination again.
 */
@JvmInline
value class MagneticHeading(val degrees: Double)

/** An azimuth measured clockwise from **true** (geographic) north. */
@JvmInline
value class TrueHeading(val degrees: Double)

/**
 * Angle arithmetic on the circle, separate from anything Android.
 *
 * **The convention used throughout Salah.** All headings and bearings are degrees clockwise from
 * north, in `[0, 360)` — the same convention as a compass rose. "Turn" angles are degrees
 * clockwise from the reference direction, in `(-180, 180]`, so a positive turn means the target
 * lies to the device's right.
 *
 * Kept Android-free so that wrapping, normalization and the declination step are unit-tested
 * without a sensor, a display or a device.
 */
object HeadingMath {

    /**
     * Folds an angle in degrees into `[0, 360)`.
     *
     * Anything not finite folds to `0.0` rather than being propagated: NaN reaching the compass
     * would silently rotate the dial to an arbitrary place, and a visible zero is a bug report while
     * a silently wrong dial is not. Infinity folds for the same reason — `infinity % 360` is NaN in
     * IEEE arithmetic, so testing only for NaN would let it through.
     */
    fun normalize(degrees: Double): Double {
        if (!degrees.isFinite()) return 0.0
        val wrapped = degrees % 360.0
        return if (wrapped < 0) wrapped + 360.0 else wrapped
    }

    /** Builds a normalized [MagneticHeading]. */
    fun magnetic(degrees: Double): MagneticHeading = MagneticHeading(normalize(degrees))

    /** Builds a normalized [TrueHeading]. */
    fun trueHeading(degrees: Double): TrueHeading = TrueHeading(normalize(degrees))

    /**
     * Converts a magnetic heading to a true heading by adding the local magnetic declination.
     *
     * **Applied exactly once.** Declination is the angle from true north to magnetic north, so
     * `true = magnetic + declination`. Because this is the only place it is applied, and because it
     * consumes a [MagneticHeading], the classic bug — correcting an already-corrected heading,
     * producing an error of twice the declination — cannot be expressed.
     *
     * A null [declinationDegrees] means no estimate is available, and the caller must then present
     * the heading as magnetic rather than claim an alignment against a true-north bearing.
     */
    fun toTrueHeading(magnetic: MagneticHeading, declinationDegrees: Double): TrueHeading =
        TrueHeading(normalize(magnetic.degrees + declinationDegrees))

    /**
     * The shortest signed rotation from [fromDegrees] to [toDegrees], in `(-180, 180]`.
     *
     * Positive is clockwise. This is the single definition of "how far apart are these two
     * directions" used by the compass, the alignment indicator and the dial, so the UI cannot
     * disagree with itself about whether a target is left or right.
     *
     * The wraparound case is the whole point: 359° to 1° is `+2°`, not `-358°`.
     */
    fun shortestSignedDelta(fromDegrees: Double, toDegrees: Double): Double {
        val raw = (toDegrees - fromDegrees) % 360.0
        return when {
            raw > 180.0 -> raw - 360.0
            raw <= -180.0 -> raw + 360.0
            else -> raw
        }
    }

    /**
     * Where the Qibla lies relative to where the device is pointing.
     *
     * Positive means the Qibla is clockwise from the current heading, i.e. "turn right". Both
     * inputs must be on the same north reference — enforced by the parameter types — because
     * comparing a true-north bearing with an uncorrected magnetic heading is exactly the error the
     * reference types exist to prevent.
     */
    fun turnFromTrue(from: TrueHeading, to: TrueHeading): Double =
        shortestSignedDelta(from.degrees, to.degrees)

    /**
     * The dial rotation, in degrees, that places the Qibla marker on screen.
     *
     * The dial is drawn once in a fixed frame and rotated by the negative of the current heading so
     * that the top of the dial always represents where the device points. The marker therefore
     * lands at the signed turn, which is what keeps the display correct through 0°/360°.
     */
    fun dialRotationDegrees(heading: TrueHeading): Double = -normalize(heading.degrees)
}
