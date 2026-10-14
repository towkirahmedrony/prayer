package io.github.towkirahmedrony.salah.feature.location.map

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.towkirahmedrony.salah.BuildConfig
import io.github.towkirahmedrony.salah.R
import io.github.towkirahmedrony.salah.core.designsystem.LocationPinFilledGlyph
import io.github.towkirahmedrony.salah.core.designsystem.components.SalahSectionCard
import io.github.towkirahmedrony.salah.core.designsystem.components.SalahSkeletonBlock
import io.github.towkirahmedrony.salah.core.designsystem.components.SalahSubpageHeader
import io.github.towkirahmedrony.salah.core.domain.location.BangladeshMapViewport
import io.github.towkirahmedrony.salah.core.domain.location.GeoPoint
import kotlinx.coroutines.delay
import org.koin.androidx.compose.koinViewModel
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

/**
 * The interactive map-based location picker.
 *
 * **What it is.** A real, interactive MapLibre map — real geographic data, panned and zoomed with the
 * platform's own gestures — with the selection held at the map's centre. A drag, a tap and the
 * device-location button all end in the same place: the map reports its new centre, the centre becomes
 * the selection, and the panel below shows that point's coordinates, its boundary classification and
 * whatever address the provider returned. Nothing is stored until the user confirms.
 *
 * **Why the marker is fixed at the centre rather than an annotation on the map.** A fixed marker has one
 * authoritative selection (the camera target), so there is no second "selected coordinate" that could
 * drift from what the user sees, no deprecated annotation API to depend on, and no ambiguity when a tap
 * and a drag disagree. It is also how the boundary constraint behaves sensibly: the camera is limited to
 * a rectangle around Bangladesh, so the marker simply stops at the edge.
 *
 * **What is not here.** No map tiles are bundled, so street-level detail needs a connection; the
 * coordinates, their validation and the save all work offline. There is no "offline map" claim — see
 * `docs/MAP_PICKER.md` for that and for the tile and geocoding providers' terms.
 *
 * The screen is a pure function of [MapPickerUiState]; everything with a decision in it lives in the view
 * model, so this file is arrangement and wording only.
 */
@Composable
fun MapPickerScreen(
    onBack: () -> Unit,
    onConfirmed: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MapPickerViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        viewModel.start()
    }

    // The location permission is requested only from this button and nowhere else, so opening the map
    // never prompts. A denied request is not a dead end: the map, and the whole manual flow, stay usable.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        viewModel.useCurrentLocation()
    }

    // Leaving the screen is an effect of the confirmed write, not of the button: the store is the source
    // of truth for "the location changed", so a rejected or slow write cannot navigate away.
    LaunchedEffect(state.isSaved) {
        if (state.isSaved) onConfirmed()
    }

    MapPickerContent(
        state = state,
        modifier = modifier,
        onBack = onBack,
        onSelection = viewModel::onSelectionChanged,
        onCameraRequestConsumed = viewModel::onCameraRequestConsumed,
        onConfirm = viewModel::confirm,
        onDismissMessage = viewModel::dismissMessage,
        onUseCurrentLocation = {
            // The contract itself is the permission request; if the permission is already granted it
            // returns immediately, so both cases land in the same place.
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
            )
        },
    )
}

@Composable
private fun MapPickerContent(
    state: MapPickerUiState,
    onBack: () -> Unit,
    onSelection: (GeoPoint) -> Unit,
    onCameraRequestConsumed: () -> Unit,
    onConfirm: () -> Unit,
    onDismissMessage: () -> Unit,
    onUseCurrentLocation: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The map style follows the app's theme, so a dark-theme user is not handed a bright map. The style
    // is chosen from the resolved colour scheme rather than the system setting, because Salah's own theme
    // preference can differ from the device's.
    val darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val styleUrl = if (darkTheme) BuildConfig.MAP_DARK_STYLE_URL else BuildConfig.MAP_STYLE_URL

    Column(modifier = modifier.fillMaxSize()) {
        SalahSubpageHeader(
            title = stringResource(R.string.location_map_title),
            backContentDescription = stringResource(R.string.action_back),
            onBack = onBack,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            MapViewport(
                styleUrl = styleUrl,
                initialPoint = state.initialPoint,
                initialZoom = state.initialZoom,
                cameraRequest = state.cameraRequest,
                onCameraRequestConsumed = onCameraRequestConsumed,
                onSelection = onSelection,
                modifier = Modifier.fillMaxSize(),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = PANEL_MAX_HEIGHT)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SelectionPanel(
                state = state,
                onConfirm = onConfirm,
                onUseCurrentLocation = onUseCurrentLocation,
            )

            state.message?.let { message ->
                MessageRow(message = message, onDismiss = onDismissMessage)
            }
        }
    }
}

