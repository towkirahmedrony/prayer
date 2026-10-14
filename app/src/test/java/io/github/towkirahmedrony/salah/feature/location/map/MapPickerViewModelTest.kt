package io.github.towkirahmedrony.salah.feature.location.map

import io.github.towkirahmedrony.salah.core.domain.location.BangladeshCheck
import io.github.towkirahmedrony.salah.core.domain.location.BangladeshLocationPolicy
import io.github.towkirahmedrony.salah.core.domain.location.GeoPoint
import io.github.towkirahmedrony.salah.core.domain.location.GeocodedAddress
import io.github.towkirahmedrony.salah.core.domain.location.LocationFormatting
import io.github.towkirahmedrony.salah.core.domain.location.ReverseGeocodeResult
import io.github.towkirahmedrony.salah.core.domain.location.ReverseGeocoder
import io.github.towkirahmedrony.salah.core.domain.notification.PrayerNotificationCoordinator
import io.github.towkirahmedrony.salah.core.domain.notification.PrayerNotificationPlanner
import io.github.towkirahmedrony.salah.core.domain.qibla.LocationPermissionState
import io.github.towkirahmedrony.salah.core.domain.qibla.LocationProvider
import io.github.towkirahmedrony.salah.core.domain.repository.BangladeshAreaCatalog
import io.github.towkirahmedrony.salah.core.model.LocationInfo
import io.github.towkirahmedrony.salah.core.model.LocationOrigin
import io.github.towkirahmedrony.salah.testing.FakeBangladeshAreaCatalog
import io.github.towkirahmedrony.salah.testing.FakeLocationPermissionSource
import io.github.towkirahmedrony.salah.testing.FakeLocationProvider
import io.github.towkirahmedrony.salah.testing.FakeLocationRepository
import io.github.towkirahmedrony.salah.testing.FakePermissionStatusProvider
import io.github.towkirahmedrony.salah.testing.FakePrayerNotificationPreferencesRepository
import io.github.towkirahmedrony.salah.testing.FakePrayerNotificationScheduler
import io.github.towkirahmedrony.salah.testing.FakePrayerScheduleRepository
import io.github.towkirahmedrony.salah.testing.FakeUserPreferencesRepository
import io.github.towkirahmedrony.salah.testing.RecordingLogger
import io.github.towkirahmedrony.salah.testing.syntheticDataset
import io.github.towkirahmedrony.salah.testing.testSchedule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
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
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The behaviour the map picker depends on, on virtual time with the real view model, the real country
 * policy, the real arithmetic and the real notification reconciliation.
 *
 * Only the platform and provider edges are fakes — the geocoder, the location provider, the permission
 * checker, the catalog and the repository — so what these cases assert is the app's own logic:
 *
 *  - a point inside the boundary is stored with map provenance, the resolved name and the country it was
 *    verified in, through the same repository as every other selection;
 *  - a point outside the boundary is refused and **nothing** is written;
 *  - address lookups are debounced and a superseded point is not requested;
 *  - leaving without confirming never overwrites the stored location;
 *  - a failed lookup does not stop the coordinates being saved, and the name then falls back to the
 *    administrative area.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MapPickerViewModelTest {

    private val zone: ZoneId = ZoneId.of("Asia/Dhaka")
    private val today: LocalDate = LocalDate.of(2026, 10, 11)
    private val clock: Clock = Clock.fixed(Instant.parse("2026-10-11T09:00:00Z"), zone)

    private val scheduler = TestCoroutineScheduler()
    private val mainDispatcher = StandardTestDispatcher(scheduler)

    private val logger = RecordingLogger()
    private val notificationScheduler = FakePrayerNotificationScheduler()
    private val notificationPermissions = FakePermissionStatusProvider()
    private val notificationSettings = FakePrayerNotificationPreferencesRepository()
    private val userPreferences = FakeUserPreferencesRepository()
    private val schedules = FakePrayerScheduleRepository(mapOf(today to testSchedule(today, zone)))

    private lateinit var locations: FakeLocationRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        locations = FakeLocationRepository()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `a confirmed map point is stored with map provenance and the resolved name`() =
        runTest(mainDispatcher) {
            val geocoder = FakeReverseGeocoder()
            val viewModel = viewModel(geocoder = geocoder)
            val states = observe(viewModel)
            viewModel.start()
            runCurrent()

            viewModel.onSelectionChanged(KUSHTIA_CENTRE)
            advanceTimeBy(MapPickerViewModel.GEOCODE_DEBOUNCE_MILLIS + 1)
            runCurrent()

            val loaded = states.last()
            val address = loaded.address
            assertTrue("the address should have resolved", address is MapAddressState.Resolved)
            assertEquals("Test Bazar", (address as MapAddressState.Resolved).address.placeName)

            viewModel.confirm()
            runCurrent()

            val saved = locations.location.first()
            assertNotNull("the confirmed point must be stored", saved)
            assertEquals(LocationOrigin.MAP_SELECTION, saved!!.origin)
            assertEquals(BangladeshLocationPolicy.COUNTRY_CODE, saved.countryCode)
            assertEquals(BangladeshLocationPolicy.TIME_ZONE_ID, saved.timeZoneId)
            // A point inside the boundary is stored exactly as placed, not snapped to a centre.
            assertEquals(KUSHTIA_CENTRE.latitude, saved.latitude, 1e-9)
            assertEquals(KUSHTIA_CENTRE.longitude, saved.longitude, 1e-9)
            // The displayed name is the address the provider gave, which is more specific than the area.
            assertEquals("Test Bazar", saved.label)
            assertTrue("the screen should be told the location was saved", states.last().isSaved)
        }

    @Test
    fun `a point outside the boundary is refused and nothing is stored`() = runTest(mainDispatcher) {
        val viewModel = viewModel()
        val states = observe(viewModel)
        viewModel.start()
        runCurrent()

        viewModel.onSelectionChanged(OUTSIDE_BANGLADESH)
        advanceTimeBy(MapPickerViewModel.GEOCODE_DEBOUNCE_MILLIS + 1)
        runCurrent()

        assertTrue("the point must be classified as outside", states.last().boundary is BangladeshCheck.Outside)
        assertFalse("an outside point cannot be confirmed", states.last().canSave)

        viewModel.confirm()
        runCurrent()

        assertNull("a refused point must not be written", locations.location.first())
        assertTrue(states.last().message is MapPickerMessage.OutsideCountry)
    }

    @Test
    fun `an address lookup is debounced and a superseded point is not requested`() = runTest(mainDispatcher) {
        val geocoder = FakeReverseGeocoder()
        val viewModel = viewModel(geocoder = geocoder)
        observe(viewModel)
        viewModel.start()
        runCurrent()

        viewModel.onSelectionChanged(FIRST_POINT)
        advanceTimeBy(100)
        // Moving again before the debounce elapses must cancel the first lookup, not queue a second.
        viewModel.onSelectionChanged(SECOND_POINT)
        advanceTimeBy(MapPickerViewModel.GEOCODE_DEBOUNCE_MILLIS + 1)
        runCurrent()

        assertEquals("only the settled point may be requested", listOf(SECOND_POINT), geocoder.requested)
    }

    @Test
    fun `leaving without confirming leaves the stored location untouched`() = runTest(mainDispatcher) {
        val original = LocationInfo.create(
            label = "Previously chosen",
            latitude = 23.90,
            longitude = 89.12,
            timeZoneId = BangladeshLocationPolicy.TIME_ZONE_ID,
            origin = LocationOrigin.MANUAL_ENTRY,
        )
        assertNotNull(original)
        locations.set(original)

        val viewModel = viewModel()
        observe(viewModel)
        viewModel.start()
        runCurrent()

        // Select a different point but never confirm — which is what pressing back does.
        viewModel.onSelectionChanged(SECOND_POINT)
        advanceTimeBy(MapPickerViewModel.GEOCODE_DEBOUNCE_MILLIS + 1)
        runCurrent()

        assertEquals("a provisional selection must not overwrite the stored location", original, locations.location.first())
    }

    @Test
    fun `a declined permission is reported and the map stays usable`() = runTest(mainDispatcher) {
        val viewModel = viewModel(permissionState = LocationPermissionState.DENIED)
        val states = observe(viewModel)
        viewModel.start()
        runCurrent()

        viewModel.useCurrentLocation()
        runCurrent()

        assertEquals(MapPickerMessage.PermissionDenied, states.last().message)
        // The selection made by hand is still confirmable, because nothing about it needs the permission.
        viewModel.onSelectionChanged(KUSHTIA_CENTRE)
        advanceTimeBy(MapPickerViewModel.GEOCODE_DEBOUNCE_MILLIS + 1)
        runCurrent()
        assertTrue(states.last().canSave)
    }

    @Test
    fun `a failed lookup still allows the coordinates to be saved`() = runTest(mainDispatcher) {
        val geocoder = FakeReverseGeocoder(result = ReverseGeocodeResult.Unavailable("network"))
        val viewModel = viewModel(geocoder = geocoder)
        val states = observe(viewModel)
        viewModel.start()
        runCurrent()

        viewModel.onSelectionChanged(KUSHTIA_CENTRE)
        advanceTimeBy(MapPickerViewModel.GEOCODE_DEBOUNCE_MILLIS + 1)
        runCurrent()
        assertTrue("a failed lookup is a reported state", states.last().address is MapAddressState.Failed)

        viewModel.confirm()
        runCurrent()

        val saved = locations.location.first()
        assertNotNull("the coordinates are the fact; the address is not required", saved)
        // With no address, the name falls back to the administrative area the point resolved to.
        assertEquals("Kumarkhali, Kushtia", saved!!.label)
        assertEquals(BangladeshLocationPolicy.COUNTRY_CODE, saved.countryCode)
    }

    @Test
    fun `a point with no address and no area still saves, named by its coordinates`() =
        runTest(mainDispatcher) {
            // Inside the boundary square but further from every district centre than the resolver's own
            // threshold, so neither the provider nor the bundled hierarchy can name it. The coordinates
            // are still the fact the user chose, and a missing name must not become a refused save.
            val geocoder = FakeReverseGeocoder(result = ReverseGeocodeResult.Unavailable("network"))
            val viewModel = viewModel(geocoder = geocoder)
            val states = observe(viewModel)
            viewModel.start()
            runCurrent()

            viewModel.onSelectionChanged(UNNAMED_POINT)
            advanceTimeBy(MapPickerViewModel.GEOCODE_DEBOUNCE_MILLIS + 1)
            runCurrent()
            assertTrue(states.last().isBoundaryVerified)
            assertNull("nothing could resolve this point to an area", states.last().area)

            viewModel.confirm()
            runCurrent()

            val saved = locations.location.first()
            assertNotNull("a valid coordinate must be storable without an address", saved)
            assertEquals(
                LocationFormatting.coordinates(UNNAMED_POINT),
                saved!!.label,
            )
        }

    // --- Harness ---------------------------------------------------------------

    private fun TestScope.viewModel(
        geocoder: ReverseGeocoder = FakeReverseGeocoder(),
        provider: LocationProvider = FakeLocationProvider(),
        permissionState: LocationPermissionState = LocationPermissionState.DENIED,
        catalog: BangladeshAreaCatalog = FakeBangladeshAreaCatalog(syntheticDataset()),
    ): MapPickerViewModel = MapPickerViewModel(
        locations = locations,
        catalog = catalog,
        geocoder = geocoder,
        locationProvider = provider,
        permissionChecker = FakeLocationPermissionSource(permissionState),
        // The real coordinator over fake edges: the reconciliation a save triggers is part of what is
        // being exercised, not something to stub away.
        coordinator = PrayerNotificationCoordinator(
            planner = PrayerNotificationPlanner(),
            notificationSettings = notificationSettings,
            userPreferences = userPreferences,
            locations = locations,
            schedules = schedules,
            scheduler = notificationScheduler,
            permissions = notificationPermissions,
            logger = logger,
            clock = clock,
        ),
        logger = logger,
    )

    /** Collects the screen state for the life of the test; `uiState` is a `StateFlow`. */
    private fun TestScope.observe(viewModel: MapPickerViewModel): List<MapPickerUiState> {
        val states = mutableListOf<MapPickerUiState>()
        backgroundScope.launch { viewModel.uiState.toList(states) }
        return states
    }

    /** A geocoder whose result the test sets, recording every point requested. */
    private class FakeReverseGeocoder(
        var result: ReverseGeocodeResult = ReverseGeocodeResult.Found(
            GeocodedAddress(displayName = "Test Bazar, Kushtia", placeName = "Test Bazar"),
        ),
    ) : ReverseGeocoder {

        val requested = mutableListOf<GeoPoint>()

        override suspend fun reverse(point: GeoPoint): ReverseGeocodeResult {
            requested += point
            return result
        }
    }

    private companion object {
        /** Kushtia district's centre in the synthetic dataset, inside its square boundary. */
        val KUSHTIA_CENTRE: GeoPoint = GeoPoint(latitude = 23.90, longitude = 89.12)

        /** Inside the camera rectangle but outside the synthetic boundary square. */
        val OUTSIDE_BANGLADESH: GeoPoint = GeoPoint(latitude = 26.50, longitude = 92.00)

        /**
         * Inside the boundary square, but further than [AreaResolver]'s district threshold from every
         * published centre, so the offline resolution has nothing to say about it.
         */
        val UNNAMED_POINT: GeoPoint = GeoPoint(latitude = 22.20, longitude = 88.60)

        val FIRST_POINT: GeoPoint = GeoPoint(latitude = 23.70, longitude = 89.00)
        val SECOND_POINT: GeoPoint = GeoPoint(latitude = 23.80, longitude = 89.20)
    }
}
