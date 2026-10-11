package io.github.towkirahmedrony.salah.feature.qibla

import android.Manifest
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.towkirahmedrony.salah.R
import io.github.towkirahmedrony.salah.core.designsystem.components.SalahSectionCard
import io.github.towkirahmedrony.salah.core.designsystem.components.SalahStatusPill
import io.github.towkirahmedrony.salah.core.designsystem.components.SalahUnavailableNote
import io.github.towkirahmedrony.salah.core.domain.location.LocationFreshness
import io.github.towkirahmedrony.salah.core.domain.qibla.LocationPermissionState
import io.github.towkirahmedrony.salah.core.domain.qibla.QiblaBearing
import io.github.towkirahmedrony.salah.core.domain.qibla.QiblaLocationState
import io.github.towkirahmedrony.salah.core.domain.qibla.QiblaPhase
import io.github.towkirahmedrony.salah.feature.common.LocationFormState
import io.github.towkirahmedrony.salah.feature.qibla.components.QiblaCompassDial
import org.koin.androidx.compose.koinViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.abs

/**
 * The Qibla screen.
 *
 * **What it always shows.** The calculated bearing, as soon as a location exists — because the
 * bearing is geography and needs no sensor, no permission and no network. A device with no
 * magnetometer, or a user who has declined location access, still gets an exact direction; only the
 * live dial is withheld, and with a stated reason.
 *
 * **What it never shows.** A dial that turns without a heading, a heading presented as true-north
 * when declination is unknown, or an alignment claim made while the device is tilted past the point
 * where the flat convention holds. Each of those becomes a sentence rather than a number.
 */
@Composable
fun QiblaScreen(
    modifier: Modifier = Modifier,
    viewModel: QiblaViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // The sensor listener's lifetime is the screen's visible lifetime. `ON_START`/`ON_STOP` rather
    // than composition, so a backgrounded screen stops reading the magnetometer.
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        viewModel.refreshPermissionState()
        viewModel.suggestDeviceZone()
        viewModel.startCompass()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        viewModel.stopCompass()
    }

    // Both permissions are requested together so Android 12+ can offer the user the choice between
    // "Approximate" and "Precise" and have it mean something. Either answer is enough for a bearing.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants.values.any { granted -> granted }) {
            viewModel.refreshPermissionState()
            viewModel.useCurrentLocation()
        } else {
            viewModel.onLocationPermissionDenied()
        }
    }

    val settingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        viewModel.refreshPermissionState()
    }

    QiblaContent(
        state = state,
        modifier = modifier,
        onRequestDeviceLocation = {
            if (state.permissionState.isGranted) {
                viewModel.useCurrentLocation()
            } else {
                permissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                        Manifest.permission.ACCESS_FINE_LOCATION,
                    ),
                )
            }
        },
        onOpenLocationSettings = {
            settingsLauncher.launch(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        },
        onFormChange = viewModel::onFormChange,
        onSaveManualLocation = viewModel::saveManualLocation,
        onLocationCleared = viewModel::clearLocation,
        onDismissMessage = viewModel::dismissMessage,
        onDismissCalibration = viewModel::dismissCalibrationNote,
    )
}

@Composable
private fun QiblaContent(
    state: QiblaUiState,
    onRequestDeviceLocation: () -> Unit,
    onOpenLocationSettings: () -> Unit,
    onFormChange: ((LocationFormState) -> LocationFormState) -> Unit,
    onSaveManualLocation: () -> Unit,
    onLocationCleared: () -> Unit,
    onDismissMessage: () -> Unit,
    onDismissCalibration: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.nav_qibla_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(R.string.qibla_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        state.message?.let { message ->
            MessageCard(text = stringResource(message.textRes()), onDismiss = onDismissMessage)
        }

        CompassCard(state = state)

        if (state.showCalibrationNote) {
            CalibrationCard(onDismiss = onDismissCalibration)
        }

        LocationCard(
            state = state,
            onRequestDeviceLocation = onRequestDeviceLocation,
            onOpenLocationSettings = onOpenLocationSettings,
            onFormChange = onFormChange,
            onSaveManualLocation = onSaveManualLocation,
            onLocationCleared = onLocationCleared,
        )

        SensorCard(state = state)

        LimitsCard(kaabaSource = state.kaabaSource)
    }
}

// --- Compass -----------------------------------------------------------------

