package io.github.towkirahmedrony.salah.feature.location

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.towkirahmedrony.salah.R
import io.github.towkirahmedrony.salah.core.designsystem.components.SalahSectionCard
import io.github.towkirahmedrony.salah.core.designsystem.components.SalahSkeletonBlock
import io.github.towkirahmedrony.salah.core.designsystem.components.SalahStatusPill
import io.github.towkirahmedrony.salah.core.designsystem.components.SalahUnavailableNote
import io.github.towkirahmedrony.salah.core.domain.location.AreaResolution
import io.github.towkirahmedrony.salah.core.domain.location.LocationFormatting
import io.github.towkirahmedrony.salah.core.domain.location.LocationFreshness
import io.github.towkirahmedrony.salah.core.model.BangladeshArea
import io.github.towkirahmedrony.salah.core.model.LocationOrigin
import io.github.towkirahmedrony.salah.core.domain.qibla.LocationPermissionAction
import io.github.towkirahmedrony.salah.core.domain.qibla.LocationPermissionPolicy
import io.github.towkirahmedrony.salah.core.domain.qibla.LocationPermissionState
import io.github.towkirahmedrony.salah.feature.common.sourceLabelRes
import org.koin.androidx.compose.koinViewModel

/**
 * The location management screen, reached from Settings.
 *
 * The framing the first-launch [LocationSetupScreen] does not have: it starts from the stored location,
 * shows it with its provenance, and offers the same two ways to replace it plus removal. Both screens
 * read one state and call one view model, so the first launch and every later change apply exactly the
 * same validation — there is no second code path that could accept a location the first one would
 * refuse.
 */
@Composable
fun LocationScreen(
    modifier: Modifier = Modifier,
    viewModel: LocationViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        viewModel.start()
        viewModel.refreshPermissionState()
    }

    val settingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        viewModel.refreshPermissionState()
    }

    // The permission decision lives in one place (LocationPermissionPolicy); this supplies the two
    // platform facts it needs, so a permanently denied permission opens app settings instead of
    // re-launching a dialog the system would silently ignore.
    val onUseDeviceLocation = rememberDeviceLocationAction(
        viewModel = viewModel,
        permissionState = state.permissionState,
    )

    LocationContent(
        state = state,
        modifier = modifier,
        onUseDeviceLocation = onUseDeviceLocation,
        onOpenLocationSettings = {
            settingsLauncher.launch(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        },
        onOpenAreaPicker = viewModel::openAreaPicker,
        onCloseAreaPicker = viewModel::closeAreaPicker,
        onSelectDivision = viewModel::selectDivision,
        onSelectDistrict = viewModel::selectDistrict,
        onSelectUpazila = viewModel::selectUpazila,
        onSaveSelection = viewModel::saveSelectedArea,
        onConfirmFix = viewModel::confirmPendingFix,
        onDiscardFix = viewModel::discardPendingFix,
        onClearLocation = viewModel::clearLocation,
        onDismissMessage = viewModel::dismissMessage,
    )
}

/**
 * The management screen's body.
 *
 * Order matters and is deliberate: the dataset's status, then the stored location, then the two ways
 * to replace it, then a pending fix or the open picker, then the actions and any message. Nothing is
 * shown before the dataset has been read, because a picker with no divisions in it is worse than a
 * spinner.
 *
 * The first-launch screen is a separate composable with its own framing; both render the same state
 * and call the same view model, so there is still exactly one set of rules behind the two.
 */
