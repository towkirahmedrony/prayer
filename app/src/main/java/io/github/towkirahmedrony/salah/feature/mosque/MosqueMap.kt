package io.github.towkirahmedrony.salah.feature.mosque

import android.graphics.PointF
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.towkirahmedrony.salah.R
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
 * style document that names its own vector tile sources, fonts and sprites; without a key there is no
 * URL to load, and the screen says so rather than showing an empty canvas. This is also why the map
 * has no full-offline claim: the Places cache in Room makes the *list* usable offline, while tiles
 * follow their own service terms and are not pre-downloaded by this app.
 *
 * **The camera follows the user's attention.** On first results it frames the search centre and the
 * returned mosques together; choosing a mosque animates to it and a highlighted ring marks the
 * selection. Tapping a marker selects the mosque through the same callback the list uses, so there is
 * one selection state and one detail card in both views.
 *
 * **Lifecycle.** The `MapView` is created and destroyed with the composition, and its render loop is
 * forwarded from the screen's lifecycle. Leaving the screen therefore stops it rather than leaving a
 * renderer running behind another destination.
 *
 * **Failure.** A style that cannot load is reported as a message over the map after the style callback
 * or a bounded timeout, whichever happens first. The list is not affected: it is drawn from the same
 * state and remains the accessible, offline-capable view.
 */
