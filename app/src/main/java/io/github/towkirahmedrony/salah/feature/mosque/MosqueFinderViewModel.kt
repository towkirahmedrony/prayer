package io.github.towkirahmedrony.salah.feature.mosque

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.towkirahmedrony.salah.core.domain.location.GeoPoint
import io.github.towkirahmedrony.salah.core.domain.mosque.MosqueCacheRead
import io.github.towkirahmedrony.salah.core.domain.mosque.MosqueCacheSnapshot
import io.github.towkirahmedrony.salah.core.domain.mosque.MosqueDataOrigin
import io.github.towkirahmedrony.salah.core.domain.mosque.MosqueFailure
import io.github.towkirahmedrony.salah.core.domain.mosque.MosqueLoadResult
import io.github.towkirahmedrony.salah.core.domain.mosque.MosqueQuery
import io.github.towkirahmedrony.salah.core.domain.mosque.MosqueRepository
import io.github.towkirahmedrony.salah.core.domain.mosque.MosqueSearchConfig
import io.github.towkirahmedrony.salah.core.domain.mosque.OfflineMapArea
import io.github.towkirahmedrony.salah.core.domain.mosque.OfflineMapException
import io.github.towkirahmedrony.salah.core.domain.mosque.OfflineMapFailure
import io.github.towkirahmedrony.salah.core.domain.mosque.OfflineMapProgress
import io.github.towkirahmedrony.salah.core.domain.mosque.OfflineMapRequest
import io.github.towkirahmedrony.salah.core.domain.mosque.OfflineMapStore
import io.github.towkirahmedrony.salah.core.domain.network.AlwaysOnlineNetworkMonitor
import io.github.towkirahmedrony.salah.core.domain.network.NetworkMonitor
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
 * between list and map, select a mosque, ask for directions, deliberately replace the search centre
 * with a current device fix, or save the map area for offline use. The view model owns each of those
 * transitions; composables only render state and forward gestures.
 *
 * ## Offline-first, in two steps
 *
 * A search is not one call. The cache is read first and, when it holds anything, drawn immediately —
 * before any request is made. Only then is the network asked, and its answer replaces the cached list
 * when it arrives. This is what stops a device with a weak connection from showing a spinner for the
 * length of a connect timeout over data it already has. When the platform says there is no network at
 * all, no request is attempted: the cached answer, or an honest offline empty state, is the answer.
 *
 * A refresh never clears the list it is refreshing. The only action that clears results is a new
 * search centre, because the old distances then describe the wrong place.
 *
 * ## Recovery
 *
 * The connectivity flow is collected for as long as the screen exists. When it changes from offline
 * to online and the screen is showing cached or failed data, one refresh is started. It is guarded by
 * the in-flight search job, so a flapping connection cannot produce a burst of duplicate requests.
 * The selected mosque and the map camera are preserved because the centre does not change and the
 * map only re-frames when it sees a new centre.
 *
 * ## Offline map areas
 *
 * The map is rendered by MapLibre from Geoapify tiles. To make it work without a connection, the user
 * explicitly downloads the area around the current centre as a MapLibre offline region: the engine
 * stores the style, fonts, sprites and tiles persistently and serves them later with no network. The
 * plan (area, zooms, estimated size) is shown before the download starts, progress is reported while
 * it runs, a cancel stops it and removes the partial region, and a stored region can be deleted. The
 * app never downloads tiles without that tap.
 *
 * ## The saved location is the default search centre, not a stored write
 *
 * Salah's saved location is the one Home, the Qibla screen, the calendar and every reminder use. A
 * mosque search is a *view* of that location, so it is read but never written. "Use current location"
 * obtains a one-shot fix for this screen only: writing it back would silently change the user's prayer
 * times because they looked at a map, which is not a decision a map tap should make.
 *
 * ## Cancellation
 *
 * A new search cancels the previous one; a new location request is ignored while one is already in
 * flight; leaving the screen cancels an offline map download (whose partial region the store then
 * discards). Cancellation is rethrown rather than turned into a failure state.
 */
