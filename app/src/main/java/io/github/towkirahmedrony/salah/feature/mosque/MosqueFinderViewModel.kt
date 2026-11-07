package io.github.towkirahmedrony.salah.feature.mosque

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.towkirahmedrony.salah.core.domain.location.GeoPoint
import io.github.towkirahmedrony.salah.core.domain.mosque.MosqueDataOrigin
import io.github.towkirahmedrony.salah.core.domain.mosque.MosqueFailure
import io.github.towkirahmedrony.salah.core.domain.mosque.MosqueLoadResult
import io.github.towkirahmedrony.salah.core.domain.mosque.MosqueQuery
import io.github.towkirahmedrony.salah.core.domain.mosque.MosqueRepository
import io.github.towkirahmedrony.salah.core.domain.mosque.MosqueSearchConfig
import io.github.towkirahmedrony.salah.core.domain.qibla.LocationAcquisitionResult
import io.github.towkirahmedrony.salah.core.domain.qibla.LocationPermissionChecker
import io.github.towkirahmedrony.salah.core.domain.qibla.LocationProvider
import io.github.towkirahmedrony.salah.core.domain.repository.LocationRepository
import io.github.towkirahmedrony.salah.core.domain.repository.UserPreferencesRepository
import io.github.towkirahmedrony.salah.data.mosque.MosqueMapStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

/**
 * Drives the mosque finder.
 *
 * ## What the screen does, and therefore what this class does
 *
 * The screen searches once around the app's saved location, then lets the user refresh, switch
 * between list and map, select a mosque, ask for directions, or deliberately replace the search centre
 * with a current device fix. The view model owns each of those transitions; composables only render
 * state and forward gestures.
 *
 * ## The saved location is the default search centre, not a stored write
 *
 * Salah's saved location is the one Home, the Qibla screen, the calendar and every reminder use. A
 * mosque search is a *view* of that location, so it is read but never written. "Use current location"
 * obtains a one-shot fix for this screen only: writing it back would silently change the user's prayer
 * times because they looked at a map, which is not a decision a map tap should make. The saved location
 * remains the app's answer to where the user is, and the screen says which of the two it is searching
 * around.
 *
 * ## Permissions and GPS
 *
 * No location permission is requested on entry. The saved location normally makes the first search
 * possible without one. The platform is asked for a position only when the user taps "Use current
 * location"; the screen requests the runtime permission only if it has not already been granted. The
 * view model handles the outcome of that request through [onPermissionResult], and never keeps a
 * location listener alive: [LocationProvider.currentLocation] is a one-shot API, and the repository's
 * own timeout bounds it. There is no background location request anywhere in this feature.
 *
 * ## Cancellation
 *
 * A new search cancels the previous one; a new location request is ignored while one is already in
 * flight. Both run in `viewModelScope`, so leaving the screen cancels any outstanding work. A
 * cancellation is rethrown rather than turned into a failure state, because there is no screen left to
 * show it to.
 */
