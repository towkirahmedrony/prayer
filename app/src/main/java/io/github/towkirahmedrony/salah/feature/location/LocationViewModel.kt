package io.github.towkirahmedrony.salah.feature.location

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.towkirahmedrony.salah.core.domain.DiagnosticsLogger
import io.github.towkirahmedrony.salah.core.domain.SalahDiagnosticEvents
import io.github.towkirahmedrony.salah.core.domain.location.AreaResolution
import io.github.towkirahmedrony.salah.core.domain.location.AreaResolver
import io.github.towkirahmedrony.salah.core.domain.location.AreaSelection
import io.github.towkirahmedrony.salah.core.domain.location.BangladeshCheck
import io.github.towkirahmedrony.salah.core.domain.location.BangladeshLocationDataset
import io.github.towkirahmedrony.salah.core.domain.location.BangladeshLocationPolicy
import io.github.towkirahmedrony.salah.core.domain.location.GeoPoint
import io.github.towkirahmedrony.salah.core.domain.location.LocationFormatting
import io.github.towkirahmedrony.salah.core.domain.location.LocationFreshness
import io.github.towkirahmedrony.salah.core.domain.notification.PrayerNotificationCoordinator
import io.github.towkirahmedrony.salah.core.domain.notification.ReconcileReason
import io.github.towkirahmedrony.salah.core.domain.qibla.LocationAcquisitionResult
import io.github.towkirahmedrony.salah.core.domain.qibla.LocationPermissionChecker
import io.github.towkirahmedrony.salah.core.domain.qibla.LocationPermissionState
import io.github.towkirahmedrony.salah.core.domain.qibla.LocationProvider
import io.github.towkirahmedrony.salah.core.domain.repository.BangladeshAreaCatalog
import io.github.towkirahmedrony.salah.core.domain.repository.LocationRepository
import io.github.towkirahmedrony.salah.core.model.LocationInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import kotlin.time.Duration.Companion.seconds

/**
 * The location feature's state and actions, shared by the first-launch setup screen and the location
 * management screen.
 *
 * **One view model, two frames.** The two screens differ in what they say, not in what they do: both
 * offer a device fix or a manual area, both validate the same way, and both write through the same
 * repository. A second view model would be a second copy of the policy, the resolution rules and the
 * notification reconciliation — the three things that must not diverge between the first launch and
 * every later change.
 *
 * **Ordering guarantees.** A device fix is never saved on sight: it is checked against the
 * Bangladesh-only policy, resolved to an area where possible, and held as a [PendingDeviceFix] until
 * the user confirms it. The stored location is only replaced by an explicit action, and every write is
 * followed by a notification reconciliation, so a changed location cannot leave alarms pointing at the
 * previous one.
 *
 * **What is never logged.** The coordinates. Diagnostics record the origin, the accuracy, the distance
 * to a boundary or an area centre and the outcome code — never a latitude or a longitude.
 */
