package io.github.towkirahmedrony.salah.feature.qibla

import android.Manifest
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.towkirahmedrony.salah.R
import io.github.towkirahmedrony.salah.core.designsystem.CheckGlyph
import io.github.towkirahmedrony.salah.core.designsystem.LocationPinGlyph
import io.github.towkirahmedrony.salah.core.designsystem.QiblaGlyph
import io.github.towkirahmedrony.salah.core.designsystem.RulerGlyph
import io.github.towkirahmedrony.salah.core.designsystem.components.SalahSectionCard
import io.github.towkirahmedrony.salah.core.designsystem.components.SalahStatusPill
import io.github.towkirahmedrony.salah.core.designsystem.components.SalahUnavailableNote
import io.github.towkirahmedrony.salah.core.domain.location.LocationDisplayName
import io.github.towkirahmedrony.salah.core.domain.location.LocationFreshness
import io.github.towkirahmedrony.salah.core.domain.qibla.LocationPermissionState
import io.github.towkirahmedrony.salah.core.domain.qibla.QiblaBearing
import io.github.towkirahmedrony.salah.core.domain.qibla.QiblaLocationState
import io.github.towkirahmedrony.salah.core.domain.qibla.QiblaPhase
import io.github.towkirahmedrony.salah.core.model.LocationOrigin
import io.github.towkirahmedrony.salah.feature.common.LocationFormState
import io.github.towkirahmedrony.salah.feature.common.sourceLabelRes
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
 * **The shape of the screen.** One reading, then the detail: a heading that names the position it is
 * calculated from and its current status, the dial with the instruction over it, the bearing, the
 * distance and the compass accuracy as three equal readings under it, and — only when the dial cannot
 * be trusted at this moment — one sentence saying why. Everything that is not the reading is a card
 * below: the location and its actions, and the sensor with what the dial can and cannot show.
 *
 * **What it always shows.** The calculated bearing, as soon as a location exists — because the
 * bearing is geography and needs no sensor, no permission and no network. A device with no
 * magnetometer, or a user who has declined location access, still gets an exact direction; only the
 * live dial is withheld, and with a stated reason.
 *
 * **What it never shows.** A dial that turns without a heading, a heading presented as true-north
 * when declination is unknown, or an alignment claim made while the device is tilted past the point
 * where the flat convention holds. Each of those becomes a sentence rather than a number.
 *
 * **What it shows once.** Each fact has exactly one place. The position's label and how it was
 * obtained are on the heading line; its coordinates, age and accuracy are in the Location card; the
 * bearing is a reading and the bearing's reference frame is prose; the compass accuracy is a reading
 * and the sensor behind it is in the last card. The intro paragraph, the second bearing row, the
 * duplicated compass status and the per-card sensor labelling the first version carried are gone.
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
    // Saveable so that a rotation does not close the form the user is typing into. It is the one
    // piece of state the screen owns itself: whether a disclosure is open is not something the app's
    // stored state should have to carry.
    var isManualOpen by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        QiblaHeader(state = state)

        // The one request in flight, drawn where the eye already is. The pill names the state and
        // this says the screen is working on it, so the button below does not look ignored.
        if (state.isAcquiringLocation) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        state.message?.let { message ->
            MessageCard(text = stringResource(message.textRes()), onDismiss = onDismissMessage)
        }

        val headline = headlineKindOf(state)
        CompassHero(state = state, headline = headline)
        // The bearing is a reading, and it is shown once: as the headline when no turn can be stated,
        // and in the readings row when the headline is a turn or an alignment statement instead.
        DirectionMetrics(state = state, showsBearing = headline != HeadlineKind.BEARING)
        DirectionNote(state = state)

        if (state.showCalibrationNote) {
            CalibrationCard(onDismiss = onDismissCalibration)
        }

        LocationCard(
            state = state,
            isManualOpen = isManualOpen,
            onToggleManual = { isManualOpen = !isManualOpen },
            onRequestDeviceLocation = onRequestDeviceLocation,
            onOpenLocationSettings = onOpenLocationSettings,
            onFormChange = onFormChange,
            onSaveManualLocation = onSaveManualLocation,
            onLocationCleared = onLocationCleared,
        )

        AccuracyAndLimitsCard(state = state)
    }
}

// --- Header ------------------------------------------------------------------