class MosqueFinderViewModel(
    private val repository: MosqueRepository,
    private val locations: LocationRepository,
    private val userPreferences: UserPreferencesRepository,
    private val locationProvider: LocationProvider,
    private val permissionChecker: LocationPermissionChecker,
    private val config: MosqueSearchConfig,
    private val networkMonitor: NetworkMonitor = AlwaysOnlineNetworkMonitor,
    private val offlineMapStore: OfflineMapStore? = null,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        MosqueFinderUiState(
            permissionState = permissionChecker.state(),
            radiusMetres = config.radiusMetres,
            // The saved location read and the first cache read are both outstanding until `init`
            // settles; the map is therefore not drawn until a centre or a result exists.
            isLoading = true,
        ),
    )

    /** Everything the screen draws. */
    val uiState: StateFlow<MosqueFinderUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null
    private var locationJob: Job? = null
    private var offlineMapJob: Job? = null

    init {
        observeConnectivity()
        refreshOfflineRegions()
        readSavedLocationAndSearch()
    }

    /**
     * The style URL MapLibre should load.
     *
     * With no connection, a stored region's own style is used: that is the style whose tiles, fonts and
     * sprites were downloaded, and requesting the current theme's style instead would be a request the
     * device cannot make. With a connection the active theme decides, as before.
     *
     * The key never enters the UI state; the URL is the only string that carries it, straight to
     * MapLibre.
     */
    fun mapStyleUrl(darkTheme: Boolean): String? {
        val state = _uiState.value
        if (!state.isOnline) {
            state.offlineMap.coveringRegion?.let { region -> return region.styleUrl }
        }
        return MosqueMapStyle.styleUrl(config.apiKey, darkTheme)
    }

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
     * Downloads the map area around the current centre for offline use.
     *
     * [darkTheme] selects the style to store, matching what the map is drawing now. The download is a
     * deliberate action: nothing here is called automatically, and an area whose tile count exceeds
     * [OfflineMapArea.MAX_TILES] is not offered at all. Starting a second download replaces the first;
     * its partial region is discarded by the store.
     */
    fun downloadOfflineMap(darkTheme: Boolean) {
        val store = offlineMapStore ?: return
        val state = _uiState.value
        if (state.offlineMap.isDownloading) return

        val center = state.center ?: return
        val plan = state.offlineMap.plan ?: OfflineMapArea.plan(center, config.radiusMetres)
        if (plan == null || !plan.isWithinLimit) return

        if (!state.isOnline) {
            _uiState.update { current ->
                current.copy(
                    offlineMap = current.offlineMap.copy(
                        download = OfflineMapDownload.Failed(OfflineMapFailure.NETWORK),
                    ),
                )
            }
            return
        }

        val styleUrl = MosqueMapStyle.styleUrl(config.apiKey, darkTheme) ?: return
        val label = state.centerLabel ?: coordinateLabel(center)

        _uiState.update { current ->
            current.copy(
                notice = null,
                offlineMap = current.offlineMap.copy(
                    download = OfflineMapDownload.InProgress(
                        OfflineMapProgress(
                            completedResources = 0,
                            requiredResources = 0,
                            downloadedBytes = 0,
                            isComplete = false,
                        ),
                    ),
                ),
            )
        }

        offlineMapJob?.cancel()
        offlineMapJob = viewModelScope.launch {
            val request = OfflineMapRequest(
                styleUrl = styleUrl,
                bounds = plan.bounds,
                minZoom = plan.minZoom,
                maxZoom = plan.maxZoom,
            )
            try {
                store.download(request, label) { progress ->
                    _uiState.update { current ->
                        if (current.offlineMap.isDownloading) {
                            current.copy(
                                offlineMap = current.offlineMap.copy(
                                    download = OfflineMapDownload.InProgress(progress),
                                ),
                            )
                        } else {
                            current
                        }
                    }
                }
                refreshOfflineRegionsInternal()
                _uiState.update { current ->
                    current.copy(
                        offlineMap = current.offlineMap.copy(download = OfflineMapDownload.None),
                    )
                }
            } catch (cancellation: CancellationException) {
                _uiState.update { current ->
                    current.copy(
                        offlineMap = current.offlineMap.copy(download = OfflineMapDownload.None),
                    )
                }
                throw cancellation
            } catch (error: OfflineMapException) {
                _uiState.update { current ->
                    current.copy(
                        offlineMap = current.offlineMap.copy(
                            download = OfflineMapDownload.Failed(error.reason),
                        ),
                    )
                }
            } catch (_: Exception) {
                _uiState.update { current ->
                    current.copy(
                        offlineMap = current.offlineMap.copy(
                            download = OfflineMapDownload.Failed(OfflineMapFailure.UNKNOWN),
                        ),
                    )
                }
            }
        }
    }

    /**
     * Stops the running download.
     *
     * The store stops the engine and deletes the partial region, so a cancel never leaves the storage
     * of a map that cannot be used. The notice states what happened rather than leaving the row looking
     * as if nothing occurred.
     */
    fun cancelOfflineMapDownload() {
        val wasDownloading = _uiState.value.offlineMap.isDownloading
        offlineMapJob?.cancel()
        offlineMapJob = null
        _uiState.update { current ->
            current.copy(
                offlineMap = current.offlineMap.copy(download = OfflineMapDownload.None),
                notice = if (wasDownloading) MosqueNotice.OFFLINE_MAP_CANCELLED else current.notice,
            )
        }
    }

    /** Deletes a stored offline area. A region that cannot be deleted stays listed. */
    fun deleteOfflineMap(regionId: Long) {
        val store = offlineMapStore ?: return
        if (_uiState.value.offlineMap.isDownloading) return
        viewModelScope.launch {
            try {
                store.delete(regionId)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // Best effort: re-listing below will still show the region if it survived.
            }
            refreshOfflineRegionsInternal()
        }
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
                            cacheUnavailable = false,
                            notice = null,
                        )
                    }
                    refreshOfflineCoverage()
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

    /**
     * Follows connectivity for the life of the screen.
     *
     * The first emission only records the state. A later offline-to-online transition refreshes only
     * when the screen is showing something other than a live result — cached data, a failure, or no
     * data at all — so a healthy live list is not re-fetched merely because the network reconnected.
     */
    private fun observeConnectivity() {
        viewModelScope.launch {
            networkMonitor.connectivity().collect { online ->
                val wasOnline = _uiState.value.isOnline
                _uiState.update { state -> state.copy(isOnline = online) }
                if (online && !wasOnline) onNetworkRestored()
            }
        }
    }

    private fun onNetworkRestored() {
        val state = _uiState.value
        val center = state.center ?: return
        if (searchJob?.isActive == true || locationJob?.isActive == true) return

        val needsRefresh = state.origin != MosqueDataOrigin.NETWORK ||
            state.refreshFailure != null ||
            state.failure != null ||
            state.mosques.isEmpty()
        if (!needsRefresh) return

        search(center = center, refreshing = state.hasResults)
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
            refreshOfflineCoverage()
            search(center = center, refreshing = false)
        }
    }

    /**
     * Runs one search and reduces its results into the screen's state.
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
            val languageTag = language?.languageTag ?: Locale.getDefault().language
            val query = MosqueQuery(
                center = center,
                radiusMetres = config.radiusMetres,
                pageSize = config.pageSize,
                maxPages = config.maxPages,
                language = languageTag,
            )

            // The cache is consulted first, and its result is drawn before the request is made. A
            // saved list is data the user already waited for once; making them wait again behind a
            // spinner is the defect this ordering removes.
            when (val cached = readCache(query)) {
                is MosqueCacheRead.Available -> {
                    applyCached(cached.snapshot)
                    if (_uiState.value.isOnline) {
                        _uiState.update { state ->
                            state.copy(isLoading = false, isRefreshing = true)
                        }
                    }
                }

                MosqueCacheRead.Empty -> _uiState.update { state ->
                    state.copy(cacheUnavailable = false)
                }

                MosqueCacheRead.Unavailable -> _uiState.update { state ->
                    state.copy(cacheUnavailable = true)
                }
            }

            if (!_uiState.value.isOnline) {
                // No request is made. Whatever the cache produced is the answer, and no refresh
                // failure is claimed beside it: the screen states that this is saved data.
                _uiState.update { state ->
                    state.copy(
                        isLoading = false,
                        isRefreshing = false,
                        failure = null,
                        refreshFailure = if (state.hasResults) {
                            MosqueFailure.NETWORK_UNAVAILABLE
                        } else {
                            null
                        },
                    )
                }
                return@launch
            }

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

    /** A cache read that never throws: a storage problem is a state, not an exception. */
    private suspend fun readCache(query: MosqueQuery): MosqueCacheRead = try {
        repository.cachedNearby(query)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        MosqueCacheRead.Unavailable
    }

    /**
     * Draws a cached snapshot and keeps the screen honest about it.
     *
     * The selection is preserved when the cached list still contains it; otherwise the nearest saved
     * mosque becomes selected, exactly as the first live result does. The camera is not touched: the
     * centre did not change, so the map keeps its position.
     */
    private fun applyCached(snapshot: MosqueCacheSnapshot) {
        _uiState.update { state ->
            val ids = snapshot.mosques.mapTo(HashSet()) { item -> item.mosque.placeId }
            val selected = state.selectedPlaceId?.takeIf { id -> id in ids }
                ?: snapshot.mosques.firstOrNull()?.mosque?.placeId
            state.copy(
                isLoading = false,
                mosques = snapshot.mosques,
                selectedPlaceId = selected,
                origin = MosqueDataOrigin.CACHE,
                cachedAtEpochMillis = snapshot.cachedAtEpochMillis,
                failure = null,
                cacheUnavailable = false,
            )
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

    /** Re-lists stored regions and re-computes whether one covers the centre. */
    private fun refreshOfflineRegions() {
        val store = offlineMapStore ?: return
        viewModelScope.launch { refreshOfflineRegionsInternal() }
    }

    private suspend fun refreshOfflineRegionsInternal() {
        val store = offlineMapStore ?: return
        val regions = try {
            store.regions()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // An unreadable region database is "no regions"; the list and map failure handling still
            // work, and the user can download again.
            emptyList()
        }

        _uiState.update { state ->
            state.copy(
                offlineMap = state.offlineMap.copy(
                    regions = regions,
                    // Only a complete region is usable: a partial one left by an interrupted download
                    // would draw holes in the map while claiming to be available offline.
                    coveringRegion = regions.firstOrNull { region ->
                        region.isComplete && region.covers(state.center)
                    },
                    plan = state.center?.let { center -> OfflineMapArea.plan(center, config.radiusMetres) },
                ),
            )
        }
    }

    /** Re-computes the plan and coverage for a new centre without touching stored regions. */
    private fun refreshOfflineCoverage() {
        _uiState.update { state ->
            state.copy(
                offlineMap = state.offlineMap.copy(
                    coveringRegion = state.offlineMap.regions.firstOrNull { region ->
                        region.isComplete && region.covers(state.center)
                    },
                    plan = state.center?.let { center -> OfflineMapArea.plan(center, config.radiusMetres) },
                ),
            )
        }
    }

    /**
     * A persisted description for a downloaded area, used where a place label does not exist.
     *
     * It is stored with the region, not rendered as interface copy; the screen names the action, not
     * the coordinate.
     */
    private fun coordinateLabel(center: GeoPoint): String =
        String.format(Locale.ROOT, "%.4f, %.4f", center.latitude, center.longitude)

    private companion object {
        /**
         * How long a one-shot fix may take. The same order as the Qibla screen's request: long enough
         * for a cold GPS start outdoors, short enough that a user indoors is not left waiting without
         * an answer.
         */
        val LOCATION_TIMEOUT = 15.seconds
    }
}
