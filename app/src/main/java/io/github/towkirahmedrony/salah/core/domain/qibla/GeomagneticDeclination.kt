package io.github.towkirahmedrony.salah.core.domain.qibla

import io.github.towkirahmedrony.salah.core.model.LocationInfo

/**
 * Estimates the angle from true north to magnetic north at a place and time.
 *
 * **Why this is needed at all.** The Qibla bearing from [QiblaCalculator] is referenced to **true**
 * north, because it is pure geography. A magnetic compass reads **magnetic** north, because that is
 * what a magnetometer measures. The two differ by the local declination — from about −20° in
 * southern Africa to +20° in parts of Canada and Siberia, and it drifts year by year. Comparing an
 * uncorrected magnetic heading against a true-north bearing and declaring "aligned" is therefore
 * wrong by up to 20°, which is far larger than the compass's own error and can point the user well
 * outside the correct direction.
 *
 * **How Salah obtains it.** `android.hardware.GeomagneticField`, which evaluates the World Magnetic
 * Model inside the platform — no network, no API key, no dependency. It takes the observer's
 * coordinates, the altitude and the date, so an update costs one object construction.
 *
 * Because it is a port, the domain has no Android import, the correction step is testable with a
 * fixed declination, and a future higher-order model would be a change in `data` only.
 */
interface GeomagneticDeclinationProvider {

    /**
     * Declination in degrees at [location], positive when magnetic north lies **east** of true
     * north, or `null` when no estimate is possible.
     *
     * Null is a real answer: without coordinates there is nothing to evaluate the model at, and
     * guessing zero would be silently asserting "no declination here".
     *
     * @param altitudeMetres height above the WGS-84 ellipsoid, or `null` when unknown. Declination
     *   changes by only a few hundredths of a degree per kilometre of altitude, so `null` is
     *   treated as sea level and this is documented rather than treated as an error.
     */
    fun declinationDegrees(
        location: LocationInfo,
        atEpochMillis: Long,
        altitudeMetres: Double? = null,
    ): Double?
}
