package io.github.towkirahmedrony.salah.feature.mosque

import android.graphics.PointF
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.towkirahmedrony.salah.R
import io.github.towkirahmedrony.salah.core.designsystem.LocateGlyph
import io.github.towkirahmedrony.salah.core.designsystem.MinusGlyph
import io.github.towkirahmedrony.salah.core.designsystem.PlusGlyph
import io.github.towkirahmedrony.salah.core.domain.calendar.CalendarFormatting
import io.github.towkirahmedrony.salah.core.domain.location.GeoPoint
import io.github.towkirahmedrony.salah.core.domain.mosque.MosqueWithDistance
import kotlinx.coroutines.delay
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleOpacity
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

/**
 * The map half of the mosque finder.
 *
 * **MapLibre renders, Geoapify supplies.** The SDK contains no map data. [styleUrl] is a Geoapify
 * style document; without a key there is no URL to load and the screen says so rather than showing an
 * empty canvas. The Places cache in Room makes the *list* usable offline; tiles are not pre-downloaded.
 *
 * **Full-page mode.** With [fullBleed] the map has no frame and fills whatever it is given; the screen
 * floats a thin bar at the top and a thin card at the bottom. [topInset] and [bottomInset] are those
 * overlays' heights: they become the map's logical padding, so the camera frames the results in the
 * part of the map the user can see, and the controls sit in the free space between.
 *
 * **"You are here" is the most prominent mark.** The search centre is drawn above every mosque marker,
 * larger than they are, with a pale ring and a soft halo, so it can be found at the zoom that frames
 * all results. The selected mosque is drawn above it.
 *
 * **One selection state.** Tapping a marker selects the mosque through the same callback the list uses.
 *
 * **Attribution.** MapLibre's own logo and info button are turned off because this composable always
 * draws the Geoapify and OpenStreetMap attribution itself.
 */
@Composable
fun MosqueMap(
    styleUrl: String?,
    center: GeoPoint?,
    mosques: List<MosqueWithDistance>,
    selectedPlaceId: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    fullBleed: Boolean = false,
    topInset: Dp = 0.dp,
    bottomInset: Dp = 0.dp,
    isAcquiringLocation: Boolean = false,
    onUseCurrentLocation: (() -> Unit)? = null,
) {
    if (styleUrl == null) {
        MapFallback(
            text = stringResource(R.string.mosque_map_unavailable),
            modifier = modifier,
        )
        return
    }

    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val coloring = MaterialTheme.colorScheme
    val locale = androidx.compose.ui.platform.LocalResources.current.configuration.locales[0]

    val markerColor = coloring.primary.toArgb()
    val selectedColor = coloring.primary.toArgb()
    val centerColor = coloring.secondary.toArgb()
    val centerRingColor = coloring.onSurface.toArgb()
    val outlineColor = coloring.surface.toArgb()

    val density = LocalDensity.current
    val topInsetPx = with(density) { topInset.roundToPx() }
    val bottomInsetPx = with(density) { bottomInset.roundToPx() }

    // One renderer for the life of the map. It is not keyed on the palette, because recreating it on a
    // theme change would orphan the map and its tap listener; the palette is handed to it instead.
    val renderer = remember { MosqueMapRenderer() }
    val latestSelect = rememberUpdatedState(onSelect)
    SideEffect {
        renderer.onSelect = { placeId -> latestSelect.value(placeId) }
        renderer.setInsets(top = topInsetPx, bottom = bottomInsetPx)
        renderer.updatePalette(
            markerColor = markerColor,
            selectedColor = selectedColor,
            centerColor = centerColor,
            centerRingColor = centerRingColor,
            outlineColor = outlineColor,
        )
    }

    val mapView = remember(context) {
        // MapLibre's renderer must have one process-wide instance before a MapView is constructed.
        MapLibre.getInstance(context)
        MapView(context).apply { onCreate(null) }
    }

    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var hasFailed by remember { mutableStateOf(false) }

    DisposableEffect(mapView, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            // The observer above forwards onStop when the screen stops. Calling it again here would
            // pair one onStart with two onStop calls, so disposal only tears the renderer down.
            mapView.onDestroy()
        }
    }

    LaunchedEffect(map, renderer) {
        map?.let { readyMap -> renderer.attachMap(readyMap) }
    }

    LaunchedEffect(styleUrl, map) {
        val readyMap = map ?: return@LaunchedEffect
        isLoading = true
        hasFailed = false
        readyMap.setStyle(styleUrl) { style ->
            renderer.onStyleLoaded(style)
            isLoading = false
            hasFailed = false
        }
        // A style request that never answers is treated as a failure rather than an endless overlay.
        delay(MAP_LOAD_TIMEOUT_MILLIS)
        if (isLoading) {
            isLoading = false
            hasFailed = true
        }
    }

    LaunchedEffect(center, mosques, selectedPlaceId) {
        renderer.update(
            center = center,
            mosques = mosques,
            selectedPlaceId = selectedPlaceId,
        )
    }

    val count = mosques.size
    val localizedCount = FORMATTING.localizeDigits(count.toString(), locale)
    val mapDescription = if (count == 1) {
        stringResource(R.string.mosque_map_description_one)
    } else {
        stringResource(R.string.mosque_map_description, localizedCount)
    }
    val shape = MaterialTheme.shapes.large
    val frame = if (fullBleed) {
        modifier
    } else {
        modifier
            .clip(shape)
            .border(1.dp, coloring.outlineVariant, shape)
    }

    Box(
        modifier = frame
            .background(coloring.surfaceVariant)
            .semantics { contentDescription = mapDescription },
    ) {
        AndroidView(
            factory = {
                mapView.apply {
                    addOnDidFailLoadingMapListener { _ ->
                        isLoading = false
                        hasFailed = true
                    }
                    getMapAsync { readyMap ->
                        map = readyMap
                        renderer.attachMap(readyMap)
                    }
                }
            },
            modifier = Modifier.matchParentSize(),
        )

        if (isLoading) {
            MapOverlay(text = stringResource(R.string.mosque_map_loading))
        } else if (hasFailed) {
            MapOverlay(text = stringResource(R.string.mosque_map_failed))
        }

        if (fullBleed) {
            MapControls(
                onZoomIn = renderer::zoomIn,
                onZoomOut = renderer::zoomOut,
                onUseCurrentLocation = onUseCurrentLocation,
                isAcquiringLocation = isAcquiringLocation,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(top = topInset, bottom = bottomInset, end = 12.dp),
            )
        }

        Text(
            text = stringResource(R.string.mosque_map_attribution),
            style = MaterialTheme.typography.labelSmall,
            color = coloring.onSurfaceVariant,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 6.dp + bottomInset)
                .clip(MaterialTheme.shapes.small)
                .background(coloring.surface.copy(alpha = ATTRIBUTION_ALPHA))
                .padding(horizontal = 6.dp, vertical = 3.dp),
        )
    }
}

