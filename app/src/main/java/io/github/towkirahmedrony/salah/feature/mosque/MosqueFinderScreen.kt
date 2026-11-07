package io.github.towkirahmedrony.salah.feature.mosque

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.towkirahmedrony.salah.R
import io.github.towkirahmedrony.salah.core.designsystem.DirectionsGlyph
import io.github.towkirahmedrony.salah.core.designsystem.ListGlyph
import io.github.towkirahmedrony.salah.core.designsystem.LocationPinFilledGlyph
import io.github.towkirahmedrony.salah.core.designsystem.LocationPinGlyph
import io.github.towkirahmedrony.salah.core.designsystem.MapGlyph
import io.github.towkirahmedrony.salah.core.designsystem.RefreshGlyph
import io.github.towkirahmedrony.salah.core.designsystem.components.SalahSectionCard
import io.github.towkirahmedrony.salah.core.designsystem.components.SalahSkeletonBlock
import io.github.towkirahmedrony.salah.core.designsystem.components.SalahStatusPill
import io.github.towkirahmedrony.salah.core.designsystem.components.SalahSubpageHeader
import io.github.towkirahmedrony.salah.core.designsystem.components.SalahUnavailableNote
import io.github.towkirahmedrony.salah.core.domain.calendar.CalendarFormatting
import io.github.towkirahmedrony.salah.core.domain.mosque.Mosque
import io.github.towkirahmedrony.salah.core.domain.mosque.MosqueDataOrigin
import io.github.towkirahmedrony.salah.core.domain.mosque.MosqueFailure
import io.github.towkirahmedrony.salah.core.domain.mosque.MosqueWithDistance
import org.koin.androidx.compose.koinViewModel
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The mosque finder.
 *
 * ## What the screen is
 *
 * One search question — "which mosques are around me?" — answered as a nearest-first list or the same
 * results on a map. The header, the search context and the two small controls are fixed; the result
 * area is the rest of the screen. Switching views never re-queries: the list and the map read one
 * list from one state.
 *
 * ## Why the list is the default
 *
 * It is the view that survives every partial failure. A missing Geoapify key, a style that cannot
 * load or a slow connection leaves the list and its directions fully usable, and the map is a second
 * presentation rather than a prerequisite. It is also the accessible view: a screen reader can read
 * the names and distances in order, while a rendered map is a single canvas.
 *
 * ## Location is a deliberate action
 *
 * The first search uses the app's saved location — the same one prayer times use — without asking for
 * permission. The location control on the right asks the platform for a one-shot fix and requests the
 * runtime permission only if it has not already been granted. A fix obtained this way is used for this
 * screen and is not written back to the saved location; see [MosqueFinderViewModel] for why.
 *
 * ## What it deliberately does not do
 *
 * No Place Details request, no phone-call or website intent, no fake mosques and no second selection
 * state. The map is a view of the list, and the detail card is the same mosque the list would select.
 * External directions are handed to whichever installed app accepts a `geo:` URI, with a web maps
 * fallback, because Salah has no routing engine of its own.
 */