/**
 * The screen's name, the position the bearing is calculated from, and the compass's status.
 *
 * The position is named here rather than in the Location card because it is part of the reading:
 * "277.6°" means nothing until it is tied to the coordinates it was derived from, and the source
 * label is the honest part of that — a GPS observation, a chosen area's published centre and a typed
 * coordinate are three different claims. The status pill sits on the same line so the two questions
 * the user has before reading anything else — *where* and *is the compass working* — are answered
 * without scrolling.
 *
 * When no position is stored there is no label to show, and the pill carries the whole status on its
 * own: inventing a position line for that state would either repeat the pill or contradict it.
 */
@Composable
private fun QiblaHeader(state: QiblaUiState) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.shortcut_qibla),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val location = state.location
            if (location is QiblaLocationState.Available) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LocationPinGlyph(
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        // The join is a resource, not a concatenation in code: the separator and the
                        // order of the two facts are properties of the language, and a template
                        // would freeze them into Kotlin.
                        text = stringResource(
                            R.string.qibla_location_line,
                            // The shared display policy, not the stored string, so this line names the
                            // location exactly as Home and the location screen do.
                            LocationDisplayName.of(location.location),
                            stringResource(location.location.origin.sourceLabelRes()),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }

            SalahStatusPill(
                text = stringResource(state.phase.statusRes()),
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

// --- The reading -------------------------------------------------------------

/**
 * What the hero's headline is stating.
 *
 * A pure function of the state, so the headline and the readings row cannot disagree about it: the
 * row omits the bearing exactly when the headline is the bearing. Nothing else in the screen branches
 * on this, which is what keeps the rule in one place rather than implied by two separate conditions.
 */
private enum class HeadlineKind {
    /** No bearing at all: the screen asks for a location. */
    PROMPT,

    /** A bearing, but no turn the screen can support: the headline states the bearing. */
    BEARING,

    /** A turn instruction, left or right. */
    TURN,

    /** The device is on the Qibla. */
    ALIGNED,
}

/**
 * Resolves the headline. See [HeadlineKind].
 *
 * A turn exists only when the heading is usable **and** on the true-north reference — a tilted device
 * or an unknown declination leaves `turnDegrees` null, and the headline then states the bearing rather
 * than a turn the screen cannot honour.
 */
private fun headlineKindOf(state: QiblaUiState): HeadlineKind {
    if (state.bearingDegrees == null) return HeadlineKind.PROMPT

    val turn = state.heading?.takeIf { it.isOrientationUsable }?.turnDegrees
        ?: return HeadlineKind.BEARING

    return if (abs(turn) <= ALIGNMENT_TOLERANCE_DEGREES) HeadlineKind.ALIGNED else HeadlineKind.TURN
}

/**
 * The dial, with the one instruction that matters drawn above it.
 *
 * The instruction is the largest text on the screen because it is the only value the user acts on.
 * It states a turn, an alignment, or — when there is no turn to state because the compass cannot
 * measure one — the bearing itself, so that the top of the screen always answers the question the
 * user opened it with. It never states a turn it cannot support: the direction comes from the
 * *signed shortest* difference, so approaching the Qibla from 359° reads as "turn right 2°" rather
 * than as nearly a full rotation.
 */
@Composable
private fun CompassHero(state: QiblaUiState, headline: HeadlineKind) {
    val heading = state.heading
    val isAligned = headline == HeadlineKind.ALIGNED

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        TurnHeadline(state = state, headline = headline)

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

        Spacer(modifier = Modifier.height(6.dp))

        QiblaCompassDial(
            headingDegrees = heading?.trueDegrees,
            qiblaDegrees = state.bearingDegrees,
            isAligned = isAligned,
            isLive = state.isDialLive,
            accessibilityLabel = dialDescription(bearingDegrees = state.bearingDegrees, heading = heading),
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )

        HeadingCaption(heading = heading)
    }
}

/** The instruction, the alignment statement, or the bearing when neither can be said. */
@Composable
private fun TurnHeadline(state: QiblaUiState, headline: HeadlineKind) {
    val turn = state.heading?.takeIf { it.isOrientationUsable }?.turnDegrees
    val bearingDegrees = state.bearingDegrees

    // Two sizes, not a size per case: an instruction is a headline, and a sentence is body text. The
    // alternative — setting an explanation in the instruction's type — makes a screen that asks the
    // user to read a paragraph in the place they expect one word.
    val instruction = MaterialTheme.typography.headlineMedium
    val statement = MaterialTheme.typography.headlineSmall

    // Each case is guarded on the value it needs rather than on the kind alone, so there is no
    // branch that could print a turn or a bearing the state does not have: a missing value falls
    // through to the sentence below instead of to a substituted zero.
    val (text, style) = when {
        headline == HeadlineKind.ALIGNED ->
            stringResource(R.string.qibla_turn_aligned) to instruction

        headline == HeadlineKind.TURN && turn != null && turn > 0 ->
            stringResource(R.string.qibla_turn_right, formatWhole(turn)) to instruction

        headline == HeadlineKind.TURN && turn != null ->
            stringResource(R.string.qibla_turn_left, formatWhole(-turn)) to instruction

        // The bearing, with its reference frame: a bare number would not say which north it is from.
        // This is the headline when the device cannot say which way it is pointing, and the fallback
        // if a kind ever arrives without the value it describes.
        bearingDegrees != null ->
            stringResource(R.string.qibla_bearing_value, formatTenths(bearingDegrees)) to statement

        else -> stringResource(R.string.qibla_turn_unknown) to statement
    }

    Text(
        text = text,
        style = style,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
        color = if (headline == HeadlineKind.ALIGNED) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurface
        },
    )
}

