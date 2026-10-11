package io.github.towkirahmedrony.salah.feature.qibla

import io.github.towkirahmedrony.salah.core.domain.qibla.CalibrationAdvice
import io.github.towkirahmedrony.salah.core.domain.qibla.CompassSensorFamily
import io.github.towkirahmedrony.salah.core.domain.qibla.MagneticAccuracy
import io.github.towkirahmedrony.salah.core.domain.qibla.QiblaBearing
import io.github.towkirahmedrony.salah.core.domain.qibla.LocationPermissionState
import io.github.towkirahmedrony.salah.core.domain.qibla.LocationUnavailableReason
import io.github.towkirahmedrony.salah.core.domain.qibla.QiblaLocationState
import io.github.towkirahmedrony.salah.core.domain.qibla.QiblaPhase
import io.github.towkirahmedrony.salah.feature.common.LocationFormState

/**
 * Where the device is pointing, and where the Qibla is relative to that.
 *
 * Both angles are carried, not just the difference, because they answer different questions: the
 * heading is "which way am I facing", the turn is "which way do I turn". Around 0°/360° the turn is
 * the only one of the two that is safe to act on, since the raw difference of 359° and 1° is 358°
 * and the signed shortest difference is 2°.
 */
data class QiblaHeadingUi(
    /**
     * Heading measured from true north, or `null` when no declination estimate is available.
     *
     * Null is the honest answer rather than a fallback to magnetic: without declination there is no
     * way to compare a magnetic reading against the true-north bearing, and showing an uncorrected
     * angle next to one would invite exactly the misreading the correction exists to prevent.
     */
    val trueDegrees: Double?,

    /** Heading measured from magnetic north — always known while the sensor reports. */
    val magneticDegrees: Double,

    /** Signed shortest rotation from the heading to the Qibla, or `null` when [trueDegrees] is. */
    val turnDegrees: Double?,

    /** The platform's own confidence, or `null` while the accuracy gate is still warming up. */
    val accuracy: MagneticAccuracy?,

    /** How far the device is from horizontal; large values make the flat convention unusable. */
    val tiltDegrees: Double,

    /** Whether [tiltDegrees] is small enough for the heading to mean anything. */
    val isOrientationUsable: Boolean,
)

/** Transient feedback shown after an action. */
sealed interface QiblaMessage {

    /** A manual location was stored. */
    data object LocationSaved : QiblaMessage

    /** Manual input was rejected; the form is flagged. */
    data object LocationRejected : QiblaMessage

    /** The stored location was removed. */
    data object LocationCleared : QiblaMessage

    /** A device fix was obtained and stored. */
    data object DeviceLocationAcquired : QiblaMessage

    /** A device fix was requested without permission. */
    data object PermissionDenied : QiblaMessage

    /** Location is switched off for the whole device. */
    data object ServicesDisabled : QiblaMessage

    /** No fix arrived in time. */
    data object LocationTimedOut : QiblaMessage

    /** The provider failed. */
    data object LocationFailed : QiblaMessage
}

/**
 * Everything the Qibla screen renders.
 *
 * [phase] is derived by
 * [io.github.towkirahmedrony.salah.core.domain.qibla.QiblaStateReducer] rather than assembled here,
 * so the precedence between "no location", "no bearing" and "no sensor" is decided in one tested
 * place instead of being implied by a chain of `if`s in the UI.
 */
data class QiblaUiState(
    val phase: QiblaPhase,
    val location: QiblaLocationState,
    val bearing: QiblaBearing?,
    val heading: QiblaHeadingUi?,
    val sensorAvailable: Boolean,
    val sensorFamily: CompassSensorFamily?,
    val calibration: CalibrationAdvice,
    val showCalibrationNote: Boolean,
    val kaabaSource: String,
    val form: LocationFormState,
    val isAcquiringLocation: Boolean,
    val message: QiblaMessage?,
    /** Whether automatic location is possible at all, and at what precision. */
    val permissionState: LocationPermissionState,
) {
    /** The bearing in degrees, or `null` when there is none to show. */
    val bearingDegrees: Double?
        get() = (bearing as? QiblaBearing.Available)?.degrees

    /** Distance to the Kaaba in metres, or `null` when the bearing is unavailable. */
    val distanceToKaabaMetres: Double?
        get() = (bearing as? QiblaBearing.Available)?.distanceMetres

    /**
     * True when the dial should be drawn turning with the device.
     *
     * Requires a heading *and* a usable orientation *and* a true-north reference: a dial that
     * animated on a tilted device or on an uncorrected heading would be showing movement that does
     * not correspond to the alignment it appears to claim.
     */
    val isDialLive: Boolean
        get() = heading?.trueDegrees != null && heading.isOrientationUsable

    /**
     * True when location is switched off for the whole device.
     *
     * A separate flag because it is the one location failure the user can fix without leaving the
     * screen's subject: the offer is to open the system location settings, not to retry.
     */
    val isLocationServicesDisabled: Boolean
        get() = (location as? QiblaLocationState.Unavailable)?.reason ==
            LocationUnavailableReason.SERVICES_DISABLED
}
