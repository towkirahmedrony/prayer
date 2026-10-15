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
import io.github.towkirahmedrony.salah.core.domain.location.LocationDisplayName
import io.github.towkirahmedrony.salah.core.domain.location.LocationFormatting
import io.github.towkirahmedrony.salah.core.domain.location.LocationFreshness
import io.github.towkirahmedrony.salah.core.domain.location.LocationNameLanguage
import io.github.towkirahmedrony.salah.core.domain.location.LocationSearch
import io.github.towkirahmedrony.salah.core.domain.location.LocationSearchResult
import io.github.towkirahmedrony.salah.core.domain.location.LocalityResolution
import io.github.towkirahmedrony.salah.core.domain.location.LocalityResolver
import io.github.towkirahmedrony.salah.core.domain.location.hierarchyOf
import io.github.towkirahmedrony.salah.core.domain.location.locationNameOf
import io.github.towkirahmedrony.salah.core.domain.notification.PrayerNotificationCoordinator
import io.github.towkirahmedrony.salah.core.domain.notification.ReconcileReason
import io.github.towkirahmedrony.salah.core.domain.qibla.LocationAcquisitionResult
import io.github.towkirahmedrony.salah.core.domain.qibla.LocationPermissionChecker
import io.github.towkirahmedrony.salah.core.domain.qibla.LocationPermissionState
import io.github.towkirahmedrony.salah.core.domain.qibla.LocationProvider
import io.github.towkirahmedrony.salah.core.domain.repository.BangladeshAreaCatalog
import io.github.towkirahmedrony.salah.core.domain.repository.LocationRepository
import io.github.towkirahmedrony.salah.core.model.BangladeshLocalityRef
import io.github.towkirahmedrony.salah.core.model.LocationInfo
import io.github.towkirahmedrony.salah.core.model.LocationOrigin
import io.github.towkirahmedrony.salah.feature.common.LocationFormState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
        val isManualFormOpen: Boolean = false,
        val manualForm: LocationFormState = LocationFormState(),
        val nameLanguage: LocationNameLanguage = LocationNameLanguage.ENGLISH,
        val searchQuery: String = "",
        val searchResults: List<LocationSearchResult> = emptyList(),
        val isSearching: Boolean = false,
        val searchFailed: Boolean = false,
    )

    private val datasetSlot = MutableStateFlow(DatasetSlot(isLoading = true, dataset = null))
    private val transient = MutableStateFlow(Transient())

    /** The in-flight search, held so a newer keystroke can cancel the obsolete one. */
    private var searchJob: Job? = null

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
        // A search typed before the read finished is still waiting on it: it was held in the
        // `isSearching` state rather than failed, and this is what completes it — or reports the
        // failure now that "unfinished" and "unreadable" are finally distinguishable.
        runSearch()
    }

    // Device location --------------------------------------------------------

    /**
     * Asks the platform for one position, and stores it if it passes the Bangladesh-only policy.
     *
     * The screen requests the permission before calling this; a call without permission returns
     * [LocationAcquisitionResult.PermissionDenied] rather than throwing, so the same path reports it.
     *
     * @param saveOnArrival when true, a fix is stored as soon as it arrives instead of being held for
     *   confirmation. The first-launch screen passes true: on a fresh install there is no existing
     *   location to compare a position against, and a confirmation step there stands between the user
     *   and the app for a decision they can reverse in Settings like any other location change. The
     *   location screen leaves it false, where the confirmation is the point — the user is reviewing a
     *   location they already have, and the area name is a nearest-match estimate worth a look before
     *   it replaces something they chose.
     *
     * The policy check itself is the same either way, and a position outside Bangladesh is refused
     * either way: saving without asking is not saving without checking.
     */
    fun useCurrentLocation(saveOnArrival: Boolean = false) {
        // One acquisition at a time. A second tap while a fix is already in flight is ignored rather
        // than starting a second request: the two would race to set `pendingFix`, and the loser could
        // replace the fix the user is looking at.
        if (transient.value.isAcquiring) return
        transient.update { it.copy(isAcquiring = true, message = null) }
        viewModelScope.launch {
            try {
                refreshPermissionState()
                logger.info(
                    SalahDiagnosticEvents.LOCATION_SETUP_EVALUATED,
                    mapOf("action" to "device_location_requested"),
                )
                val result = try {
                    locationProvider.currentLocation(GPS_TIMEOUT)
                } catch (cancellation: CancellationException) {
                    // Cancellation is not a provider failure: rethrow so the coroutine is genuinely
                    // cancelled instead of being reported as a failed fix.
                    throw cancellation
                } catch (error: Exception) {
                    LocationAcquisitionResult.Failed(error.javaClass.simpleName)
                }
                when (result) {
                    is LocationAcquisitionResult.Acquired -> onFixAcquired(result, saveOnArrival)
                    LocationAcquisitionResult.TimedOut -> show(LocationMessage.TimedOut)
                    LocationAcquisitionResult.PermissionDenied -> show(LocationMessage.PermissionDenied)
                    LocationAcquisitionResult.ServicesDisabled -> show(LocationMessage.ServicesDisabled)
                    is LocationAcquisitionResult.Failed -> show(LocationMessage.ProviderFailed)
                }
            } finally {
                // Cleared on every outcome — success, failure, timeout and cancellation — so the
                // acquiring state can never get stuck and block the next tap.
                transient.update { it.copy(isAcquiring = false) }
            }
        }
    }

    /** Called when the platform's permission dialog was declined. Manual selection stays available. */
    fun onLocationPermissionDenied() {
        refreshPermissionState()
        show(LocationMessage.PermissionDenied)
    }

    /**
     * Applies the Bangladesh-only policy to a fix and, if it passes, either holds it for confirmation
     * or stores it outright ([saveOnArrival]).
     *
     * The policy runs here rather than in the screen so there is exactly one place a position can
     * become a location, and so the "outside Bangladesh" decision is testable without a device.
     */
    private suspend fun onFixAcquired(
        fix: LocationAcquisitionResult.Acquired,
        saveOnArrival: Boolean,
    ) {
        val point = GeoPoint(latitude = fix.latitude, longitude = fix.longitude)
        val dataset = datasetSlot.value.dataset
        val check = dataset?.let { BangladeshLocationPolicy.check(point, it.boundaryRings) }
            ?: BangladeshCheck.BoundaryUnavailable

        // Coarse bounds are consulted when the boundary file is missing: they can reject with
        // certainty, and rejecting a position 3 000 km away needs no boundary. They never establish
        // that a position is inside Bangladesh, so such a fix is stored without a country code.
        val verified = check is BangladeshCheck.Inside
        val withinTolerance = check is BangladeshCheck.WithinBoundaryTolerance
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

        // The locality is resolved against the district the administrative pass attributed this position
        // to, so a name can only be attached to a position for which the same dataset also established
        // the district it belongs to. It is best-effort by design: failing to match one is not a
        // rejection, it is a coarser (and still correct) answer.
        val locality = resolveLocality(
            point = point,
            accuracyMetres = fix.accuracyMetres,
            resolution = resolution,
            dataset = dataset,
        )

        val accepted = PendingDeviceFix(
            point = point,
            accuracyMetres = fix.accuracyMetres,
            acquiredAtEpochMillis = fix.acquiredAtEpochMillis,
            resolution = resolution,
            locality = locality,
            countryCode = if (verified) BangladeshLocationPolicy.COUNTRY_CODE else null,
            isCountryVerified = verified,
            isWithinBoundaryTolerance = withinTolerance,
        )

        if (saveOnArrival) {
            // Stored inside this coroutine rather than launched alongside it, so `isAcquiring` stays
            // true until the location exists: the screen cannot offer a second request while the
            // first one is still being written, and the gate opens the moment the write lands.
            saveDeviceFix(accepted)
            return
        }

        transient.update { it.copy(pendingFix = accepted, message = null) }
    }

    /**
     * The named locality this position is in, or `null` when none could be established.
     *
     * Never throws and never blocks: it is a lookup in the already-parsed dataset. The three outcomes are
     * logged distinctly because they are not equivalent — an approximate fix and a district with no
     * locality data are both "no name", but only one of them is about the user's location at all.
     */
    private fun resolveLocality(
        point: GeoPoint,
        accuracyMetres: Double?,
        resolution: AreaResolution,
        dataset: BangladeshLocationDataset?,
    ): BangladeshLocalityRef? {
        val districtId = (resolution as? AreaResolution.Suggested)?.suggestion?.reference?.districtId
        return when (val outcome = LocalityResolver.resolve(point, accuracyMetres, districtId, dataset)) {
            is LocalityResolution.Matched -> {
                logger.info(
                    SalahDiagnosticEvents.LOCATION_LOCALITY_RESOLVED,
                    mapOf(
                        "kind" to outcome.suggestion.reference.kind.name,
                        "distanceMetres" to outcome.suggestion.distanceMetres.toInt(),
                    ),
                )
                outcome.suggestion.reference
            }

            LocalityResolution.NotPreciseEnough -> {
                logger.info(
                    SalahDiagnosticEvents.LOCATION_LOCALITY_UNRESOLVED,
                    mapOf("reason" to "not_precise_enough", "accuracyMetres" to accuracyMetres?.toInt()),
                )
                null
            }

            is LocalityResolution.Unmatched -> {
                logger.info(
                    SalahDiagnosticEvents.LOCATION_LOCALITY_UNRESOLVED,
                    mapOf(
                        "reason" to if (districtId == null) "no_district" else "too_far",
                        "nearestDistanceMetres" to outcome.nearestDistanceMetres?.toInt(),
                    ),
                )
                null
            }
        }
    }

    /** Saves the fix the user confirmed, then brings the notification schedule up to date. */
    fun confirmPendingFix() {
        val fix = transient.value.pendingFix ?: return
        viewModelScope.launch { saveDeviceFix(fix) }
    }

    /**
     * Writes an accepted fix to the store and reconciles what depends on it.
     *
     * The last step before a position becomes the app's location, for both entry points: the first
     * launch arrives here as soon as a fix passes the policy, the location screen only after the user
     * has confirmed one.
     */
    private suspend fun saveDeviceFix(fix: PendingDeviceFix) {
        val saved = locations.saveDeviceLocation(
            // Composed by the shared policy rather than concatenated here, so the name written into the
            // record is the same one every screen will show, and a matched locality is what makes the
            // name specific: "Mirpur, Dhaka" instead of the city corporation the administrative layer
            // could name on its own.
            label = fix.displayName,
            latitude = fix.point.latitude,
            longitude = fix.point.longitude,
            // The country is fixed and has one zone, so a device fix is stored in Bangladesh's zone
            // rather than whichever zone the phone happens to be set to.
            timeZoneId = BangladeshLocationPolicy.TIME_ZONE_ID,
            accuracyMetres = fix.accuracyMetres,
            acquiredAtEpochMillis = fix.acquiredAtEpochMillis,
            countryCode = fix.countryCode,
            area = fix.suggestedArea,
            locality = fix.locality,
            datasetVersion = fix.suggestedArea?.let { datasetSlot.value.dataset?.version },
        )
        if (!saved) {
            logger.warn(SalahDiagnosticEvents.LOCATION_SAVE_REJECTED, mapOf("origin" to "device_gps"))
            show(LocationMessage.SaveRejected)
            return
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

    /** Discards the fix without saving it, leaving the stored location untouched. */
    fun discardPendingFix() {
        transient.update { it.copy(pendingFix = null) }
    }

    // Manual area selection --------------------------------------------------

    fun openAreaPicker() {
        transient.update { it.copy(isPickerOpen = true, message = null) }
    }

    /**
     * Closes the picker and forgets the partial selection, so it never half-applies.
     *
     * The search field is cleared with it: a query belongs to one opening of the picker, and carrying
     * one over would reopen the screen on results the user has already acted on. The stored location
     * is untouched — cancelling a selection is not choosing a new one.
     */
    fun closeAreaPicker() {
        searchJob?.cancel()
        searchJob = null
        transient.update {
            it.copy(
                isPickerOpen = false,
                selection = AreaSelection.EMPTY,
                searchQuery = "",
                searchResults = emptyList(),
                isSearching = false,
                searchFailed = false,
            )
        }
    }

    fun selectDivision(areaId: String) = updateSelection { selection, dataset ->
        selection.selectDivision(areaId, dataset)
    }

    fun selectDistrict(areaId: String) = updateSelection { selection, dataset ->
        selection.selectDistrict(areaId, dataset)
    }

    fun selectUpazila(areaId: String) = updateSelection { selection, dataset ->
        selection.selectUpazila(areaId, dataset)
    }

    /**
     * Applies a search result to the hierarchy the dropdowns show.
     *
     * The ids go through the same [AreaSelection] guards as a dropdown tap: each level is selected
     * parent-first, and a level the result does not carry leaves the selection at that point — a
     * district result keeps the division and clears any upazila chosen under the previous district,
     * because [AreaSelection.selectDistrict] does that itself. Nothing is written to the repository
     * here; confirming remains an explicit save.
     */
    fun selectSearchResult(result: LocationSearchResult) = updateSelection { selection, dataset ->
        var updated = selection
        result.divisionId?.let { id -> updated = updated.selectDivision(id, dataset) }
        result.districtId?.let { id -> updated = updated.selectDistrict(id, dataset) }
        result.upazilaId?.let { id -> updated = updated.selectUpazila(id, dataset) }
        updated
    }

    /**
     * The language the picker's location names are displayed and searched in.
     *
     * Supplied by the screens from the resources they render with — the one place that knows whether
     * `AppLanguage` resolved to Bangla or English, including "follow the device". Changing it re-runs
     * an in-flight query in the new language rather than leaving results from the old one.
     */
    fun setLanguage(language: LocationNameLanguage) {
        val changed = transient.value.nameLanguage != language
        transient.update { it.copy(nameLanguage = language) }
        if (changed) runSearch()
    }

    /**
     * Keeps the search field's text and schedules a search for it.
     *
     * Typing does not search synchronously: a fast typist would run a search per keystroke, and each
     * one would be obsolete before it finished. [runSearch] debounces, cancels the previous attempt and
     * only ever publishes results for the query that is current when it completes.
     */
    fun onSearchQueryChanged(query: String) {
        transient.update { it.copy(searchQuery = query) }
        runSearch()
    }

    /**
     * Runs the current query after [SEARCH_DEBOUNCE], replacing whatever the previous keystroke left.
     *
     * Cancelling the previous job is what makes obsolete work stop rather than land out of order: a
     * slow search for "ku" must not overwrite the results for "kushtia" that were requested after it.
     * The search itself is an in-memory scan of the bundled dataset — no network, no index to build.
     */
    private fun runSearch() {
        searchJob?.cancel()
        searchJob = null

        val query = transient.value.searchQuery
        if (query.isBlank()) {
            transient.update {
                it.copy(searchResults = emptyList(), isSearching = false, searchFailed = false)
            }
            return
        }

        val slot = datasetSlot.value
        if (slot.dataset == null && !slot.isLoading) {
            // Loaded and unusable: there is nothing to search, and waiting would never resolve.
            transient.update {
                it.copy(searchResults = emptyList(), isSearching = false, searchFailed = true)
            }
            return
        }
        // Either already searched below or — while the dataset read is still running — held in the
        // searching state until `loadDataset` calls back.
        val language = transient.value.nameLanguage
        transient.update { it.copy(isSearching = true, searchFailed = false) }
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MILLIS)
            val current = datasetSlot.value
            val dataset = current.dataset
            if (dataset == null && current.isLoading) {
                // Still reading: leave the searching state up and let `loadDataset`'s own `runSearch`
                // finish this query once the dataset exists, rather than failing a search that was only
                // ever waiting for a local file.
                return@launch
            }
            if (dataset == null || !dataset.isUsable) {
                transient.update {
                    it.copy(searchResults = emptyList(), isSearching = false, searchFailed = true)
                }
                return@launch
            }
            val results = try {
                LocationSearch.search(dataset, query, language)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                logger.error(
                    SalahDiagnosticEvents.LOCATION_SEARCH_FAILED,
                    error,
                    mapOf("reason" to "search_threw"),
                )
                transient.update {
                    it.copy(searchResults = emptyList(), isSearching = false, searchFailed = true)
                }
                return@launch
            }
            transient.update {
                it.copy(searchResults = results, isSearching = false, searchFailed = false)
            }
        }
    }

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
                // The same policy the device path uses, so a selected city corporation is not written as
                // "Gazipur City Corporation, Gazipur" — a fact stated twice — while a selected upazila is
                // still qualified by its district.
                label = LocationDisplayName.of(
                    origin = LocationOrigin.AREA_SELECTION,
                    storedLabel = reference.label,
                    area = reference,
                    locality = null,
                ),
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
            // The picker closes with a clean search field, exactly as closing it by hand would: the
            // query belonged to the selection that was just confirmed.
            searchJob?.cancel()
            searchJob = null
            transient.update {
                it.copy(
                    isPickerOpen = false,
                    selection = AreaSelection.EMPTY,
                    searchQuery = "",
                    searchResults = emptyList(),
                    isSearching = false,
                    searchFailed = false,
                    message = LocationMessage.Saved,
                )
            }
            reconcileNotifications()
        }
    }

    // Manual coordinates ------------------------------------------------------

    /**
     * Opens or closes the manual-coordinate form.
     *
     * A disclosure rather than a screen of its own: it is the third way to give Salah a location, and
     * the least used, so it stays out of the way of the two most people are here for — while remaining
     * on the screen that owns the stored location, so the result of saving appears directly above it.
     */
    fun toggleManualCoordinates() {
        transient.update { it.copy(isManualFormOpen = !it.isManualFormOpen, message = null) }
    }

    /** Applies an edit to the manual form, marking it dirty so a stored value cannot overwrite it. */
    fun onManualFormChange(transform: (LocationFormState) -> LocationFormState) {
        transient.update { current ->
            current.copy(manualForm = transform(current.manualForm).copy(isDirty = true), message = null)
        }
    }

    /**
     * Validates and stores the coordinates as entered.
     *
     * No country check runs on this path, and the screen says so: this form records a position the user
     * *asserts*, which is exactly what someone outside the bundled area dataset needs, and applying the
     * Bangladesh-only policy here would make it useless to them. Everything else is the same as the
     * other two ways in — one repository, one validation, and a reconciliation afterwards.
     *
     * The reconciliation is why this action lives on this screen rather than in a settings list of its
     * own: a location written without one would leave the alarms announcing the times of the place the
     * user has just stopped being at. It is the one behaviour this screen adds to what the old settings
     * form did, and it brings the manual path in line with a device fix and an area selection, which
     * have always reconciled.
     */
    fun saveManualLocation() {
        val form = transient.value.manualForm
        val latitude = form.latitude.trim().toDoubleOrNull()
        val longitude = form.longitude.trim().toDoubleOrNull()

        viewModelScope.launch {
            val saved = latitude != null && longitude != null && locations.saveManualLocation(
                label = form.label.trim(),
                latitude = latitude,
                longitude = longitude,
                timeZoneId = form.timeZoneId.trim(),
            )
            if (!saved) {
                logger.warn(SalahDiagnosticEvents.LOCATION_SAVE_REJECTED, mapOf("origin" to "manual_entry"))
                transient.update {
                    it.copy(
                        manualForm = it.manualForm.copy(hasValidationError = true),
                        message = LocationMessage.SaveRejected,
                    )
                }
                return@launch
            }
            logger.info(SalahDiagnosticEvents.LOCATION_SAVED, mapOf("origin" to "manual_entry"))
            transient.update {
                it.copy(
                    manualForm = it.manualForm.copy(isDirty = false, hasValidationError = false),
                    isManualFormOpen = false,
                    message = LocationMessage.Saved,
                )
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

    /**
     * Applies a selection change against the loaded dataset.
     *
     * The transform receives the *current* selection, so the rules that make a hierarchy valid stay
     * enforced by [AreaSelection] itself instead of being re-derived here.
     */
    private fun updateSelection(
        transform: (AreaSelection, BangladeshLocationDataset) -> AreaSelection,
    ) {
        val dataset = datasetSlot.value.dataset ?: return
        transient.update { current -> current.copy(selection = transform(current.selection, dataset)) }
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
        val language = current.nameLanguage
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
            stored = stored?.let { describe(it, dataset, language) },
            selection = selection,
            divisionOptions = dataset?.divisions.orEmpty(),
            districtOptions = dataset?.let { selection.districts(it) }.orEmpty(),
            upazilaOptions = dataset?.let { selection.upazilas(it) }.orEmpty(),
            // Localized rather than the reference's stored English names: the hierarchy is shown in the
            // language the picker is rendering in, falling back to the stored names for ids this
            // dataset no longer contains.
            selectionSummary = reference?.let { hierarchyOf(it, dataset, language) },
            selectionCoordinateText = resolvedArea?.let {
                LocationFormatting.coordinates(GeoPoint(it.latitude, it.longitude))
            },
            canSaveSelection = reference != null && resolvedArea != null,
            isPickerOpen = current.isPickerOpen,
            pendingFix = current.pendingFix,
            isAcquiring = current.isAcquiring,
            permissionState = current.permissionState,
            isManualFormOpen = current.isManualFormOpen,
            // Pre-filled only from a location the user typed in, and only until they touch the form: a
            // device fix seeded here would blur a declared position with an observed one.
            manualForm = current.manualForm.withStoredLocation(stored),
            message = current.message,
            nameLanguage = language,
            searchQuery = current.searchQuery,
            searchResults = current.searchResults,
            isSearching = current.isSearching,
            searchFailed = current.searchFailed,
        )
    }

    private fun describe(
        location: LocationInfo,
        dataset: BangladeshLocationDataset?,
        language: LocationNameLanguage,
    ): StoredLocationUi {
        val point = GeoPoint(latitude = location.latitude, longitude = location.longitude)
        val check = dataset?.let { BangladeshLocationPolicy.check(point, it.boundaryRings) }
        val outsideCoarseBounds = !BangladeshLocationPolicy.isWithinCoarseBounds(point)
        val area = location.area
        return StoredLocationUi(
            location = location,
            origin = location.origin,
            countryCode = location.countryCode,
            area = area,
            divisionDisplayName = area?.let {
                locationNameOf(it.divisionId, it.divisionName, dataset, language)
            },
            districtDisplayName = area?.let {
                locationNameOf(it.districtId, it.districtName, dataset, language)
            },
            // A fix resolved only as far as a district has no upazila at all, and that must stay
            // "unknown" rather than acquiring the district's name.
            upazilaDisplayName = area?.let {
                locationNameOf(it.upazilaId, it.upazilaName, dataset, language)
            },
            locality = location.locality,
            timeZoneId = location.timeZoneId,
            point = point,
            accuracyText = LocationFormatting.accuracy(location.accuracyMetres),
            coordinateText = LocationFormatting.coordinates(point),
            freshness = LocationFreshness.of(location, clock.instant()),
            isVerifiedInSupportedCountry = check is BangladeshCheck.Inside,
            isWithinBoundaryTolerance = check is BangladeshCheck.WithinBoundaryTolerance,
            isKnownOutsideSupportedCountry = outsideCoarseBounds || check is BangladeshCheck.Outside,
        )
    }

    companion object {
        /** How long a single fix may take before the screen offers manual selection instead. */
        val GPS_TIMEOUT = 20.seconds

        /**
         * How long typing pauses before a search runs, in milliseconds.
         *
         * Long enough that a word typed at a normal speed is searched once, short enough that the
         * results feel attached to the field. The dataset is in memory, so the search itself is
         * milliseconds; this exists to cancel rather than to schedule.
         */
        const val SEARCH_DEBOUNCE_MILLIS: Long = 200L

        /** Stops the state flow shortly after the screen goes away. */
        private const val STOP_TIMEOUT_MILLIS = 5_000L

        /** Stored label used when a fix could not be attributed to an area. */
        const val DEVICE_LABEL: String = "GPS location"
    }
}