@Composable
private fun CompassCard(state: QiblaUiState) {
    val heading = state.heading
    val bearingDegrees = state.bearingDegrees
    val turn = heading?.turnDegrees
    val isAligned = heading != null && turn != null &&
        heading.isOrientationUsable &&
        abs(turn) <= ALIGNMENT_TOLERANCE_DEGREES

    SalahSectionCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.qibla_section_compass),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            SalahStatusPill(
                text = stringResource(state.phase.statusRes()),
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (state.isAcquiringLocation) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(12.dp))
        }

        QiblaCompassDial(
            headingDegrees = heading?.trueDegrees,
            qiblaDegrees = bearingDegrees,
            isAligned = isAligned,
            isLive = state.isDialLive,
        )

        Spacer(modifier = Modifier.height(12.dp))

        TurnSummary(state = state)

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

        bearingDegrees?.let { bearing ->
            MetricRow(
                label = stringResource(R.string.qibla_bearing_label),
                value = stringResource(R.string.qibla_bearing_value, formatTenths(bearing)),
            )
        }

        heading?.let { current ->
            val trueDegrees = current.trueDegrees
            MetricRow(
                label = stringResource(R.string.qibla_heading_label),
                value = if (trueDegrees != null) {
                    stringResource(R.string.qibla_heading_true, formatTenths(trueDegrees))
                } else {
                    stringResource(
                        R.string.qibla_heading_magnetic_only,
                        formatTenths(current.magneticDegrees),
                    )
                },
            )
        }

        state.distanceToKaabaMetres?.let { distance ->
            MetricRow(
                label = stringResource(R.string.qibla_distance_label),
                value = stringResource(R.string.qibla_distance_value, formatTenths(distance / 1_000.0)),
            )
        }

        val explanation = when {
            state.phase == QiblaPhase.CALCULATION_ERROR -> {
                val reason = (state.bearing as? QiblaBearing.Unavailable)?.reason
                reason?.explanationRes() ?: R.string.qibla_status_calculation_error
            }

            state.phase == QiblaPhase.SENSOR_UNAVAILABLE -> R.string.qibla_sensor_unavailable

            heading != null && !heading.isOrientationUsable -> R.string.qibla_tilt_warning

            heading != null && heading.trueDegrees == null -> R.string.qibla_no_declination

            else -> null
        }

        explanation?.let { textRes ->
            Spacer(modifier = Modifier.height(12.dp))
            SalahUnavailableNote(text = stringResource(textRes))
        }
    }
}

/**
 * The headline: either an alignment statement or a turn instruction.
 *
 * Deliberately the largest text on the screen, because it is the only value the user acts on. The
 * direction comes from the *signed shortest* difference, so approaching the Qibla from 359° reads as
 * "turn right 2°" rather than as nearly a full rotation.
 */
