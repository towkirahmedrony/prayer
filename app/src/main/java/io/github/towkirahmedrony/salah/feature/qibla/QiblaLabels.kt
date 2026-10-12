package io.github.towkirahmedrony.salah.feature.qibla

import androidx.annotation.StringRes
import io.github.towkirahmedrony.salah.R
import io.github.towkirahmedrony.salah.core.domain.location.LocationFreshness
import io.github.towkirahmedrony.salah.core.domain.qibla.CompassSensorFamily
import io.github.towkirahmedrony.salah.core.domain.qibla.MagneticAccuracy
import io.github.towkirahmedrony.salah.core.domain.qibla.QiblaPhase
import io.github.towkirahmedrony.salah.core.domain.qibla.QiblaUnavailableReason

/**
 * Resource mappings for the Qibla domain types.
 *
 * Kept beside the feature rather than inside it so the screen reads as layout, and kept out of the
 * domain so the domain never depends on `R`.
 */

@StringRes
fun QiblaPhase.statusRes(): Int = when (this) {
    QiblaPhase.INITIAL -> R.string.qibla_status_initial
    QiblaPhase.REQUESTING_LOCATION -> R.string.qibla_status_requesting_location
    QiblaPhase.LOCATION_PERMISSION_DENIED -> R.string.qibla_status_permission_denied
    QiblaPhase.LOCATION_UNAVAILABLE -> R.string.qibla_status_location_unavailable
    QiblaPhase.CALCULATION_ERROR -> R.string.qibla_status_calculation_error
    QiblaPhase.SENSOR_UNAVAILABLE -> R.string.qibla_status_sensor_unavailable
    QiblaPhase.AWAITING_HEADING -> R.string.qibla_status_awaiting_heading
    QiblaPhase.CALIBRATION_RECOMMENDED -> R.string.qibla_status_calibration_recommended
    QiblaPhase.COMPASS_OPERATIONAL -> R.string.qibla_status_operational
}

@StringRes
fun QiblaUnavailableReason.explanationRes(): Int = when (this) {
    QiblaUnavailableReason.INVALID_COORDINATES -> R.string.qibla_error_invalid_coordinates
    QiblaUnavailableReason.AT_KAABA -> R.string.qibla_error_at_kaaba
    QiblaUnavailableReason.ANTIPODAL -> R.string.qibla_error_antipodal
}

@StringRes
fun CompassSensorFamily.labelRes(): Int = when (this) {
    CompassSensorFamily.ROTATION_VECTOR -> R.string.qibla_sensor_rotation_vector
    CompassSensorFamily.GEOMAGNETIC_ROTATION_VECTOR -> R.string.qibla_sensor_geomagnetic
    CompassSensorFamily.ACCELEROMETER_AND_MAGNETIC_FIELD -> R.string.qibla_sensor_accelerometer_magnetic
}

@StringRes
fun MagneticAccuracy.labelRes(): Int = when (this) {
    MagneticAccuracy.UNKNOWN -> R.string.qibla_accuracy_unknown
    MagneticAccuracy.UNRELIABLE -> R.string.qibla_accuracy_unreliable
    MagneticAccuracy.LOW -> R.string.qibla_accuracy_low
    MagneticAccuracy.MEDIUM -> R.string.qibla_accuracy_medium
    MagneticAccuracy.HIGH -> R.string.qibla_accuracy_high
}

@StringRes
fun LocationFreshness.labelRes(): Int = when (this) {
    LocationFreshness.DECLARED -> R.string.qibla_location_declared
    LocationFreshness.FRESH -> R.string.qibla_location_fresh

    // RECENT and STALE read the same on purpose: both are a stored position whose recorded time is
    // shown alongside, and the difference between "a few hours ago" and "three days ago" is in that
    // timestamp rather than in a second word for it.
    LocationFreshness.RECENT -> R.string.qibla_location_saved
    LocationFreshness.STALE -> R.string.qibla_location_saved
    LocationFreshness.UNKNOWN -> R.string.qibla_location_unknown_time
}

@StringRes
fun QiblaMessage.textRes(): Int = when (this) {
    QiblaMessage.LocationSaved -> R.string.qibla_msg_location_saved
    QiblaMessage.LocationRejected -> R.string.qibla_msg_location_rejected
    QiblaMessage.LocationCleared -> R.string.qibla_msg_location_cleared
    QiblaMessage.DeviceLocationAcquired -> R.string.qibla_msg_device_acquired
    QiblaMessage.OutsideSupportedCountry -> R.string.qibla_msg_outside_country
    QiblaMessage.AreaNotAttributed -> R.string.qibla_msg_not_attributed
    QiblaMessage.PermissionDenied -> R.string.qibla_msg_permission_denied
    QiblaMessage.ServicesDisabled -> R.string.qibla_msg_services_disabled
    QiblaMessage.LocationTimedOut -> R.string.qibla_msg_timed_out
    QiblaMessage.LocationFailed -> R.string.qibla_msg_failed
}