@Composable
private fun LocationContent(
    state: LocationUiState,
    modifier: Modifier = Modifier,
    onUseDeviceLocation: () -> Unit,
    onOpenLocationSettings: () -> Unit,
    onOpenAreaPicker: () -> Unit,
    onCloseAreaPicker: () -> Unit,
    onSelectDivision: (String) -> Unit,
    onSelectDistrict: (String) -> Unit,
    onSelectUpazila: (String) -> Unit,
    onSaveSelection: () -> Unit,
    onConfirmFix: () -> Unit,
    onDiscardFix: () -> Unit,
    onClearLocation: () -> Unit,
    onDismissMessage: () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.location_title),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )

        // Dataset status -----------------------------------------------------
        if (state.isLoading) {
            // A stand-in for the dataset version line that replaces it, rather than a progress bar.
            // The read is a local file parse that finishes in milliseconds, and both ways of choosing
            // a location below stay disabled until it finishes, so there is nothing here a user could
            // act on early and nothing to wait behind a moving bar.
            SalahSkeletonBlock(
                height = 14.dp,
                modifier = Modifier.fillMaxWidth(0.55f),
            )
        } else if (!state.isDatasetUsable) {
            SalahSectionCard {
                Text(
                    text = stringResource(R.string.location_dataset_unavailable_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.location_dataset_unavailable_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.datasetDetail?.let { detail ->
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.location_dataset_incomplete, detail),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            state.datasetVersion?.let { version ->
                Text(
                    text = stringResource(R.string.location_dataset_label, version),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Stored location ----------------------------------------------------
        state.stored?.let { stored -> StoredLocationCard(stored = stored) }

        // A device fix awaiting confirmation --------------------------------
        state.pendingFix?.let { fix ->
            PendingFixCard(
                fix = fix,
                onConfirm = onConfirmFix,
                onDiscard = onDiscardFix,
                onChooseArea = {
                    onDiscardFix()
                    onOpenAreaPicker()
                },
            )
        }

        // The two primary ways to give Salah a location ----------------------
        if (state.pendingFix == null) {
            if (state.isAcquiring) {
                SalahSectionCard {
                    Text(
                        text = stringResource(R.string.location_gps_acquiring),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            } else {
                Button(
                    onClick = onUseDeviceLocation,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = true,
                ) {
                    Text(text = stringResource(R.string.location_action_use_gps))
                }
                OutlinedButton(
                    onClick = onOpenAreaPicker,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state.canChooseArea,
                ) {
                    Text(text = stringResource(R.string.location_action_select_manually))
                }
            }
        }

        Text(
            text = stringResource(R.string.location_offline_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Manual picker ------------------------------------------------------
        if (state.isPickerOpen) {
            AreaPickerSection(
                state = state,
                onSelectDivision = onSelectDivision,
                onSelectDistrict = onSelectDistrict,
                onSelectUpazila = onSelectUpazila,
                onSaveSelection = onSaveSelection,
                onCloseAreaPicker = onCloseAreaPicker,
            )
        }

        // Actions on an existing location ------------------------------------
        if (state.stored != null && !state.isPickerOpen && state.pendingFix == null) {
            state.stored?.let { stored ->
                if (stored.freshness.shouldOfferRefresh || stored.origin == LocationOrigin.AREA_SELECTION) {
                    OutlinedButton(
                        onClick = onUseDeviceLocation,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(text = stringResource(R.string.location_action_refresh_gps))
                    }
                }
            }
            OutlinedButton(
                onClick = onClearLocation,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = stringResource(R.string.location_action_clear))
            }
        }

        // Offered when automatic location is unavailable *and* nothing is stored yet: it is the one
        // failure the user can fix without leaving the screen's subject. Once a location exists there
        // is nothing to unblock, so the button would only be noise.
        if (state.permissionState == LocationPermissionState.DENIED && state.stored == null) {
            TextButton(onClick = onOpenLocationSettings) {
                Text(text = stringResource(R.string.location_action_open_location_settings))
            }
        }

        state.message?.let { message ->
            LocationMessageRow(message = message, onDismiss = onDismissMessage)
        }
    }
}

@Composable
private fun StoredLocationCard(stored: StoredLocationUi) {
    var showAdvanced by remember { mutableStateOf(false) }

    SalahSectionCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.location_section_current_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
            SalahStatusPill(
                text = stringResource(stored.origin.sourceLabelRes()),
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }

        Spacer(modifier = Modifier.height(10.dp))
        LocationDetailRow(
            label = stringResource(R.string.location_country_label),
            value = stored.countryCode?.let { stringResource(R.string.location_country_value) }
                ?: stringResource(R.string.location_country_not_established),
        )
        LocationDetailRow(
            label = stringResource(R.string.location_division_label),
            value = stored.area?.divisionName ?: stringResource(R.string.location_area_unknown),
        )
        LocationDetailRow(
            label = stringResource(R.string.location_district_label),
            value = stored.area?.districtName ?: stringResource(R.string.location_area_unknown),
        )
        LocationDetailRow(
            label = stringResource(R.string.location_upazila_label),
            value = stored.area?.upazilaName ?: stringResource(R.string.location_area_unknown),
        )
        LocationDetailRow(
            label = stringResource(R.string.location_time_zone_label),
            value = stored.timeZoneId,
        )
        stored.accuracyText?.let { accuracy ->
            LocationDetailRow(
                label = stringResource(R.string.location_accuracy_label),
                value = accuracy,
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(freshnessRes(stored.freshness)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (stored.origin == LocationOrigin.AREA_SELECTION) {
            Spacer(modifier = Modifier.height(8.dp))
            SalahUnavailableNote(text = stringResource(R.string.location_area_reference_note))
        }

        if (stored.isKnownOutsideSupportedCountry) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.location_unsupported_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.location_unsupported_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(modifier = Modifier.height(6.dp))
        TextButton(onClick = { showAdvanced = !showAdvanced }) {
            Text(text = stringResource(R.string.location_advanced_title))
        }

        if (showAdvanced) {
            Text(
                text = stringResource(R.string.location_advanced_coordinates, stored.coordinateText),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            stored.location.datasetVersion?.let { version ->
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.location_advanced_dataset_selected, version),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = stringResource(
                    when {
                        stored.isVerifiedInSupportedCountry ->
                            R.string.location_advanced_bangladesh_verified
                        stored.isWithinBoundaryTolerance ->
                            R.string.location_advanced_bangladesh_within_tolerance
                        else -> R.string.location_advanced_bangladesh_unverified
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun PendingFixCard(
    fix: PendingDeviceFix,
    onConfirm: () -> Unit,
    onDiscard: () -> Unit,
    onChooseArea: () -> Unit,
) {
    SalahSectionCard {
        Text(
            text = stringResource(R.string.location_gps_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.location_gps_coordinates, fix.coordinateText),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        fix.accuracyText?.let { accuracy ->
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.location_gps_accuracy, accuracy),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(modifier = Modifier.height(10.dp))
        when (val resolution = fix.resolution) {
            is AreaResolution.Suggested -> {
                Text(
                    text = stringResource(
                        R.string.location_gps_matched,
                        resolution.suggestion.reference.hierarchyLabel,
                        fix.matchDistanceText ?: "—",
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(6.dp))
                SalahUnavailableNote(text = stringResource(R.string.location_gps_matched_approximate))
            }

            is AreaResolution.Unresolved -> {
                Text(
                    text = stringResource(
                        R.string.location_gps_unmatched,
                        fix.nearestDistanceText?.let { distance ->
                            stringResource(R.string.location_gps_unmatched_distance, distance)
                        }.orEmpty(),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            AreaResolution.DatasetUnavailable -> {
                SalahUnavailableNote(text = stringResource(R.string.location_dataset_unavailable_body))
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(
                when {
                    fix.isCountryVerified -> R.string.location_gps_verified
                    fix.isWithinBoundaryTolerance -> R.string.location_gps_within_tolerance
                    else -> R.string.location_gps_unverified
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(12.dp))
        Button(onClick = onConfirm, modifier = Modifier.fillMaxWidth()) {
            Text(text = stringResource(R.string.location_action_confirm_location))
        }
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = onChooseArea, modifier = Modifier.weight(1f)) {
                Text(text = stringResource(R.string.location_action_change_area))
            }
            OutlinedButton(onClick = onDiscard, modifier = Modifier.weight(1f)) {
                Text(text = stringResource(R.string.location_action_discard))
            }
        }
    }
}

@Composable
private fun AreaPickerSection(
    state: LocationUiState,
    onSelectDivision: (String) -> Unit,
    onSelectDistrict: (String) -> Unit,
    onSelectUpazila: (String) -> Unit,
    onSaveSelection: () -> Unit,
    onCloseAreaPicker: () -> Unit,
) {
    SalahSectionCard {
        Text(
            text = stringResource(R.string.location_picker_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
        )

        Spacer(modifier = Modifier.height(4.dp))
        state.datasetVersion?.let { version ->
            Text(
                text = stringResource(R.string.location_dataset_label, version),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        AreaLevelList(
            title = stringResource(R.string.location_picker_step_division),
            emptyMessage = null,
            options = state.divisionOptions,
            selectedId = state.selection.divisionId,
            onSelect = onSelectDivision,
        )

        AreaLevelList(
            title = stringResource(R.string.location_picker_step_district),
            emptyMessage = stringResource(R.string.location_picker_needs_division),
            options = state.districtOptions,
            selectedId = state.selection.districtId,
            onSelect = onSelectDistrict,
        )

        AreaLevelList(
            title = stringResource(R.string.location_picker_step_upazila),
            emptyMessage = stringResource(R.string.location_picker_needs_district),
            options = state.upazilaOptions,
            selectedId = state.selection.upazilaId,
            onSelect = onSelectUpazila,
        )

        Spacer(modifier = Modifier.height(10.dp))
        state.selectionSummary?.let { summary ->
            Text(
                text = summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
        }
        val referenceText = state.selectionCoordinateText?.let { coordinates ->
            stringResource(R.string.location_picker_reference, coordinates)
        } ?: stringResource(R.string.location_picker_incomplete)
        Text(
            text = referenceText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(12.dp))
        Button(
            onClick = onSaveSelection,
            modifier = Modifier.fillMaxWidth(),
            enabled = state.canSaveSelection,
        ) {
            Text(text = stringResource(R.string.location_action_save))
        }
        Spacer(modifier = Modifier.height(6.dp))
        OutlinedButton(onClick = onCloseAreaPicker, modifier = Modifier.fillMaxWidth()) {
            Text(text = stringResource(R.string.location_action_cancel))
        }
    }
}

/**
 * One step of the hierarchy: every option for the current parent, as a selectable row.
 *
 * A list rather than a dropdown, because a district has at most a few dozen upazilas and every one of
 * them being visible is what makes "the area is not there" a verifiable observation rather than a
 * suspicion about a search box. Rows are radio buttons so the current choice is unmistakable, and the
 * Bangla name is shown beside the English one whenever the dataset has it.
 */
@Composable
private fun AreaLevelList(
    title: String,
    emptyMessage: String?,
    options: List<BangladeshArea>,
    selectedId: String?,
    onSelect: (String) -> Unit,
) {
    Spacer(modifier = Modifier.height(12.dp))
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
    )
    if (options.isEmpty()) {
        if (emptyMessage != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = emptyMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    // One selectable group per level, so TalkBack announces each row as a radio option with its own
    // selected state and the number of options in the group. The row owns the selection and the
    // RadioButton is non-interactive: a separately focusable button inside a clickable row makes the
    // same option appear twice and hides which one is chosen.
    Column(modifier = Modifier.selectableGroup()) {
        options.forEach { area ->
            val selected = area.id == selectedId
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = selected,
                        role = Role.RadioButton,
                        onClick = { onSelect(area.id) },
                    )
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = selected, onClick = null)
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = area.labelledName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun LocationDetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
internal fun LocationMessageRow(message: LocationMessage, onDismiss: () -> Unit) {
    val text = when (message) {
        LocationMessage.Saved -> stringResource(R.string.location_message_saved)
        LocationMessage.SaveRejected -> stringResource(R.string.location_message_rejected)
        LocationMessage.PermissionDenied -> stringResource(R.string.location_message_permission_denied)
        LocationMessage.ServicesDisabled -> stringResource(R.string.location_message_services_disabled)
        LocationMessage.TimedOut -> stringResource(R.string.location_message_timeout)
        LocationMessage.ProviderFailed -> stringResource(R.string.location_message_provider_failed)
        LocationMessage.Cleared -> stringResource(R.string.location_message_cleared)
        LocationMessage.DatasetUnavailable -> stringResource(R.string.location_message_dataset_unavailable)
        is LocationMessage.OutsideSupportedCountry -> message.distanceMetres?.let { distance ->
            stringResource(
                R.string.location_message_outside_country_distance,
                LocationFormatting.distance(distance),
            )
        } ?: stringResource(R.string.location_message_outside_country)
    }

    SalahSectionCard {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(4.dp))
        TextButton(onClick = onDismiss) {
            Text(text = stringResource(R.string.location_message_dismiss))
        }
    }
}

private fun freshnessRes(freshness: LocationFreshness): Int = when (freshness) {
    LocationFreshness.DECLARED -> R.string.location_freshness_declared
    LocationFreshness.FRESH -> R.string.location_freshness_fresh
    LocationFreshness.RECENT -> R.string.location_freshness_recent
    LocationFreshness.STALE -> R.string.location_freshness_stale
    LocationFreshness.UNKNOWN -> R.string.location_freshness_unknown
}

/**
 * Builds the "use the device's location" click handler shared by both location screens.
 *
 * Both screens offered this identically before and must keep offering it identically, so the
 * permission handling is written once: the launcher, the decision from [LocationPermissionPolicy], and
 * the two settings paths. Manual area selection is unaffected by any of it and stays usable with the
 * permission denied.
 */
@Composable
internal fun rememberDeviceLocationAction(
    viewModel: LocationViewModel,
    permissionState: LocationPermissionState,
): () -> Unit {
    val context = LocalContext.current
    val activity = context.findActivity()
    var hasRequestedDialog by rememberSaveable { mutableStateOf(false) }

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

    val appSettingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        // Returning from settings: re-read the permission so the button reflects the new answer.
        viewModel.refreshPermissionState()
    }

    return {
        val shouldShowRationale = activity?.let { host ->
            LOCATION_PERMISSIONS.any { permission ->
                ActivityCompat.shouldShowRequestPermissionRationale(host, permission)
            }
        } ?: false
        when (
            LocationPermissionPolicy.action(
                state = permissionState,
                hasRequestedDialog = hasRequestedDialog,
                shouldShowRationale = shouldShowRationale,
            )
        ) {
            LocationPermissionAction.ALREADY_GRANTED -> viewModel.useCurrentLocation()
            LocationPermissionAction.REQUEST -> {
                hasRequestedDialog = true
                permissionLauncher.launch(LOCATION_PERMISSIONS)
            }
            LocationPermissionAction.OPEN_APP_SETTINGS -> appSettingsLauncher.launch(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", context.packageName, null),
                ),
            )
        }
    }
}

/** The hosting [Activity], found through any [ContextWrapper] the Compose tree was handed. */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Both permissions, requested together so Android 12+ can offer "Approximate" or "Precise". */
private val LOCATION_PERMISSIONS = arrayOf(
    Manifest.permission.ACCESS_COARSE_LOCATION,
    Manifest.permission.ACCESS_FINE_LOCATION,
)