/**
 * The device's own heading, under the dial it is drawn on.
 *
 * Kept subordinate to the instruction because it answers a different question — "which way am I
 * facing" rather than "which way do I turn" — and states its reference frame, since the heading is
 * measured from magnetic north until declination is known and presenting the pair as the same kind of
 * angle is the error the whole north-reference discipline exists to prevent.
 */
@Composable
private fun HeadingCaption(heading: QiblaHeadingUi?) {
    if (heading == null) return

    val trueDegrees = heading.trueDegrees
    val value = if (trueDegrees != null) {
        stringResource(R.string.qibla_heading_true, formatTenths(trueDegrees))
    } else {
        stringResource(R.string.qibla_heading_magnetic_only, formatTenths(heading.magneticDegrees))
    }

    Text(
        text = stringResource(R.string.qibla_heading_caption, value),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

/**
 * The bearing, the distance and the compass accuracy, as three equal readings.
 *
 * These are the screen's numbers, and they are the same three the dial cannot state for itself: the
 * bearing is the answer, the distance is what makes it concrete, and the accuracy is what says how
 * much to trust the arrow. A reading with nothing to show is left out rather than filled with a dash:
 * a device with no magnetometer has no accuracy to report, and three columns with one hole in them
 * would imply a value Salah does not have. [showsBearing] is false when the headline above already
 * states the bearing, so the same number is never printed twice.
 *
 * The accuracy column is drawn with the check mark whatever the level is. That is deliberate: the
 * level itself is the platform's own word — `High` or `Unreliable`, printed as it reports it — and no
 * glyph on this screen contradicts it or invents a score. A low reading is answered by the calibration
 * note below, which is the thing the user can act on, not by hiding the reading.
 */
@Composable
private fun DirectionMetrics(state: QiblaUiState, showsBearing: Boolean) {
    val bearingDegrees = state.bearingDegrees?.takeIf { showsBearing }
    val distanceMetres = state.distanceToKaabaMetres
    // Only a live sensor has an accuracy to report. The accuracy gate in front of the heading already
    // withholds the platform's value until its warm-up has passed, so what arrives here is a reading
    // the platform has actually made; a device with no sensor has none, and the column is absent
    // rather than filled with a dash Salah cannot stand behind.
    val accuracy = state.heading?.accuracy?.takeIf { state.sensorAvailable }

    if (bearingDegrees == null && distanceMetres == null && accuracy == null) return

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            bearingDegrees?.let { bearing ->
                DirectionMetric(
                    glyph = { tint -> QiblaGlyph(tint = tint) },
                    value = stringResource(R.string.qibla_bearing_value_degrees, formatTenths(bearing)),
                    label = stringResource(R.string.qibla_metric_qibla),
                )
            }

            distanceMetres?.let { distance ->
                DirectionMetric(
                    glyph = { tint -> RulerGlyph(tint = tint) },
                    value = stringResource(
                        R.string.qibla_distance_value,
                        formatKilometres(distance / 1_000.0),
                    ),
                    label = stringResource(R.string.qibla_metric_distance),
                )
            }

            accuracy?.let { reported ->
                DirectionMetric(
                    glyph = { tint -> CheckGlyph(tint = tint) },
                    value = stringResource(reported.labelRes()),
                    label = stringResource(R.string.qibla_metric_accuracy),
                )
            }
        }
    }
}