@Composable
private fun TurnSummary(state: QiblaUiState) {
    val heading = state.heading
    val turn = heading?.turnDegrees

    val text = when {
        state.bearingDegrees == null -> stringResource(R.string.qibla_turn_unknown)

        heading == null || turn == null -> stringResource(R.string.qibla_turn_needs_compass)

        !heading.isOrientationUsable -> stringResource(R.string.qibla_turn_hold_flat)

        abs(turn) <= ALIGNMENT_TOLERANCE_DEGREES -> stringResource(R.string.qibla_turn_aligned)

        turn > 0 -> stringResource(R.string.qibla_turn_right, formatWhole(turn))

        else -> stringResource(R.string.qibla_turn_left, formatWhole(-turn))
    }

    val isAligned = heading != null && turn != null &&
        heading.isOrientationUsable &&
        abs(turn) <= ALIGNMENT_TOLERANCE_DEGREES

    Text(
        text = text,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.SemiBold,
        color = if (isAligned) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurface
        },
    )

    if (isAligned) {
        Text(
            text = stringResource(
                R.string.qibla_aligned_tolerance,
                formatWhole(ALIGNMENT_TOLERANCE_DEGREES),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CalibrationCard(onDismiss: () -> Unit) {
    SalahSectionCard {
        Text(
            text = stringResource(R.string.qibla_calibration_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.qibla_calibration_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onDismiss) {
            Text(text = stringResource(R.string.qibla_calibration_dismiss))
        }
    }
}

// --- Location ----------------------------------------------------------------

@Composable
private fun LocationCard(
    state: QiblaUiState,
    onRequestDeviceLocation: () -> Unit,
    onOpenLocationSettings: () -> Unit,
    onFormChange: ((LocationFormState) -> LocationFormState) -> Unit,
    onSaveManualLocation: () -> Unit,
    onLocationCleared: () -> Unit,
) {
    SalahSectionCard {
        Text(
            text = stringResource(R.string.qibla_section_location),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )

        Spacer(modifier = Modifier.height(8.dp))

        when (val location = state.location) {
            is QiblaLocationState.Available -> {
                Text(
                    text = location.location.label,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(
                        R.string.qibla_coordinates,
                        formatCoordinates(location.location.latitude),
                        formatCoordinates(location.location.longitude),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = freshnessText(
                        freshness = location.freshness,
                        accuracyMetres = location.location.accuracyMetres,
                        acquiredAt = location.location.acquiredAt,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (location.freshness.shouldOfferRefresh) {
                    Spacer(modifier = Modifier.height(6.dp))
                    SalahUnavailableNote(text = stringResource(R.string.qibla_location_not_current))
                }
            }

            QiblaLocationState.None -> SalahUnavailableNote(
                text = stringResource(R.string.qibla_location_none),
            )

            QiblaLocationState.PermissionDenied -> SalahUnavailableNote(
                text = stringResource(R.string.qibla_location_permission_denied),
            )

            is QiblaLocationState.Unavailable -> SalahUnavailableNote(
                text = stringResource(R.string.qibla_location_unavailable_body),
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = onRequestDeviceLocation,
                enabled = !state.isAcquiringLocation,
            ) {
                Text(
                    text = stringResource(
                        when {
                            state.isAcquiringLocation -> R.string.qibla_location_requesting
                            state.permissionState.isGranted -> R.string.qibla_location_refresh
                            else -> R.string.qibla_location_allow
                        },
                    ),
                )
            }

            if (state.isLocationServicesDisabled) {
                OutlinedButton(onClick = onOpenLocationSettings) {
                    Text(text = stringResource(R.string.qibla_location_settings))
                }
            }
        }

        if (state.permissionState == LocationPermissionState.COARSE) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.qibla_location_approximate_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

        Text(
            text = stringResource(R.string.qibla_manual_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.qibla_manual_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(10.dp))
        OutlinedTextField(
            value = state.form.label,
            onValueChange = { value -> onFormChange { it.copy(label = value) } },
            label = { Text(text = stringResource(R.string.qibla_manual_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = state.form.latitude,
                onValueChange = { value -> onFormChange { it.copy(latitude = value) } },
                label = { Text(text = stringResource(R.string.qibla_manual_latitude)) },
                singleLine = true,
                isError = state.form.hasValidationError,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = state.form.longitude,
                onValueChange = { value -> onFormChange { it.copy(longitude = value) } },
                label = { Text(text = stringResource(R.string.qibla_manual_longitude)) },
                singleLine = true,
                isError = state.form.hasValidationError,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = state.form.timeZoneId,
            onValueChange = { value -> onFormChange { it.copy(timeZoneId = value) } },
            label = { Text(text = stringResource(R.string.qibla_manual_time_zone)) },
            supportingText = { Text(text = stringResource(R.string.qibla_manual_time_zone_hint)) },
            singleLine = true,
            isError = state.form.hasValidationError,
            modifier = Modifier.fillMaxWidth(),
        )

        if (state.form.hasValidationError) {
            Spacer(modifier = Modifier.height(8.dp))
            SalahUnavailableNote(text = stringResource(R.string.qibla_manual_invalid))
        }

        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = onSaveManualLocation) {
                Text(text = stringResource(R.string.qibla_manual_save))
            }
            if (state.location is QiblaLocationState.Available) {
                OutlinedButton(onClick = onLocationCleared) {
                    Text(text = stringResource(R.string.qibla_manual_clear))
                }
            }
        }
    }
}

// --- Sensor ------------------------------------------------------------------

@Composable
private fun SensorCard(state: QiblaUiState) {
    SalahSectionCard {
        Text(
            text = stringResource(R.string.qibla_section_sensor),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(8.dp))

        val family = state.sensorFamily
        if (state.sensorAvailable && family != null) {
            MetricRow(
                label = stringResource(R.string.qibla_sensor_detected),
                value = stringResource(family.labelRes()),
            )
            state.heading?.accuracy?.let { accuracy ->
                MetricRow(
                    label = stringResource(R.string.qibla_sensor_magnetic_accuracy),
                    value = stringResource(accuracy.labelRes()),
                )
            }
        } else {
            SalahUnavailableNote(text = stringResource(R.string.qibla_sensor_unavailable))
        }
    }
}

@Composable
private fun LimitsCard(kaabaSource: String) {
    SalahSectionCard {
        Text(
            text = stringResource(R.string.qibla_section_limits),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.qibla_limits_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.qibla_kaaba_source, kaabaSource),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MessageCard(text: String, onDismiss: () -> Unit) {
    SalahSectionCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.qibla_message_dismiss))
            }
        }
    }
}

@Composable
private fun MetricRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

// --- Formatting ---------------------------------------------------------------

/** Tolerance within which the device is treated as facing the Qibla. */
private const val ALIGNMENT_TOLERANCE_DEGREES = 5.0

/**
 * Renders how the position was obtained, when it was taken and how accurate it was.
 *
 * A stored fix is never described as current: the age is stated outright, which is what lets the
 * screen comply with "do not claim a cached location is the user's position" without hiding the
 * position the bearing was actually calculated from.
 */
@Composable
private fun freshnessText(
    freshness: LocationFreshness,
    accuracyMetres: Double?,
    acquiredAt: Instant?,
): String {
    val stamp = acquiredAt?.let {
        DateTimeFormatter
            .ofLocalizedDateTime(FormatStyle.MEDIUM)
            .withLocale(Locale.getDefault())
            .withZone(ZoneId.systemDefault())
            .format(it)
    }

    val parts = buildList {
        if (stamp != null && freshness != LocationFreshness.FRESH) {
            add(stringResource(R.string.qibla_location_updated, stamp))
        } else {
            add(stringResource(freshness.labelRes()))
        }
        accuracyMetres?.let { add(stringResource(R.string.qibla_location_accuracy, formatWhole(it))) }
    }
    return parts.joinToString(separator = " · ")
}

private fun formatTenths(value: Double): String = "%.1f".format(Locale.getDefault(), value)

private fun formatWhole(value: Double): String = "%.0f".format(Locale.getDefault(), value)

private fun formatCoordinates(value: Double): String = "%.4f".format(Locale.getDefault(), value)
