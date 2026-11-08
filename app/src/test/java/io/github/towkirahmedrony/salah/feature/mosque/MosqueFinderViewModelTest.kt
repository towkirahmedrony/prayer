package io.github.towkirahmedrony.salah.feature.mosque

import io.github.towkirahmedrony.salah.core.domain.location.GeoPoint
import io.github.towkirahmedrony.salah.core.domain.mosque.Mosque
import io.github.towkirahmedrony.salah.core.domain.mosque.MosqueCacheRead
import io.github.towkirahmedrony.salah.core.domain.mosque.MosqueCacheSnapshot
import io.github.towkirahmedrony.salah.core.domain.mosque.MosqueDataOrigin
import io.github.towkirahmedrony.salah.core.domain.mosque.MosqueFailure
import io.github.towkirahmedrony.salah.core.domain.mosque.MosqueLoadResult
import io.github.towkirahmedrony.salah.core.domain.mosque.MosqueQuery
import io.github.towkirahmedrony.salah.core.domain.mosque.MosqueRepository
import io.github.towkirahmedrony.salah.core.domain.mosque.MosqueSearchConfig
import io.github.towkirahmedrony.salah.core.domain.mosque.MosqueWithDistance
import io.github.towkirahmedrony.salah.core.domain.mosque.OfflineMapException
import io.github.towkirahmedrony.salah.core.domain.mosque.OfflineMapFailure
import io.github.towkirahmedrony.salah.core.domain.mosque.OfflineMapProgress
import io.github.towkirahmedrony.salah.core.domain.mosque.OfflineMapRegion
import io.github.towkirahmedrony.salah.core.domain.mosque.OfflineMapRequest
import io.github.towkirahmedrony.salah.core.domain.mosque.OfflineMapStore
import io.github.towkirahmedrony.salah.core.domain.network.NetworkMonitor
import io.github.towkirahmedrony.salah.core.domain.qibla.LocationAcquisitionResult
import io.github.towkirahmedrony.salah.core.domain.qibla.LocationPermissionState
import io.github.towkirahmedrony.salah.core.domain.repository.LocationRepository
import io.github.towkirahmedrony.salah.core.model.AppLanguage
import io.github.towkirahmedrony.salah.core.model.LocationInfo
import io.github.towkirahmedrony.salah.testing.FakeLocationPermissionSource
import io.github.towkirahmedrony.salah.testing.FakeLocationProvider
import io.github.towkirahmedrony.salah.testing.FakeLocationRepository
import io.github.towkirahmedrony.salah.testing.FakeUserPreferencesRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The mosque finder's state transitions, driven through the real view model.
 *
 * This is the wiring layer: the saved location becomes the first search centre without asking for
 * permission, a current-location request is a deliberate action, a failed refresh does not erase a
 * real list, and a one-shot fix is never written back to the app's location. The pure rules (filter,
 * distance, parsing, caching) are tested in their own files; this file is about how they are
 * orchestrated.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MosqueFinderViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val config = MosqueSearchConfig(
        apiKey = "test-key",
        radiusMetres = 5_000,
        pageSize = 50,
        maxPages = 3,
    )

    @Before
    fun installMainDispatcher() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun removeMainDispatcher() {
        Dispatchers.resetMain()
    }

    // --- First search ---------------------------------------------------------

    @Test
    fun `the saved location starts one search and exposes the result`() = runTest(dispatcher) {
        val repository = FakeMosqueRepository(
            next = MosqueLoadResult.Success(
                mosques = listOf(
                    withDistance("near", "Near Mosque", latitude = 23.8123),
                    withDistance("far", "Far Mosque", latitude = 23.8303),
                ),
                origin = MosqueDataOrigin.NETWORK,
            ),
        )

        val viewModel = viewModel(repository = repository)
        val state = viewModel.uiState.value

        assertFalse(state.isLoading)
        assertEquals(MosqueViewMode.LIST, state.viewMode)
        assertEquals(MosqueSearchCenterOrigin.SAVED_LOCATION, state.centerOrigin)
        assertEquals("Dhaka", state.centerLabel)
        assertEquals(listOf("near", "far"), state.mosques.map { it.mosque.placeId })
        assertEquals("near", state.selectedPlaceId)
        assertEquals(MosqueDataOrigin.NETWORK, state.origin)
        assertNull(state.failure)
        assertEquals(1, repository.queries.size)
        assertEquals(DHAKA, repository.queries.single().center)
        assertEquals(5_000, repository.queries.single().radiusMetres)
    }

    @Test
    fun `without a saved location nothing is searched and no permission is requested`() = runTest(dispatcher) {
        val repository = FakeMosqueRepository()
        val provider = FakeLocationProvider()

        val viewModel = viewModel(
            repository = repository,
            locations = FakeLocationRepository(),
            provider = provider,
        )
        val state = viewModel.uiState.value

        assertFalse(state.isLoading)
        assertNull(state.center)
        assertTrue(state.mosques.isEmpty())
        assertTrue(repository.queries.isEmpty())
        assertEquals(0, provider.calls)
    }

    @Test
    fun `the first request is observable as loading`() = runTest(dispatcher) {
        val repository = FakeMosqueRepository(next = success(withDistance("one", "One Mosque")))
        repository.gate = CompletableDeferred()

        val viewModel = viewModel(repository = repository)

        assertTrue(viewModel.uiState.value.isLoading)

        repository.gate!!.complete(Unit)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isLoading)
        assertEquals(listOf("one"), viewModel.uiState.value.mosques.map { it.mosque.placeId })
    }

    @Test
    fun `an empty success is an empty result, not a failure`() = runTest(dispatcher) {
        val viewModel = viewModel(
            repository = FakeMosqueRepository(
                next = MosqueLoadResult.Success(emptyList(), MosqueDataOrigin.NETWORK),
            ),
        )
        val state = viewModel.uiState.value

        assertFalse(state.hasResults)
        assertTrue(state.isEmptyResult)
        assertNull(state.failure)
        assertEquals(MosqueDataOrigin.NETWORK, state.origin)
    }

    @Test
    fun `a failed first load exposes the reason`() = runTest(dispatcher) {
        val viewModel = viewModel(
            repository = FakeMosqueRepository(
                next = MosqueLoadResult.Failure(MosqueFailure.SERVER),
            ),
        )
        val state = viewModel.uiState.value

        assertEquals(MosqueFailure.SERVER, state.failure)
        assertFalse(state.isLoading)
        assertTrue(state.mosques.isEmpty())
        assertNull(state.origin)
    }

    @Test
    fun `a cached success says where it came from and why it was refreshed`() = runTest(dispatcher) {
        val viewModel = viewModel(
            repository = FakeMosqueRepository(
                next = MosqueLoadResult.Success(
                    mosques = listOf(withDistance("cached", "Cached Mosque")),
                    origin = MosqueDataOrigin.CACHE,
                    cachedAtEpochMillis = 1234,
                    refreshFailure = MosqueFailure.TIMEOUT,
                ),
            ),
        )
        val state = viewModel.uiState.value

        assertEquals(MosqueDataOrigin.CACHE, state.origin)
        assertEquals(1234L, state.cachedAtEpochMillis)
        assertEquals(MosqueFailure.TIMEOUT, state.refreshFailure)
        assertNull(state.failure)
    }

    @Test
    fun `a failed refresh keeps the earlier list and reports only the refresh`() = runTest(dispatcher) {
        val repository = FakeMosqueRepository(
            next = success(withDistance("one", "One Mosque")),
        )
        val viewModel = viewModel(repository = repository)
        assertEquals(1, viewModel.uiState.value.mosques.size)

        repository.next = MosqueLoadResult.Failure(MosqueFailure.NETWORK_UNAVAILABLE)
        viewModel.refresh()

        val state = viewModel.uiState.value
        assertEquals(listOf("one"), state.mosques.map { it.mosque.placeId })
        assertNull(state.failure)
        assertEquals(MosqueFailure.NETWORK_UNAVAILABLE, state.refreshFailure)
        assertFalse(state.isRefreshing)
        assertEquals(2, repository.queries.size)
    }

    @Test
    fun `the interface language is read on every search`() = runTest(dispatcher) {
        val preferences = FakeUserPreferencesRepository(initialAppLanguage = AppLanguage.BANGLA)
        val repository = FakeMosqueRepository()

        val viewModel = viewModel(repository = repository, preferences = preferences)
        assertEquals("bn", repository.queries.single().language)

        preferences.setAppLanguage(AppLanguage.ENGLISH)
        viewModel.refresh()

        assertEquals("en", repository.queries.last().language)
    }

    // --- Selection and view mode ---------------------------------------------

    @Test
    fun `the first result is selected and a tap toggles the detail card`() = runTest(dispatcher) {
        val viewModel = viewModel(
            repository = FakeMosqueRepository(
                next = MosqueLoadResult.Success(
                    mosques = listOf(
                        withDistance("one", "One Mosque"),
                        withDistance("two", "Two Mosque"),
                    ),
                    origin = MosqueDataOrigin.NETWORK,
                ),
            ),
        )
        assertEquals("one", viewModel.uiState.value.selectedPlaceId)
        assertEquals("One Mosque", viewModel.uiState.value.selected?.mosque?.name)

        viewModel.selectMosque("two")
        assertEquals("two", viewModel.uiState.value.selectedPlaceId)

        viewModel.selectMosque("two")
        assertNull(viewModel.uiState.value.selectedPlaceId)
    }

    @Test
    fun `switching to the map does not re-query`() = runTest(dispatcher) {
        val repository = FakeMosqueRepository(next = success(withDistance("one", "One Mosque")))
        val viewModel = viewModel(repository = repository)

        viewModel.setViewMode(MosqueViewMode.MAP)

        assertEquals(MosqueViewMode.MAP, viewModel.uiState.value.viewMode)
        assertEquals(1, repository.queries.size)
    }

    // --- Current location, permission and GPS ---------------------------------

    @Test
    fun `a current-location request without permission explains it and does not power up GPS`() = runTest(dispatcher) {
        val provider = FakeLocationProvider()
        val viewModel = viewModel(
            permission = FakeLocationPermissionSource(LocationPermissionState.DENIED),
            provider = provider,
        )

        viewModel.useCurrentLocation()

        assertEquals(MosqueNotice.PERMISSION_DENIED, viewModel.uiState.value.notice)
        assertEquals(0, provider.calls)
        assertFalse(viewModel.uiState.value.isAcquiringLocation)
    }

    @Test
    fun `location switched off is reported without asking the provider`() = runTest(dispatcher) {
        val provider = FakeLocationProvider(servicesEnabled = false)
        val viewModel = viewModel(provider = provider)

        viewModel.useCurrentLocation()

        assertEquals(MosqueNotice.LOCATION_SERVICES_DISABLED, viewModel.uiState.value.notice)
        assertEquals(0, provider.calls)
    }

    @Test
    fun `a timed-out fix is reported and the saved-location list is kept`() = runTest(dispatcher) {
        val provider = FakeLocationProvider(result = LocationAcquisitionResult.TimedOut)
        val viewModel = viewModel(
            repository = FakeMosqueRepository(next = success(withDistance("one", "One Mosque"))),
            provider = provider,
        )

        viewModel.useCurrentLocation()

        val state = viewModel.uiState.value
        assertEquals(MosqueNotice.LOCATION_TIMED_OUT, state.notice)
        assertEquals(DHAKA, state.center)
        assertEquals(listOf("one"), state.mosques.map { it.mosque.placeId })
        assertEquals(1, provider.calls)
    }

    @Test
    fun `an acquired fix becomes the search centre but is not written back to the app's location`() = runTest(dispatcher) {
        val locations = FakeLocationRepository(dhaka())
        val provider = FakeLocationProvider()
        provider.fixAt(GeoPoint(23.75, 90.38))
        val repository = FakeMosqueRepository(next = success(withDistance("new", "New Mosque")))
        val viewModel = viewModel(
            repository = repository,
            locations = locations,
            provider = provider,
        )

        viewModel.useCurrentLocation()

        val state = viewModel.uiState.value
        assertEquals(GeoPoint(23.75, 90.38), state.center)
        assertEquals(MosqueSearchCenterOrigin.CURRENT_LOCATION, state.centerOrigin)
        assertNull(state.centerLabel)
        assertEquals(GeoPoint(23.75, 90.38), repository.queries.last().center)
        assertEquals(listOf("new"), state.mosques.map { it.mosque.placeId })

        // The saved location is the app's answer to where the user is; a mosque search is a view of
        // it, not an update to it.
        val stored = locations.location.first()!!
        assertEquals(DHAKA.latitude, stored.latitude, 0.0)
        assertEquals(DHAKA.longitude, stored.longitude, 0.0)
    }

    @Test
    fun `a granted permission continues into the fix the user asked for`() = runTest(dispatcher) {
        val permission = FakeLocationPermissionSource(LocationPermissionState.DENIED)
        val provider = FakeLocationProvider()
        provider.fixAt(GeoPoint(23.75, 90.38))
        val viewModel = viewModel(
            permission = permission,
            provider = provider,
        )

        permission.state = LocationPermissionState.COARSE
        viewModel.onPermissionResult(granted = true)

        assertEquals(GeoPoint(23.75, 90.38), viewModel.uiState.value.center)
        assertEquals(MosqueSearchCenterOrigin.CURRENT_LOCATION, viewModel.uiState.value.centerOrigin)
        assertEquals(1, provider.calls)
    }

    @Test
    fun `a refused permission reports denied and leaves the centre alone`() = runTest(dispatcher) {
        val viewModel = viewModel()

        viewModel.onPermissionResult(granted = false)

        assertEquals(MosqueNotice.PERMISSION_DENIED, viewModel.uiState.value.notice)
        assertEquals(DHAKA, viewModel.uiState.value.center)
    }

    @Test
    fun `a provider that returns an unusable coordinate is reported as unavailable`() = runTest(dispatcher) {
        val provider = FakeLocationProvider()
        provider.result = LocationAcquisitionResult.Acquired(
            latitude = Double.NaN,
            longitude = 90.38,
            accuracyMetres = 10.0,
            acquiredAtEpochMillis = null,
        )
        val viewModel = viewModel(provider = provider)

        viewModel.useCurrentLocation()

        assertEquals(MosqueNotice.LOCATION_UNAVAILABLE, viewModel.uiState.value.notice)
        assertEquals(DHAKA, viewModel.uiState.value.center)
    }

    @Test
    fun `refresh without a centre asks for a current fix instead of searching nowhere`() = runTest(dispatcher) {
        val provider = FakeLocationProvider()
        provider.fixAt(GeoPoint(23.75, 90.38))
        val repository = FakeMosqueRepository(next = success(withDistance("one", "One Mosque")))
        val viewModel = viewModel(
            repository = repository,
            locations = FakeLocationRepository(),
            provider = provider,
        )

        viewModel.refresh()

        assertEquals(GeoPoint(23.75, 90.38), viewModel.uiState.value.center)
        assertEquals(1, provider.calls)
        assertEquals(GeoPoint(23.75, 90.38), repository.queries.single().center)
    }

    @Test
    fun `a failed fix does not clear an existing result list`() = runTest(dispatcher) {
        val provider = FakeLocationProvider(result = LocationAcquisitionResult.Failed("no fix"))
        val viewModel = viewModel(
            repository = FakeMosqueRepository(next = success(withDistance("one", "One Mosque"))),
            provider = provider,
        )

        viewModel.useCurrentLocation()

        assertEquals(MosqueNotice.LOCATION_UNAVAILABLE, viewModel.uiState.value.notice)
        assertEquals(listOf("one"), viewModel.uiState.value.mosques.map { it.mosque.placeId })
        assertEquals(MosqueSearchCenterOrigin.SAVED_LOCATION, viewModel.uiState.value.centerOrigin)
    }

    // --- Offline cache and recovery ------------------------------------------

    @Test
    fun `a cached list is drawn before the network answers`() = runTest(dispatcher) {
        val repository = FakeMosqueRepository(
            next = success(withDistance("live", "Live Mosque")),
            cached = MosqueCacheRead.Available(
                MosqueCacheSnapshot(
                    mosques = listOf(withDistance("cached", "Cached Mosque")),
                    cachedAtEpochMillis = 99L,
                ),
            ),
        )
        repository.gate = CompletableDeferred()

        val viewModel = viewModel(repository = repository)

        val during = viewModel.uiState.value
        assertEquals(listOf("cached"), during.mosques.map { it.mosque.placeId })
        assertEquals(MosqueDataOrigin.CACHE, during.origin)
        assertEquals(99L, during.cachedAtEpochMillis)
        assertFalse(during.isLoading)
        assertTrue(during.isRefreshing)

        repository.gate!!.complete(Unit)
        advanceUntilIdle()

        val after = viewModel.uiState.value
        assertEquals(listOf("live"), after.mosques.map { it.mosque.placeId })
        assertEquals(MosqueDataOrigin.NETWORK, after.origin)
        assertFalse(after.isRefreshing)
        assertNull(after.failure)
    }

    @Test
    fun `offline uses the cache and never calls the network`() = runTest(dispatcher) {
        val repository = FakeMosqueRepository(
            cached = MosqueCacheRead.Available(
                MosqueCacheSnapshot(
                    mosques = listOf(withDistance("cached", "Cached Mosque")),
                    cachedAtEpochMillis = 42L,
                ),
            ),
        )
        val viewModel = viewModel(
            repository = repository,
            monitor = FakeNetworkMonitor(initialOnline = false),
        )

        val state = viewModel.uiState.value
        assertFalse(state.isOnline)
        assertEquals(listOf("cached"), state.mosques.map { it.mosque.placeId })
        assertEquals(MosqueDataOrigin.CACHE, state.origin)
        assertEquals(MosqueFailure.NETWORK_UNAVAILABLE, state.refreshFailure)
        assertFalse(state.isLoading)
        assertFalse(state.isRefreshing)
        assertTrue(repository.queries.isEmpty())
    }

    @Test
    fun `offline with an empty cache is a proper empty state, not a loader`() = runTest(dispatcher) {
        val viewModel = viewModel(monitor = FakeNetworkMonitor(initialOnline = false))

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertFalse(state.isRefreshing)
        assertTrue(state.isOfflineEmpty)
        assertFalse(state.hasResults)
        assertNull(state.failure)
        assertFalse(state.cacheUnavailable)
    }

    @Test
    fun `an unreadable cache is reported without an endless loader`() = runTest(dispatcher) {
        val viewModel = viewModel(
            repository = FakeMosqueRepository(cached = MosqueCacheRead.Unavailable),
            monitor = FakeNetworkMonitor(initialOnline = false),
        )

        val state = viewModel.uiState.value
        assertTrue(state.cacheUnavailable)
        assertTrue(state.isOfflineEmpty)
        assertFalse(state.isLoading)
    }

    @Test
    fun `coming back online refreshes cached data once and keeps the selection`() = runTest(dispatcher) {
        val repository = FakeMosqueRepository(
            cached = MosqueCacheRead.Available(
                MosqueCacheSnapshot(
                    mosques = listOf(
                        withDistance("one", "One Mosque"),
                        withDistance("two", "Two Mosque"),
                    ),
                    cachedAtEpochMillis = 1L,
                ),
            ),
            next = success(
                withDistance("one", "One Mosque"),
                withDistance("two", "Two Mosque"),
            ),
        )
        val monitor = FakeNetworkMonitor(initialOnline = false)
        val viewModel = viewModel(repository = repository, monitor = monitor)

        viewModel.selectMosque("two")
        assertEquals("two", viewModel.uiState.value.selectedPlaceId)
        assertEquals(0, repository.queries.size)

        monitor.setOnline(true)
        advanceUntilIdle()

        assertEquals(1, repository.queries.size)
        assertEquals("two", viewModel.uiState.value.selectedPlaceId)
        assertEquals(MosqueDataOrigin.NETWORK, viewModel.uiState.value.origin)
        assertFalse(viewModel.uiState.value.isRefreshing)

        // A repeated "online" notification is not a second refresh.
        monitor.setOnline(true)
        advanceUntilIdle()
        assertEquals(1, repository.queries.size)
    }

    // --- Offline map ----------------------------------------------------------

    @Test
    fun `a downloaded area becomes available offline with its stored style`() = runTest(dispatcher) {
        val store = FakeOfflineMapStore()
        store.gate = CompletableDeferred()
        val monitor = FakeNetworkMonitor()
        val viewModel = viewModel(offlineMapStore = store, monitor = monitor)

        assertNotNull(viewModel.uiState.value.offlineMap.plan)

        viewModel.downloadOfflineMap(darkTheme = false)
        val downloading = viewModel.uiState.value.offlineMap.download
        assertTrue(downloading is OfflineMapDownload.InProgress)
        assertEquals(50, (downloading as OfflineMapDownload.InProgress).progress.percent)
        assertEquals(1, store.downloads)

        store.gate!!.complete(Unit)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.offlineMap.isAvailable)
        assertEquals(OfflineMapDownload.None, state.offlineMap.download)
        assertEquals(1, state.offlineMap.regions.size)
        assertEquals(store.regionStyleUrl, state.offlineMap.coveringRegion?.styleUrl)

        // While online, the active theme still chooses the map style.
        assertTrue(viewModel.mapStyleUrl(darkTheme = false)!!.contains("positron"))

        monitor.setOnline(false)
        advanceUntilIdle()

        // Offline, the region's own stored style is drawn — the one whose tiles were downloaded.
        assertFalse(viewModel.uiState.value.isOnline)
        assertEquals(store.regionStyleUrl, viewModel.mapStyleUrl(darkTheme = true))
    }

    @Test
    fun `cancelling an offline map download discards it and says so`() = runTest(dispatcher) {
        val store = FakeOfflineMapStore()
        store.gate = CompletableDeferred()
        val viewModel = viewModel(offlineMapStore = store)

        viewModel.downloadOfflineMap(darkTheme = false)
        assertTrue(viewModel.uiState.value.offlineMap.isDownloading)

        viewModel.cancelOfflineMapDownload()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.offlineMap.isDownloading)
        assertEquals(MosqueNotice.OFFLINE_MAP_CANCELLED, state.notice)
        assertTrue(store.cancelled)
        assertTrue(state.offlineMap.regions.isEmpty())
    }

    @Test
    fun `a storage failure is reported instead of a false success`() = runTest(dispatcher) {
        val store = FakeOfflineMapStore().apply { failWith = OfflineMapFailure.STORAGE }
        val viewModel = viewModel(offlineMapStore = store)

        viewModel.downloadOfflineMap(darkTheme = true)
        advanceUntilIdle()

        val download = viewModel.uiState.value.offlineMap.download
        assertTrue(download is OfflineMapDownload.Failed)
        assertEquals(OfflineMapFailure.STORAGE, (download as OfflineMapDownload.Failed).reason)
        assertFalse(viewModel.uiState.value.offlineMap.isAvailable)
    }

    @Test
    fun `no offline download is attempted while offline`() = runTest(dispatcher) {
        val store = FakeOfflineMapStore()
        val viewModel = viewModel(
            offlineMapStore = store,
            monitor = FakeNetworkMonitor(initialOnline = false),
        )

        viewModel.downloadOfflineMap(darkTheme = false)

        assertEquals(0, store.downloads)
        val download = viewModel.uiState.value.offlineMap.download
        assertTrue(download is OfflineMapDownload.Failed)
        assertEquals(OfflineMapFailure.NETWORK, (download as OfflineMapDownload.Failed).reason)
    }

    @Test
    fun `a saved offline area can be deleted`() = runTest(dispatcher) {
        val store = FakeOfflineMapStore()
        val viewModel = viewModel(offlineMapStore = store)

        viewModel.downloadOfflineMap(darkTheme = false)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.offlineMap.isAvailable)

        viewModel.deleteOfflineMap(1L)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.offlineMap.isAvailable)
        assertTrue(viewModel.uiState.value.offlineMap.regions.isEmpty())
    }

    // --- API and map configuration -------------------------------------------

    @Test
    fun `a missing key yields no map style and a configured one follows the theme`() = runTest(dispatcher) {
        val blank = viewModel(config = MosqueSearchConfig(apiKey = ""))
        assertNull(blank.mapStyleUrl(darkTheme = false))
        assertNull(blank.mapStyleUrl(darkTheme = true))

        val configured = viewModel()
        val light = configured.mapStyleUrl(darkTheme = false)
        val dark = configured.mapStyleUrl(darkTheme = true)
        assertNotNull(light)
        assertNotNull(dark)
        assertTrue(light!!.contains("positron"))
        assertTrue(dark!!.contains("dark-matter"))
    }

    // --- Helpers -------------------------------------------------------------

    private fun viewModel(
        repository: FakeMosqueRepository = FakeMosqueRepository(),
        locations: LocationRepository = FakeLocationRepository(dhaka()),
        preferences: FakeUserPreferencesRepository =
            FakeUserPreferencesRepository(initialAppLanguage = AppLanguage.BANGLA),
        provider: FakeLocationProvider = FakeLocationProvider(),
        permission: FakeLocationPermissionSource =
            FakeLocationPermissionSource(LocationPermissionState.COARSE),
        config: MosqueSearchConfig = this.config,
        monitor: FakeNetworkMonitor = FakeNetworkMonitor(),
        offlineMapStore: OfflineMapStore? = null,
    ) = MosqueFinderViewModel(
        repository = repository,
        locations = locations,
        userPreferences = preferences,
        locationProvider = provider,
        permissionChecker = permission,
        config = config,
        networkMonitor = monitor,
        offlineMapStore = offlineMapStore,
    )

    private class FakeMosqueRepository(
        var next: MosqueLoadResult =
            MosqueLoadResult.Success(emptyList(), MosqueDataOrigin.NETWORK),
        var cached: MosqueCacheRead = MosqueCacheRead.Empty,
    ) : MosqueRepository {

        val queries: MutableList<MosqueQuery> = mutableListOf()
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun nearby(query: MosqueQuery): MosqueLoadResult {
            queries += query
            gate?.await()
            return next
        }

        override suspend fun cachedNearby(query: MosqueQuery): MosqueCacheRead = cached
    }

    /** Connectivity a test drives; the flow replays its current value like the real monitor does. */
    private class FakeNetworkMonitor(initialOnline: Boolean = true) : NetworkMonitor {

        private val online = MutableStateFlow(initialOnline)

        override fun connectivity(): Flow<Boolean> = online

        fun setOnline(value: Boolean) {
            online.value = value
        }
    }

    /**
     * An offline map store with a gate, so a test can observe progress, completion, cancellation and
     * storage failures without an Android map engine.
     */
    private class FakeOfflineMapStore : OfflineMapStore {

        var downloads: Int = 0
            private set
        var cancelled: Boolean = false
            private set
        var failWith: OfflineMapFailure? = null
        var gate: CompletableDeferred<Unit>? = null
        var regionStyleUrl: String = ""
            private set

        private val regions = mutableListOf<OfflineMapRegion>()

        override suspend fun regions(): List<OfflineMapRegion> = regions.toList()

        override suspend fun download(
            request: OfflineMapRequest,
            label: String,
            onProgress: (OfflineMapProgress) -> Unit,
        ): OfflineMapRegion {
            downloads += 1
            regionStyleUrl = request.styleUrl
            onProgress(
                OfflineMapProgress(
                    completedResources = 5,
                    requiredResources = 10,
                    downloadedBytes = 7_000,
                    isComplete = false,
                ),
            )
            try {
                gate?.await()
            } catch (cancellation: CancellationException) {
                cancelled = true
                throw cancellation
            }
            failWith?.let { reason -> throw OfflineMapException(reason) }

            val region = OfflineMapRegion(
                id = 1L,
                label = label,
                styleUrl = request.styleUrl,
                bounds = request.bounds,
                minZoom = request.minZoom,
                maxZoom = request.maxZoom,
                downloadedBytes = 70_000,
                isComplete = true,
                createdAtEpochMillis = 1L,
            )
            regions += region
            return region
        }

        override suspend fun delete(regionId: Long) {
            regions.removeAll { region -> region.id == regionId }
        }
    }

    private fun success(vararg mosques: MosqueWithDistance) =
        MosqueLoadResult.Success(mosques.toList(), MosqueDataOrigin.NETWORK)

    private fun withDistance(
        placeId: String,
        name: String,
        latitude: Double = 23.8103,
        longitude: Double = 90.4125,
    ) = MosqueWithDistance(
        mosque = Mosque(
            placeId = placeId,
            name = name,
            address = null,
            location = GeoPoint(latitude, longitude),
            categories = listOf(Mosque.CATEGORY_MOSQUE_SPECIFIC),
        ),
        distanceMetres = 12.0,
    )

    private fun dhaka(): LocationInfo = LocationInfo.create(
        label = "Dhaka",
        latitude = DHAKA.latitude,
        longitude = DHAKA.longitude,
        timeZoneId = "Asia/Dhaka",
    )!!

    private companion object {
        val DHAKA = GeoPoint(23.8103, 90.4125)
    }
}