@Composable
private fun RowScope.DirectionMetric(
    glyph: @Composable (Color) -> Unit,
    value: String,
    label: String,
) {
    Column(
        modifier = Modifier.weight(1f),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        glyph(MaterialTheme.colorScheme.primary)
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
    }
}

/**
 * The one reason the dial cannot be trusted right now, if there is one.
 *
 * One note, never two: the conditions are ordered, and the first that applies is the one that
 * matters. Every sentence here is a state Salah cannot resolve by showing a number — which is why
 * they exist at all rather than being folded into the readings.
 */
@Composable
private fun DirectionNote(state: QiblaUiState) {
    val heading = state.heading
    val note = when {
        state.phase == QiblaPhase.CALCULATION_ERROR -> {
            val reason = (state.bearing as? QiblaBearing.Unavailable)?.reason
            reason?.explanationRes() ?: R.string.qibla_status_calculation_error
        }

        !state.sensorAvailable -> R.string.qibla_sensor_unavailable

        heading != null && !heading.isOrientationUsable -> R.string.qibla_tilt_warning

        heading != null && heading.trueDegrees == null -> R.string.qibla_no_declination

        else -> null
    }

    note?.let { textRes -> SalahUnavailableNote(text = stringResource(textRes)) }
}

// --- Calibration -------------------------------------------------------------

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

/**
 * The position, how current it is, and the ways to change it.
 *
 * The card does not repeat the position's name or its source: both are on the heading line, which is
 * where the bearing's inputs belong. What is here is what the heading line cannot carry — the
 * coordinates themselves, how old they are, how accurate — followed by the actions, and the manual
 * form behind a disclosure. The disclosure is the same pattern the Location screen uses for the same
 * form, and for the same reason: typing coordinates is the least used way in, and it should not
 * compete with the one button most users are looking for.
 */
@Composable
private fun LocationCard(
    state: QiblaUiState,
    isManualOpen: Boolean,
    onToggleManual: () -> Unit,
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
                    text = stringResource(
                        R.string.qibla_coordinates,
                        formatCoordinates(location.location.latitude),
                        formatCoordinates(location.location.longitude),
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
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

                if (location.location.origin == LocationOrigin.AREA_SELECTION) {
                    Spacer(modifier = Modifier.height(6.dp))
                    SalahUnavailableNote(text = stringResource(R.string.qibla_location_area_note))
                }

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

        Button(
            onClick = onRequestDeviceLocation,
            enabled = !state.isAcquiringLocation,
            modifier = Modifier.fillMaxWidth(),
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
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = onOpenLocationSettings,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = stringResource(R.string.qibla_location_settings))
            }
        }

        if (state.permissionState == LocationPermissionState.COARSE) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.qibla_location_approximate_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

        if (isManualOpen) {
            ManualLocationForm(
                state = state,
                onFormChange = onFormChange,
                onSave = onSaveManualLocation,
                onClear = onLocationCleared,
                onClose = onToggleManual,
            )
        } else {
            OutlinedButton(
                onClick = onToggleManual,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = stringResource(R.string.settings_location_manual_open))
            }
        }
    }
}

/**
 * The manual-coordinate form, opened from the Location card.
 *
 * It stays on this screen because it is the offline fallback for the screen's own subject: a user
 * who has declined location access, or who is somewhere the device cannot get a fix, still needs a
 * bearing, and sending them to another screen for it would be the one case where the Qibla screen's
 * answer depends on something the user cannot see here.
 */
