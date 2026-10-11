package io.github.towkirahmedrony.salah.core.domain.qibla

import io.github.towkirahmedrony.salah.core.domain.location.LocationFreshness
import io.github.towkirahmedrony.salah.core.model.LocationInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Qibla screen's state machine.
 *
 * The precedence between "no location", "no bearing" and "no sensor" is decided here rather than by
 * a chain of `if`s in the layout, so it can be tested — including the combinations that need
 * particular hardware to observe on a device, such as a phone with a location but no magnetometer.
 */
class QiblaStateReducerTest {

    private val availableSensor = CompassAvailability.Available(
        family = CompassSensorFamily.ROTATION_VECTOR,
        reportsAccuracy = true,
    )
    private val silentAccuracySensor = CompassAvailability.Available(
        family = CompassSensorFamily.ACCELEROMETER_AND_MAGNETIC_FIELD,
        reportsAccuracy = false,
    )
    private val bearing = QiblaBearing.Available(degrees = 277.5, distanceMetres = 5_172_000.0)

    private val location = requireNotNull(
        LocationInfo.create(
            label = "Dhaka",
            latitude = 23.8103,
            longitude = 90.4125,
            timeZoneId = "Asia/Dhaka",
        ),
    )

    private val located = QiblaLocationState.Available(
        location = location,
        freshness = LocationFreshness.DECLARED,
    )

    // --- Phase -----------------------------------------------------------------

    @Test
    fun `an in-flight request outranks every other fact`() {
        // It is what the user just did, so it is what the screen reports, even if a previous
        // location and bearing are still on screen behind the spinner.
        assertEquals(
            QiblaPhase.REQUESTING_LOCATION,
            phase(isAcquiringLocation = true),
        )
    }

    @Test
    fun `no location and no attempt is the initial state`() {
        assertEquals(
            QiblaPhase.INITIAL,
            phase(location = QiblaLocationState.None),
        )
    }

    @Test
    fun `refused permission is reported as such`() {
        assertEquals(
            QiblaPhase.LOCATION_PERMISSION_DENIED,
            phase(location = QiblaLocationState.PermissionDenied),
        )
    }

    @Test
    fun `a failed acquisition is reported as unavailable`() {
        assertEquals(
            QiblaPhase.LOCATION_UNAVAILABLE,
            phase(
                location = QiblaLocationState.Unavailable(LocationUnavailableReason.TIMEOUT),
            ),
        )
    }

    @Test
    fun `a location with no bearing is a calculation error`() {
        listOf(
            QiblaUnavailableReason.AT_KAABA,
            QiblaUnavailableReason.ANTIPODAL,
            QiblaUnavailableReason.INVALID_COORDINATES,
        ).forEach { reason ->
            assertEquals(
                "for $reason",
                QiblaPhase.CALCULATION_ERROR,
                phase(bearing = QiblaBearing.Unavailable(reason)),
            )
        }
    }

    @Test
    fun `a missing bearing is a calculation error rather than a waiting state`() {
        // A null bearing means the calculation has not produced anything at all, which for an
        // available location is a failure and not a pending step.
        assertEquals(
            QiblaPhase.CALCULATION_ERROR,
            phase(bearing = null),
        )
    }

    @Test
    fun `a device without a compass shows the bearing and says the dial cannot turn`() {
        assertEquals(
            QiblaPhase.SENSOR_UNAVAILABLE,
            phase(sensor = CompassAvailability.Unavailable),
        )
    }

    @Test
    fun `a bearing with no heading yet is awaiting the compass`() {
        assertEquals(
            QiblaPhase.AWAITING_HEADING,
            phase(hasHeading = false),
        )
    }

    @Test
    fun `low reported accuracy raises the calibration phase`() {
        assertEquals(
            QiblaPhase.CALIBRATION_RECOMMENDED,
            phase(calibration = CalibrationAdvice.RECOMMENDED),
        )
    }

    @Test
    fun `everything present and trustworthy is the operational phase`() {
        assertEquals(
            QiblaPhase.COMPASS_OPERATIONAL,
            phase(),
        )
    }

