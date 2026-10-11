package io.github.towkirahmedrony.salah.core.domain.qibla

import kotlinx.coroutines.flow.Flow

/**
 * How trustworthy the device considers its own magnetic heading.
 *
 * Mirrors `SensorManager.SENSOR_STATUS_ACCURACY_*`, plus [UNKNOWN] for the case that matters most
 * for honesty: a device that simply does not report accuracy. Reporting "uncalibrated" in that case
 * would be inventing a measurement, and reporting "good" would be asserting one.
 */
enum class MagneticAccuracy { UNKNOWN, UNRELIABLE, LOW, MEDIUM, HIGH }

/**
 * One heading sample from the platform.
 *
 * @param magneticAzimuthDegrees the direction the top edge of the **display** points, in degrees
 *   clockwise from **magnetic** north, in `[0, 360)`. Magnetic, not true north: converting is
 *   [HeadingMath.toTrueHeading], and doing it here would hide the declination step from the tests.
 * @param accuracy the platform's own confidence in the reading.
 */
data class CompassReading(
    val magneticAzimuthDegrees: Double,
    val accuracy: MagneticAccuracy,
    val timestampMillis: Long,
    /**
     * How far the device is from horizontal, in degrees: the larger of its pitch and roll.
     *
     * **Why this is reported.** Salah's heading convention is the *flat* one — the direction of the
     * display's top edge projected on the horizontal plane — which is the convention
     * `SensorManager.getOrientation` produces and the one a hand-held compass is used in. Tilt the
     * device towards vertical and that projection collapses: the azimuth becomes sensitive to
     * fractions of a degree of movement and flickers over tens of degrees. Rather than quietly
     * showing a number that has stopped meaning anything, the reading carries its own tilt so the
     * screen can ask the user to lay the device flat.
     */
    val tiltDegrees: Double,
) {
    /**
     * True when the device is held close enough to horizontal for the heading to be meaningful.
     *
     * The threshold is generous on purpose: it is not a precision claim, only the point past which
     * the projection stops being usable.
     */
    val isOrientationUsable: Boolean get() = tiltDegrees <= HOLD_FLAT_THRESHOLD_DEGREES

    companion object {
        /** Beyond this tilt the flat-convention heading is too unstable to present. */
        const val HOLD_FLAT_THRESHOLD_DEGREES: Double = 40.0
    }
}

/** Which sensor combination is producing headings. */
enum class CompassSensorFamily {

    /**
     * `TYPE_ROTATION_VECTOR` — the fused rotation vector. Preferred: it combines accelerometer,
     * gyroscope and magnetometer into a north-referenced orientation that is already tilt
     * compensated and noticeably steadier than raw magnetometer output.
     */
    ROTATION_VECTOR,

    /**
     * `TYPE_GEOMAGNETIC_ROTATION_VECTOR` — a magnetometer-only rotation vector, typically far less
     * power-hungry. Used when the fused sensor is absent.
     */
    GEOMAGNETIC_ROTATION_VECTOR,

    /**
     * `TYPE_ACCELEROMETER` plus `TYPE_MAGNETIC_FIELD` — the classical pair, combined by
     * `SensorManager.getRotationMatrix`. The last resort, and the noisiest of the three.
     */
    ACCELEROMETER_AND_MAGNETIC_FIELD,
}

/**
 * Whether a usable compass exists on this device.
 *
 * Modelled as availability rather than assumed, because sensor complement varies enormously across
 * Android hardware: plenty of devices ship without a magnetometer at all, and a Qibla feature that
 * assumed one would simply show a dead dial there.
 */
sealed interface CompassAvailability {

    /**
     * @param reportsAccuracy whether the sensor supplies meaningful accuracy information. When
     *   `false`, Salah does not offer calibration guidance, because it would be guesswork.
     */
    data class Available(
        val family: CompassSensorFamily,
        val reportsAccuracy: Boolean,
    ) : CompassAvailability

    /** No magnetometer-backed orientation sensor is present. */
    data object Unavailable : CompassAvailability
}

/**
 * The device compass, as a port.
 *
 * A cold [readings] flow rather than a listener callback: registering and unregistering the sensor
 * is then tied to collection, so the listener's lifetime is the screen's lifetime and there is no
 * separate start/stop protocol to get wrong. Sensor registration is also the most battery-visible
 * thing this feature does, and one mechanism for it is better than two.
 */
interface CompassSensorSource {

    /** What this device can do, evaluated once. */
    fun availability(): CompassAvailability

    /**
     * Headings as the display orientation changes.
     *
     * Emits only while collected. Implementations must not keep a listener registered after the
     * collector is cancelled.
     */
    fun readings(): Flow<CompassReading>
}