@Composable
private fun ManualLocationForm(
    state: QiblaUiState,
    onFormChange: ((LocationFormState) -> LocationFormState) -> Unit,
    onSave: () -> Unit,
    onClear: () -> Unit,
    onClose: () -> Unit,
) {
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

    Spacer(modifier = Modifier.height(12.dp))
    OutlinedTextField(
        value = state.form.label,
        onValueChange = { value -> onFormChange { it.copy(label = value) } },
        label = { Text(text = stringResource(R.string.qibla_manual_label)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    Spacer(modifier = Modifier.height(10.dp))
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
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = state.form.longitude,
            onValueChange = { value -> onFormChange { it.copy(longitude = value) } },
            label = { Text(text = stringResource(R.string.qibla_manual_longitude)) },
            singleLine = true,
            isError = state.form.hasValidationError,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.weight(1f),
        )
    }

    Spacer(modifier = Modifier.height(10.dp))
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
        Button(onClick = onSave) {
            Text(text = stringResource(R.string.qibla_manual_save))
        }
        OutlinedButton(onClick = onClose) {
            Text(text = stringResource(R.string.location_action_cancel))
        }
    }

    if (state.location is QiblaLocationState.Available) {
        TextButton(onClick = onClear) {
            Text(text = stringResource(R.string.qibla_manual_clear))
        }
    }
}

// --- Sensor and limits -------------------------------------------------------

/**
 * The sensor behind the arrow, and what the dial can and cannot show.
 *
 * One card rather than the two this used to be: the sensor's identity and the accuracy it can reach
 * are one subject, and splitting them put the same explanation on the screen twice. The accuracy
 * itself is a reading and lives with the other readings above — this card is the part that has to be
 * read rather than glanced at, so it is last.
 */
@Composable
private fun AccuracyAndLimitsCard(state: QiblaUiState) {
    SalahSectionCard {
        Text(
            text = stringResource(R.string.qibla_section_limits),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(8.dp))

        val family = state.sensorFamily
        if (state.sensorAvailable && family != null) {
            DetailRow(
                label = stringResource(R.string.qibla_sensor_detected),
                value = stringResource(family.labelRes()),
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        Text(
            text = stringResource(R.string.qibla_limits_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.qibla_kaaba_source, state.kaabaSource),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// --- Small parts -------------------------------------------------------------

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
private fun DetailRow(label: String, value: String) {
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
 * What a screen reader should say for the dial.
 *
 * The dial is a `Canvas` and carries no text of its own, so without this it is invisible to
 * accessibility services — a user relying on TalkBack would hear the turn summary but never the bearing
 * and heading the dial is drawing. The summary is built from the same two values the dial renders, and
 * it names them rather than describing the picture, because "a green wedge" would be a description of
 * the drawing rather than of the reading.
 *
 * The cases are separate rather than one sentence with blanks, so the announcement never claims a
 * heading that is not being measured.
 */
@Composable
private fun dialDescription(bearingDegrees: Double?, heading: QiblaHeadingUi?): String {
    val trueHeading = heading?.trueDegrees
    return when {
        bearingDegrees == null -> stringResource(R.string.qibla_dial_description_no_bearing)

        trueHeading == null -> stringResource(
            R.string.qibla_dial_description_no_heading,
            formatTenths(bearingDegrees),
        )

        else -> stringResource(
            R.string.qibla_dial_description,
            formatTenths(bearingDegrees),
            formatTenths(trueHeading),
        )
    }
}

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
            .withLocale(numberLocale())
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

@Composable
private fun formatTenths(value: Double): String = "%.1f".format(numberLocale(), value)

/** Kilometres, grouped: 5167.3 reads as 5,167.3, which is how a distance is written. */
@Composable
private fun formatKilometres(value: Double): String = "%,.1f".format(numberLocale(), value)

@Composable
private fun formatWhole(value: Double): String = "%.0f".format(numberLocale(), value)

@Composable
private fun formatCoordinates(value: Double): String = "%.4f".format(numberLocale(), value)

/**
 * The locale numbers should be formatted in.
 *
 * Read through the composition rather than from `Locale.getDefault()`, so that a locale change
 * recomposes the formatted values instead of leaving a stale figure on screen. `Locale.ROOT` is the
 * fallback for the degenerate case of an empty `LocaleList`, which keeps the decimal separator
 * deterministic rather than falling back to a non-observable read.
 */
@Composable
private fun numberLocale(): Locale {
    val configuration = LocalConfiguration.current
    return if (configuration.locales.size() > 0) configuration.locales[0] else Locale.ROOT
}