@Composable
fun MosqueFinderScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MosqueFinderViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Both permissions are requested together so Android 12+ can offer the user the choice between
    // approximate and precise. Salah accepts either; nothing here needs a background grant.
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        viewModel.onPermissionResult(granted = result.values.any { granted -> granted })
    }

    val requestCurrentLocation: () -> Unit = {
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
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SalahSubpageHeader(
            title = stringResource(R.string.mosque_title),
            backContentDescription = stringResource(R.string.mosque_back),
            onBack = onBack,
        )
        Text(
            text = stringResource(R.string.mosque_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SearchContextRow(
            state = state,
            centerText = searchCenterText(state),
        )

        ControlsRow(
            state = state,
            onSelectView = viewModel::setViewMode,
            onUseCurrentLocation = requestCurrentLocation,
            onRefresh = viewModel::refresh,
        )

        StatusNotices(state = state)

        if (state.hasResults) {
            ResultsSummary(state = state)
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            when {
                state.isLoading && !state.hasResults -> LoadingResults()
                state.failure != null && !state.hasResults -> FailurePanel(
                    failure = state.failure,
                    onRetry = viewModel::refresh,
                )
                state.center == null && !state.hasResults -> NoLocationPanel(
                    onUseCurrentLocation = requestCurrentLocation,
                )
                state.isEmptyResult && !state.hasResults -> EmptyPanel(
                    radiusMetres = state.radiusMetres,
                    onRetry = viewModel::refresh,
                )
                state.viewMode == MosqueViewMode.MAP -> MapResults(
                    state = state,
                    viewModel = viewModel,
                    onDirectionsUnavailable = viewModel::onDirectionsUnavailable,
                )
                else -> ResultList(
                    state = state,
                    onSelect = viewModel::selectMosque,
                    onDirectionsUnavailable = viewModel::onDirectionsUnavailable,
                )
            }
        }
    }
}

/** One line saying which place the results describe. */
@Composable
private fun SearchContextRow(
    state: MosqueFinderUiState,
    centerText: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            LocationPinGlyph(
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(18.dp),
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = centerText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (state.isAcquiringLocation) {
                Text(
                    text = stringResource(R.string.mosque_location_acquiring),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else if (state.isRefreshing) {
                Text(
                    text = stringResource(R.string.mosque_refreshing),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** The view toggle and the two actions that change what is being searched. */
@Composable
private fun ControlsRow(
    state: MosqueFinderUiState,
    onSelectView: (MosqueViewMode) -> Unit,
    onUseCurrentLocation: () -> Unit,
    onRefresh: () -> Unit,
) {
    val locationDescription = stringResource(R.string.mosque_action_use_current_location)
    val refreshDescription = stringResource(R.string.mosque_action_refresh)

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ViewModeSelector(
            selected = state.viewMode,
            onSelect = onSelectView,
        )
        Spacer(modifier = Modifier.weight(1f))
        IconButton(
            onClick = onUseCurrentLocation,
            enabled = !state.isAcquiringLocation,
            modifier = Modifier.semantics { contentDescription = locationDescription },
        ) {
            if (state.isAcquiringLocation) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                LocationPinFilledGlyph(
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        IconButton(
            onClick = onRefresh,
            enabled = state.center != null && !state.isLoading && !state.isRefreshing,
            modifier = Modifier.semantics { contentDescription = refreshDescription },
        ) {
            RefreshGlyph(
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/**
 * A compact two-option selector.
 *
 * `selectable` with the radio-button role rather than two independent buttons: list and map are
 * mutually exclusive, and a screen reader should announce the current one and its state. The text
 * labels are visible so the icons are never the only way to tell the options apart.
 */
@Composable
private fun ViewModeSelector(
    selected: MosqueViewMode,
    onSelect: (MosqueViewMode) -> Unit,
) {
    val groupDescription = stringResource(R.string.mosque_view_mode_description)

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(100.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(3.dp)
            .selectableGroup()
            .semantics { contentDescription = groupDescription },
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        ViewModeOption(
            mode = MosqueViewMode.LIST,
            selected = selected == MosqueViewMode.LIST,
            label = stringResource(R.string.mosque_view_list),
            onSelect = onSelect,
        )
        ViewModeOption(
            mode = MosqueViewMode.MAP,
            selected = selected == MosqueViewMode.MAP,
            label = stringResource(R.string.mosque_view_map),
            onSelect = onSelect,
        )
    }
}

@Composable
private fun ViewModeOption(
    mode: MosqueViewMode,
    selected: Boolean,
    label: String,
    onSelect: (MosqueViewMode) -> Unit,
) {
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(100.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
            )
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = { onSelect(mode) },
            )
            .padding(horizontal = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        when (mode) {
            MosqueViewMode.LIST -> ListGlyph(tint = contentColor, modifier = Modifier.size(17.dp))
            MosqueViewMode.MAP -> MapGlyph(tint = contentColor, modifier = Modifier.size(17.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = contentColor,
        )
    }
}

/** Notices that qualify the results without replacing them. */
@Composable
private fun StatusNotices(state: MosqueFinderUiState) {
    state.notice?.let { notice ->
        SalahUnavailableNote(text = noticeText(notice))
    }

    val refreshFailure = state.refreshFailure
    if (refreshFailure != null) {
        val explanation = if (state.origin == MosqueDataOrigin.CACHE) {
            stringResource(R.string.mosque_cached_notice)
        } else {
            stringResource(R.string.mosque_refresh_failed_notice)
        }
        SalahUnavailableNote(
            text = listOf(explanation, failureText(refreshFailure)).joinToString(" "),
        )
    }
}

/** The result count, and whether the list came from the cache. */
@Composable
private fun ResultsSummary(state: MosqueFinderUiState) {
    val count = state.mosques.size
    val locale = currentLocale()
    val digits = CalendarFormatting.localizeDigits(count.toString(), locale)

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (count == 1) {
                stringResource(R.string.mosque_results_count_one)
            } else {
                stringResource(R.string.mosque_results_count, digits)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.weight(1f))
        if (state.origin == MosqueDataOrigin.CACHE) {
            SalahStatusPill(
                text = stringResource(R.string.mosque_origin_cached),
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

/** The nearest-first list. */
@Composable
private fun ResultList(
    state: MosqueFinderUiState,
    onSelect: (String) -> Unit,
    onDirectionsUnavailable: () -> Unit,
) {
    val context = LocalContext.current
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(items = state.mosques, key = { item -> item.mosque.placeId }) { item ->
            val selected = item.mosque.placeId == state.selectedPlaceId
            MosqueResultCard(
                item = item,
                selected = selected,
                onSelect = { onSelect(item.mosque.placeId) },
                onDirections = {
                    openDirections(
                        context = context,
                        mosque = item.mosque,
                        onUnavailable = onDirectionsUnavailable,
                    )
                },
            )
        }
    }
}

/**
 * One mosque in the list.
 *
 * Collapsed it states the name, the distance and, when the service provided one, the address.
 * Selecting it expands the same card with the full address and the contact fields the response
 * carried, plus the one action that leaves the app. Nothing is fetched on selection.
 */
@Composable
private fun MosqueResultCard(
    item: MosqueWithDistance,
    selected: Boolean,
    onSelect: () -> Unit,
    onDirections: () -> Unit,
) {
    val mosque = item.mosque
    val context = LocalContext.current

    SalahSectionCard(onClick = onSelect) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = mosque.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                mosque.address?.let { address ->
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = address,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (selected) 3 else 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(modifier = Modifier.size(10.dp))
            Text(
                text = distanceLabel(item.distanceMetres),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        if (selected) {
            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(8.dp))

            if (mosque.address == null) {
                Text(
                    text = stringResource(R.string.mosque_address_unknown),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ContactRow(label = stringResource(R.string.mosque_phone), value = mosque.phone)
            ContactRow(label = stringResource(R.string.mosque_website), value = mosque.website)

            val directionsDescription =
                stringResource(R.string.mosque_open_directions, mosque.name)
            TextButton(
                onClick = {
                    openDirections(
                        context = context,
                        mosque = mosque,
                        onUnavailable = onDirectionsUnavailable,
                    )
                },
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .semantics { contentDescription = directionsDescription },
            ) {
                DirectionsGlyph(
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.size(6.dp))
                Text(text = stringResource(R.string.mosque_action_directions))
            }
        }
    }
}

/** One labelled contact field, drawn only when the response carried a value. */
@Composable
private fun ContactRow(label: String, value: String?) {
    if (value == null) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 8.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The map and the selected mosque's compact card beneath it. */
@Composable
private fun MapResults(
    state: MosqueFinderUiState,
    viewModel: MosqueFinderViewModel,
    onDirectionsUnavailable: () -> Unit,
) {
    val darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val selected = state.selected
    val context = LocalContext.current

    Column(modifier = Modifier.fillMaxSize()) {
        MosqueMap(
            styleUrl = viewModel.mapStyleUrl(darkTheme = darkTheme),
            center = state.center,
            mosques = state.mosques,
            selectedPlaceId = state.selectedPlaceId,
            onSelect = viewModel::selectMosque,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        )
        if (selected != null) {
            Spacer(modifier = Modifier.height(8.dp))
            SelectedMosqueCard(
                item = selected,
                onDirections = {
                    openDirections(
                        context = context,
                        mosque = selected.mosque,
                        onUnavailable = onDirectionsUnavailable,
                    )
                },
            )
        }
    }
}

/** The compact detail card under the map. */
@Composable
private fun SelectedMosqueCard(
    item: MosqueWithDistance,
    onDirections: () -> Unit,
) {
    val mosque = item.mosque
    val context = LocalContext.current

    SalahSectionCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.mosque_selected_title),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = mosque.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = mosque.address ?: stringResource(R.string.mosque_address_unknown),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.size(10.dp))
            Text(
                text = distanceLabel(item.distanceMetres),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        val directionsDescription =
            stringResource(R.string.mosque_open_directions, mosque.name)
        TextButton(
            onClick = {
                openDirections(
                    context = context,
                    mosque = mosque,
                    onUnavailable = onDirectionsUnavailable,
                )
            },
            modifier = Modifier
                .heightIn(min = 48.dp)
                .semantics { contentDescription = directionsDescription },
        ) {
            DirectionsGlyph(
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.size(6.dp))
            Text(text = stringResource(R.string.mosque_action_directions))
        }
    }
}

/** A search that has not produced a list yet. */
@Composable
private fun LoadingResults() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        repeat(3) {
            SalahSectionCard {
                SalahSkeletonBlock(
                    height = 16.dp,
                    modifier = Modifier.fillMaxWidth(0.55f),
                )
                Spacer(modifier = Modifier.height(8.dp))
                SalahSkeletonBlock(
                    height = 12.dp,
                    modifier = Modifier.fillMaxWidth(0.8f),
                )
            }
        }
    }
}

/** A load that produced no list at all. */
@Composable
private fun FailurePanel(
    failure: MosqueFailure,
    onRetry: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        SalahSectionCard {
            Text(
                text = stringResource(R.string.mosque_failure_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = failureText(failure),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (failure != MosqueFailure.NOT_CONFIGURED) {
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = onRetry,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(text = stringResource(R.string.action_retry))
                }
            }
        }
    }
}

/** A search that succeeded and found nothing. */
@Composable
private fun EmptyPanel(
    radiusMetres: Int,
    onRetry: () -> Unit,
) {
    val locale = currentLocale()
    val radiusKilometres = ((radiusMetres.coerceAtLeast(0) + 999) / 1000).toString()
    val radiusDigits = CalendarFormatting.localizeDigits(radiusKilometres, locale)

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        SalahSectionCard {
            Text(
                text = stringResource(R.string.mosque_empty_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.mosque_empty_body, radiusDigits),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = onRetry,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(text = stringResource(R.string.action_retry))
            }
        }
    }
}

/** No saved location and no current fix yet: the one state where there is nothing to search around. */
@Composable
private fun NoLocationPanel(
    onUseCurrentLocation: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        SalahSectionCard {
            Text(
                text = stringResource(R.string.mosque_no_location),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = onUseCurrentLocation,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(text = stringResource(R.string.mosque_action_use_current_location))
            }
        }
    }
}

/** The saved location's label, the current-location label, or the honest absence of one. */
@Composable
private fun searchCenterText(state: MosqueFinderUiState): String {
    val center = when {
        state.center == null -> stringResource(R.string.mosque_no_location)
        state.centerOrigin == MosqueSearchCenterOrigin.CURRENT_LOCATION ->
            stringResource(R.string.mosque_location_current)
        state.centerLabel != null -> stringResource(R.string.mosque_location_saved, state.centerLabel)
        else -> stringResource(R.string.mosque_location_current)
    }
    return stringResource(R.string.mosque_searching_around, center)
}

@Composable
private fun failureText(failure: MosqueFailure): String = when (failure) {
    MosqueFailure.NOT_CONFIGURED -> stringResource(R.string.mosque_failure_not_configured)
    MosqueFailure.NETWORK_UNAVAILABLE -> stringResource(R.string.mosque_failure_network_unavailable)
    MosqueFailure.TIMEOUT -> stringResource(R.string.mosque_failure_timeout)
    MosqueFailure.CONNECTION -> stringResource(R.string.mosque_failure_connection)
    MosqueFailure.RATE_LIMITED -> stringResource(R.string.mosque_failure_rate_limited)
    MosqueFailure.ACCESS_DENIED -> stringResource(R.string.mosque_failure_access_denied)
    MosqueFailure.SERVER -> stringResource(R.string.mosque_failure_server)
    MosqueFailure.MALFORMED -> stringResource(R.string.mosque_failure_malformed)
    MosqueFailure.RESPONSE_TOO_LARGE -> stringResource(R.string.mosque_failure_too_large)
    MosqueFailure.UNKNOWN -> stringResource(R.string.mosque_failure_unknown)
}

@Composable
private fun noticeText(notice: MosqueNotice): String = when (notice) {
    MosqueNotice.PERMISSION_DENIED -> stringResource(R.string.mosque_location_permission_denied)
    MosqueNotice.LOCATION_SERVICES_DISABLED -> stringResource(R.string.mosque_location_services_disabled)
    MosqueNotice.LOCATION_TIMED_OUT -> stringResource(R.string.mosque_location_timeout)
    MosqueNotice.LOCATION_UNAVAILABLE -> stringResource(R.string.mosque_location_unavailable)
    MosqueNotice.DIRECTIONS_UNAVAILABLE -> stringResource(R.string.mosque_directions_unavailable)
}

/** A distance in the interface language's numerals: metres under a kilometre, then one decimal. */
@Composable
private fun distanceLabel(distanceMetres: Double): String {
    val locale = currentLocale()
    return if (distanceMetres < METRES_PER_KILOMETRE) {
        val metres = distanceMetres.roundToInt().coerceAtLeast(0)
        stringResource(
            R.string.mosque_distance_metres,
            CalendarFormatting.localizeDigits(metres.toString(), locale),
        )
    } else {
        val formatter = NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = 1
            maximumFractionDigits = 1
        }
        val kilometres = formatter.format(distanceMetres / METRES_PER_KILOMETRE)
        stringResource(
            R.string.mosque_distance_kilometres,
            CalendarFormatting.localizeDigits(kilometres, locale),
        )
    }
}

@Composable
private fun currentLocale(): Locale =
    LocalResources.current.configuration.locales.getOrNull(0) ?: Locale.getDefault()

/**
 * Hands a mosque to an installed maps app.
 *
 * A `geo:` URI is the platform-neutral form every maps app can accept; the web maps URL is the
 * fallback for a device with a browser but no maps app. Neither is part of Salah's own routing — the
 * app opens the destination and lets the user's chosen application decide the route. No activity is
 * resolved in advance: package-visibility rules make a pre-flight check unreliable, so the attempt
 * itself is the check.
 */
private fun openDirections(
    context: Context,
    mosque: Mosque,
    onUnavailable: () -> Unit,
) {
    val coordinates = "${mosque.location.latitude},${mosque.location.longitude}"
    val encodedName = Uri.encode(mosque.name)
    val geoUri = Uri.parse("geo:$coordinates?q=$coordinates($encodedName)")
    val webUri = Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$coordinates")

    val opened = runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, geoUri))
    }.isSuccess

    if (!opened) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, webUri))
        }.onFailure { onUnavailable() }
    }
}

/** Metres in a kilometre, used only for choosing the unit the distance is written in. */
private const val METRES_PER_KILOMETRE = 1_000.0
