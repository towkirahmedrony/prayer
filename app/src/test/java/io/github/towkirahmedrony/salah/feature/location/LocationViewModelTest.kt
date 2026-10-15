package io.github.towkirahmedrony.salah.feature.location

import io.github.towkirahmedrony.salah.core.domain.SalahDiagnosticEvents
import io.github.towkirahmedrony.salah.core.domain.location.BangladeshLocationDataset
import io.github.towkirahmedrony.salah.core.domain.location.BangladeshLocationPolicy
import io.github.towkirahmedrony.salah.core.domain.location.GeoPoint
import io.github.towkirahmedrony.salah.core.domain.location.LocationNameLanguage
import io.github.towkirahmedrony.salah.core.domain.notification.PrayerNotificationCoordinator
import io.github.towkirahmedrony.salah.core.domain.notification.PrayerNotificationPlanner
import io.github.towkirahmedrony.salah.core.domain.qibla.LocationAcquisitionResult
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
import io.github.towkirahmedrony.salah.testing.localityRow
import io.github.towkirahmedrony.salah.testing.syntheticDataset
import io.github.towkirahmedrony.salah.testing.syntheticLocalities
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
import kotlin.time.Duration

/**
 * The behaviour the one location selection screen depends on, asserted once.
 *
 * `LocationSelectionScreen` renders one state and calls one view model in both of its entry contexts —
 * a first launch, which stores a fix on arrival, and a later change, which holds it for confirmation —
 * so the rules that decide what a tap does are tested here rather than through either framing's Compose
 * tree. (`LocationSelectionWiringTest` pins the wiring; this pins the behaviour behind it.)
 *
 *  - a device fix is checked against the Bangladesh-only policy in **both** contexts, and what differs
 *    is only when it is written: a change holds it for confirmation while a first launch stores it on
 *    arrival — so granting the permission is enough to reach the app;
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

    // The default, which is the location screen's behaviour: a fix is held, not written.
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
    fun `a first-launch fix is stored on arrival without a confirmation step`() =
        runTest(mainDispatcher) {
            val provider = FakeLocationProvider().apply { fixAt(KUSHTIA_CENTRE) }
            val viewModel = viewModel(provider = provider)
            val states = observe(viewModel)
            viewModel.start()
            runCurrent()

            // What the first-launch screen asks for: granting the permission and a fix that passes the
            // policy is the whole interaction, and the app opens on it.
            viewModel.useCurrentLocation(saveOnArrival = true)
            runCurrent()

            val state = states.settled()
            assertNull("nothing may be left waiting for a confirmation", state.pendingFix)
            assertEquals(LocationMessage.Saved, state.message)
            assertFalse(state.isAcquiring)

            val saved = locations.location.first()
            assertNotNull("the fix must be stored", saved)
            assertEquals(LocationOrigin.DEVICE_GPS, saved!!.origin)
            assertEquals(23.90, saved.latitude, 0.000001)
            assertEquals(89.12, saved.longitude, 0.000001)
            assertEquals(BangladeshLocationPolicy.TIME_ZONE_ID, saved.timeZoneId)
        }

    @Test
    fun `a first-launch fix outside Bangladesh is refused just the same`() = runTest(mainDispatcher) {
        // Saving without asking is not saving without checking: the policy runs before the write on
        // both paths, so an unsupported position is refused and nothing is stored.
        val provider = FakeLocationProvider().apply { fixAt(GeoPoint(51.5074, -0.1278)) }
        val viewModel = viewModel(provider = provider)
        val states = observe(viewModel)
        viewModel.start()
        runCurrent()

        viewModel.useCurrentLocation(saveOnArrival = true)
        runCurrent()

        val state = states.settled()
        assertTrue(
            "expected an outside-the-country message, was ${state.message}",
            state.message is LocationMessage.OutsideSupportedCountry,
        )
        assertNull(state.pendingFix)
        assertNull("an unsupported position must never be stored", locations.location.first())
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

    // --- Manual coordinates ----------------------------------------------------

    @Test
    fun `a manual entry is stored as entered and reconciles the reminders`() = runTest(mainDispatcher) {
        val viewModel = viewModel()
        val states = observe(viewModel)
        viewModel.start()
        runCurrent()

        viewModel.toggleManualCoordinates()
        runCurrent()
        assertTrue("the disclosure opens the form", states.settled().isManualFormOpen)

        viewModel.onManualFormChange {
            it.copy(label = "Home", latitude = "23.8103", longitude = "90.4125", timeZoneId = "Asia/Dhaka")
        }
        runCurrent()
        viewModel.saveManualLocation()
        runCurrent()

        val saved = locations.location.first()
        assertNotNull("a valid entry must be stored", saved)
        assertEquals("Home", saved!!.label)
        assertEquals(23.8103, saved.latitude, 1e-9)
        assertEquals(90.4125, saved.longitude, 1e-9)
        assertEquals("Asia/Dhaka", saved.timeZoneId)
        // Stored as asserted rather than as observed: no country check runs on this path, which is what
        // makes the form usable for a place the bundled dataset does not contain.
        assertEquals(LocationOrigin.MANUAL_ENTRY, saved.origin)
        assertNull(saved.countryCode)

        val afterSave = states.settled()
        assertEquals(LocationMessage.Saved, afterSave.message)
        assertFalse("the saved form closes", afterSave.isManualFormOpen)

        // The repair this screen adds: the old settings form wrote the location and left the alarms on
        // the previous one, so the reminders are reconciled here as they are for the other two paths.
        assertEquals(
            "a saved location must reschedule the reminders",
            "LOCATION_CHANGED",
            logger.detailsOf(SalahDiagnosticEvents.RECONCILE_STARTED)?.get("reason"),
        )

        // The coordinates are in the store, but never in the log: diagnostics record the outcome, not
        // the position.
        assertFalse(logger.renderAll().contains("23.8103"))
        assertFalse(logger.renderAll().contains("90.4125"))
    }

    @Test
    fun `an invalid manual entry is refused and changes nothing`() = runTest(mainDispatcher) {
        val viewModel = viewModel()
        val states = observe(viewModel)
        viewModel.start()
        runCurrent()

        val reconcilesBefore = logger.count(SalahDiagnosticEvents.RECONCILE_STARTED)

        viewModel.toggleManualCoordinates()
        runCurrent()
        viewModel.onManualFormChange {
            // A latitude that is not a number and no longitude at all: the repository is never reached.
            it.copy(label = "Home", latitude = "north", longitude = "", timeZoneId = "Asia/Dhaka")
        }
        runCurrent()
        viewModel.saveManualLocation()
        runCurrent()

        val afterSave = states.settled()
        assertEquals(LocationMessage.SaveRejected, afterSave.message)
        assertTrue("the fields are marked as the problem", afterSave.manualForm.hasValidationError)
        assertTrue("the form stays open so the entry can be corrected", afterSave.isManualFormOpen)
        assertNull("nothing may be stored", locations.location.first())
        assertEquals(
            "a refused save must not reschedule anything",
            reconcilesBefore,
            logger.count(SalahDiagnosticEvents.RECONCILE_STARTED),
        )
    }

    @Test
    fun `a stored manual entry fills the form until the user types over it`() = runTest(mainDispatcher) {
        locations.set(
            requireNotNull(
                LocationInfo.create(
                    label = "Home",
                    latitude = 23.8103,
                    longitude = 90.4125,
                    timeZoneId = "Asia/Dhaka",
                ),
            ),
        )
        val viewModel = viewModel()
        val states = observe(viewModel)
        viewModel.start()
        runCurrent()

        val restored = states.settled().manualForm
        assertEquals("Home", restored.label)
        assertEquals("23.8103", restored.latitude)
        assertEquals("90.4125", restored.longitude)
        assertEquals("Asia/Dhaka", restored.timeZoneId)

        viewModel.onManualFormChange { it.copy(label = "Office") }
        runCurrent()

        // The user's own text wins for the rest of the visit, and the stored location is untouched until
        // it is saved.
        assertEquals("Office", states.settled().manualForm.label)
        assertEquals("Home", locations.location.first()?.label)
    }

    // --- A named locality -------------------------------------------------------

    @Test
    fun `a fix accurate enough is stored under the neighbourhood it matched`() = runTest(mainDispatcher) {
        val provider = FakeLocationProvider().apply { fixAt(KUSHTIA_CENTRE) }
        val viewModel = viewModel(
            provider = provider,
            catalog = FakeBangladeshAreaCatalog(datasetWithLocality()),
        )
        val states = observe(viewModel)
        viewModel.start()
        runCurrent()

        viewModel.useCurrentLocation(saveOnArrival = true)
        runCurrent()

        assertEquals(LocationMessage.Saved, states.settled().message)
        val saved = requireNotNull(locations.location.first()) { "the fix must be stored" }

        // The name is the neighbourhood's, qualified by the district — not the city corporation or the
        // upazila the administrative layer could name on its own.
        assertEquals("Test Bazar", saved.locality?.name)
        assertEquals("BD1001", saved.locality?.districtId)
        assertEquals("Test Bazar, Kushtia", saved.label)

        // And the locality did not replace the administrative hierarchy: both are recorded, because they
        // say different things and the upazila is what the rest of the app means by "the area".
        assertEquals("BD1001001", saved.area?.upazilaId)
        assertEquals("Kumarkhali", saved.area?.upazilaName)
    }

    @Test
    fun `an approximate fix is stored by area only, never by neighbourhood`() = runTest(mainDispatcher) {
        // What an approximate permission produces: a fix whose reported accuracy is wider than the
        // neighbourhood it is standing in. Salah names the area it can prove and says nothing about the
        // street, rather than translating coarse accuracy into a confident local name.
        val provider = FakeLocationProvider().apply {
            fixAt(KUSHTIA_CENTRE, accuracyMetres = 3_000.0)
        }
        val viewModel = viewModel(
            provider = provider,
            catalog = FakeBangladeshAreaCatalog(datasetWithLocality()),
        )
        val states = observe(viewModel)
        viewModel.start()
        runCurrent()

        viewModel.useCurrentLocation(saveOnArrival = true)
        runCurrent()

        assertEquals(LocationMessage.Saved, states.settled().message)
        val saved = requireNotNull(locations.location.first()) { "the fix must still be stored" }
        assertNull("a ±3 km fix cannot establish a neighbourhood", saved.locality)
        assertEquals("Kumarkhali, Kushtia", saved.label)
        // The fix itself is not discarded: the reduced precision costs the name, not the location.
        assertEquals(3_000.0, requireNotNull(saved.accuracyMetres), 0.000001)
    }

    // --- Manual search ----------------------------------------------------------

    @Test
    fun `a search query returns results in the active language after the debounce`() = runTest(mainDispatcher) {
        val viewModel = viewModel()
        val states = observe(viewModel)
        viewModel.start()
        runCurrent()

        viewModel.setLanguage(LocationNameLanguage.BANGLA)
        viewModel.onSearchQueryChanged("কুমার")
        runCurrent()
        val queued = states.settled()
        assertTrue("results arrive only after the debounce", queued.searchResults.isEmpty())
        assertTrue("the field reports that a search is in flight", queued.isSearching)

        advanceTimeBy(LocationViewModel.SEARCH_DEBOUNCE_MILLIS)
        runCurrent()

        val done = states.settled()
        assertFalse(done.isSearching)
        assertFalse(done.searchFailed)
        assertEquals(listOf("area:BD1001001"), done.searchResults.map { it.key })
        // The name is Bangla because the screen set Bangla — not because the query was written in it.
        assertEquals("কুমারখালী", done.searchResults.single().displayName)
    }

    @Test
    fun `a newer query cancels the search for the previous one`() = runTest(mainDispatcher) {
        val viewModel = viewModel()
        val states = observe(viewModel)
        viewModel.start()
        runCurrent()

        viewModel.onSearchQueryChanged("kumarkhali")
        advanceTimeBy(LocationViewModel.SEARCH_DEBOUNCE_MILLIS / 2)
        runCurrent()
        viewModel.onSearchQueryChanged("khulna")
        advanceTimeBy(LocationViewModel.SEARCH_DEBOUNCE_MILLIS)
        runCurrent()

        // Only the query that was current when the search ran has results: the half-finished one for
        // "kumarkhali" was cancelled rather than landing after it.
        val state = states.settled()
        assertEquals(listOf("area:BD10"), state.searchResults.map { it.key })
        assertFalse(state.isSearching)
    }

    @Test
    fun `clearing the query clears the results`() = runTest(mainDispatcher) {
        val viewModel = viewModel()
        val states = observe(viewModel)
        viewModel.start()
        runCurrent()

        viewModel.onSearchQueryChanged("kumarkhali")
        advanceTimeBy(LocationViewModel.SEARCH_DEBOUNCE_MILLIS)
        runCurrent()
        assertEquals(1, states.settled().searchResults.size)

        viewModel.onSearchQueryChanged("")
        runCurrent()

        val cleared = states.settled()
        assertTrue(cleared.searchResults.isEmpty())
        assertFalse(cleared.isSearching)
        assertFalse(cleared.searchFailed)
    }

    @Test
    fun `a chosen search result fills the hierarchy and saving stores that area`() = runTest(mainDispatcher) {
        val viewModel = viewModel()
        val states = observe(viewModel)
        viewModel.start()
        runCurrent()

        viewModel.openAreaPicker()
        viewModel.onSearchQueryChanged("kumarkhali")
        advanceTimeBy(LocationViewModel.SEARCH_DEBOUNCE_MILLIS)
        runCurrent()

        val found = states.settled()
        assertTrue(found.isPickerOpen)
        viewModel.selectSearchResult(found.searchResults.single())
        runCurrent()

        val selected = states.settled()
        assertEquals("BD10", selected.selection.divisionId)
        assertEquals("BD1001", selected.selection.districtId)
        assertEquals("BD1001001", selected.selection.upazilaId)
        assertTrue(selected.canSaveSelection)
        assertEquals("Kumarkhali, Kushtia, Khulna", selected.selectionSummary)

        viewModel.saveSelectedArea()
        runCurrent()

        val saved = requireNotNull(locations.location.first()) { "the selection must be stored" }
        assertEquals(LocationOrigin.AREA_SELECTION, saved.origin)
        assertEquals("BD1001001", saved.area?.upazilaId)
        // The shared display policy's composition: deepest name qualified by its district (the
        // division is carried in `area` but not repeated in the label).
        assertEquals("Kumarkhali, Kushtia", saved.label)
        // The published centre of the chosen upazila, not a device observation.
        assertEquals(23.86, saved.latitude, 1e-9)
        assertEquals(89.24, saved.longitude, 1e-9)

        val closed = states.settled()
        assertFalse("the picker closes on a confirmed save", closed.isPickerOpen)
        assertEquals("the query belonged to the selection that was confirmed", "", closed.searchQuery)
        assertTrue(closed.searchResults.isEmpty())
    }

    @Test
    fun `changing the language re-runs an open query and re-renders the results`() = runTest(mainDispatcher) {
        val viewModel = viewModel()
        val states = observe(viewModel)
        viewModel.start()
        runCurrent()

        viewModel.onSearchQueryChanged("kush")
        advanceTimeBy(LocationViewModel.SEARCH_DEBOUNCE_MILLIS)
        runCurrent()
        val english = states.settled().searchResults
        assertTrue(english.isNotEmpty())
        assertEquals("Kushtia", english.first().displayName)

        viewModel.setLanguage(LocationNameLanguage.BANGLA)
        advanceTimeBy(LocationViewModel.SEARCH_DEBOUNCE_MILLIS)
        runCurrent()

        val bangla = states.settled()
        assertTrue(bangla.searchResults.isNotEmpty())
        // The same query now renders in Bangla: the district's own Bangla name, and a context line
        // under it in Bangla too — display follows the language, not the typed text.
        val district = bangla.searchResults.first { it.key == "area:BD1001" }
        assertEquals("কুষ্টিয়া", district.displayName)
        assertEquals(listOf("খুলনা"), district.contextNames)
    }

    @Test
    fun `a search result switches divisions, and a district result clears the upazila below it`() =
        runTest(mainDispatcher) {
            val viewModel = viewModel()
            val states = observe(viewModel)
            viewModel.start()
            runCurrent()

            viewModel.selectDivision(KHULNA)
            viewModel.selectDistrict(KUSHTIA)
            viewModel.selectUpazila(KUMARKHALI)
            runCurrent()

            // A result in another division moves every level: its own division is selected first, so
            // the previous division's district cannot survive the switch.
            viewModel.onSearchQueryChanged("sherpur")
            advanceTimeBy(LocationViewModel.SEARCH_DEBOUNCE_MILLIS)
            runCurrent()
            viewModel.selectSearchResult(states.settled().searchResults.single())
            runCurrent()

            val moved = states.settled()
            assertEquals("BD20", moved.selection.divisionId)
            assertEquals("BD2001", moved.selection.districtId)
            assertEquals("BD2001001", moved.selection.upazilaId)
            assertTrue(moved.canSaveSelection)

            // A district result carries no upazila, so the one chosen under the previous district is
            // cleared and the save button goes back to waiting.
            viewModel.onSearchQueryChanged("jashore")
            advanceTimeBy(LocationViewModel.SEARCH_DEBOUNCE_MILLIS)
            runCurrent()
            viewModel.selectSearchResult(states.settled().searchResults.single())
            runCurrent()

            val districtOnly = states.settled()
            assertEquals("BD10", districtOnly.selection.divisionId)
            assertEquals("BD1002", districtOnly.selection.districtId)
            assertEquals(null, districtOnly.selection.upazilaId)
            assertFalse(districtOnly.canSaveSelection)
        }

    @Test
    fun `a search typed before the dataset is read waits for it instead of failing`() = runTest(mainDispatcher) {
        val catalog = HeldCatalog(syntheticDataset())
        val viewModel = viewModel(catalog = catalog)
        val states = observe(viewModel)
        viewModel.start()
        runCurrent()

        viewModel.onSearchQueryChanged("kumarkhali")
        advanceTimeBy(LocationViewModel.SEARCH_DEBOUNCE_MILLIS)
        runCurrent()

        val waiting = states.last()
        assertTrue("the read is still running", waiting.isLoading)
        assertTrue("held in the searching state", waiting.isSearching)
        assertFalse("an unfinished read is not a failed search", waiting.searchFailed)

        catalog.gate.complete(Unit)
        runCurrent()
        advanceTimeBy(LocationViewModel.SEARCH_DEBOUNCE_MILLIS)
        runCurrent()

        val done = states.last()
        assertEquals(listOf("area:BD1001001"), done.searchResults.map { it.key })
        assertFalse(done.isSearching)
        assertFalse(done.searchFailed)
    }

    @Test
    fun `a search with no readable dataset reports the failure`() = runTest(mainDispatcher) {
        val viewModel = viewModel(catalog = FakeBangladeshAreaCatalog(null))
        val states = observe(viewModel)
        viewModel.start()
        runCurrent()

        viewModel.onSearchQueryChanged("kushtia")
        runCurrent()

        val failed = states.last()
        assertTrue("there is nothing to search and nothing to wait for", failed.searchFailed)
        assertFalse(failed.isSearching)
        assertTrue(failed.searchResults.isEmpty())
    }

    @Test
    fun `closing the picker after a search leaves the stored location untouched`() = runTest(mainDispatcher) {
        locations.set(
            requireNotNull(
                LocationInfo.create(
                    label = "Home",
                    latitude = 23.8103,
                    longitude = 90.4125,
                    timeZoneId = "Asia/Dhaka",
                ),
            ),
        )
        val viewModel = viewModel()
        val states = observe(viewModel)
        viewModel.start()
        runCurrent()

        viewModel.openAreaPicker()
        viewModel.onSearchQueryChanged("kumarkhali")
        advanceTimeBy(LocationViewModel.SEARCH_DEBOUNCE_MILLIS)
        runCurrent()
        viewModel.selectSearchResult(states.settled().searchResults.single())
        runCurrent()

        viewModel.closeAreaPicker()
        runCurrent()

        val closed = states.settled()
        assertFalse(closed.isPickerOpen)
        assertEquals(null, closed.selection.divisionId)
        assertEquals("", closed.searchQuery)
        assertTrue(closed.searchResults.isEmpty())
        // Cancelling a selection is not choosing one: the stored location is exactly where it was.
        val stored = locations.location.first()
        assertEquals("Home", stored?.label)
        assertEquals(23.8103, stored?.latitude ?: 0.0, 1e-9)
    }

    @Test
    fun `the two entry points read one location, not one each`() = runTest(mainDispatcher) {
        // What the app's two ways into the selection screen are: two view models, because each open
        // screen has its own, and one repository behind both. The first-launch screen saves a location
        // here; the screen a later change would open — already observing, and never refreshed by hand —
        // must read exactly that record, which is what makes this one flow rather than two copies that
        // happen to agree.
        val firstLaunch = viewModel()
        val laterChange = viewModel()
        val laterStates = observe(laterChange)
        laterChange.start()
        runCurrent()

        val firstStates = observe(firstLaunch)
        firstLaunch.start()
        runCurrent()

        firstLaunch.selectDivision(KHULNA)
        firstLaunch.selectDistrict(KUSHTIA)
        firstLaunch.selectUpazila(KUMARKHALI)
        firstLaunch.saveSelectedArea()
        runCurrent()

        assertEquals(LocationMessage.Saved, firstStates.settled().message)
        val seen = requireNotNull(laterStates.settled().stored) {
            "the screen a later change opens must see the location the first launch saved"
        }
        assertEquals(LocationOrigin.AREA_SELECTION, seen.origin)
        assertEquals("BD1001001", seen.area?.upazilaId)
    }

    // --- Harness ---------------------------------------------------------------

    /**
     * The miniature dataset plus one named locality at the point every device-fix test uses.
     *
     * Put at Kushtia's own centre so the case that is about the neighbourhood is not also a case about
     * distance, and in district BD1001 so the verified-district gate is satisfied by construction.
     */
    private fun datasetWithLocality() = syntheticDataset(
        localities = syntheticLocalities(
            localityRow("QUARTER", KUSHTIA, "Test Bazar", KUSHTIA_CENTRE.latitude, KUSHTIA_CENTRE.longitude),
        ),
    )

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

    /** A catalog whose read waits on a gate, so "the dataset is still loading" is observable. */
    private class HeldCatalog(
        private val dataset: BangladeshLocationDataset,
    ) : BangladeshAreaCatalog {

        val gate = CompletableDeferred<Unit>()

        override suspend fun dataset(): BangladeshLocationDataset? {
            gate.await()
            return dataset
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
