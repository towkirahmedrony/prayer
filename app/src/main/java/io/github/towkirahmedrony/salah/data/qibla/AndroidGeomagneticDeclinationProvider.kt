package io.github.towkirahmedrony.salah.data.qibla

import android.hardware.GeomagneticField
import io.github.towkirahmedrony.salah.core.domain.qibla.GeomagneticDeclinationProvider
import io.github.towkirahmedrony.salah.core.model.LocationInfo

/**
 * Magnetic declination from the World Magnetic Model bundled in the Android framework.
 *
 * **Why this and not a lookup table.** Declination varies with both position and time — it drifts by
 * up to about a tenth of a degree per year in places. A hard-coded table would be a snapshot that
 * silently ages, and Salah has no network to fetch a newer one.
 * `android.hardware.GeomagneticField` evaluates the model on the device from the coordinates, the
 * altitude and the date, so the estimate is correct for where and when it is asked, works offline,
 * needs no API key, and adds no dependency.
 *
 * **Cost.** One object construction per call. The Qibla screen asks at most once per location change,
 * not once per sensor sample.
 *
 * **What it is not.** This is a model estimate, not a measurement. Near the magnetic poles
 * declination changes steeply over short distances and its absolute value is large, so a bearing
 * corrected with it there is less certain than the same calculation in, say, Bangladesh. The Qibla
 * bearing itself is unaffected — it is geographic — and the documented consequence is that the
 * *alignment* indicator is the part whose accuracy varies.
 */
class AndroidGeomagneticDeclinationProvider : GeomagneticDeclinationProvider {

    override fun declinationDegrees(
        location: LocationInfo,
        atEpochMillis: Long,
        altitudeMetres: Double?,
    ): Double? = runCatching {
        GeomagneticField(
            location.latitude.toFloat(),
            location.longitude.toFloat(),
            // Declination changes by a few hundredths of a degree per kilometre of altitude, so sea
            // level is an acceptable stand-in when the altitude is unknown.
            (altitudeMetres ?: SEA_LEVEL_METRES).toFloat(),
            atEpochMillis,
        ).declination.toDouble()
    }.getOrNull()

    private companion object {
        const val SEA_LEVEL_METRES: Double = 0.0
    }
}