@Composable
fun MosqueMap(
    styleUrl: String?,
    center: GeoPoint?,
    mosques: List<MosqueWithDistance>,
    selectedPlaceId: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
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
    val selectedColor = coloring.secondary.toArgb()
    val centerColor = coloring.tertiary.toArgb()
    val outlineColor = coloring.surface.toArgb()

    // One renderer for the life of the map. It is not keyed on the palette, because recreating it on a
    // theme change would orphan the map and its tap listener; the palette is handed to it instead.
    val renderer = remember { MosqueMapRenderer() }
    val latestSelect = rememberUpdatedState(onSelect)
    SideEffect {
        renderer.onSelect = { placeId -> latestSelect.value(placeId) }
        renderer.updatePalette(
            markerColor = markerColor,
            selectedColor = selectedColor,
            centerColor = centerColor,
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
        // The style callback above clears the flag when it does arrive.
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

    Box(
        modifier = modifier
            .clip(shape)
            .border(1.dp, coloring.outlineVariant, shape)
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
            modifier = Modifier.fillMaxSize(),
        )

        if (isLoading) {
            MapOverlay(text = stringResource(R.string.mosque_map_loading))
        } else if (hasFailed) {
            MapOverlay(text = stringResource(R.string.mosque_map_failed))
        }

        Text(
            text = stringResource(R.string.mosque_map_attribution),
            style = MaterialTheme.typography.labelSmall,
            color = coloring.onSurfaceVariant,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(8.dp)
                .clip(MaterialTheme.shapes.small)
                .background(coloring.surface.copy(alpha = ATTRIBUTION_ALPHA))
                .padding(horizontal = 6.dp, vertical = 3.dp),
        )
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
private fun MapOverlay(text: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
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

// Formatting is stateless; one instance serves every distance and count on this map. CalendarFormatting
// is a class rather than an object, so this is the shared instance instead of a new one per call.
private val FORMATTING = CalendarFormatting()

// Map feedback timings and overlay opacities. They are used by the composable, not by the renderer,
// so they live at file scope rather than in the renderer's companion.
private const val MAP_LOAD_TIMEOUT_MILLIS = 20_000L
private const val ATTRIBUTION_ALPHA = 0.82f
private const val OVERLAY_ALPHA = 0.86f

/**
 * The imperative side of the map: sources, layers, camera and tap handling.
 *
 * Kept out of the composable so the map's own bookkeeping — which style is loaded, which centre was
 * fitted, which mosque was last selected — is one place rather than a set of remembered flags the
 * composition could lose. It is created once per colour palette and receives the latest data through
 * [update].
 */
private class MosqueMapRenderer {
    var onSelect: (String) -> Unit = {}

    private var markerColor: Int = 0
    private var selectedColor: Int = 0
    private var centerColor: Int = 0
    private var outlineColor: Int = 0

    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var center: GeoPoint? = null
    private var mosques: List<MosqueWithDistance> = emptyList()
    private var selectedPlaceId: String? = null

    private var lastCentered: GeoPoint? = null
    private var fittedCenter: GeoPoint? = null
    private var lastSelected: String? = null

    /**
     * Applies the active theme's colours, updating the drawn layers when the style is already loaded.
     *
     * A theme change normally reloads the style, which recreates the layers with the new values via
     * [ensureLayers]. Updating them here as well keeps the map correct in the case where only the
     * palette changes.
     */
    fun updatePalette(
        markerColor: Int,
        selectedColor: Int,
        centerColor: Int,
        outlineColor: Int,
    ) {
        val changed = this.markerColor != markerColor ||
            this.selectedColor != selectedColor ||
            this.centerColor != centerColor ||
            this.outlineColor != outlineColor
        this.markerColor = markerColor
        this.selectedColor = selectedColor
        this.centerColor = centerColor
        this.outlineColor = outlineColor
        if (!changed) return

        val loaded = style ?: return
        loaded.getLayerAs<CircleLayer>(CENTER_LAYER_ID)?.setProperties(
            circleColor(centerColor),
            circleRadius(CENTER_RADIUS),
            circleStrokeColor(outlineColor),
            circleStrokeWidth(CENTER_STROKE),
        )
        loaded.getLayerAs<CircleLayer>(MOSQUE_LAYER_ID)?.setProperties(
            circleColor(markerColor),
            circleRadius(MARKER_RADIUS),
            circleStrokeColor(outlineColor),
            circleStrokeWidth(MARKER_STROKE),
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

    private fun ensureLayers(style: Style) {
        if (style.getSource(CENTER_SOURCE_ID) == null) {
            style.addSource(GeoJsonSource(CENTER_SOURCE_ID, FeatureCollection.fromFeatures(emptyList())))
            style.addLayer(
                CircleLayer(CENTER_LAYER_ID, CENTER_SOURCE_ID).withProperties(
                    circleColor(centerColor),
                    circleRadius(CENTER_RADIUS),
                    circleStrokeColor(outlineColor),
                    circleStrokeWidth(CENTER_STROKE),
                ),
            )
        }

        if (style.getSource(MOSQUE_SOURCE_ID) == null) {
            style.addSource(GeoJsonSource(MOSQUE_SOURCE_ID, FeatureCollection.fromFeatures(emptyList())))
            style.addLayer(
                CircleLayer(MOSQUE_LAYER_ID, MOSQUE_SOURCE_ID).withProperties(
                    circleColor(markerColor),
                    circleRadius(MARKER_RADIUS),
                    circleStrokeColor(outlineColor),
                    circleStrokeWidth(MARKER_STROKE),
                ),
            )
        }

        if (style.getSource(SELECTED_SOURCE_ID) == null) {
            style.addSource(GeoJsonSource(SELECTED_SOURCE_ID, FeatureCollection.fromFeatures(emptyList())))
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
        const val MOSQUE_SOURCE_ID = "nearby-mosques"
        const val MOSQUE_LAYER_ID = "nearby-mosques-layer"
        const val SELECTED_SOURCE_ID = "selected-mosque"
        const val SELECTED_LAYER_ID = "selected-mosque-layer"
        const val PROPERTY_PLACE_ID = "placeId"
        const val CENTER_FEATURE_ID = "search-center"

        const val MARKER_RADIUS = 7f
        const val MARKER_STROKE = 2.5f
        const val SELECTED_RADIUS = 10f
        const val SELECTED_STROKE = 3.5f
        const val CENTER_RADIUS = 7f
        const val CENTER_STROKE = 3f

        const val INITIAL_ZOOM = 14.0
        const val SELECTED_ZOOM = 15.5
        const val CAMERA_ANIMATION_MILLIS = 600
        const val FIT_PADDING_PX = 96
        const val MIN_FIT_SPAN = 0.0001
    }
}
