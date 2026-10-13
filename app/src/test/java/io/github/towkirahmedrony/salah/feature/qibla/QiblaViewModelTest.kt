package io.github.towkirahmedrony.salah.feature.qibla

import io.github.towkirahmedrony.salah.core.domain.SalahDiagnosticEvents
import io.github.towkirahmedrony.salah.core.domain.location.GeoPoint
import io.github.towkirahmedrony.salah.core.domain.location.LocationFreshness
import io.github.towkirahmedrony.salah.core.domain.notification.PrayerNotificationCoordinator
import io.github.towkirahmedrony.salah.core.domain.notification.PrayerNotificationPlanner
import io.github.towkirahmedrony.salah.core.domain.qibla.CompassAvailability
import io.github.towkirahmedrony.salah.core.domain.qibla.CompassSensorSource
import io.github.towkirahmedrony.salah.core.domain.qibla.GeomagneticDeclinationProvider
import io.github.towkirahmedrony.salah.core.domain.qibla.LocationPermissionChecker
import io.github.towkirahmedrony.salah.core.domain.qibla.LocationProvider
import io.github.towkirahmedrony.salah.core.domain.qibla.QiblaBearing
import io.github.towkirahmedrony.salah.core.domain.qibla.QiblaCalculator
import io.github.towkirahmedrony.salah.core.domain.qibla.QiblaLocationState
import io.github.towkirahmedrony.salah.core.domain.qibla.QiblaPhase
import io.github.towkirahmedrony.salah.core.domain.qibla.QiblaUnavailableReason
import io.github.towkirahmedrony.salah.core.domain.repository.BangladeshAreaCatalog
import io.github.towkirahmedrony.salah.core.domain.repository.LocationRepository
import io.github.towkirahmedrony.salah.core.model.BangladeshAreaRef
import io.github.towkirahmedrony.salah.core.model.LocationInfo
import io.github.towkirahmedrony.salah.core.model.LocationOrigin
import io.github.towkirahmedrony.salah.testing.FakeBangladeshAreaCatalog
import io.github.towkirahmedrony.salah.testing.FakeCompassSensorSource
import io.github.towkirahmedrony.salah.testing.FakeLocationPermissionSource
import io.github.towkirahmedrony.salah.testing.FakeLocationProvider
import io.github.towkirahmedrony.salah.testing.FakeLocationRepository
import io.github.towkirahmedrony.salah.testing.FakePermissionStatusProvider
import io.github.towkirahmedrony.salah.testing.FakePrayerNotificationPreferencesRepository
import io.github.towkirahmedrony.salah.testing.FakePrayerNotificationScheduler
import io.github.towkirahmedrony.salah.testing.FakePrayerScheduleRepository
import io.github.towkirahmedrony.salah.testing.FakeUserPreferencesRepository
import io.github.towkirahmedrony.salah.testing.FixedDeclinationProvider
import io.github.towkirahmedrony.salah.testing.RecordingLogger
import io.github.towkirahmedrony.salah.testing.syntheticDataset
import io.github.towkirahmedrony.salah.testing.testSchedule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
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
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

