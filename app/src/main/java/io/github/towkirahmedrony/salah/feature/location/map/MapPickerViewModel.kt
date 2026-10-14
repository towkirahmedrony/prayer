package io.github.towkirahmedrony.salah.feature.location.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.towkirahmedrony.salah.core.domain.DiagnosticsLogger
import io.github.towkirahmedrony.salah.core.domain.SalahDiagnosticEvents
import io.github.towkirahmedrony.salah.core.domain.location.AreaResolution
import io.github.towkirahmedrony.salah.core.domain.location.AreaResolver
import io.github.towkirahmedrony.salah.core.domain.location.BangladeshCheck
import io.github.towkirahmedrony.salah.core.domain.location.BangladeshLocationDataset
import io.github.towkirahmedrony.salah.core.domain.location.BangladeshLocationPolicy
import io.github.towkirahmedrony.salah.core.domain.location.BangladeshMapViewport
import io.github.towkirahmedrony.salah.core.domain.location.GeoPoint
import io.github.towkirahmedrony.salah.core.domain.location.LocationDisplayName
import io.github.towkirahmedrony.salah.core.domain.location.LocationFormatting
import io.github.towkirahmedrony.salah.core.domain.location.ReverseGeocodeResult
import io.github.towkirahmedrony.salah.core.domain.location.ReverseGeocoder
import io.github.towkirahmedrony.salah.core.domain.notification.PrayerNotificationCoordinator
import io.github.towkirahmedrony.salah.core.domain.notification.ReconcileReason
import io.github.towkirahmedrony.salah.core.domain.qibla.LocationAcquisitionResult
import io.github.towkirahmedrony.salah.core.domain.qibla.LocationPermissionChecker
import io.github.towkirahmedrony.salah.core.domain.qibla.LocationPermissionState
import io.github.towkirahmedrony.salah.core.domain.qibla.LocationProvider
import io.github.towkirahmedrony.salah.core.domain.repository.BangladeshAreaCatalog
import io.github.towkirahmedrony.salah.core.domain.repository.LocationRepository
import io.github.towkirahmedrony.salah.core.model.LocationInfo
import io.github.towkirahmedrony.salah.core.model.LocationOrigin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.roundToLong
import kotlin.time.Duration.Companion.seconds

/**
 * The map picker's state and actions.
 *
 * **One write path, shared with the rest of the location system.** The confirmed point is written through
 * the same [LocationRepository] as a device fix and an area selection, with
 * [LocationOrigin.MAP_SELECTION] as its provenance, and the same notification reconciliation follows the
 * write. The screen never talks to a map or an address provider directly: it calls this view model, and
 * this view model calls the [ReverseGeocoder] and [LocationProvider] ports.
 *
 * **The selection is the marker, one path only.** The map keeps the marker at its centre, so a drag, a
 * tap and a programmatic camera move all end the same way — the map reports its new centre on *idle*, and
 * that centre becomes the selection. There is no second "selected coordinate" that a gesture could update
 * and the camera could miss.
 *
 * **Address resolution is best-effort and never blocks.** Movements are debounced
 * ([GEOCODE_DEBOUNCE_MILLIS]); an obsolete lookup is cancelled when the point changes; and a result is
 * cached per rounded coordinate so returning to the same place does not re-request it. A failed lookup
 * leaves [MapPickerUiState.address] in a failed state and the confirm button untouched.
 *
 * **What is never logged, and never persisted before confirmation.** Diagnostics record outcome codes and
 * booleans only — never a coordinate. Provisional movements update the in-memory state alone; only
 * [confirm] writes, so a look around the map cannot overwrite the stored location.
 */
