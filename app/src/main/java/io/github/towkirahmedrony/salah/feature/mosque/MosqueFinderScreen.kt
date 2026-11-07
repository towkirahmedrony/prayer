package io.github.towkirahmedrony.salah.feature.mosque

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.towkirahmedrony.salah.R
import io.github.towkirahmedrony.salah.core.designsystem.ChevronLeftGlyph
import io.github.towkirahmedrony.salah.core.designsystem.DirectionsGlyph
import io.github.towkirahmedrony.salah.core.designsystem.ListGlyph
import io.github.towkirahmedrony.salah.core.designsystem.LocationPinFilledGlyph
import io.github.towkirahmedrony.salah.core.designsystem.LocationPinGlyph
import io.github.towkirahmedrony.salah.core.designsystem.MapGlyph
import io.github.towkirahmedrony.salah.core.designsystem.MosqueGlyph
import io.github.towkirahmedrony.salah.core.designsystem.RefreshGlyph
import io.github.towkirahmedrony.salah.core.designsystem.components.SalahSectionCard
import io.github.towkirahmedrony.salah.core.designsystem.components.SalahSkeletonBlock
import io.github.towkirahmedrony.salah.core.designsystem.components.SalahStatusPill
import io.github.towkirahmedrony.salah.core.designsystem.components.SalahUnavailableNote
import io.github.towkirahmedrony.salah.core.designsystem.theme.LocalSalahAccents
import io.github.towkirahmedrony.salah.core.domain.calendar.CalendarFormatting
import io.github.towkirahmedrony.salah.core.domain.mosque.Mosque
import io.github.towkirahmedrony.salah.core.domain.mosque.MosqueDataOrigin
import io.github.towkirahmedrony.salah.core.domain.mosque.MosqueFailure
import io.github.towkirahmedrony.salah.core.domain.mosque.MosqueWithDistance
import org.koin.androidx.compose.koinViewModel
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * The mosque finder.
 *
 * One question — "which mosques are around me?" — answered two ways from one state:
 *
 *  - **List:** the nearest mosque as a highlighted card with its distance, an estimated walking time
 *    and a Directions button, then the rest as compact rows, nearest first.
 *  - **Map:** a full-page map with as little text over it as possible. One thin bar floats at the top
 *    (back, list/map switch, refresh) and one thin card floats at the bottom (the selected mosque, its
 *    distance and Directions). The map pads its camera by exactly those two overlays.
 *
 * Switching views never re-queries. The list is the default because it survives every partial
 * failure: a missing key or a failed style leaves distances and directions fully usable.
 *
 * Location is a deliberate action. The first search uses the app's saved location without asking for
 * permission; the location button asks for a one-shot fix and requests the runtime permission only if
 * it has not been granted. That fix is used for this screen only and never written back.
 *
 * No Place Details request, no phone or website intent, no fake mosques. External directions go to
 * whichever installed app accepts a `geo:` URI, with a web maps fallback.
 *
 * Insets: like the screen it replaces, this does not pad for the status or navigation bar. The host
 * scaffold already provides that padding for every destination.
 */