/**
 * The Qibla screen's state, driven through the real ViewModel.
 *
 * **What this covers that the unit tests around it cannot.** `QiblaCalculatorTest`,
 * `HeadingMathTest` and `QiblaStateReducerTest` each verify one pure piece. This file verifies the
 * *wiring between them*, which is where the Qibla screen's actual defects have lived:
 *
 *  * which stored location the bearing is calculated from, and that it is recalculated when the stored
 *    location changes;
 *  * that a device fix is adopted through the shared Bangladesh policy, so a fix outside the country or
 *    one that cannot be attributed to an area leaves the stored location alone (the defect the location
 *    foundation repair fixed);
 *  * that the heading is converted to true north by applying declination exactly once, to the heading
 *    and never to the bearing;
 *  * that the compass is collected only while the screen asks for it and released afterwards.
 *
 * **Why the calculator is used as the oracle here.** These are wiring tests, so the assertion is "the
 * ViewModel reports the bearing for the coordinates that are stored". The bearing arithmetic itself is
 * verified independently in `QiblaCalculatorTest` against two unrelated formulations; re-deriving it
 * here would only duplicate that check while testing a different layer. The bearings asserted as
 * literals — Dhaka's `277.567140` — come from that file's frozen, triple-checked table, not from a run
 * of the function under test.
 *
 * The fakes are the production ports: a real [QiblaCalculator], real models, and the location, sensor
 * and declination ports substituted. Nothing here needs a device, a permission or a magnetometer.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QiblaViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    private val logger = RecordingLogger()
    private val notificationScheduler = FakePrayerNotificationScheduler()
    private val notificationPermissions = FakePermissionStatusProvider()
    private val notificationSettings = FakePrayerNotificationPreferencesRepository()
    private val userPreferences = FakeUserPreferencesRepository()

    /** The date the stored fix falls on in Dhaka, so reconciliation has a schedule to find. */
    private val schedules = FakePrayerScheduleRepository(mapOf(FIX_DATE to testSchedule(FIX_DATE, DHAKA_ZONE)))

    @Before
    fun installMainDispatcher() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun removeMainDispatcher() {
        Dispatchers.resetMain()
    }

    // --- No location ----------------------------------------------------------

    @Test
    fun `with nothing stored the screen is initial and no fix is requested`() = runTest(dispatcher) {
        val provider = FakeLocationProvider()
        val viewModel = viewModel(locations = FakeLocationRepository(), provider = provider)
        observe(viewModel)

        val state = viewModel.uiState.value

        assertEquals(QiblaPhase.INITIAL, state.phase)
        assertNull(state.bearingDegrees)
        assertTrue(state.location is QiblaLocationState.None)

        // The bearing needs a position, not a permission: opening the screen must not power up the
        // GNSS receiver on its own.
        assertEquals(0, provider.calls)
    }

    @Test
    fun `a fix is requested only when the user asks for one`() = runTest(dispatcher) {
        val provider = FakeLocationProvider()
        val viewModel = viewModel(locations = FakeLocationRepository(), provider = provider)
        observe(viewModel)

        assertEquals(0, provider.calls)

        provider.fixAt(GeoPoint(23.86, 89.24))
        viewModel.useCurrentLocation()
        advanceUntilIdle()

        assertEquals(1, provider.calls)
    }

    // --- A manually entered and an area-selected location ---------------------

    @Test
    fun `a manually stored location produces the bearing for its coordinates`() = runTest(dispatcher) {
        val viewModel = viewModel(locations = FakeLocationRepository(dhaka()))
        observe(viewModel)

        val state = viewModel.uiState.value
        val bearing = state.bearingDegrees

        assertNotNull("expected a bearing for the stored location", bearing)
        // Dhaka, from QiblaCalculatorTest's frozen and triple-checked reference table.
        assertEquals(277.567140, bearing!!, 1e-4)

        val distance = state.distanceToKaabaMetres
        assertNotNull(distance)
        assertTrue(
            "distance ${distance} is not on the Dhaka–Mecca scale",
            distance!! in 5_100_000.0..5_250_000.0,
        )

        assertTrue(state.location is QiblaLocationState.Available)
        assertEquals(
            LocationFreshness.DECLARED,
            (state.location as QiblaLocationState.Available).freshness,
        )
    }

    @Test
    fun `an area-selected location produces its bearing and keeps its source and area`() =
        runTest(dispatcher) {
            val stored = areaSelection()
            val viewModel = viewModel(locations = FakeLocationRepository(stored))
            observe(viewModel)

            val state = viewModel.uiState.value
            val available = state.location as QiblaLocationState.Available

            // The origin and the hierarchy survive, which is what lets the screen say the position is a
            // selected area's published centre rather than the user's own position.
            assertEquals(LocationOrigin.AREA_SELECTION, available.location.origin)
            assertEquals("BD1001", available.location.area?.districtId)
            assertEquals("BD", available.location.countryCode)
            assertEquals(LocationFreshness.DECLARED, available.freshness)

            // The bearing is the one for the area's centre. The distance is ~5 233 km; the point is that
            // it is derived from the stored coordinate rather than from anything else.
            assertEquals(
                QiblaCalculator().bearingFrom(stored.latitude, stored.longitude),
                state.bearing,
            )
        }

    @Test
    fun `changing the stored location recalculates the bearing`() = runTest(dispatcher) {
        val locations = FakeLocationRepository(dhaka())
        val viewModel = viewModel(locations = locations)
        observe(viewModel)

        val before = viewModel.uiState.value
        assertEquals(277.567140, before.bearingDegrees!!, 1e-4)

        // A second Bangladeshi location: Sylhet, about 200 km north-east of Dhaka. Two points inside
        // Bangladesh differ by well under a degree in Qibla bearing — both are around 5 000 km from
        // Mecca — so "the reading changed by a large angle" is not a usable test here. The distance is
        // what separates them unambiguously, and the bearing is checked against its own value rather
        // than against the size of a change.
        locations.set(manual(label = "Sylhet", latitude = 24.8949, longitude = 91.8687))
        advanceUntilIdle()

        val after = viewModel.uiState.value
        val expected = QiblaCalculator().bearingFrom(24.8949, 91.8687) as QiblaBearing.Available

        // The same oracle as elsewhere in this file: the calculator's value for the coordinates now
        // stored, which is a different layer from the ViewModel wiring under test here.
        assertEquals(expected.degrees, after.bearingDegrees!!, 1e-9)
        assertEquals(expected.distanceMetres, after.distanceToKaabaMetres!!, 1e-6)

        // Frozen pins for the pair, so a change to either coordinate is caught by name rather than by
        // a threshold: Sylhet is ~131 km further from the Kaaba than Dhaka.
        assertEquals(277.011537408, after.bearingDegrees!!, 1e-4)
        assertEquals(5_302_998.4, after.distanceToKaabaMetres!!, 1.0)

        // And the reading is genuinely new rather than the previous location's still being shown.
        assertTrue(
            "the bearing did not move: ${before.bearingDegrees} then ${after.bearingDegrees}",
            abs(after.bearingDegrees!! - before.bearingDegrees!!) > 0.1,
        )
        assertTrue(
            "the distance did not follow the location",
            abs(after.distanceToKaabaMetres!! - before.distanceToKaabaMetres!!) > 100_000.0,
        )
        assertEquals("Sylhet", (after.location as QiblaLocationState.Available).location.label)
    }

    @Test
    fun `invalid stored coordinates are a calculation error rather than a bearing`() = runTest(dispatcher) {
        // Constructed directly, because LocationInfo.create refuses these — which is the point: the
        // screen must survive a location the store somehow holds rather than crash or invent a bearing.
        val invalid = LocationInfo(
            label = "Nowhere",
            latitude = 95.0,
            longitude = 200.0,
            timeZoneId = "Asia/Dhaka",
            origin = LocationOrigin.MANUAL_ENTRY,
        )
        val viewModel = viewModel(locations = FakeLocationRepository(invalid))
        observe(viewModel)

        val state = viewModel.uiState.value

        assertEquals(QiblaPhase.CALCULATION_ERROR, state.phase)
        assertNull(state.bearingDegrees)
        assertEquals(
            QiblaBearing.Unavailable(QiblaUnavailableReason.INVALID_COORDINATES),
            state.bearing,
        )
    }

    // --- The device-fix write path --------------------------------------------

    @Test
    fun `a fix inside Bangladesh is adopted and becomes the stored location`() = runTest(dispatcher) {
        val locations = FakeLocationRepository(dhaka())
        val provider = FakeLocationProvider()
        val viewModel = viewModel(locations = locations, provider = provider)
        observe(viewModel)

        provider.fixAt(GeoPoint(23.86, 89.24), accuracyMetres = 12.0)
        viewModel.useCurrentLocation()
        advanceUntilIdle()

        val stored = viewModel.storedLocation()
        assertNotNull("expected the fix to be stored", stored)
        assertEquals(LocationOrigin.DEVICE_GPS, stored!!.origin)
        // Attributed through the shared policy, not assumed: the country code is only stored once the
        // boundary check has passed, and the area comes from the bundled dataset.
        assertEquals("BD", stored.countryCode)
        assertEquals("BD1001001", stored.area?.upazilaId)
        // Bangladesh's single zone, not the device's own — see QiblaViewModel.storeDeviceLocation.
        assertEquals("Asia/Dhaka", stored.timeZoneId)
        assertEquals("Kumarkhali, Kushtia", stored.label)
        assertEquals(QiblaMessage.DeviceLocationAcquired, viewModel.uiState.value.message)
    }

    @Test
    fun `a fix outside Bangladesh does not replace the stored location`() = runTest(dispatcher) {
        val original = dhaka()
        val locations = FakeLocationRepository(original)
        val provider = FakeLocationProvider()
        val viewModel = viewModel(locations = locations, provider = provider)
        observe(viewModel)

        // ~26 N, 92 E: well outside the bundled boundary.
        provider.fixAt(GeoPoint(26.0, 92.0))
        viewModel.useCurrentLocation()
        advanceUntilIdle()

        val stored = viewModel.storedLocation()
        assertEquals("the stored location must be preserved", original, stored)
        assertEquals(QiblaMessage.OutsideSupportedCountry, viewModel.uiState.value.message)

        // And the bearing still describes the preserved location, not the rejected fix.
        assertEquals(277.567140, viewModel.uiState.value.bearingDegrees!!, 1e-4)
    }

    @Test
    fun `a fix that cannot be attributed to an area does not replace the stored location`() =
        runTest(dispatcher) {
            val original = dhaka()
            val locations = FakeLocationRepository(original)
            val provider = FakeLocationProvider()
            val viewModel = viewModel(locations = locations, provider = provider)
            observe(viewModel)

            // Inside the synthetic boundary, but further from every district centre than the resolver's
            // district threshold allows.
            provider.fixAt(GeoPoint(22.1, 88.6))
            viewModel.useCurrentLocation()
            advanceUntilIdle()

            assertEquals("the stored location must be preserved", original, viewModel.storedLocation())
            assertEquals(QiblaMessage.AreaNotAttributed, viewModel.uiState.value.message)
        }

    @Test
    fun `a rejected fix with no location stored leaves nothing stored`() = runTest(dispatcher) {
        val locations = FakeLocationRepository()
        val provider = FakeLocationProvider()
        val viewModel = viewModel(locations = locations, provider = provider)
        observe(viewModel)

        provider.fixAt(GeoPoint(26.0, 92.0))
        viewModel.useCurrentLocation()
        advanceUntilIdle()

        assertNull(viewModel.storedLocation())
        assertEquals(QiblaPhase.INITIAL, viewModel.uiState.value.phase)
        assertEquals(QiblaMessage.OutsideSupportedCountry, viewModel.uiState.value.message)
    }

    @Test
    fun `an unreadable dataset refuses the fix rather than storing an unverified position`() =
        runTest(dispatcher) {
            val original = dhaka()
            val catalog = FakeBangladeshAreaCatalog(dataset = null)
            val provider = FakeLocationProvider()
            val viewModel = viewModel(
                locations = FakeLocationRepository(original),
                provider = provider,
                areaCatalog = catalog,
            )
            observe(viewModel)

            provider.fixAt(GeoPoint(23.86, 89.24))
            viewModel.useCurrentLocation()
            advanceUntilIdle()

            assertEquals("the stored location must be preserved", original, viewModel.storedLocation())
            assertEquals(QiblaMessage.LocationFailed, viewModel.uiState.value.message)
        }

    // --- Heading, declination and the sensor lifecycle ------------------------

    @Test
    fun `no heading is claimed before the first sensor sample`() = runTest(dispatcher) {
        val compass = FakeCompassSensorSource()
        val viewModel = viewModel(locations = FakeLocationRepository(dhaka()), compass = compass)
        observe(viewModel)

        val state = viewModel.uiState.value

        assertEquals(QiblaPhase.AWAITING_HEADING, state.phase)
        assertNull(state.heading)
        // The dial must not animate without a measured heading.
        assertFalse(state.isDialLive)
    }

    @Test
    fun `declination is applied to the heading exactly once and never to the bearing`() =
        runTest(dispatcher) {
            val compass = FakeCompassSensorSource()
            val viewModel = viewModel(
                locations = FakeLocationRepository(dhaka()),
                compass = compass,
                declination = FixedDeclinationProvider(degrees = 2.0),
            )
            observe(viewModel)

            viewModel.startCompass()
            compass.emit(magneticDegrees = 100.0)
            advanceUntilIdle()

            val state = viewModel.uiState.value
            val heading = state.heading
            assertNotNull("expected a heading after a sample", heading)

            // Magnetic 100 with a +2 declination is true 102. Exact, because the first sample is adopted
            // unfiltered by HeadingSmoother.
            assertEquals(100.0, heading!!.magneticDegrees, 1e-9)
            assertEquals(102.0, heading.trueDegrees!!, 1e-9)

            // The bearing is geography and must be untouched by declination. If the correction were
            // applied on both sides this would read 279.567.
            assertEquals(277.567140, state.bearingDegrees!!, 1e-4)

            // The signed shortest turn from true 102 to the bearing: 277.567 - 102.
            assertEquals(175.567140, heading.turnDegrees!!, 1e-4)
            assertEquals(QiblaPhase.COMPASS_OPERATIONAL, state.phase)
        }

    @Test
    fun `with no declination the heading is reported as magnetic and no alignment is claimed`() =
        runTest(dispatcher) {
            val compass = FakeCompassSensorSource()
            val viewModel = viewModel(
                locations = FakeLocationRepository(dhaka()),
                compass = compass,
                declination = FixedDeclinationProvider(degrees = null),
            )
            observe(viewModel)

            viewModel.startCompass()
            compass.emit(magneticDegrees = 100.0)
            advanceUntilIdle()

            val heading = viewModel.uiState.value.heading
            assertNotNull(heading)
            assertEquals(100.0, heading!!.magneticDegrees, 1e-9)
            // Comparing an uncorrected heading with a true-north bearing is exactly the error the
            // reference types exist to prevent, so neither the true heading nor the turn is produced.
            assertNull(heading.trueDegrees)
            assertNull(heading.turnDegrees)
            assertFalse(viewModel.uiState.value.isDialLive)
        }

    @Test
    fun `the compass is collected only while it is started, and once per start`() = runTest(dispatcher) {
        val compass = FakeCompassSensorSource()
        val viewModel = viewModel(locations = FakeLocationRepository(dhaka()), compass = compass)
        observe(viewModel)

        assertEquals(0, compass.activeCollections)

        viewModel.startCompass()
        advanceUntilIdle()
        assertEquals(1, compass.collections)
        assertEquals(1, compass.activeCollections)

        // A resumed screen asking again must not register a second listener.
        viewModel.startCompass()
        advanceUntilIdle()
        assertEquals(1, compass.collections)
        assertEquals(1, compass.activeCollections)

        viewModel.stopCompass()
        advanceUntilIdle()
        assertEquals(0, compass.activeCollections)
    }

    @Test
    fun `stopping the compass withdraws the heading instead of freezing it`() = runTest(dispatcher) {
        val compass = FakeCompassSensorSource()
        val viewModel = viewModel(locations = FakeLocationRepository(dhaka()), compass = compass)
        observe(viewModel)

        viewModel.startCompass()
        compass.emit(magneticDegrees = 100.0)
        advanceUntilIdle()
        assertNotNull(viewModel.uiState.value.heading)
        assertEquals(QiblaPhase.COMPASS_OPERATIONAL, viewModel.uiState.value.phase)

        viewModel.stopCompass()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNull("a stale heading must not be presented as live", state.heading)
        assertEquals(QiblaPhase.AWAITING_HEADING, state.phase)
        // The bearing is unaffected: it never needed the sensor.
        assertEquals(277.567140, state.bearingDegrees!!, 1e-4)
    }

    @Test
    fun `a device without a compass still shows the exact bearing`() = runTest(dispatcher) {
        val compass = FakeCompassSensorSource(CompassAvailability.Unavailable)
        val viewModel = viewModel(locations = FakeLocationRepository(dhaka()), compass = compass)
        observe(viewModel)

        val state = viewModel.uiState.value

        assertEquals(QiblaPhase.SENSOR_UNAVAILABLE, state.phase)
        assertFalse(state.sensorAvailable)
        // Geography, not sensing: the bearing is exact even with no compass to point it out.
        assertEquals(277.567140, state.bearingDegrees!!, 1e-4)
        assertNull(state.heading)
    }

    @Test
    fun `a manual entry from the screen is stored and drives the bearing`() = runTest(dispatcher) {
        val locations = FakeLocationRepository()
        val viewModel = viewModel(locations = locations)
        observe(viewModel)

        viewModel.onFormChange {
            it.copy(label = "Sylhet", latitude = "24.8949", longitude = "91.8687", timeZoneId = "Asia/Dhaka")
        }
        viewModel.saveManualLocation()
        advanceUntilIdle()

        val stored = viewModel.storedLocation()
        assertNotNull("expected the manual entry to be stored", stored)
        assertEquals(LocationOrigin.MANUAL_ENTRY, stored!!.origin)
        assertEquals(24.8949, stored.latitude, 1e-9)
        assertEquals(QiblaMessage.LocationSaved, viewModel.uiState.value.message)
        assertEquals(
            QiblaCalculator().bearingFrom(24.8949, 91.8687),
            viewModel.uiState.value.bearing,
        )
    }

    @Test
    fun `a manual entry with an unusable time zone is rejected and nothing is stored`() = runTest(dispatcher) {
        val locations = FakeLocationRepository()
        val viewModel = viewModel(locations = locations)
        observe(viewModel)

        viewModel.onFormChange {
            it.copy(label = "Nowhere", latitude = "24.0", longitude = "90.0", timeZoneId = "Not/AZone")
        }
        viewModel.saveManualLocation()
        advanceUntilIdle()

        assertNull("nothing must be stored for an invalid entry", viewModel.storedLocation())
        assertEquals(QiblaMessage.LocationRejected, viewModel.uiState.value.message)
        assertTrue(viewModel.uiState.value.form.hasValidationError)
    }

    // --- The location this screen writes is the app's location ------------------

    @Test
    fun `adopting a device fix reschedules the reminders`() = runTest(dispatcher) {
        val provider = FakeLocationProvider().apply { fixAt(GeoPoint(23.86, 89.24), accuracyMetres = 12.0) }
        val viewModel = viewModel(locations = FakeLocationRepository(), provider = provider)
        observe(viewModel)

        assertEquals(
            "opening the screen schedules nothing",
            0,
            logger.count(SalahDiagnosticEvents.RECONCILE_STARTED),
        )

        viewModel.useCurrentLocation()
        advanceUntilIdle()

        assertEquals(
            "the adopted location rebuilds the alarm set once",
            1,
            logger.count(SalahDiagnosticEvents.RECONCILE_STARTED),
        )
        assertTrue(
            "the reminders are planned from the stored location's own schedule",
            notificationScheduler.registered.isNotEmpty(),
        )
    }

    @Test
    fun `a refused fix leaves the alarms alone`() = runTest(dispatcher) {
        val provider = FakeLocationProvider().apply { fixAt(GeoPoint(26.0, 92.0)) }
        val viewModel = viewModel(locations = FakeLocationRepository(), provider = provider)
        observe(viewModel)

        viewModel.useCurrentLocation()
        advanceUntilIdle()

        assertEquals(
            "nothing was stored, so there is nothing to reschedule",
            0,
            logger.count(SalahDiagnosticEvents.RECONCILE_STARTED),
        )
        assertTrue("an unadopted fix must not schedule anything", notificationScheduler.registered.isEmpty())
    }

    @Test
    fun `storing manual coordinates reschedules the reminders`() = runTest(dispatcher) {
        val viewModel = viewModel(locations = FakeLocationRepository(dhaka()))
        observe(viewModel)

        viewModel.onFormChange {
            it.copy(label = "Home", latitude = "23.8103", longitude = "90.4125", timeZoneId = "Asia/Dhaka")
        }
        viewModel.saveManualLocation()
        advanceUntilIdle()

        assertEquals(1, logger.count(SalahDiagnosticEvents.RECONCILE_STARTED))

        // An entry the repository refuses must not reschedule either.
        val rejected = viewModel(locations = FakeLocationRepository(dhaka()))
        observe(rejected)
        rejected.onFormChange { it.copy(label = "Home", latitude = "north", longitude = "") }
        rejected.saveManualLocation()
        advanceUntilIdle()

        assertEquals(
            "a rejected entry changes no location, so no reminders move",
            1,
            logger.count(SalahDiagnosticEvents.RECONCILE_STARTED),
        )
    }

    // --- Harness --------------------------------------------------------------

    /**
     * Subscribes to the screen's state.
     *
     * `uiState` is shared with `WhileSubscribed`, so its upstream only runs while something is
     * collecting it — which is what makes the state the test reads the state the screen would render.
     */
    private fun TestScope.observe(viewModel: QiblaViewModel) {
        backgroundScope.launch { viewModel.uiState.collect { } }
    }

    /** The stored location as the repository sees it, so a write can be checked at its source. */
    private fun QiblaViewModel.storedLocation(): LocationInfo? =
        (uiState.value.location as? QiblaLocationState.Available)?.location

    private fun viewModel(
        locations: LocationRepository,
        provider: LocationProvider = FakeLocationProvider(),
        areaCatalog: BangladeshAreaCatalog = FakeBangladeshAreaCatalog(syntheticDataset()),
        compass: CompassSensorSource = FakeCompassSensorSource(),
        permissionChecker: LocationPermissionChecker = FakeLocationPermissionSource(),
        declination: GeomagneticDeclinationProvider = FixedDeclinationProvider(degrees = 0.0),
    ) = QiblaViewModel(
        locations = locations,
        areaCatalog = areaCatalog,
        compassSensor = compass,
        locationProvider = provider,
        permissionChecker = permissionChecker,
        declinationProvider = declination,
        qiblaCalculator = QiblaCalculator(),
        // The real coordinator over fake edges: this screen writes the app's only location, so the
        // rescheduling a write triggers is part of what is being exercised here.
        coordinator = PrayerNotificationCoordinator(
            planner = PrayerNotificationPlanner(),
            notificationSettings = notificationSettings,
            userPreferences = userPreferences,
            locations = locations,
            schedules = schedules,
            scheduler = notificationScheduler,
            permissions = notificationPermissions,
            logger = logger,
            clock = CLOCK,
        ),
        logger = logger,
        clock = CLOCK,
    )

    private companion object {

        /**
         * A clock a minute after [FakeLocationProvider.FIX_TIMESTAMP], so an adopted fix ages as
         * `FRESH` and the assertions do not depend on the wall clock.
         */
        val CLOCK: Clock = Clock.fixed(
            Instant.ofEpochMilli(FakeLocationProvider.FIX_TIMESTAMP + 60_000L),
            ZoneId.of("Asia/Dhaka"),
        )

        fun manual(
            label: String,
            latitude: Double,
            longitude: Double,
        ): LocationInfo = requireNotNull(
            LocationInfo.create(
                label = label,
                latitude = latitude,
                longitude = longitude,
                timeZoneId = "Asia/Dhaka",
                origin = LocationOrigin.MANUAL_ENTRY,
            ),
        )

        val DHAKA_ZONE: ZoneId = ZoneId.of("Asia/Dhaka")

        /** The local day the device fix below lands on, so the schedule lookup is not a guess. */
        val FIX_DATE: LocalDate = Instant.ofEpochMilli(FakeLocationProvider.FIX_TIMESTAMP)
            .atZone(DHAKA_ZONE)
            .toLocalDate()

        /** Dhaka, whose bearing is pinned independently in `QiblaCalculatorTest`. */
        fun dhaka(): LocationInfo = manual(label = "Dhaka, Bangladesh", latitude = 23.8103, longitude = 90.4125)

        /**
         * Kumarkhali, Kushtia, stored the way the location screen stores a chosen area: the coordinate is
         * the area's published centre, not the user's position.
         */
        fun areaSelection(): LocationInfo = requireNotNull(
            LocationInfo.create(
                label = "Kumarkhali, Kushtia",
                latitude = 23.86,
                longitude = 89.24,
                timeZoneId = "Asia/Dhaka",
                origin = LocationOrigin.AREA_SELECTION,
                countryCode = "BD",
                area = BangladeshAreaRef(
                    divisionId = "BD10",
                    divisionName = "Khulna",
                    districtId = "BD1001",
                    districtName = "Kushtia",
                    upazilaId = "BD1001001",
                    upazilaName = "Kumarkhali",
                ),
                datasetVersion = "test-v1",
            ),
        )
    }
}