/** The panel under the map: what is selected, what is known about it, and the confirm action. */
@Composable
private fun SelectionPanel(
    state: MapPickerUiState,
    onConfirm: () -> Unit,
    onUseCurrentLocation: () -> Unit,
) {
    SalahSectionCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.location_map_selected_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
            OutlinedButton(onClick = onUseCurrentLocation, enabled = !state.isAcquiring) {
                Text(text = stringResource(R.string.location_map_use_gps))
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (state.isLoading) {
            SalahSkeletonBlock(height = 14.dp, modifier = Modifier.fillMaxWidth(0.6f))
            return@SalahSectionCard
        }

        // The country verdict, before any address: it is the thing that decides whether this location can
        // be used at all, and it never claims containment the check did not establish.
        when {
            state.isOutsideCountry -> {
                Text(
                    text = stringResource(R.string.location_map_outside_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.location_map_outside_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            state.isBoundaryVerified -> StatusLine(R.string.location_map_boundary_verified)
            state.isWithinBoundaryTolerance -> StatusLine(R.string.location_map_boundary_tolerance)
            else -> StatusLine(R.string.location_map_boundary_unverified)
        }

        Spacer(modifier = Modifier.height(10.dp))

        // The address, when the provider returned one. Every row is conditional, so a rural point with no
        // named road simply has no road row rather than a placeholder.
        when (val address = state.address) {
            MapAddressState.Idle -> Unit

            MapAddressState.Loading -> {
                Text(
                    text = stringResource(R.string.location_map_address_loading),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            is MapAddressState.Resolved -> {
                address.address.placeName?.let { name ->
                    DetailRow(label = stringResource(R.string.location_map_place_label), value = name)
                }
                address.address.locality?.let { locality ->
                    DetailRow(
                        label = stringResource(R.string.location_map_locality_label),
                        value = locality,
                    )
                }
                address.address.road?.let { road ->
                    DetailRow(label = stringResource(R.string.location_map_road_label), value = road)
                }
            }

            is MapAddressState.Failed -> {
                Text(
                    text = if (address.isPlaceUnknown) {
                        stringResource(R.string.location_map_address_unknown)
                    } else {
                        stringResource(R.string.location_map_address_unavailable)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // The administrative rows come from the offline resolution against the bundled dataset, so they
        // are present with the radio off — and they are the same dataset the rest of the app uses, which a
        // provider's generic keys could not stand in for.
        state.area?.let { area ->
            DetailRow(label = stringResource(R.string.location_upazila_label), value = area.upazilaName ?: area.districtName)
            DetailRow(label = stringResource(R.string.location_district_label), value = area.districtName)
            DetailRow(label = stringResource(R.string.location_division_label), value = area.divisionName)
        }

        Spacer(modifier = Modifier.height(8.dp))
        // `home_time_unavailable` is the app's one em-dash placeholder, so a point that has not been
        // reported yet reads the same here as it does on Home rather than showing an empty row.
        val dash = stringResource(R.string.home_time_unavailable)
        DetailRow(
            label = stringResource(R.string.location_map_coordinates_label),
            value = state.coordinateText.ifBlank { dash },
        )

        Spacer(modifier = Modifier.height(12.dp))
        Button(
            onClick = onConfirm,
            modifier = Modifier.fillMaxWidth(),
            enabled = state.canSave,
        ) {
            Text(text = stringResource(R.string.location_map_confirm))
        }

        if (state.isSaving) {
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun StatusLine(textRes: Int) {
    Text(
        text = stringResource(textRes),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
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
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

@Composable
private fun MessageRow(message: MapPickerMessage, onDismiss: () -> Unit) {
    SalahSectionCard {
        Text(
            text = stringResource(
                when (message) {
                    MapPickerMessage.Saved -> R.string.location_map_message_saved
                    MapPickerMessage.Rejected -> R.string.location_message_rejected
                    is MapPickerMessage.OutsideCountry -> R.string.location_map_message_outside
                    MapPickerMessage.DatasetUnavailable -> R.string.location_message_dataset_unavailable
                    MapPickerMessage.PermissionDenied -> R.string.location_message_permission_denied
                    MapPickerMessage.ServicesDisabled -> R.string.location_message_services_disabled
                    MapPickerMessage.TimedOut -> R.string.location_message_timeout
                    MapPickerMessage.ProviderFailed -> R.string.location_message_provider_failed
                },
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(4.dp))
        TextButton(onClick = onDismiss) {
            Text(text = stringResource(R.string.location_message_dismiss))
        }
    }
}

/**
 * The map itself, as a Compose `AndroidView` around MapLibre's own [MapView].
 *
 * Lifecycle is forwarded explicitly because a Compose host has no Activity callbacks to piggy-back on,
 * and MapLibre requires them: missing `onDestroy` leaks the native map, missing `onStop` keeps rendering
 * in the background. The current lifecycle state is replayed on attachment so a composable that enters an
 * already-resumed screen still receives `onResume`.
 *
 * A style that never loads is reported after [STYLE_LOAD_TIMEOUT_MILLIS] so the screen says so instead of
 * showing a blank rectangle forever; the coordinates and the confirm button are unaffected by it.
 */
@Composable
private fun MapViewport(
    styleUrl: String,
    initialPoint: GeoPoint,
    initialZoom: Double,
    cameraRequest: MapCameraRequest?,
    onCameraRequestConsumed: () -> Unit,
    onSelection: (GeoPoint) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnSelection by rememberUpdatedState(onSelection)
    val currentOnCameraConsumed by rememberUpdatedState(onCameraRequestConsumed)

    var mapView by remember { mutableStateOf<MapView?>(null) }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var isStyleReady by remember { mutableStateOf(false) }
    var isStyleFailed by remember { mutableStateOf(false) }
    var isInitialCameraApplied by remember { mutableStateOf(false) }

    val tileLoadingBackground = MaterialTheme.colorScheme.surfaceVariant
    val onSurface = MaterialTheme.colorScheme.onSurface

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            // The SDK singleton must exist before a MapView is constructed; `runCatching` because a
            // second call after a process-level initialisation is not an error but is not free either, and
            // a failure here should degrade to the "map unavailable" overlay rather than crash the screen.
            runCatching { MapLibre.getInstance(ctx.applicationContext) }
            MapView(ctx).also { view ->
                view.onCreate(null)
                mapView = view
            }
        },
        update = { },
    )

    // MapLibre lifecycle. `destroyed` guards the double-destroy that would otherwise happen when this
    // composable leaves a screen whose Activity also reaches ON_DESTROY.
    DisposableEffect(lifecycleOwner, mapView) {
        val view = mapView ?: return@DisposableEffect onDispose { }
        var destroyed = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> view.onStart()
                Lifecycle.Event.ON_RESUME -> view.onResume()
                Lifecycle.Event.ON_PAUSE -> view.onPause()
                Lifecycle.Event.ON_STOP -> view.onStop()
                Lifecycle.Event.ON_DESTROY -> {
                    view.onDestroy()
                    destroyed = true
                }

                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        val state = lifecycleOwner.lifecycle.currentState
        if (state.isAtLeast(Lifecycle.State.STARTED)) view.onStart()
        if (state.isAtLeast(Lifecycle.State.RESUMED)) view.onResume()

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (!destroyed) {
                runCatching { view.onDestroy() }
            }
        }
    }

    LaunchedEffect(mapView) {
        val view = mapView ?: return@LaunchedEffect
        view.getMapAsync { mapLibreMap ->
            map = mapLibreMap
            // A tap moves the camera rather than adding an annotation, so the marker (the map centre) is
            // always exactly the selection; the idle listener below then reports it.
            mapLibreMap.addOnMapClickListener { latLng ->
                currentOnSelection(GeoPoint(latLng.latitude, latLng.longitude))
                mapLibreMap.animateCamera(
                    CameraUpdateFactory.newLatLngZoom(latLng, mapLibreMap.cameraPosition.zoom),
                )
                true
            }
            mapLibreMap.addOnCameraIdleListener {
                // `target` is nullable in the SDK even though a live camera always has one; a camera
                // without a target has no selection to report, so nothing is emitted rather than a
                // fabricated point.
                val target = mapLibreMap.cameraPosition.target ?: return@addOnCameraIdleListener
                currentOnSelection(GeoPoint(target.latitude, target.longitude))
            }
        }
    }

    LaunchedEffect(map, styleUrl) {
        val mapLibreMap = map ?: return@LaunchedEffect
        isStyleReady = false
        isStyleFailed = false
        runCatching {
            // The camera constraint keeps the view over the country so a pan cannot wander away; the
            // *country check* is still the polygon test in the view model, never this rectangle.
            mapLibreMap.setLatLngBoundsForCameraTarget(BANGLADESH_BOUNDS)
            mapLibreMap.setMinZoomPreference(BangladeshMapViewport.MIN_ZOOM)
            mapLibreMap.setMaxZoomPreference(BangladeshMapViewport.MAX_ZOOM)
            mapLibreMap.setStyle(Style.Builder().fromUri(styleUrl)) { isStyleReady = true }
        }.onFailure { isStyleFailed = true }
    }

    // A style that never arrives (offline, or a provider outage) must say so rather than leaving a blank
    // rectangle. The timeout is generous: a slow connection should still get the map.
    LaunchedEffect(map, styleUrl) {
        delay(STYLE_LOAD_TIMEOUT_MILLIS)
        if (!isStyleReady) isStyleFailed = true
    }

    LaunchedEffect(map, isStyleReady, initialPoint, initialZoom) {
        val mapLibreMap = map ?: return@LaunchedEffect
        if (!isStyleReady || isInitialCameraApplied) return@LaunchedEffect
        isInitialCameraApplied = true
        mapLibreMap.cameraPosition = CameraPosition.Builder()
            .target(initialPoint.toLatLng())
            .zoom(initialZoom)
            .build()
    }

    LaunchedEffect(map, isStyleReady, cameraRequest) {
        val mapLibreMap = map ?: return@LaunchedEffect
        val request = cameraRequest ?: return@LaunchedEffect
        if (!isStyleReady) return@LaunchedEffect
        mapLibreMap.animateCamera(
            CameraUpdateFactory.newLatLngZoom(request.point.toLatLng(), request.zoom),
        )
        currentOnCameraConsumed()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // The marker, fixed at the map's centre so the selection is exactly what the user sees. It is
        // lifted by half its height so the pin's tip — not its middle — marks the point.
        LocationPinFilledGlyph(
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .align(Alignment.Center)
                .size(CROSSHAIR_SIZE)
                .offset(y = -(CROSSHAIR_SIZE / 2)),
        )

        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val zoomInLabel = stringResource(R.string.location_map_zoom_in)
            val zoomOutLabel = stringResource(R.string.location_map_zoom_out)
            FilledTonalIconButton(
                onClick = {
                    map?.let { mapLibreMap ->
                        mapLibreMap.animateCamera(CameraUpdateFactory.zoomIn())
                    }
                },
                modifier = Modifier.semantics { contentDescription = zoomInLabel },
            ) {
                Text(text = PLUS)
            }
            FilledTonalIconButton(
                onClick = {
                    map?.let { mapLibreMap ->
                        mapLibreMap.animateCamera(CameraUpdateFactory.zoomOut())
                    }
                },
                modifier = Modifier.semantics { contentDescription = zoomOutLabel },
            ) {
                Text(text = MINUS)
            }
        }

        // Attribution is a licence requirement of both the map data and the tiles, so it is always shown
        // on the map rather than behind a toggle. MapLibre's own attribution control stays enabled too.
        Text(
            text = stringResource(R.string.location_map_attribution),
            style = MaterialTheme.typography.labelSmall,
            color = onSurface,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(8.dp)
                .background(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f),
                    shape = RoundedCornerShape(4.dp),
                )
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )

        if (!isStyleReady) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(tileLoadingBackground.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (isStyleFailed) {
                        Text(
                            text = stringResource(R.string.location_map_unavailable_title),
                            style = MaterialTheme.typography.titleSmall,
                            color = onSurface,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.location_map_unavailable_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = onSurface,
                        )
                    } else {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = stringResource(R.string.location_map_loading),
                            style = MaterialTheme.typography.bodySmall,
                            color = onSurface,
                        )
                    }
                }
            }
        }
    }
}

private fun GeoPoint.toLatLng(): LatLng = LatLng(latitude, longitude)

/** The camera constraint: a rectangle around Bangladesh, not the validation boundary. */
private val BANGLADESH_BOUNDS: LatLngBounds
    get() = LatLngBounds.Builder()
        .include(LatLng(BangladeshMapViewport.NORTH, BangladeshMapViewport.WEST))
        .include(LatLng(BangladeshMapViewport.SOUTH, BangladeshMapViewport.EAST))
        .build()

private val CROSSHAIR_SIZE = 40.dp

/** How long the panel may grow before it scrolls, so the map keeps a usable share of a small screen. */
private val PANEL_MAX_HEIGHT = 320.dp

private const val STYLE_LOAD_TIMEOUT_MILLIS = 15_000L

/** Symbol-only zoom labels; the words that name them come from resources. */
private const val PLUS = "+"
private const val MINUS = "\u2212"