class MapPickerViewModel(
    private val locations: LocationRepository,
    private val catalog: BangladeshAreaCatalog,
    private val geocoder: ReverseGeocoder,
    private val locationProvider: LocationProvider,
    private val permissionChecker: LocationPermissionChecker,
    private val coordinator: PrayerNotificationCoordinator,
    private val logger: DiagnosticsLogger,
) : ViewModel() {

    /** The dataset slot, so the UI can tell "still reading" from "read and failed". */
    private data class DatasetSlot(
        val isLoading: Boolean,
        val dataset: BangladeshLocationDataset?,
    )

    /** Everything that changes as the user acts. */
    private data class Transient(
        val selected: GeoPoint? = null,
        val address: MapAddressState = MapAddressState.Idle,
        val isSaving: Boolean = false,
        val isSaved: Boolean = false,
        val cameraRequest: MapCameraRequest? = null,
        val isAcquiring: Boolean = false,
        val message: MapPickerMessage? = null,
    )

    private val slot = MutableStateFlow(DatasetSlot(isLoading = true, dataset = null))
    private val transient = MutableStateFlow(Transient())

    /**
     * Address results for points already looked up, keyed by the coordinate rounded to
     * [CACHE_KEY_DECIMALS] decimals (about 11 m). Bounded, and in memory only: it exists to stop the same
     * coordinate being requested twice in a session, which is the provider's caching requirement, and it
     * deliberately does not persist a record of where the user looked. Failures are cached too, so a
     * point the provider has no address for is not re-requested on every pass over it.
     */
    private val addressCache = LinkedHashMap<String, MapAddressState>()

    /** The in-flight debounced lookup, cancelled when the selection changes. */
    private var geocodeJob: Job? = null

    /** True once the initial selection has been chosen, so it is not reset on every start. */
    private var hasStarted = false

    val uiState: StateFlow<MapPickerUiState> = combine(
        slot,
        locations.location,
        transient,
    ) { datasetSlot, stored, current ->
        buildState(datasetSlot, stored, current)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = MapPickerUiState(),
    )

    /**
     * Reads the dataset and picks the initial selection. Safe to call on every screen start.
     *
     * The initial point is the stored location when there is one — so the picker opens where the user
     * already is, and "modify the selected location" is a small adjustment rather than a hunt across the
     * country — and Bangladesh's centre otherwise.
     */
    fun start() {
        if (slot.value.isLoading && slot.value.dataset == null) {
            viewModelScope.launch { loadDataset() }
        }
        if (hasStarted) return
        hasStarted = true
        viewModelScope.launch {
            val stored = runCatching { locations.location.first() }.getOrNull()
            val point = stored?.let { GeoPoint(it.latitude, it.longitude) } ?: BangladeshMapViewport.CENTER
            val zoom = if (stored != null) BangladeshMapViewport.FOCUS_ZOOM else BangladeshMapViewport.INITIAL_ZOOM
            transient.update { it.copy(cameraRequest = MapCameraRequest(point, zoom)) }
            onSelectionChanged(point)
        }
    }

    private suspend fun loadDataset() {
        val dataset = runCatching { catalog.dataset() }.getOrNull()
        slot.value = DatasetSlot(isLoading = false, dataset = dataset)
        if (dataset == null || !dataset.isUsable) {
            logger.warn(
                SalahDiagnosticEvents.LOCATION_DATASET_UNAVAILABLE,
                mapOf(
                    "read" to (dataset != null),
                    "divisions" to (dataset?.divisions?.size ?: 0),
                    "rings" to (dataset?.boundaryRings?.size ?: 0),
                ),
            )
        }
    }

    /**
     * Records a new selection, from a drag, a tap or a programmatic camera move.
     *
     * The selection is applied immediately — the coordinate readout and the boundary check must not lag
     * the marker — while the address lookup waits for the movement to settle.
     */
    fun onSelectionChanged(point: GeoPoint) {
        if (!point.isFinite) return
        val clamped = BangladeshMapViewport.clampToCamera(point)
        transient.update { it.copy(selected = clamped) }
        scheduleLookup(clamped)
    }

    /** Clears a camera request once the screen has applied it. */
    fun onCameraRequestConsumed() {
        transient.update { it.copy(cameraRequest = null) }
    }

    fun dismissMessage() {
        transient.update { it.copy(message = null) }
    }

    // Address resolution -------------------------------------------------------

    private fun scheduleLookup(point: GeoPoint) {
        geocodeJob?.cancel()
        val key = cacheKey(point)
        addressCache[key]?.let { cached ->
            transient.update { it.copy(address = cached) }
            return
        }
        transient.update { it.copy(address = MapAddressState.Loading) }
        geocodeJob = viewModelScope.launch {
            // Debounced: a drag emits a centre on every idle, and requesting each one would both flood
            // the provider and be discarded a moment later. Waiting for the movement to stop is what the
            // provider's usage policy expects of an app.
            delay(GEOCODE_DEBOUNCE_MILLIS)
            val result = runCatching { geocoder.reverse(point) }.getOrElse { error ->
                if (error is CancellationException) throw error
                ReverseGeocodeResult.Unavailable(REASON_FAILED)
            }
            val state = result.asAddressState()
            addressCache[key] = state
            trimCache()
            transient.update { it.copy(address = state) }
        }
    }

    private fun ReverseGeocodeResult.asAddressState(): MapAddressState = when (this) {
        is ReverseGeocodeResult.Found -> MapAddressState.Resolved(address)
        is ReverseGeocodeResult.NotFound -> MapAddressState.Failed(MapAddressState.NO_ADDRESS)
        is ReverseGeocodeResult.Unavailable -> MapAddressState.Failed(reason)
    }

    private fun trimCache() {
        while (addressCache.size > MAX_CACHE_ENTRIES) {
            val oldest = addressCache.keys.firstOrNull() ?: return
            addressCache.remove(oldest)
        }
    }

    private fun cacheKey(point: GeoPoint): String =
        "${roundTo(point.latitude)},${roundTo(point.longitude)}"

    private fun roundTo(value: Double): Long = (value * CACHE_SCALE).roundToLong()

    // Device location ----------------------------------------------------------

    /**
     * Asks the platform for one position and, when it is usable, moves the camera to it.
     *
     * The permission is requested by the screen before this is called; a call without permission returns
     * [LocationAcquisitionResult.PermissionDenied] rather than throwing. A fix outside Bangladesh does not
     * move the camera: the map is constrained to the country, so moving to an out-of-country point would
     * silently place the marker somewhere the device is not. Instead the fix is reported and the previous
     * selection is kept, exactly like the location screen's refusal.
     */
    fun useCurrentLocation() {
        if (transient.value.isAcquiring) return
        transient.update { it.copy(isAcquiring = true, message = null) }
        viewModelScope.launch {
            try {
                val permission = runCatching { permissionChecker.state() }
                    .getOrDefault(LocationPermissionState.DENIED)
                if (permission == LocationPermissionState.DENIED) {
                    show(MapPickerMessage.PermissionDenied)
                    return@launch
                }
                val result = try {
                    locationProvider.currentLocation(GPS_TIMEOUT)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Exception) {
                    LocationAcquisitionResult.Failed(error.javaClass.simpleName)
                }
                when (result) {
                    is LocationAcquisitionResult.Acquired -> adoptFix(result)
                    LocationAcquisitionResult.TimedOut -> show(MapPickerMessage.TimedOut)
                    LocationAcquisitionResult.PermissionDenied -> show(MapPickerMessage.PermissionDenied)
                    LocationAcquisitionResult.ServicesDisabled -> show(MapPickerMessage.ServicesDisabled)
                    is LocationAcquisitionResult.Failed -> show(MapPickerMessage.ProviderFailed)
                }
            } finally {
                transient.update { it.copy(isAcquiring = false) }
            }
        }
    }

    private fun adoptFix(fix: LocationAcquisitionResult.Acquired) {
        val point = GeoPoint(fix.latitude, fix.longitude)
        val dataset = slot.value.dataset
        val check = dataset?.let { BangladeshLocationPolicy.check(point, it.boundaryRings) }
        if (check is BangladeshCheck.Outside) {
            logger.warn(
                SalahDiagnosticEvents.LOCATION_OUTSIDE_SUPPORTED_COUNTRY,
                mapOf("distanceMetres" to check.distanceMetres.toInt(), "origin" to "map_picker"),
            )
            show(MapPickerMessage.OutsideCountry(check.distanceMetres))
            return
        }
        if (!BangladeshMapViewport.containsCameraPoint(point)) {
            show(MapPickerMessage.OutsideCountry(null))
            return
        }
        transient.update { it.copy(cameraRequest = MapCameraRequest(point, BangladeshMapViewport.FOCUS_ZOOM)) }
    }

    // Confirmation -------------------------------------------------------------

    /**
     * Validates the selected point against the bundled boundary and stores it.
     *
     * Both the strict and the tolerance outcomes are accepted, matching every other write path in Salah:
     * the tolerance exists for a simplifed border line, and refusing it would strand a user standing on
     * the boundary. Only a point beyond the tolerance is refused, and then nothing is written — the
     * selection is left exactly where the user put it so they can move it, rather than being replaced by
     * a nearby "correct" point.
     */
    fun confirm() {
        val point = transient.value.selected ?: return
        val dataset = slot.value.dataset
        if (dataset == null || !dataset.isUsable) {
            show(MapPickerMessage.DatasetUnavailable)
            return
        }
        val check = BangladeshLocationPolicy.check(point, dataset.boundaryRings)
        val accepted = check is BangladeshCheck.Inside || check is BangladeshCheck.WithinBoundaryTolerance
        if (!accepted) {
            val distance = (check as? BangladeshCheck.Outside)?.distanceMetres
            logger.warn(
                SalahDiagnosticEvents.LOCATION_OUTSIDE_SUPPORTED_COUNTRY,
                mapOf("distanceMetres" to distance?.toInt(), "origin" to "map_selection"),
            )
            show(MapPickerMessage.OutsideCountry(distance))
            return
        }

        viewModelScope.launch {
            transient.update { it.copy(isSaving = true) }
            val resolution = AreaResolver.resolve(point, dataset)
            val area = (resolution as? AreaResolution.Suggested)?.suggestion?.reference
            when (resolution) {
                is AreaResolution.Suggested -> logger.info(
                    SalahDiagnosticEvents.LOCATION_AREA_RESOLVED,
                    mapOf(
                        "level" to resolution.suggestion.matchedLevel.name,
                        "distanceMetres" to resolution.suggestion.distanceMetres.toInt(),
                    ),
                )

                is AreaResolution.Unresolved -> logger.warn(
                    SalahDiagnosticEvents.LOCATION_AREA_UNRESOLVED,
                    mapOf("nearestDistanceMetres" to resolution.nearestDistanceMetres?.toInt()),
                )

                AreaResolution.DatasetUnavailable -> Unit
            }

            val address = (transient.value.address as? MapAddressState.Resolved)?.address
            // The stored label may never be blank — [LocationInfo.create] refuses one — so the last
            // fallback is the coordinate itself. That is not a invented place name: it is the fact the
            // user actually chose, it reads the same in every language, and it is what the panel already
            // shows when no address and no administrative area could be found for the point.
            val label = LocationDisplayName.of(
                origin = LocationOrigin.MAP_SELECTION,
                storedLabel = address?.primaryName.orEmpty(),
                area = area,
                locality = null,
            ).ifBlank { LocationFormatting.coordinates(point) }
            val saved = locations.saveMapLocation(
                latitude = point.latitude,
                longitude = point.longitude,
                // The resolved address when there is one, and the administrative name composed from the
                // resolution when there is not — produced by the same display policy every screen reads,
                // so the name written and the name shown cannot differ.
                label = label,
                // `BD` only when the boundary strictly contained the point. A tolerance-accepted point is
                // stored with no country code, because it is not a verified position inside Bangladesh.
                countryCode = if (check is BangladeshCheck.Inside) BangladeshLocationPolicy.COUNTRY_CODE else null,
                area = area,
                datasetVersion = area?.let { dataset.version },
            )
            if (!saved) {
                logger.warn(SalahDiagnosticEvents.LOCATION_SAVE_REJECTED, mapOf("origin" to "map_selection"))
                transient.update { it.copy(isSaving = false) }
                show(MapPickerMessage.Rejected)
                return@launch
            }
            logger.info(
                SalahDiagnosticEvents.LOCATION_SAVED,
                mapOf("origin" to "map_selection", "hasArea" to (area != null)),
            )
            transient.update { it.copy(isSaving = false, isSaved = true, message = MapPickerMessage.Saved) }
            reconcileNotifications()
        }
    }

    private suspend fun reconcileNotifications() {
        runCatching { coordinator.reconcile(ReconcileReason.LOCATION_CHANGED) }
            .onFailure { error ->
                logger.error(
                    SalahDiagnosticEvents.RECONCILE_UNAVAILABLE,
                    error,
                    mapOf("reason" to "location_changed"),
                )
            }
    }

    private fun show(message: MapPickerMessage) {
        transient.update { it.copy(message = message) }
    }

    private fun buildState(
        datasetSlot: DatasetSlot,
        stored: LocationInfo?,
        current: Transient,
    ): MapPickerUiState {
        val dataset = datasetSlot.dataset
        val selected = current.selected
        val point = stored?.let { GeoPoint(it.latitude, it.longitude) } ?: BangladeshMapViewport.CENTER
        val check = if (selected != null && dataset != null) {
            BangladeshLocationPolicy.check(selected, dataset.boundaryRings)
        } else {
            BangladeshCheck.BoundaryUnavailable
        }
        val area = if (selected != null && dataset != null) {
            (AreaResolver.resolve(selected, dataset) as? AreaResolution.Suggested)?.suggestion?.reference
        } else {
            null
        }
        return MapPickerUiState(
            isLoading = datasetSlot.isLoading,
            isDatasetUsable = dataset?.isUsable == true,
            datasetVersion = dataset?.version,
            initialPoint = point,
            initialZoom = if (stored != null) BangladeshMapViewport.FOCUS_ZOOM else BangladeshMapViewport.INITIAL_ZOOM,
            selected = selected,
            boundary = check,
            area = area,
            address = current.address,
            isSaving = current.isSaving,
            isSaved = current.isSaved,
            cameraRequest = current.cameraRequest,
            isAcquiring = current.isAcquiring,
            message = current.message,
        )
    }

    companion object {
        /**
         * How long the map must stop moving before an address is requested.
         *
         * Long enough that a drag or a burst of zooming produces one request rather than dozens, and short
         * enough that a deliberate stop feels immediate. It also keeps the app within the provider's
         * request expectations without a per-device limiter.
         */
        const val GEOCODE_DEBOUNCE_MILLIS: Long = 500

        /** Stops the state flow shortly after the screen goes away. */
        private const val STOP_TIMEOUT_MILLIS = 5_000L

        /** Coordinate decimals used as the cache key: four is about 11 m, finer than a tap can select. */
        private const val CACHE_KEY_DECIMALS = 4
        private const val CACHE_SCALE = 10_000.0

        /** How many address results are retained before the oldest is dropped. */
        private const val MAX_CACHE_ENTRIES = 32

        /** How long a single device fix may take before the button is offered again. */
        val GPS_TIMEOUT = 20.seconds

        /** Reason code for a lookup that failed for a reason the adapter did not classify. */
        private const val REASON_FAILED: String = "failed"
    }
}