class MosqueFinderViewModel(
    private val repository: MosqueRepository,
    private val locations: LocationRepository,
    private val userPreferences: UserPreferencesRepository,
    private val locationProvider: LocationProvider,
    private val permissionChecker: LocationPermissionChecker,
    private val config: MosqueSearchConfig,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        MosqueFinderUiState(
            permissionState = permissionChecker.state(),
            radiusMetres = config.radiusMetres,
            // The saved location read and the first request are both outstanding until `init` settles.
            isLoading = true,
        ),
    )

    /** Everything the screen draws. */
    val uiState: StateFlow<MosqueFinderUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null
    private var locationJob: Job? = null

    init {
        readSavedLocationAndSearch()
    }

    /**
     * The style URL for the current theme, or `null` when this build has no Geoapify key.
     *
     * The key never enters the UI state, and the URL is the only string that carries it — straight to
     * MapLibre, which loads the style the same way a browser would.
     */
    fun mapStyleUrl(darkTheme: Boolean): String? = MosqueMapStyle.styleUrl(config.apiKey, darkTheme)

    /** Switches between the list and the map. The data is the same in both. */
    fun setViewMode(mode: MosqueViewMode) {
        _uiState.update { state -> state.copy(viewMode = mode) }
    }

    /** Opens the detail card for [placeId], or closes it when it is already open. */
    fun selectMosque(placeId: String) {
        _uiState.update { state ->
            state.copy(
                selectedPlaceId = if (state.selectedPlaceId == placeId) null else placeId,
            )
        }
    }

    /** Clears the selected mosque without changing the list. */
    fun clearSelection() {
        _uiState.update { state -> state.copy(selectedPlaceId = null) }
    }

    /** Repeats the search around the current centre. */
    fun refresh() {
        val center = _uiState.value.center
        if (center == null) {
            useCurrentLocation()
            return
        }
        search(center = center, refreshing = _uiState.value.hasResults)
    }

    /**
     * Replaces the search centre with a one-shot device fix.
     *
     * The permission check is repeated here even though the screen also checks it: the screen's check
     * decides whether to show the system dialog, while this one protects the provider call from being
     * made without a grant if the state and the platform ever disagree. A missing grant is reported as
     * [MosqueNotice.PERMISSION_DENIED] rather than throwing.
     */
    fun useCurrentLocation() {
        if (locationJob?.isActive == true) return

        val permission = permissionChecker.state()
        _uiState.update { state -> state.copy(permissionState = permission) }
        if (!permission.isGranted) {
            _uiState.update { state -> state.copy(notice = MosqueNotice.PERMISSION_DENIED) }
            return
        }
        if (!locationProvider.servicesEnabled()) {
            _uiState.update { state -> state.copy(notice = MosqueNotice.LOCATION_SERVICES_DISABLED) }
            return
        }

        locationJob = viewModelScope.launch {
            _uiState.update { state ->
                state.copy(isAcquiringLocation = true, notice = null)
            }

            val result = try {
                locationProvider.currentLocation(LOCATION_TIMEOUT)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                LocationAcquisitionResult.Failed("location provider failed")
            }

            when (result) {
                is LocationAcquisitionResult.Acquired -> {
                    val center = GeoPoint(
                        latitude = result.latitude,
                        longitude = result.longitude,
                    )
                    if (!center.isFinite) {
                        _uiState.update { state ->
                            state.copy(
                                isAcquiringLocation = false,
                                notice = MosqueNotice.LOCATION_UNAVAILABLE,
                            )
                        }
                        return@launch
                    }

                    // A new centre invalidates the old distances, so the old list is cleared rather
                    // than left beside a loading indicator that claims to be refreshing it.
                    _uiState.update { state ->
                        state.copy(
                            center = center,
                            centerLabel = null,
                            centerOrigin = MosqueSearchCenterOrigin.CURRENT_LOCATION,
                            isAcquiringLocation = false,
                            mosques = emptyList(),
                            selectedPlaceId = null,
                            origin = null,
                            cachedAtEpochMillis = null,
                            failure = null,
                            refreshFailure = null,
                            notice = null,
                        )
                    }
                    search(center = center, refreshing = false)
                }

                LocationAcquisitionResult.PermissionDenied -> {
                    _uiState.update { state ->
                        state.copy(
                            isAcquiringLocation = false,
                            permissionState = permissionChecker.state(),
                            notice = MosqueNotice.PERMISSION_DENIED,
                        )
                    }
                }

                LocationAcquisitionResult.ServicesDisabled -> {
                    _uiState.update { state ->
                        state.copy(
                            isAcquiringLocation = false,
                            notice = MosqueNotice.LOCATION_SERVICES_DISABLED,
                        )
                    }
                }

                LocationAcquisitionResult.TimedOut -> {
                    _uiState.update { state ->
                        state.copy(
                            isAcquiringLocation = false,
                            notice = MosqueNotice.LOCATION_TIMED_OUT,
                        )
                    }
                }

                is LocationAcquisitionResult.Failed -> {
                    _uiState.update { state ->
                        state.copy(
                            isAcquiringLocation = false,
                            notice = MosqueNotice.LOCATION_UNAVAILABLE,
                        )
                    }
                }
            }
        }
    }

    /**
     * The outcome of the runtime permission dialog the screen showed.
     *
     * The platform has already recorded the answer by the time this is called, so the state is
     * re-read rather than assumed from [granted]. A grant continues straight into the position
     * request the user originally asked for; a refusal changes nothing else on screen.
     */
    fun onPermissionResult(granted: Boolean) {
        val permission = permissionChecker.state()
        _uiState.update { state -> state.copy(permissionState = permission) }
        if (granted && permission.isGranted) {
            useCurrentLocation()
        } else {
            _uiState.update { state -> state.copy(notice = MosqueNotice.PERMISSION_DENIED) }
        }
    }

    /** The device had no app that could open the directions URI. */
    fun onDirectionsUnavailable() {
        _uiState.update { state -> state.copy(notice = MosqueNotice.DIRECTIONS_UNAVAILABLE) }
    }

    /** Dismisses a transient notice without touching the results. */
    fun dismissNotice() {
        _uiState.update { state -> state.copy(notice = null) }
    }

    private fun readSavedLocationAndSearch() {
        viewModelScope.launch {
            val stored = try {
                locations.location.first()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                null
            }

            val permission = permissionChecker.state()
            if (stored == null) {
                _uiState.update { state ->
                    state.copy(
                        isLoading = false,
                        permissionState = permission,
                    )
                }
                return@launch
            }

            val center = GeoPoint(latitude = stored.latitude, longitude = stored.longitude)
            if (!center.isFinite) {
                _uiState.update { state ->
                    state.copy(isLoading = false, permissionState = permission)
                }
                return@launch
            }

            _uiState.update { state ->
                state.copy(
                    center = center,
                    centerLabel = stored.label,
                    centerOrigin = MosqueSearchCenterOrigin.SAVED_LOCATION,
                    permissionState = permission,
                    radiusMetres = config.radiusMetres,
                )
            }
            search(center = center, refreshing = false)
        }
    }

    /**
     * Runs one search and reduces its result into the screen's state.
     *
     * The language sent to the provider is the app's selected interface language, so mosque names and
     * addresses come back in the script the user is reading. It is read per search rather than held at
     * construction, so changing the language in Settings and returning to this screen refreshes into
     * the new language.
     */
    private fun search(center: GeoPoint, refreshing: Boolean) {
        searchJob?.cancel()
        _uiState.update { state ->
            state.copy(
                isLoading = !refreshing,
                isRefreshing = refreshing,
                failure = null,
                refreshFailure = null,
                notice = null,
            )
        }

        searchJob = viewModelScope.launch {
            val language = try {
                userPreferences.appLanguage.first()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                null
            }
            val languageTag = language.languageTag ?: Locale.getDefault().language
            val query = MosqueQuery(
                center = center,
                radiusMetres = config.radiusMetres,
                pageSize = config.pageSize,
                maxPages = config.maxPages,
                language = languageTag,
            )

            val result = try {
                repository.nearby(query)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                MosqueLoadResult.Failure(MosqueFailure.UNKNOWN)
            }

            applyResult(result)
        }
    }

    private fun applyResult(result: MosqueLoadResult) {
        when (result) {
            is MosqueLoadResult.Success -> {
                _uiState.update { state ->
                    val ids = result.mosques.mapTo(HashSet()) { item -> item.mosque.placeId }
                    val selected = state.selectedPlaceId?.takeIf { it in ids }
                        ?: result.mosques.firstOrNull()?.mosque?.placeId
                    state.copy(
                        isLoading = false,
                        isRefreshing = false,
                        mosques = result.mosques,
                        selectedPlaceId = selected,
                        origin = result.origin,
                        cachedAtEpochMillis = result.cachedAtEpochMillis,
                        failure = null,
                        refreshFailure = result.refreshFailure,
                        notice = null,
                    )
                }
            }

            is MosqueLoadResult.Failure -> {
                _uiState.update { state ->
                    if (state.mosques.isEmpty()) {
                        state.copy(
                            isLoading = false,
                            isRefreshing = false,
                            mosques = emptyList(),
                            selectedPlaceId = null,
                            origin = null,
                            cachedAtEpochMillis = null,
                            failure = result.reason,
                            refreshFailure = null,
                        )
                    } else {
                        // The earlier list is still real data. Replacing it with an error would make
                        // a failed refresh look like a failed search; the notice says which failed.
                        state.copy(
                            isLoading = false,
                            isRefreshing = false,
                            failure = null,
                            refreshFailure = result.reason,
                        )
                    }
                }
            }
        }
    }

    private companion object {
        /**
         * How long a one-shot fix may take. The same order as the Qibla screen's request: long enough
         * for a cold GPS start outdoors, short enough that a user indoors is not left waiting without
         * an answer.
         */
        val LOCATION_TIMEOUT = 15.seconds
    }
}
