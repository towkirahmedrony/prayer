package io.github.towkirahmedrony.salah.core.domain.qibla

import io.github.towkirahmedrony.salah.core.domain.location.LocationFreshness
import io.github.towkirahmedrony.salah.core.model.LocationInfo

/** Why no usable location is available. */
enum class LocationUnavailableReason {
    /** Location is switched off for the whole device. */
    SERVICES_DISABLED,

    /** A fix was requested and none arrived in time. */
    TIMEOUT,

    /** The provider reported an error. */
    PROVIDER_ERROR,
}

/**
 * What Salah currently knows about the user's position.
 *
 * Separate from [QiblaBearing] because the two fail independently: there can be a location with no
 * bearing (the user is standing on the Kaaba) and, transiently, a bearer with no location.
 */
sealed interface QiblaLocationState {

    /** Nothing is configured and nothing is being attempted. The user must choose. */
    data object None : QiblaLocationState

    /** Location permission has not been granted. Manual coordinates still work. */
    data object PermissionDenied : QiblaLocationState

    /** Permission exists but no position was obtained. */
    data class Unavailable(val reason: LocationUnavailableReason) : QiblaLocationState

    /**
     * A usable position.
     *
     * @param freshness whether this describes where the user is now, or where they were.
     */
    data class Available(
        val location: LocationInfo,
        val freshness: LocationFreshness,
    ) : QiblaLocationState
}

/**
 * The single state the Qibla screen presents.
 *
 * Derived by [QiblaStateReducer] from the independent facts (location, bearing, sensor, heading), so
 * the screen never has to decide precedence itself and the precedence can be tested directly.
 */
enum class QiblaPhase {

    /** No location yet, and not asking for one. */
    INITIAL,

    /** A position is being requested from the platform. */
    REQUESTING_LOCATION,

    /** Location permission was refused. Manual coordinates are the way forward. */
    LOCATION_PERMISSION_DENIED,

    /** Permission exists but no position arrived. */
    LOCATION_UNAVAILABLE,

    /**
     * A position exists but no bearing does — the observer is at, or at the antipode of, the Kaaba,
     * or the stored coordinates are invalid.
     */
    CALCULATION_ERROR,

    /**
     * A bearing is known but this device has no magnetometer-backed sensor.
     *
     * Not a failure: the Qibla direction is still exact, and the screen says the dial cannot turn
     * by itself.
     */
    SENSOR_UNAVAILABLE,

    /**
     * The bearing is known and the first sensor sample has not arrived.
     *
     * This merges two states a user would not distinguish — "we know where you are" and "waiting for
     * the compass" — because they occur within a few hundred milliseconds of each other and the
     * screen shows the same thing for both.
     */
    AWAITING_HEADING,

    /** The compass is live but the platform reports poor magnetic accuracy. */
    CALIBRATION_RECOMMENDED,

    /** The compass is live and reporting usable accuracy. */
    COMPASS_OPERATIONAL,
}

/** What the screen should tell the user about calibration, if anything. */
enum class CalibrationAdvice {

    /** Accuracy is usable; say nothing. */
    NOT_NEEDED,

    /**
     * The platform reports low or unreliable magnetic accuracy, so a figure-eight motion may help.
     */
    RECOMMENDED,

    /**
     * This device does not report magnetic accuracy at all, so Salah cannot know whether calibration
     * would help. No guidance is offered rather than guessing.
     */
    NOT_REPORTED,

    /** There is no sensor to calibrate. */
    NO_SENSOR,
}

/**
 * Turns the independent facts into one [QiblaPhase] and one [CalibrationAdvice].
 *
 * Pure and Android-free, so the whole state machine — including the cases that need a magnetometer
 * to observe on a device — is unit-tested.
 */
object QiblaStateReducer {

    /**
     * Resolves the phase.
     *
     * Precedence is deliberate and runs outermost-first: an in-flight request outranks everything
     * (it is what the user just did), then whether a position exists at all, then whether a bearing
     * can be derived from it, then whether the device can show a live direction.
     */
    fun phase(
        isAcquiringLocation: Boolean,
        location: QiblaLocationState,
        bearing: QiblaBearing?,
        sensor: CompassAvailability,
        hasHeading: Boolean,
        calibration: CalibrationAdvice,
    ): QiblaPhase {
        if (isAcquiringLocation) return QiblaPhase.REQUESTING_LOCATION

        when (location) {
            QiblaLocationState.PermissionDenied -> return QiblaPhase.LOCATION_PERMISSION_DENIED
            is QiblaLocationState.Unavailable -> return QiblaPhase.LOCATION_UNAVAILABLE
            QiblaLocationState.None -> return QiblaPhase.INITIAL
            is QiblaLocationState.Available -> Unit
        }

        // A location exists but produced no direction: at or opposite the Kaaba, or bad coordinates.
        if (bearing !is QiblaBearing.Available) return QiblaPhase.CALCULATION_ERROR

        if (sensor is CompassAvailability.Unavailable) return QiblaPhase.SENSOR_UNAVAILABLE
        if (!hasHeading) return QiblaPhase.AWAITING_HEADING
        if (calibration == CalibrationAdvice.RECOMMENDED) return QiblaPhase.CALIBRATION_RECOMMENDED

        return QiblaPhase.COMPASS_OPERATIONAL
    }

    /**
     * Resolves the calibration advice from the sensor's own confidence signal.
     *
     * Only the platform's accuracy value is consulted. Salah has no way to measure field strength
     * against an expected model here, and inventing a "calibration score" from, say, heading
     * variance would be presenting a guess as a measurement.
     */
    fun calibrationAdvice(
        sensor: CompassAvailability,
        accuracy: MagneticAccuracy?,
    ): CalibrationAdvice = when (sensor) {
        CompassAvailability.Unavailable -> CalibrationAdvice.NO_SENSOR

        is CompassAvailability.Available -> if (!sensor.reportsAccuracy) {
            CalibrationAdvice.NOT_REPORTED
        } else {
            when (accuracy) {
                MagneticAccuracy.UNRELIABLE, MagneticAccuracy.LOW -> CalibrationAdvice.RECOMMENDED
                MagneticAccuracy.MEDIUM, MagneticAccuracy.HIGH -> CalibrationAdvice.NOT_NEEDED

                // The sensor claims to report accuracy but has not yet sent a value, or sent a
                // value Salah cannot interpret. Neither is a reason to tell the user anything.
                null, MagneticAccuracy.UNKNOWN -> CalibrationAdvice.NOT_REPORTED
            }
        }
    }
}