/** Zoom in and out as one joined control, and the button that asks for a current location fix. */
@Composable
private fun MapControls(
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onUseCurrentLocation: (() -> Unit)?,
    isAcquiringLocation: Boolean,
    modifier: Modifier = Modifier,
) {
    val zoomInLabel = stringResource(R.string.mosque_zoom_in)
    val zoomOutLabel = stringResource(R.string.mosque_zoom_out)
    val locationLabel = stringResource(R.string.mosque_action_use_current_location)
    val shape = MaterialTheme.shapes.small
    val glyphTint = MaterialTheme.colorScheme.onSurface

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(
            modifier = Modifier
                .clip(shape)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = CONTROL_ALPHA))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
        ) {
            MapControlButton(label = zoomInLabel, onClick = onZoomIn) {
                PlusGlyph(tint = glyphTint, modifier = Modifier.size(22.dp))
            }
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 10.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            MapControlButton(label = zoomOutLabel, onClick = onZoomOut) {
                MinusGlyph(tint = glyphTint, modifier = Modifier.size(22.dp))
            }
        }
        if (onUseCurrentLocation != null) {
            Box(
                modifier = Modifier
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = CONTROL_ALPHA))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
            ) {
                MapControlButton(
                    label = locationLabel,
                    onClick = onUseCurrentLocation,
                    enabled = !isAcquiringLocation,
                ) {
                    if (isAcquiringLocation) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        LocateGlyph(
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MapControlButton(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/** What the map area shows when this build has no Geoapify key, so there is no style to load. */
@Composable
private fun MapFallback(
    text: String,
    modifier: Modifier = Modifier,
) {
    val shape = MaterialTheme.shapes.large
    Box(
        modifier = modifier
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A small, quiet overlay over the map for its loading and failure states. */
@Composable
private fun BoxScope.MapOverlay(text: String) {
    Box(
        modifier = Modifier
            .matchParentSize()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = OVERLAY_ALPHA)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(20.dp)
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = OVERLAY_ALPHA))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

// Formatting is stateless; one instance serves every count on this map.
private val FORMATTING = CalendarFormatting()

private const val MAP_LOAD_TIMEOUT_MILLIS = 20_000L
private const val ATTRIBUTION_ALPHA = 0.82f
private const val OVERLAY_ALPHA = 0.86f
private const val CONTROL_ALPHA = 0.94f

/**
 * The imperative side of the map: sources, layers, camera and tap handling.
 *
 * Kept out of the composable so the map's own bookkeeping — which style is loaded, which centre was
 * fitted, which mosque was last selected — is one place rather than a set of remembered flags the
 * composition could lose.
 */
private class MosqueMapRenderer {
    var onSelect: (String) -> Unit = {}

    private var markerColor: Int = 0
    private var selectedColor: Int = 0
    private var centerColor: Int = 0
    private var centerRingColor: Int = 0
    private var outlineColor: Int = 0

    private var topInsetPx: Int = 0
    private var bottomInsetPx: Int = 0

    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var center: GeoPoint? = null
    private var mosques: List<MosqueWithDistance> = emptyList()
    private var selectedPlaceId: String? = null

    private var lastCentered: GeoPoint? = null
    private var fittedCenter: GeoPoint? = null
    private var lastSelected: String? = null

    /**
     * The heights of the overlays drawn over the map. They become the map's logical padding, so every
     * camera move centres on the visible part of the map rather than behind the bar or the card.
     */
    fun setInsets(top: Int, bottom: Int) {
        if (top == topInsetPx && bottom == bottomInsetPx) return
        topInsetPx = top
        bottomInsetPx = bottom
        map?.setPadding(0, top, 0, bottom)
    }

    /** Applies the active theme's colours, updating the drawn layers when the style is already loaded. */
    fun updatePalette(
        markerColor: Int,
        selectedColor: Int,
        centerColor: Int,
        centerRingColor: Int,
        outlineColor: Int,
    ) {
        val changed = this.markerColor != markerColor ||
            this.selectedColor != selectedColor ||
            this.centerColor != centerColor ||
            this.centerRingColor != centerRingColor ||
            this.outlineColor != outlineColor
        this.markerColor = markerColor
        this.selectedColor = selectedColor
        this.centerColor = centerColor
        this.centerRingColor = centerRingColor
        this.outlineColor = outlineColor
        if (!changed) return

        val loaded = style ?: return
        loaded.getLayerAs<CircleLayer>(MOSQUE_LAYER_ID)?.setProperties(
            circleColor(markerColor),
            circleRadius(MARKER_RADIUS),
            circleStrokeColor(outlineColor),
            circleStrokeWidth(MARKER_STROKE),
        )
        loaded.getLayerAs<CircleLayer>(CENTER_HALO_LAYER_ID)?.setProperties(
            circleColor(centerColor),
            circleRadius(CENTER_HALO_RADIUS),
            circleOpacity(CENTER_HALO_OPACITY),
        )
        loaded.getLayerAs<CircleLayer>(CENTER_LAYER_ID)?.setProperties(
            circleColor(centerColor),
            circleRadius(CENTER_RADIUS),
            circleStrokeColor(centerRingColor),
            circleStrokeWidth(CENTER_STROKE),
        )
        loaded.getLayerAs<CircleLayer>(SELECTED_HALO_LAYER_ID)?.setProperties(
            circleColor(selectedColor),
            circleRadius(HALO_RADIUS),
            circleOpacity(HALO_OPACITY),
        )
        loaded.getLayerAs<CircleLayer>(SELECTED_LAYER_ID)?.setProperties(
            circleColor(selectedColor),
            circleRadius(SELECTED_RADIUS),
            circleStrokeColor(outlineColor),
            circleStrokeWidth(SELECTED_STROKE),
        )
    }

    fun attachMap(readyMap: MapLibreMap) {
        if (map === readyMap) return
        map = readyMap
        // The attribution is drawn by the composable, so the SDK's own logo and info button, which
        // would sit under the bottom card, are switched off.
        readyMap.uiSettings.isLogoEnabled = false
        readyMap.uiSettings.isAttributionEnabled = false
        readyMap.setPadding(0, topInsetPx, 0, bottomInsetPx)
        readyMap.addOnMapClickListener { latLng ->
            val clicked = clickedPlaceId(readyMap, latLng)
            if (clicked != null) {
                onSelect(clicked)
                true
            } else {
                false
            }
        }
        // A style may have loaded before the map callback ran; draw whatever is already known.
        render()
    }

    fun onStyleLoaded(loadedStyle: Style) {
        style = loadedStyle
        // A new style starts with no sources or layers, even though the camera and the data did not
        // change. Forget what was fitted so the next render frames the results again.
        fittedCenter = null
        lastSelected = null
        ensureLayers(loadedStyle)
        render()
    }

    fun update(
        center: GeoPoint?,
        mosques: List<MosqueWithDistance>,
        selectedPlaceId: String?,
    ) {
        this.center = center
        this.mosques = mosques
        this.selectedPlaceId = selectedPlaceId
        render()
    }

    fun zoomIn() {
        map?.animateCamera(CameraUpdateFactory.zoomIn(), ZOOM_ANIMATION_MILLIS)
    }

    fun zoomOut() {
        map?.animateCamera(CameraUpdateFactory.zoomOut(), ZOOM_ANIMATION_MILLIS)
    }

    private fun render() {
        val style = style ?: return
        val center = center
        val map = map ?: return

        val centerFeatures = center?.let { point ->
            listOf(featureAt(point, CENTER_FEATURE_ID))
        }.orEmpty()
        style.source(CENTER_SOURCE_ID)?.setGeoJson(FeatureCollection.fromFeatures(centerFeatures))

        val mosqueFeatures = mosques.map { item ->
            featureAt(item.mosque.location, item.mosque.placeId)
        }
        style.source(MOSQUE_SOURCE_ID)?.setGeoJson(FeatureCollection.fromFeatures(mosqueFeatures))

        val selected = mosques.firstOrNull { item -> item.mosque.placeId == selectedPlaceId }
        val selectedFeatures = selected?.let { item ->
            listOf(featureAt(item.mosque.location, item.mosque.placeId))
        }.orEmpty()
        style.source(SELECTED_SOURCE_ID)?.setGeoJson(FeatureCollection.fromFeatures(selectedFeatures))

        if (center != null) {
            if (lastCentered != center) {
                lastCentered = center
                map.animateCamera(
                    CameraUpdateFactory.newLatLngZoom(latLng(center), INITIAL_ZOOM),
                    CAMERA_ANIMATION_MILLIS,
                )
            }

            if (mosques.isNotEmpty() && fittedCenter != center) {
                fittedCenter = center
                fitResults(map, center)
                // The fit is the first impression of the result set; auto-selecting its nearest
                // mosque should not immediately zoom past the other markers. Treat that first
                // selection as already shown, so only a later tap moves the camera to a mosque.
                lastSelected = selectedPlaceId
            } else if (selectedPlaceId != lastSelected) {
                lastSelected = selectedPlaceId
                selected?.let { item ->
                    map.animateCamera(
                        CameraUpdateFactory.newLatLngZoom(latLng(item.mosque.location), SELECTED_ZOOM),
                        CAMERA_ANIMATION_MILLIS,
                    )
                }
            }
        }
    }

    private fun fitResults(map: MapLibreMap, center: GeoPoint) {
        val points = buildList {
            add(center)
            mosques.forEach { item -> add(item.mosque.location) }
        }
        val north = points.maxOf { it.latitude }
        val south = points.minOf { it.latitude }
        val east = points.maxOf { it.longitude }
        val west = points.minOf { it.longitude }

        if (north - south < MIN_FIT_SPAN && east - west < MIN_FIT_SPAN) {
            map.animateCamera(
                CameraUpdateFactory.newLatLngZoom(latLng(center), SELECTED_ZOOM),
                CAMERA_ANIMATION_MILLIS,
            )
            return
        }

        val bounds = LatLngBounds.Builder()
            .include(latLng(GeoPoint(north, west)))
            .include(latLng(GeoPoint(south, east)))
            .build()
        map.animateCamera(
            CameraUpdateFactory.newLatLngBounds(bounds, FIT_PADDING_PX),
            CAMERA_ANIMATION_MILLIS,
        )
    }

    private fun clickedPlaceId(map: MapLibreMap, latLng: LatLng): String? {
        val screenPoint: PointF = map.projection.toScreenLocation(latLng)
        return map.queryRenderedFeatures(screenPoint, MOSQUE_LAYER_ID)
            .firstOrNull()
            ?.getStringProperty(PROPERTY_PLACE_ID)
    }

    /**
     * Adds the sources, then the layers in draw order, bottom to top: mosque markers, the search
     * centre's halo and dot, then the selected mosque's halo and marker. The centre is above the
     * markers so it is never hidden by a crowd of them; the selected mosque is above the centre so a
     * mosque a few metres away stays tappable and visible.
     */
    private fun ensureLayers(style: Style) {
        listOf(MOSQUE_SOURCE_ID, CENTER_SOURCE_ID, SELECTED_SOURCE_ID).forEach { sourceId ->
            if (style.getSource(sourceId) == null) {
                style.addSource(GeoJsonSource(sourceId, FeatureCollection.fromFeatures(emptyList())))
            }
        }

        if (style.getLayer(MOSQUE_LAYER_ID) == null) {
            style.addLayer(
                CircleLayer(MOSQUE_LAYER_ID, MOSQUE_SOURCE_ID).withProperties(
                    circleColor(markerColor),
                    circleRadius(MARKER_RADIUS),
                    circleStrokeColor(outlineColor),
                    circleStrokeWidth(MARKER_STROKE),
                ),
            )
        }
        if (style.getLayer(CENTER_HALO_LAYER_ID) == null) {
            style.addLayer(
                CircleLayer(CENTER_HALO_LAYER_ID, CENTER_SOURCE_ID).withProperties(
                    circleColor(centerColor),
                    circleRadius(CENTER_HALO_RADIUS),
                    circleOpacity(CENTER_HALO_OPACITY),
                ),
            )
        }
        if (style.getLayer(CENTER_LAYER_ID) == null) {
            style.addLayer(
                CircleLayer(CENTER_LAYER_ID, CENTER_SOURCE_ID).withProperties(
                    circleColor(centerColor),
                    circleRadius(CENTER_RADIUS),
                    circleStrokeColor(centerRingColor),
                    circleStrokeWidth(CENTER_STROKE),
                ),
            )
        }
        if (style.getLayer(SELECTED_HALO_LAYER_ID) == null) {
            style.addLayer(
                CircleLayer(SELECTED_HALO_LAYER_ID, SELECTED_SOURCE_ID).withProperties(
                    circleColor(selectedColor),
                    circleRadius(HALO_RADIUS),
                    circleOpacity(HALO_OPACITY),
                ),
            )
        }
        if (style.getLayer(SELECTED_LAYER_ID) == null) {
            style.addLayer(
                CircleLayer(SELECTED_LAYER_ID, SELECTED_SOURCE_ID).withProperties(
                    circleColor(selectedColor),
                    circleRadius(SELECTED_RADIUS),
                    circleStrokeColor(outlineColor),
                    circleStrokeWidth(SELECTED_STROKE),
                ),
            )
        }
    }

    private fun Style.source(id: String): GeoJsonSource? = getSourceAs<GeoJsonSource>(id)

    private fun featureAt(location: GeoPoint, placeId: String): Feature =
        Feature.fromGeometry(Point.fromLngLat(location.longitude, location.latitude))
            .apply { addStringProperty(PROPERTY_PLACE_ID, placeId) }

    private fun latLng(location: GeoPoint) = LatLng(location.latitude, location.longitude)

    private companion object {
        const val CENTER_SOURCE_ID = "mosque-search-center"
        const val CENTER_LAYER_ID = "mosque-search-center-layer"
        const val CENTER_HALO_LAYER_ID = "mosque-search-center-halo-layer"
        const val MOSQUE_SOURCE_ID = "nearby-mosques"
        const val MOSQUE_LAYER_ID = "nearby-mosques-layer"
        const val SELECTED_SOURCE_ID = "selected-mosque"
        const val SELECTED_LAYER_ID = "selected-mosque-layer"
        const val SELECTED_HALO_LAYER_ID = "selected-mosque-halo-layer"
        const val PROPERTY_PLACE_ID = "placeId"
        const val CENTER_FEATURE_ID = "search-center"

        const val MARKER_RADIUS = 6f
        const val MARKER_STROKE = 2f
        const val SELECTED_RADIUS = 12f
        const val SELECTED_STROKE = 3.5f
        const val HALO_RADIUS = 22f
        const val HALO_OPACITY = 0.25f

        // The "you are here" dot: clearly larger than a marker, with a pale ring and a wide soft halo.
        const val CENTER_RADIUS = 10f
        const val CENTER_STROKE = 4f
        const val CENTER_HALO_RADIUS = 30f
        const val CENTER_HALO_OPACITY = 0.3f

        const val INITIAL_ZOOM = 14.0
        const val SELECTED_ZOOM = 15.5
        const val CAMERA_ANIMATION_MILLIS = 600
        const val ZOOM_ANIMATION_MILLIS = 250
        const val FIT_PADDING_PX = 72
        const val MIN_FIT_SPAN = 0.0001
    }
}