    @Test
    fun `awaiting the heading outranks a calibration suggestion`() {
        // Nothing has been measured yet, so there is nothing to calibrate against.
        assertEquals(
            QiblaPhase.AWAITING_HEADING,
            phase(hasHeading = false, calibration = CalibrationAdvice.RECOMMENDED),
        )
    }

    @Test
    fun `a missing sensor outranks a calibration suggestion`() {
        // The advice only exists when there is a sensor to advise about.
        assertEquals(
            QiblaPhase.SENSOR_UNAVAILABLE,
            phase(
                sensor = CompassAvailability.Unavailable,
                calibration = CalibrationAdvice.RECOMMENDED,
            ),
        )
    }

    @Test
    fun `a missing location outranks everything except an in-flight request`() {
        assertEquals(
            QiblaPhase.INITIAL,
            phase(location = QiblaLocationState.None, hasHeading = true),
        )
    }

    // --- Calibration advice ----------------------------------------------------

    @Test
    fun `no sensor means there is nothing to calibrate`() {
        assertEquals(
            CalibrationAdvice.NO_SENSOR,
            QiblaStateReducer.calibrationAdvice(CompassAvailability.Unavailable, MagneticAccuracy.LOW),
        )
    }

    @Test
    fun `a sensor that does not report accuracy gets no advice`() {
        // The rule that stops Salah inventing a calibration score: without a reported accuracy there
        // is no evidence either way, so the screen stays quiet.
        MagneticAccuracy.entries.forEach { accuracy ->
            assertEquals(
                "for $accuracy",
                CalibrationAdvice.NOT_REPORTED,
                QiblaStateReducer.calibrationAdvice(silentAccuracySensor, accuracy),
            )
        }
    }

    @Test
    fun `an accuracy that has not arrived yet gets no advice`() {
        assertEquals(
            CalibrationAdvice.NOT_REPORTED,
            QiblaStateReducer.calibrationAdvice(availableSensor, null),
        )
        assertEquals(
            CalibrationAdvice.NOT_REPORTED,
            QiblaStateReducer.calibrationAdvice(availableSensor, MagneticAccuracy.UNKNOWN),
        )
    }

    @Test
    fun `unreliable and low accuracy ask for calibration`() {
        assertEquals(
            CalibrationAdvice.RECOMMENDED,
            QiblaStateReducer.calibrationAdvice(availableSensor, MagneticAccuracy.UNRELIABLE),
        )
        assertEquals(
            CalibrationAdvice.RECOMMENDED,
            QiblaStateReducer.calibrationAdvice(availableSensor, MagneticAccuracy.LOW),
        )
    }

    @Test
    fun `medium and high accuracy need nothing`() {
        assertEquals(
            CalibrationAdvice.NOT_NEEDED,
            QiblaStateReducer.calibrationAdvice(availableSensor, MagneticAccuracy.MEDIUM),
        )
        assertEquals(
            CalibrationAdvice.NOT_NEEDED,
            QiblaStateReducer.calibrationAdvice(availableSensor, MagneticAccuracy.HIGH),
        )
    }

    @Test
    fun `every accuracy value produces a defined piece of advice`() {
        // Exhaustiveness as a test: a new accuracy constant must be considered deliberately rather
        // than falling into a default that happens to look reasonable.
        MagneticAccuracy.entries.forEach { accuracy ->
            val advice = QiblaStateReducer.calibrationAdvice(availableSensor, accuracy)
            assertTrue("$accuracy produced $advice", advice in CalibrationAdvice.entries)
        }
    }

    private fun phase(
        isAcquiringLocation: Boolean = false,
        location: QiblaLocationState = located,
        bearing: QiblaBearing? = this.bearing,
        sensor: CompassAvailability = availableSensor,
        hasHeading: Boolean = true,
        calibration: CalibrationAdvice = CalibrationAdvice.NOT_NEEDED,
    ): QiblaPhase = QiblaStateReducer.phase(
        isAcquiringLocation = isAcquiringLocation,
        location = location,
        bearing = bearing,
        sensor = sensor,
        hasHeading = hasHeading,
        calibration = calibration,
    )
}
