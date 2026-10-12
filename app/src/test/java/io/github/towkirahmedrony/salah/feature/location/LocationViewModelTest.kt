package io.github.towkirahmedrony.salah.feature.location

import io.github.towkirahmedrony.salah.core.domain.location.BangladeshLocationPolicy
import io.github.towkirahmedrony.salah.core.domain.location.GeoPoint
import io.github.towkirahmedrony.salah.core.domain.notification.PrayerNotificationCoordinator
import io.github.towkirahmedrony.salah.core.domain.notification.PrayerNotificationPlanner
import io.github.towkirahmedrony.salah.core.domain.qibla.LocationAcquisitionResult
import io.github.towkirahmedrony.salah.core.domain.qibla.LocationPermissionState
import io.github.towkirahmedrony.salah.core.domain.qibla.LocationProvider
import io.github.towkirahmedrony.salah.core.domain.repository.BangladeshAreaCatalog
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
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
import kotlin.time.Duration

/**
 * The behaviour both location screens depend on, asserted once.
 *
 * [LocationSetupScreen] and [LocationScreen] are two framings of this one view model, so the rules
 * that decide what a tap does are tested here rather than through either Compose tree:
 *
 *  - a device fix is checked and *held*, never saved on sight, and confirming it is what stores it;
 *  - a declined permission, a timeout and a position outside Bangladesh each produce their own
 *    message and leave the manual option usable — none of them stores anything;
 *  - a second tap while a fix is in flight starts one request, not two;
 *  - the division → district → upazila cascade offers only the chosen parent's children and drops a
 *    child that the new parent cannot keep;
 *  - confirming a complete selection stores *that area's published centre*, with the area recorded
 *    as a selection rather than as the user's own position, and an incomplete one is refused.
 *
 * Everything runs on virtual time with the real view model, the real resolvers and the shipped
 * policy; only the platform edges — the provider, the permission checker, the catalog and the
 * repositories — are fakes, and the dataset is the real bundled file's own miniature.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocationViewModelTest {

    private val zone: ZoneId = ZoneId.of("Asia/Dhaka")

    /** The instant the schedules below are built for, so reconciliation is not working blind. */
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

    // --- A device fix ----------------------------------------------------------

    @Test
    fun `a fix waits for confirmation and is not stored on sight`() = runTest(mainDispatcher) {
        val provider = FakeLocationProvider().apply { fixAt(KUSHTIA_CENTRE) }
        val viewModel = viewModel(provider = provider)
        val states = observe(viewModel)
        viewModel.start()
        runCurrent()

        viewModel.useCurrentLocation()
        runCurrent()

        val acquired = states.settled()
        assertNotNull("the fix must be held for the user to confirm", acquired.pendingFix)
        assertFalse(acquired.isAcquiring)
        assertNull("nothing may be stored before the user confirms", locations.location.first())

        viewModel.confirmPendingFix()
        runCurrent()

        val confirmed = states.settled()
        assertNull("confirming consumes the fix", confirmed.pendingFix)
        assertEquals(LocationMessage.Saved, confirmed.message)

        val saved = locations.location.first()
        assertNotNull(saved)
        assertEquals(LocationOrigin.DEVICE_GPS, saved!!.origin)
        // A fix is stored in Bangladesh's zone, not whichever zone the phone is in.
        assertEquals(BangladeshLocationPolicy.TIME_ZONE_ID, saved.timeZoneId)
    }

    @Test
    fun `a discarded fix leaves the stored location untouched`() = runTest(mainDispatcher) {
        val provider = FakeLocationProvider().apply { fixAt(KUSHTIA_CENTRE) }
        val viewModel = viewModel(provider = provider)
        val states = observe(viewModel)
        viewModel.start()
        runCurrent()

        viewModel.useCurrentLocation()
        runCurrent()
        viewModel.discardPendingFix()
        runCurrent()

        assertNull(states.settled().pendingFix)
        assertNull("discarding must not write anything", locations.location.first())
    }

    @Test
    fun `declining permission is reported and manual selection stays available`() =
        runTest(mainDispatcher) {
            val provider = FakeLocationProvider().apply {
                result = LocationAcquisitionResult.PermissionDenied
            }
            val viewModel = viewModel(provider = provider)
            val states = observe(viewModel)
            viewModel.start()
            runCurrent()

            viewModel.useCurrentLocation()
            runCurrent()

            val state = states.settled()
            assertEquals(LocationMessage.PermissionDenied, state.message)
            assertTrue("manual selection needs no permission", state.canChooseArea)
            assertNull(state.pendingFix)
            assertFalse(state.isAcquiring)
        }

    @Test
    fun `a fix that times out leaves a retry and keeps the manual option`() = runTest(mainDispatcher) {
        val provider = FakeLocationProvider().apply { result = LocationAcquisitionResult.TimedOut }
        val viewModel = viewModel(provider = provider)
        val states = observe(viewModel)
        viewModel.start()
        runCurrent()

        viewModel.useCurrentLocation()
        runCurrent()

        val state = states.settled()
        assertEquals(LocationMessage.TimedOut, state.message)
        assertFalse("a timeout must not leave a spinner behind", state.isAcquiring)
        assertTrue(state.canChooseArea)
    }

    @Test
    fun `a position outside Bangladesh is refused rather than stored`() = runTest(mainDispatcher) {
        // London: outside the country's bounding rectangle, so it is rejected without the boundary
        // file being consulted at all.
        val provider = FakeLocationProvider().apply { fixAt(GeoPoint(51.5074, -0.1278)) }
        val viewModel = viewModel(provider = provider)
        val states = observe(viewModel)
        viewModel.start()
        runCurrent()

        viewModel.useCurrentLocation()
        runCurrent()

        val state = states.settled()
        assertTrue(
            "expected an outside-the-country message, was ${state.message}",
            state.message is LocationMessage.OutsideSupportedCountry,
        )
        assertNull("an unsupported position must not become a pending location", state.pendingFix)
        assertNull(locations.location.first())
    }

    @Test
    fun `a second tap while a fix is in flight starts only one request`() = runTest(mainDispatcher) {
        val provider = HeldLocationProvider()
        val viewModel = viewModel(provider = provider)
        val states = observe(viewModel)
        viewModel.start()
        runCurrent()

        viewModel.useCurrentLocation()
        runCurrent()
        assertTrue("the screen is waiting for a fix", states.last().isAcquiring)

        viewModel.useCurrentLocation()
        runCurrent()
        assertEquals("a second tap must not start a second request", 1, provider.calls)

        provider.gate.complete(Unit)
        runCurrent()
        assertFalse("acquiring must not get stuck after the fix lands", states.last().isAcquiring)
    }

    // --- Manual selection ------------------------------------------------------

    @Test
    fun `the cascade offers a division's own children and drops an incompatible one`() =
        runTest(mainDispatcher) {
            val viewModel = viewModel()
            val states = observe(viewModel)
            viewModel.start()
            runCurrent()

            val initial = states.settled()
            assertEquals(2, initial.divisionOptions.size)
            assertTrue("no district before a division", initial.districtOptions.isEmpty())
            assertFalse(initial.canSaveSelection)

            viewModel.selectDivision(KHULNA)
            runCurrent()
            val division = states.settled()
            assertEquals(
                setOf("Kushtia", "Jashore"),
                division.districtOptions.map { it.name }.toSet(),
            )
            assertTrue("no upazila before a district", division.upazilaOptions.isEmpty())

            viewModel.selectDistrict(KUSHTIA)
            runCurrent()
            viewModel.selectUpazila(KUMARKHALI)
            runCurrent()
            assertTrue(states.settled().canSaveSelection)

            // Another division cannot keep the previous district or upazila.
            viewModel.selectDivision(RAJSHAHI)
            runCurrent()
            val changed = states.settled()
            assertNull(changed.selection.districtId)
            assertNull(changed.selection.upazilaId)
            assertFalse("the selection is incomplete again", changed.canSaveSelection)
            assertEquals(
                setOf("Bogura"),
                changed.districtOptions.map { it.name }.toSet(),
            )
        }

    @Test
    fun `a confirmed manual selection stores the chosen area's own centre`() = runTest(mainDispatcher) {
        val viewModel = viewModel()
        val states = observe(viewModel)
        viewModel.start()
        runCurrent()

        viewModel.selectDivision(KHULNA)
        runCurrent()
        viewModel.selectDistrict(KUSHTIA)
        runCurrent()
        viewModel.selectUpazila(KUMARKHALI)
        runCurrent()

        val ready = states.settled()
        assertEquals("Kumarkhali, Kushtia, Khulna", ready.selectionSummary)
        assertNotNull(ready.selectionCoordinateText)

        viewModel.saveSelectedArea()
        runCurrent()
        assertEquals(LocationMessage.Saved, states.settled().message)

        val saved = locations.location.first()
        assertNotNull(saved)
        // The upazila's published centre from the dataset, not a position of the user's.
        assertEquals(23.86, saved!!.latitude, 0.000001)
        assertEquals(89.24, saved.longitude, 0.000001)
        assertEquals(BangladeshLocationPolicy.TIME_ZONE_ID, saved.timeZoneId)
        assertEquals(LocationOrigin.AREA_SELECTION, saved.origin)
        assertEquals("BD1001001", saved.area?.upazilaId)
        assertEquals("Kumarkhali", saved.area?.upazilaName)
    }

    @Test
    fun `confirming an incomplete selection is refused`() = runTest(mainDispatcher) {
        val viewModel = viewModel()
        val states = observe(viewModel)
        viewModel.start()
        runCurrent()

        viewModel.selectDivision(KHULNA)
        runCurrent()
        viewModel.saveSelectedArea()
        runCurrent()

        assertEquals(LocationMessage.SaveRejected, states.settled().message)
        assertNull("nothing may be stored from a partial selection", locations.location.first())
    }

    // --- Harness ---------------------------------------------------------------

    private fun TestScope.viewModel(
        provider: LocationProvider = FakeLocationProvider(),
        catalog: BangladeshAreaCatalog = FakeBangladeshAreaCatalog(syntheticDataset()),
        permissionState: LocationPermissionState = LocationPermissionState.DENIED,
    ): LocationViewModel = LocationViewModel(
        locations = locations,
        catalog = catalog,
        locationProvider = provider,
        permissionChecker = FakeLocationPermissionSource(permissionState),
        // The real coordinator over fake edges: the reconciliations a save triggers are part of what
        // is being exercised, not something to stub away.
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
        clock = clock,
    )

    /** Collects the screen state for the life of the test; `uiState` is a `StateFlow`. */
    private fun TestScope.observe(viewModel: LocationViewModel): List<LocationUiState> {
        val states = mutableListOf<LocationUiState>()
        backgroundScope.launch { viewModel.uiState.toList(states) }
        return states
    }

    /** The latest state, asserted to be past the dataset read. */
    private fun List<LocationUiState>.settled(): LocationUiState {
        assertTrue("no state was emitted", isNotEmpty())
        val state = last()
        assertFalse("the dataset read should have finished", state.isLoading)
        return state
    }

    /** A provider whose answer waits, so "while a fix is in flight" is an observable state. */
    private class HeldLocationProvider : LocationProvider {

        val gate = CompletableDeferred<Unit>()

        var calls: Int = 0
            private set

        override fun servicesEnabled(): Boolean = true

        override suspend fun currentLocation(timeout: Duration): LocationAcquisitionResult {
            calls++
            gate.await()
            return LocationAcquisitionResult.TimedOut
        }
    }

    private companion object {
        const val KHULNA: String = "BD10"
        const val RAJSHAHI: String = "BD20"
        const val KUSHTIA: String = "BD1001"
        const val KUMARKHALI: String = "BD1001001"

        /** The synthetic dataset's own coordinate for Kushtia district. */
        val KUSHTIA_CENTRE: GeoPoint = GeoPoint(latitude = 23.90, longitude = 89.12)
    }
}