@Composable
fun MosqueFinderScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MosqueFinderViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

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

    // Captured outside the `when`: `state` is a delegated property, so `state.failure` cannot be
    // smart-cast at the use site.
    val failure = state.failure
    val content = when {
        state.isLoading && !state.hasResults -> FinderContent.LOADING
        failure != null && !state.hasResults -> FinderContent.FAILURE
        state.center == null && !state.hasResults -> FinderContent.NO_LOCATION
        state.isEmptyResult && !state.hasResults -> FinderContent.EMPTY
        state.viewMode == MosqueViewMode.MAP -> FinderContent.MAP
        else -> FinderContent.LIST
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (content == FinderContent.MAP) {
            MapResults(
                state = state,
                viewModel = viewModel,
                onUseCurrentLocation = requestCurrentLocation,
                onDirectionsUnavailable = viewModel::onDirectionsUnavailable,
            )
            MapTopBar(
                state = state,
                onBack = onBack,
                onSelectView = viewModel::setViewMode,
                onRefresh = viewModel::refresh,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(horizontal = 12.dp, vertical = 12.dp),
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FinderChrome(
                    state = state,
                    onBack = onBack,
                    onSelectView = viewModel::setViewMode,
                    onUseCurrentLocation = requestCurrentLocation,
                    onRefresh = viewModel::refresh,
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    when (content) {
                        FinderContent.LOADING -> LoadingResults()
                        FinderContent.FAILURE -> FailurePanel(
                            failure = failure ?: MosqueFailure.UNKNOWN,
                            onRetry = viewModel::refresh,
                        )
                        FinderContent.NO_LOCATION -> NoLocationPanel(
                            onUseCurrentLocation = requestCurrentLocation,
                        )
                        FinderContent.EMPTY -> EmptyPanel(
                            radiusMetres = state.radiusMetres,
                            onRetry = viewModel::refresh,
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
    }
}

/** Which of the screen's six bodies is showing. Decided once, so the layout and the body agree. */
private enum class FinderContent {
    LOADING,
    FAILURE,
    NO_LOCATION,
    EMPTY,
    MAP,
    LIST,
}

/** The list screen's fixed top: header, search context, view switch and notices. */
@Composable
private fun FinderChrome(
    state: MosqueFinderUiState,
    onBack: () -> Unit,
    onSelectView: (MosqueViewMode) -> Unit,
    onUseCurrentLocation: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = MaterialTheme.colorScheme.surface

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        FinderHeader(
            state = state,
            container = container,
            onBack = onBack,
            onRefresh = onRefresh,
        )
        SearchContextCard(
            state = state,
            container = container,
            centerText = searchCenterText(state),
            onUseCurrentLocation = onUseCurrentLocation,
        )
        ViewModeSelector(
            selected = state.viewMode,
            container = container,
            onSelect = onSelectView,
            modifier = Modifier.fillMaxWidth(),
        )
        StatusNotices(state = state)
    }
}

/** Back, the title with the result count, and refresh. */
@Composable
private fun FinderHeader(
    state: MosqueFinderUiState,
    container: Color,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
) {
    val backLabel = stringResource(R.string.mosque_back)
    val refreshLabel = stringResource(R.string.mosque_action_refresh)
    val locale = currentLocale()
    val count = state.mosques.size
    val countDigits = FORMATTING.localizeDigits(count.toString(), locale)
    val subtitle = when {
        count == 0 -> null
        count == 1 -> stringResource(R.string.mosque_results_count_one)
        else -> stringResource(R.string.mosque_results_count, countDigits)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SurfaceIconButton(label = backLabel, container = container, onClick = onBack) {
            ChevronLeftGlyph(tint = MaterialTheme.colorScheme.onSurface)
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 2.dp),
        ) {
            Text(
                text = stringResource(R.string.mosque_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        SurfaceIconButton(
            label = refreshLabel,
            container = container,
            onClick = onRefresh,
            enabled = state.center != null && !state.isLoading && !state.isRefreshing,
        ) {
            RefreshGlyph(tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(22.dp))
        }
    }
}

/**
 * The map's whole top overlay: back, the list/map switch and refresh on one 48dp row. No title, no
 * count and no location card — the map is the content. A notice, when there is one, sits under it.
 */
@Composable
private fun MapTopBar(
    state: MosqueFinderUiState,
    onBack: () -> Unit,
    onSelectView: (MosqueViewMode) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = MaterialTheme.colorScheme.surface.copy(alpha = FLOATING_ALPHA)
    val backLabel = stringResource(R.string.mosque_back)
    val refreshLabel = stringResource(R.string.mosque_action_refresh)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SurfaceIconButton(label = backLabel, container = container, onClick = onBack) {
                ChevronLeftGlyph(tint = MaterialTheme.colorScheme.onSurface)
            }
            ViewModeSelector(
                selected = state.viewMode,
                container = container,
                onSelect = onSelectView,
                compact = true,
                modifier = Modifier.weight(1f),
            )
            SurfaceIconButton(
                label = refreshLabel,
                container = container,
                onClick = onRefresh,
                enabled = state.center != null && !state.isLoading && !state.isRefreshing,
            ) {
                if (state.isRefreshing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    RefreshGlyph(
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
        StatusNotices(state = state)
    }
}

/** A 48dp rounded button on a surface. */
@Composable
private fun SurfaceIconButton(
    label: String,
    container: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val shape = MaterialTheme.shapes.small
    Box(
        modifier = modifier
            .size(48.dp)
            .alpha(if (enabled || label.isEmpty()) 1f else DISABLED_ALPHA)
            .clip(shape)
            .background(container)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/** Which place the results describe, and the one-shot "use my current location" action. */
@Composable
private fun SearchContextCard(
    state: MosqueFinderUiState,
    container: Color,
    centerText: String,
    onUseCurrentLocation: () -> Unit,
) {
    val shape = MaterialTheme.shapes.medium
    val locationDescription = stringResource(R.string.mosque_action_use_current_location)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(container)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .padding(start = 12.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            LocationPinGlyph(
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(20.dp),
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = centerText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
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
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

/**
 * A two-option list/map switch.
 *
 * `selectable` with the radio-button role rather than two independent buttons: list and map are
 * mutually exclusive, and a screen reader should announce the current one and its state. [compact]
 * removes the inner padding so the whole switch is exactly one 48dp row, to sit beside two 48dp
 * buttons on the map's top bar.
 */
@Composable
private fun ViewModeSelector(
    selected: MosqueViewMode,
    container: Color,
    onSelect: (MosqueViewMode) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val groupDescription = stringResource(R.string.mosque_view_mode_description)
    val shape = MaterialTheme.shapes.small
    val inner = if (compact) 0.dp else 4.dp

    Row(
        modifier = modifier
            .clip(shape)
            .background(container)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .padding(inner)
            .selectableGroup()
            .semantics { contentDescription = groupDescription },
        horizontalArrangement = Arrangement.spacedBy(if (compact) 0.dp else 4.dp),
    ) {
        ViewModeOption(
            mode = MosqueViewMode.LIST,
            selected = selected == MosqueViewMode.LIST,
            label = stringResource(R.string.mosque_view_list),
            onSelect = onSelect,
            modifier = Modifier.weight(1f),
        )
        ViewModeOption(
            mode = MosqueViewMode.MAP,
            selected = selected == MosqueViewMode.MAP,
            label = stringResource(R.string.mosque_view_map),
            onSelect = onSelect,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ViewModeOption(
    mode: MosqueViewMode,
    selected: Boolean,
    label: String,
    onSelect: (MosqueViewMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(MaterialTheme.shapes.small)
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
            )
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = { onSelect(mode) },
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        when (mode) {
            MosqueViewMode.LIST -> ListGlyph(tint = contentColor, modifier = Modifier.size(18.dp))
            MosqueViewMode.MAP -> MapGlyph(tint = contentColor, modifier = Modifier.size(18.dp))
        }
        Spacer(modifier = Modifier.size(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = contentColor,
            maxLines = 1,
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

/** The nearest mosque as a highlight, then the rest as compact rows. */
@Composable
private fun ResultList(
    state: MosqueFinderUiState,
    onSelect: (String) -> Unit,
    onDirectionsUnavailable: () -> Unit,
) {
    val context = LocalContext.current
    val nearest = state.mosques.firstOrNull()
    val rest = state.mosques.drop(1)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (nearest != null) {
            item(key = "nearest") {
                NearestMosqueCard(
                    item = nearest,
                    cached = state.origin == MosqueDataOrigin.CACHE,
                    onDirections = {
                        openDirections(
                            context = context,
                            mosque = nearest.mosque,
                            onUnavailable = onDirectionsUnavailable,
                        )
                    },
                )
            }
        }
        if (rest.isNotEmpty()) {
            item(key = "more-header") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 2.dp, end = 2.dp, top = 12.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.mosque_more_nearby),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = stringResource(R.string.mosque_sorted_nearest),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(items = rest, key = { entry -> entry.mosque.placeId }) { entry ->
                MosqueRow(
                    item = entry,
                    selected = entry.mosque.placeId == state.selectedPlaceId,
                    onSelect = { onSelect(entry.mosque.placeId) },
                    onDirections = {
                        openDirections(
                            context = context,
                            mosque = entry.mosque,
                            onUnavailable = onDirectionsUnavailable,
                        )
                    },
                )
            }
        }
        item(key = "source-note") {
            Text(
                text = stringResource(R.string.mosque_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 2.dp, vertical = 8.dp),
            )
        }
    }
}

/** A card with the app's gold accent border, used for the nearest mosque. */
@Composable
private fun HighlightCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = MaterialTheme.shapes.large
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f), shape)
            .padding(16.dp),
        content = content,
    )
}

/**
 * The nearest mosque: its name, address, a large distance with a walking estimate, and Directions.
 *
 * The walking time is an estimate from the straight-line distance at a steady pace, and is left out
 * beyond [MAX_WALK_ESTIMATE_METRES], where it would stop being a useful number.
 */
@Composable
private fun NearestMosqueCard(
    item: MosqueWithDistance,
    cached: Boolean,
    onDirections: () -> Unit,
) {
    val mosque = item.mosque
    val accents = LocalSalahAccents.current

    HighlightCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SalahStatusPill(
                text = stringResource(R.string.mosque_nearest_label),
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = accents.goldText,
            )
            Spacer(modifier = Modifier.weight(1f))
            if (cached) {
                SalahStatusPill(
                    text = stringResource(R.string.mosque_origin_cached),
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = mosque.name,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = mosque.address ?: stringResource(R.string.mosque_address_unknown),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = distanceLabel(item.distanceMetres, short = true),
                style = MaterialTheme.typography.headlineMedium,
                color = accents.goldText,
            )
            if (item.distanceMetres <= MAX_WALK_ESTIMATE_METRES) {
                val minutes = ceil(item.distanceMetres / WALKING_METRES_PER_MINUTE)
                    .toInt()
                    .coerceAtLeast(1)
                Text(
                    text = stringResource(
                        R.string.mosque_walk_estimate,
                        FORMATTING.localizeDigits(minutes.toString(), currentLocale()),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 10.dp, bottom = 3.dp),
                )
            }
        }
        ContactRow(label = stringResource(R.string.mosque_phone), value = mosque.phone)
        ContactRow(label = stringResource(R.string.mosque_website), value = mosque.website)
        Spacer(modifier = Modifier.height(14.dp))
        DirectionsButton(mosqueName = mosque.name, onClick = onDirections)
    }
}

/**
 * One mosque in the list, collapsed to a single line of name and distance.
 *
 * Tapping it selects it, which opens the full address and any contact fields the response carried.
 * Directions are one tap on the trailing button either way. Nothing is fetched on selection.
 */
@Composable
private fun MosqueRow(
    item: MosqueWithDistance,
    selected: Boolean,
    onSelect: () -> Unit,
    onDirections: () -> Unit,
) {
    val mosque = item.mosque
    val shape = MaterialTheme.shapes.medium
    val borderColor = if (selected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }
    val directionsDescription = stringResource(R.string.mosque_open_directions, mosque.name)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, borderColor, shape)
            .clickable(onClick = onSelect)
            .padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                MosqueGlyph(
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(22.dp),
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
            ) {
                Text(
                    text = mosque.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = if (selected) 2 else 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row {
                    Text(
                        text = distanceLabel(item.distanceMetres, short = false),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (!selected && mosque.address != null) {
                        Text(
                            text = " · ",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = mosque.address,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            IconButton(
                onClick = onDirections,
                modifier = Modifier.semantics { contentDescription = directionsDescription },
            ) {
                DirectionsGlyph(
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
            }
        }

        if (selected) {
            Column(modifier = Modifier.padding(top = 8.dp, end = 12.dp)) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = mosque.address ?: stringResource(R.string.mosque_address_unknown),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                ContactRow(label = stringResource(R.string.mosque_phone), value = mosque.phone)
                ContactRow(label = stringResource(R.string.mosque_website), value = mosque.website)
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

/** The full-width filled action: hand the mosque to a maps app. */
@Composable
private fun DirectionsButton(
    mosqueName: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val description = stringResource(R.string.mosque_open_directions, mosqueName)
    Button(
        onClick = onClick,
        shape = MaterialTheme.shapes.small,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .semantics { contentDescription = description },
    ) {
        DirectionsGlyph(
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.size(8.dp))
        Text(text = stringResource(R.string.mosque_action_directions))
    }
}

/**
 * The full-page map and the selected mosque's thin card over its bottom edge.
 *
 * [MAP_TOP_INSET] and [MAP_BOTTOM_INSET] are the heights of the top bar and the card. The map pads its
 * camera by them, so the results are framed in the part of the map that is actually visible.
 */
@Composable
private fun MapResults(
    state: MosqueFinderUiState,
    viewModel: MosqueFinderViewModel,
    onUseCurrentLocation: () -> Unit,
    onDirectionsUnavailable: () -> Unit,
) {
    val darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val selected = state.selected
    val context = LocalContext.current

    Box(modifier = Modifier.fillMaxSize()) {
        MosqueMap(
            styleUrl = viewModel.mapStyleUrl(darkTheme = darkTheme),
            center = state.center,
            mosques = state.mosques,
            selectedPlaceId = state.selectedPlaceId,
            onSelect = viewModel::selectMosque,
            fullBleed = true,
            topInset = MAP_TOP_INSET,
            bottomInset = if (selected != null) MAP_BOTTOM_INSET else 0.dp,
            isAcquiringLocation = state.isAcquiringLocation,
            onUseCurrentLocation = onUseCurrentLocation,
            modifier = Modifier.fillMaxSize(),
        )
        if (selected != null) {
            SelectedMosqueBar(
                item = selected,
                onDirections = {
                    openDirections(
                        context = context,
                        mosque = selected.mosque,
                        onUnavailable = onDirectionsUnavailable,
                    )
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(12.dp),
            )
        }
    }
}

/**
 * The selected mosque as one thin row over the map: name, distance and address on the left, a
 * Directions button on the right. It is the only text at the bottom of the map.
 */
@Composable
private fun SelectedMosqueBar(
    item: MosqueWithDistance,
    onDirections: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val mosque = item.mosque
    val accents = LocalSalahAccents.current
    val shape = MaterialTheme.shapes.large
    val description = stringResource(R.string.mosque_open_directions, mosque.name)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = FLOATING_ALPHA))
            .border(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f), shape)
            .padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = mosque.name,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row {
                Text(
                    text = distanceLabel(item.distanceMetres, short = true),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = accents.goldText,
                )
                if (mosque.address != null) {
                    Text(
                        text = " · ",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = mosque.address,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        Spacer(modifier = Modifier.size(10.dp))
        Button(
            onClick = onDirections,
            shape = MaterialTheme.shapes.small,
            contentPadding = PaddingValues(horizontal = 14.dp),
            modifier = Modifier
                .heightIn(min = 48.dp)
                .semantics { contentDescription = description },
        ) {
            DirectionsGlyph(
                tint = MaterialTheme.colorScheme.onPrimary,
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
    val radiusDigits = FORMATTING.localizeDigits(radiusKilometres, locale)

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

/**
 * A distance in the interface language's numerals: metres under a kilometre, then one decimal.
 * [short] drops the "away" ("11 m" rather than "11 m away") for places where the layout already
 * says what the number is.
 */
@Composable
private fun distanceLabel(distanceMetres: Double, short: Boolean): String {
    val locale = currentLocale()
    return if (distanceMetres < METRES_PER_KILOMETRE) {
        val metres = distanceMetres.roundToInt().coerceAtLeast(0)
        stringResource(
            if (short) R.string.mosque_distance_metres_short else R.string.mosque_distance_metres,
            FORMATTING.localizeDigits(metres.toString(), locale),
        )
    } else {
        val formatter = NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = 1
            maximumFractionDigits = 1
        }
        val kilometres = formatter.format(distanceMetres / METRES_PER_KILOMETRE)
        stringResource(
            if (short) R.string.mosque_distance_km_short else R.string.mosque_distance_kilometres,
            FORMATTING.localizeDigits(kilometres, locale),
        )
    }
}

private val FORMATTING = CalendarFormatting()

@Composable
private fun currentLocale(): Locale {
    val configuration = LocalConfiguration.current
    return if (configuration.locales.size() > 0) configuration.locales[0] else Locale.ROOT
}

/**
 * Hands a mosque to an installed maps app.
 *
 * A `geo:` URI is the platform-neutral form every maps app can accept; the web maps URL is the
 * fallback for a device with a browser but no maps app. No activity is resolved in advance:
 * package-visibility rules make a pre-flight check unreliable, so the attempt itself is the check.
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

/** A steady walking pace, about 4.8 km/h, used only for the nearest mosque's walking estimate. */
private const val WALKING_METRES_PER_MINUTE = 80.0

/** Beyond this the straight-line walking estimate stops being a useful number. */
private const val MAX_WALK_ESTIMATE_METRES = 3_000.0

/** Surface opacity for pieces floating over the map: legible, but the map still shows through. */
private const val FLOATING_ALPHA = 0.94f

private const val DISABLED_ALPHA = 0.5f

/** Heights of the floating top bar and the selected-mosque bar; the map pads its camera by them. */
private val MAP_TOP_INSET = 72.dp
private val MAP_BOTTOM_INSET = 100.dp
