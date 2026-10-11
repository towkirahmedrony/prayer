package io.github.towkirahmedrony.salah.core.domain.qibla

import io.github.towkirahmedrony.salah.core.model.LocationInfo
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The Qibla bearing: the initial great-circle direction from an observer to the Kaaba.
 *
 * **Which quantity this is.** It is the direction of the *shortest path over the Earth's surface*
 * at the moment of departure — the initial bearing of the great circle through the observer and the
 * Kaaba. It is not the compass heading of the device, not the rhumb line (constant-compass) course,
 * and not the final bearing on arrival; on a long route those differ by many degrees. Confusing
 * them is the most common Qibla bug, so this calculator returns the initial great-circle bearing
 * and nothing else.
 *
 * **The formula.** With `φ1, λ1` the observer and `φ2, λ2` the Kaaba, and `Δλ = λ2 − λ1` the
 * eastward longitude difference:
 *
 * ```text
 * θ = atan2( sin Δλ · cos φ2 ,  cos φ1 · sin φ2 − sin φ1 · cos φ2 · cos Δλ )
 * ```
 *
 * `atan2` is used rather than `asin`/`acos` on the arcsine form because it is unambiguous over all
 * four quadrants and does not lose precision near ±90°, and the result is normalized into
 * `[0, 360)`.
 *
 * The implementation is deliberately free of Android types, so the whole of the geography is
 * unit-tested directly, including the cases below.
 *
 * **Edge cases handled.** Out-of-range or non-finite coordinates are refused. `Δλ` is normalized
 * into `(-180, 180]` so that a difference of `+350°` is treated as `-10°`, which is what makes a
 * location either side of the antimeridian behave identically. An observer at the Kaaba reference
 * point is refused, because the direction from a point to itself does not exist. An observer at the
 * numerical antipode is refused, because `atan2(0, 0)` would otherwise return `0.0` and quietly
 * report "due north".
 */
class QiblaCalculator {

    /** Qibla bearing from a stored location. */
    fun bearingFrom(location: LocationInfo): QiblaBearing =
        bearingFrom(latitude = location.latitude, longitude = location.longitude)

    /**
     * Qibla bearing from raw coordinates in degrees.
     *
     * @return [QiblaBearing.Available] with degrees in `[0, 360)` clockwise from true north, or
     *   [QiblaBearing.Unavailable] when the input is invalid or the direction is undefined.
     */
    fun bearingFrom(latitude: Double, longitude: Double): QiblaBearing {
        if (!isValidLatitude(latitude) || !isValidLongitude(longitude)) {
            return QiblaBearing.Unavailable(QiblaUnavailableReason.INVALID_COORDINATES)
        }

        val distance = distanceToKaabaMetres(latitude, longitude)

        // Checked before the degeneracy test: standing on the Kaaba makes both formula components
        // vanish for a different reason than the antipode does, and the two need different answers.
        if (distance < KaabaReference.MINIMUM_SEPARATION_METRES) {
            return QiblaBearing.Unavailable(QiblaUnavailableReason.AT_KAABA)
        }

        val phi1 = Math.toRadians(latitude)
        val phi2 = Math.toRadians(KaabaReference.LATITUDE_DEGREES)
        val deltaLambda = Math.toRadians(signedLongitudeDeltaDegrees(longitude))

        val east = sin(deltaLambda) * cos(phi2)
        val north = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(deltaLambda)

        if (hypot(east, north) < DEGENERACY_EPSILON) {
            return QiblaBearing.Unavailable(QiblaUnavailableReason.ANTIPODAL)
        }

        val degrees = normalizeDegrees(Math.toDegrees(atan2(east, north)))
        return QiblaBearing.Available(degrees = degrees, distanceMetres = distance)
    }

    /**
     * Great-circle distance to the Kaaba, in metres, by the haversine formula.
     *
     * Its own method rather than a by-product of the bearing: the near-Kaaba test needs the
     * distance *before* the bearing is computed, and the UI shows it as a real quantity rather than
     * an internal detail.
     */
    fun distanceToKaabaMetres(latitude: Double, longitude: Double): Double {
        val phi1 = Math.toRadians(latitude)
        val phi2 = Math.toRadians(KaabaReference.LATITUDE_DEGREES)
        val deltaPhi = Math.toRadians(KaabaReference.LATITUDE_DEGREES - latitude)
        val deltaLambda = Math.toRadians(signedLongitudeDeltaDegrees(longitude))

        val a = sin(deltaPhi / 2).let { it * it } +
            cos(phi1) * cos(phi2) * sin(deltaLambda / 2).let { it * it }

        // asin(sqrt(a)) rather than the atan2 form: for the short distances this method is used
        // over, the atan2 variant is the one that loses precision.
        return 2 * EARTH_RADIUS_METRES * asin(min(1.0, sqrt(a)))
    }

    companion object {

        /** IUGG mean Earth radius, in metres. */
        const val EARTH_RADIUS_METRES: Double = 6_371_008.8

        /**
         * Horizontal-component magnitude below which the bearing is treated as undefined.
         *
         * This is a *conditioning* threshold, not just a numerical one. Close to the antipode the
         * direction to the Kaaba turns through nearly 180° per metre of displacement, so a number
         * computed there carries no information. `1e-6` corresponds to roughly 4 m from the exact
         * antipode — well outside floating-point noise, and inside the region where the answer
         * would be meaningless anyway.
         */
        const val DEGENERACY_EPSILON: Double = 1e-6

        /**
         * The eastward longitude difference from [longitude] to the Kaaba, in `(-180, 180]`.
         *
         * `(d + 540) mod 360 - 180` folds any difference onto the short way round, so `+350°` and
         * `-10°` — the same physical separation — are treated identically instead of producing
         * mirrored answers. It is a pure function of a single argument, so it lives with the other
         * static helpers rather than on an instance.
         */
        fun signedLongitudeDeltaDegrees(longitude: Double): Double =
            ((KaabaReference.LONGITUDE_DEGREES - longitude + 540.0) % 360.0) - 180.0

        /** Normalizes an angle in degrees into `[0, 360)`. */
        fun normalizeDegrees(degrees: Double): Double {
            val wrapped = degrees % 360.0
            return if (wrapped < 0) wrapped + 360.0 else wrapped
        }

        private fun isValidLatitude(value: Double): Boolean =
            value.isFinite() && abs(value) <= LocationInfo.MAX_LATITUDE

        private fun isValidLongitude(value: Double): Boolean =
            value.isFinite() && abs(value) <= LocationInfo.MAX_LONGITUDE
    }
}