class LocationViewModel(
    private val locations: LocationRepository,
    private val catalog: BangladeshAreaCatalog,
    private val locationProvider: LocationProvider,
    private val permissionChecker: LocationPermissionChecker,
    private val coordinator: PrayerNotificationCoordinator,
    private val logger: DiagnosticsLogger,
    private val clock: Clock,
) : ViewModel() {

    /** The dataset slot, so the UI can tell "still reading" from "read and failed". */
    private data class DatasetSlot(
        val isLoading: Boolean,
        val dataset: BangladeshLocationDataset?,
    )

    /** Everything that changes as the user acts, kept in one flow so `combine` stays readable. */
    private data class Transient(
        val selection: AreaSelection = AreaSelection.EMPTY,
        val isPickerOpen: Boolean = false,
        val pendingFix: PendingDeviceFix? = null,
        val isAcquiring: Boolean = false,
        val message: LocationMessage? = null,
        val permissionState: LocationPermissionState = LocationPermissionState.DENIED,
    )

    private val datasetSlot = MutableStateFlow(DatasetSlot(isLoading = true, dataset = null))
    private val transient = MutableStateFlow(Transient())

    val uiState: StateFlow<LocationUiState> = combine(
        datasetSlot,
        locations.location,
        transient,
    ) { slot, stored, current ->
        buildState(slot, stored, current)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = LocationUiState(),
    )

    /** Reads the dataset and the permission state. Safe to call on every screen start. */
    fun start() {
        if (datasetSlot.value.isLoading && datasetSlot.value.dataset == null) {
            viewModelScope.launch { loadDataset() }
        }
        refreshPermissionState()
    }

    /** Re-reads the platform permission state, e.g. after the user returns from system settings. */
    fun refreshPermissionState() {
        val state = runCatching { permissionChecker.state() }.getOrDefault(LocationPermissionState.DENIED)
        transient.update { it.copy(permissionState = state) }
    }

    private suspend fun loadDataset() {
        val dataset = runCatching { catalog.dataset() }.getOrNull()
        datasetSlot.value = DatasetSlot(isLoading = false, dataset = dataset)
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

    // Device location --------------------------------------------------------

    /**
     * Asks the platform for one position.
     *
     * The screen requests the permission before calling this; a call without permission returns
     * [LocationAcquisitionResult.PermissionDenied] rather than throwing, so the same path reports it.
     */
    fun useCurrentLocation() {
        transient.update { it.copy(isAcquiring = true, message = null) }
        viewModelScope.launch {
            refreshPermissionState()
            logger.info(
                SalahDiagnosticEvents.LOCATION_SETUP_EVALUATED,
                mapOf("action" to "device_location_requested"),
            )
            val result = runCatching { locationProvider.currentLocation(GPS_TIMEOUT) }
                .getOrElse { error -> LocationAcquisitionResult.Failed(error.javaClass.simpleName) }
            when (result) {
                is LocationAcquisitionResult.Acquired -> onFixAcquired(result)
                LocationAcquisitionResult.TimedOut -> show(LocationMessage.TimedOut)
                LocationAcquisitionResult.PermissionDenied -> show(LocationMessage.PermissionDenied)
                LocationAcquisitionResult.ServicesDisabled -> show(LocationMessage.ServicesDisabled)
                is LocationAcquisitionResult.Failed -> show(LocationMessage.ProviderFailed)
            }
            transient.update { it.copy(isAcquiring = false) }
        }
    }

    /** Called when the platform's permission dialog was declined. Manual selection stays available. */
    fun onLocationPermissionDenied() {
        refreshPermissionState()
        show(LocationMessage.PermissionDenied)
    }

    /**
     * Applies the Bangladesh-only policy to a fix and, if it passes, holds it for confirmation.
     *
     * The policy runs here rather than in the screen so there is exactly one place a position can
     * become a location, and so the "outside Bangladesh" decision is testable without a device.
     */
    private fun onFixAcquired(fix: LocationAcquisitionResult.Acquired) {
        val point = GeoPoint(latitude = fix.latitude, longitude = fix.longitude)
        val dataset = datasetSlot.value.dataset
        val check = dataset?.let { BangladeshLocationPolicy.check(point, it.boundaryRings) }
            ?: BangladeshCheck.BoundaryUnavailable

        // Coarse bounds are consulted when the boundary file is missing: they can reject with
        // certainty, and rejecting a position 3 000 km away needs no boundary. They never establish
        // that a position is inside Bangladesh, so such a fix is stored without a country code.
        val verified = check is BangladeshCheck.Supported
        val insideCoarseBounds = BangladeshLocationPolicy.isWithinCoarseBounds(point)
        val rejectionDistance = (check as? BangladeshCheck.Outside)?.distanceMetres

        if (!verified && (!insideCoarseBounds || rejectionDistance != null)) {
            logger.warn(
                SalahDiagnosticEvents.LOCATION_OUTSIDE_SUPPORTED_COUNTRY,
                mapOf("distanceMetres" to rejectionDistance?.toInt(), "verified" to false),
            )
            show(LocationMessage.OutsideSupportedCountry(distanceMetres = rejectionDistance))
            transient.update { it.copy(pendingFix = null) }
            return
        }

        val resolution = dataset?.let { AreaResolver.resolve(point, it) }
            ?: AreaResolution.DatasetUnavailable
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

            AreaResolution.DatasetUnavailable -> logger.warn(
                SalahDiagnosticEvents.LOCATION_AREA_UNRESOLVED,
                mapOf("reason" to "dataset_unavailable"),
            )
        }

        transient.update {
            it.copy(
                pendingFix = PendingDeviceFix(
                    point = point,
                    accuracyMetres = fix.accuracyMetres,
                    acquiredAtEpochMillis = fix.acquiredAtEpochMillis,
                    resolution = resolution,
                    countryCode = if (verified) BangladeshLocationPolicy.COUNTRY_CODE else null,
                    isCountryVerified = verified,
                ),
                message = null,
            )
        }
    }

    /** Saves the fix the user confirmed, then brings the notification schedule up to date. */
    fun confirmPendingFix() {
        val fix = transient.value.pendingFix ?: return
        viewModelScope.launch {
            val saved = locations.saveDeviceLocation(
                label = fix.suggestedArea?.label ?: DEVICE_LABEL,
                latitude = fix.point.latitude,
                longitude = fix.point.longitude,
                // The country is fixed and has one zone, so a device fix is stored in Bangladesh's zone
                // rather than whichever zone the phone happens to be set to.
                timeZoneId = BangladeshLocationPolicy.TIME_ZONE_ID,
                accuracyMetres = fix.accuracyMetres,
                acquiredAtEpochMillis = fix.acquiredAtEpochMillis,
                countryCode = fix.countryCode,
                area = fix.suggestedArea,
                datasetVersion = fix.suggestedArea?.let { datasetSlot.value.dataset?.version },
            )
            if (!saved) {
                logger.warn(SalahDiagnosticEvents.LOCATION_SAVE_REJECTED, mapOf("origin" to "device_gps"))
                show(LocationMessage.SaveRejected)
                return@launch
            }
            logger.info(
                SalahDiagnosticEvents.LOCATION_SAVED,
                mapOf(
                    "origin" to "device_gps",
                    "hasArea" to (fix.suggestedArea != null),
                    "verified" to fix.isCountryVerified,
                ),
            )
            transient.update { it.copy(pendingFix = null, message = LocationMessage.Saved) }
            reconcileNotifications()
        }
    }

    /** Discards the fix without saving it, leaving the stored location untouched. */
    fun discardPendingFix() {
        transient.update { it.copy(pendingFix = null) }
    }

    // Manual area selection --------------------------------------------------

    fun openAreaPicker() {
        transient.update { it.copy(isPickerOpen = true, message = null) }
    }

    /** Closes the picker and forgets the partial selection, so it never half-applies. */
    fun closeAreaPicker() {
        transient.update {
            it.copy(isPickerOpen = false, selection = AreaSelection.EMPTY)
        }
    }

    fun selectDivision(areaId: String) = updateSelection { dataset -> selectDivision(areaId, dataset) }

    fun selectDistrict(areaId: String) = updateSelection { dataset -> selectDistrict(areaId, dataset) }

    fun selectUpazila(areaId: String) = updateSelection { dataset -> selectUpazila(areaId, dataset) }

    /** Saves the chosen hierarchy, using the selected area's published coordinate. */
    fun saveSelectedArea() {
        val dataset = datasetSlot.value.dataset
        if (dataset == null || !dataset.isUsable) {
            show(LocationMessage.DatasetUnavailable)
            return
        }
        val selection = transient.value.selection
        val reference = selection.reference(dataset)
        val area = selection.resolvedArea(dataset)
        if (reference == null || area == null) {
            show(LocationMessage.SaveRejected)
            return
        }

        viewModelScope.launch {
            val saved = locations.saveAreaLocation(
                latitude = area.latitude,
                longitude = area.longitude,
                label = reference.label,
                reference = reference,
                datasetVersion = dataset.version,
            )
            if (!saved) {
                logger.warn(SalahDiagnosticEvents.LOCATION_SAVE_REJECTED, mapOf("origin" to "area_selection"))
                show(LocationMessage.SaveRejected)
                return@launch
            }
            logger.info(
                SalahDiagnosticEvents.LOCATION_SAVED,
                mapOf("origin" to "area_selection", "level" to area.level.name),
            )
            transient.update {
                it.copy(isPickerOpen = false, selection = AreaSelection.EMPTY, message = LocationMessage.Saved)
            }
            reconcileNotifications()
        }
    }

    /** Removes the stored location, returning the app to its unconfigured state. */
    fun clearLocation() {
        viewModelScope.launch {
            locations.clearLocation()
            transient.update {
                it.copy(pendingFix = null, message = LocationMessage.Cleared, selection = AreaSelection.EMPTY)
            }
            reconcileNotifications()
        }
    }

    fun dismissMessage() {
        transient.update { it.copy(message = null) }
    }

    private fun updateSelection(
        transform: (BangladeshLocationDataset) -> AreaSelection,
    ) {
        val dataset = datasetSlot.value.dataset ?: return
        transient.update { current -> current.copy(selection = transform(dataset)) }
    }

    private fun show(message: LocationMessage) {
        transient.update { it.copy(message = message) }
    }

    /**
     * Rebuilds the alarm set for the new location.
     *
     * Called after every successful write and after a clear, because the schedule, the countdown and
     * the reminders are all derived from the coordinates: leaving the previous alarms in place would
     * have Salah announce the times of a place the user has just stopped being at.
     */
    private suspend fun reconcileNotifications() {
        runCatching { coordinator.reconcile(ReconcileReason.LOCATION_CHANGED) }
            .onFailure { error ->
                logger.error(SalahDiagnosticEvents.RECONCILE_UNAVAILABLE, error, mapOf("reason" to "location_changed"))
            }
    }

    private fun buildState(
        slot: DatasetSlot,
        stored: LocationInfo?,
        current: Transient,
    ): LocationUiState {
        val dataset = slot.dataset
        val selection = current.selection
        val reference = dataset?.let { selection.reference(it) }
        val resolvedArea = dataset?.let { selection.resolvedArea(it) }

        return LocationUiState(
            isLoading = slot.isLoading,
            datasetVersion = dataset?.version,
            isDatasetUsable = dataset?.isUsable == true,
            datasetDetail = dataset?.let { readout ->
                if (readout.isUsable) {
                    null
                } else {
                    "${readout.divisions.size} divisions, ${readout.districts.size} districts, " +
                        "${readout.upazilas.size} upazilas, ${readout.boundaryRings.size} boundary rings"
                }
            },
            stored = stored?.let { describe(it, dataset) },
            selection = selection,
            divisionOptions = dataset?.divisions.orEmpty(),
            districtOptions = selection.districts(dataset),
            upazilaOptions = selection.upazilas(dataset),
            selectionSummary = reference?.hierarchyLabel,
            selectionCoordinateText = resolvedArea?.let {
                LocationFormatting.coordinates(GeoPoint(it.latitude, it.longitude))
            },
            canSaveSelection = reference != null && resolvedArea != null,
            isPickerOpen = current.isPickerOpen,
            pendingFix = current.pendingFix,
            isAcquiring = current.isAcquiring,
            permissionState = current.permissionState,
            message = current.message,
        )
    }

    private fun describe(
        location: LocationInfo,
        dataset: BangladeshLocationDataset?,
    ): StoredLocationUi {
        val point = GeoPoint(latitude = location.latitude, longitude = location.longitude)
        val check = dataset?.let { BangladeshLocationPolicy.check(point, it.boundaryRings) }
        val outsideCoarseBounds = !BangladeshLocationPolicy.isWithinCoarseBounds(point)
        return StoredLocationUi(
            location = location,
            origin = location.origin,
            countryCode = location.countryCode,
            area = location.area,
            timeZoneId = location.timeZoneId,
            point = point,
            accuracyText = LocationFormatting.accuracy(location.accuracyMetres),
            coordinateText = LocationFormatting.coordinates(point),
            freshness = LocationFreshness.of(location, clock.instant()),
            isVerifiedInSupportedCountry = check is BangladeshCheck.Supported,
            isKnownOutsideSupportedCountry = outsideCoarseBounds || check is BangladeshCheck.Outside,
        )
    }

    companion object {
        /** How long a single fix may take before the screen offers manual selection instead. */
        val GPS_TIMEOUT = 20.seconds

        /** Stops the state flow shortly after the screen goes away. */
        private const val STOP_TIMEOUT_MILLIS = 5_000L

        /** Stored label used when a fix could not be attributed to an area. */
        const val DEVICE_LABEL: String = "GPS location"
    }
}
