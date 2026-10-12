package io.github.towkirahmedrony.salah.feature.app

import io.github.towkirahmedrony.salah.core.domain.location.BangladeshLocationDataset
import io.github.towkirahmedrony.salah.core.domain.location.LocationSetupReason
import io.github.towkirahmedrony.salah.core.domain.location.LocationSetupState
import io.github.towkirahmedrony.salah.core.domain.repository.BangladeshAreaCatalog
import io.github.towkirahmedrony.salah.core.domain.startup.StartupSnapshot
import io.github.towkirahmedrony.salah.core.model.BangladeshAreaRef
import io.github.towkirahmedrony.salah.core.model.LocationInfo
import io.github.towkirahmedrony.salah.core.model.PrayerSettings
import io.github.towkirahmedrony.salah.testing.BundledDataset
import io.github.towkirahmedrony.salah.testing.FakeBangladeshAreaCatalog
import io.github.towkirahmedrony.salah.testing.FakeUserPreferencesRepository
import io.github.towkirahmedrony.salah.testing.syntheticDataset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.ZoneId

/**
 * The first-launch gate: whether the app opens on location setup or on the app itself.
 *
 * This is the decision the shell makes before any location-dependent screen exists
 * (`SalahApp`), so it is asserted here rather than through the Compose tree. What it locks in:
 *
 *  - a fresh install is asked for a location, and is not shown a screen that would have to invent a
 *    prayer time;
 *  - a device that already holds a usable location goes straight to the app, without setup;
 *  - a stored location this build cannot use — outside Bangladesh, or an area the dataset no longer
 *    contains — sends the user back to setup with the reason;
 *  - an unreadable dataset is reported, and does not lock out a location that is already valid;
 *  - the answer is *derived from the stored location*, so it moves in both directions: saving one
 *    closes the gate and clearing it opens the gate again. Nothing here is a "setup complete" flag
 *    that could disagree with the location it describes, which is the failure mode the requirement
 *    rules out.
 *
 * The start-up snapshot, the gate and the dataset read are all the real ones, driven on virtual time
 * with only the catalog faked — and one test's valid location is checked against the dataset the app
 * actually ships.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModelTest {

    private val zone: ZoneId = ZoneId.of("Asia/Dhaka")

    private val scheduler = TestCoroutineScheduler()
    private val mainDispatcher = StandardTestDispatcher(scheduler)

    /** A real Bangladeshi position, inside the bundled boundary. */
    private val dhaka = checkNotNull(LocationInfo.create("Dhaka", 23.8103, 90.4125, zone.id))

    /** Well outside Bangladesh: the coarse bounds reject this without needing the boundary file. */
    private val london = checkNotNull(LocationInfo.create("London", 51.5074, -0.1278, "Europe/London"))

    /** Inside the synthetic dataset's square, but attributed to an area the dataset does not list. */
    private val unknownArea = checkNotNull(
        LocationInfo.create(
            label = "Somewhere",
            latitude = 23.90,
            longitude = 89.12,
            timeZoneId = zone.id,
            area = BangladeshAreaRef(
                divisionId = "BD99",
                divisionName = "Nowhere",
                districtId = "BD9901",
                districtName = "Nowhere Sadar",
            ),
        ),
    )

    private lateinit var storedLocation: MutableStateFlow<LocationInfo?>

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        storedLocation = MutableStateFlow(null)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `a fresh install is asked for a location`() = runTest(mainDispatcher) {
        val viewModel = viewModel(FakeBangladeshAreaCatalog(syntheticDataset()))
        runCurrent()

        assertEquals(LocationSetupReason.NO_LOCATION, viewModel.locationSetup.value.requiredReason())
    }

    @Test
    fun `a stored usable location goes straight to the app`() = runTest(mainDispatcher) {
        storedLocation.value = dhaka

        // The real bundled dataset, so "usable" means what the shipping app means by it.
        val viewModel = viewModel(FakeBangladeshAreaCatalog(BundledDataset.load()))
        runCurrent()

        assertEquals(dhaka, viewModel.locationSetup.value.completeLocation())
    }

    @Test
    fun `a stored location outside Bangladesh is asked for again`() = runTest(mainDispatcher) {
        storedLocation.value = london

        val viewModel = viewModel(FakeBangladeshAreaCatalog(syntheticDataset()))
        runCurrent()

        assertEquals(
            LocationSetupReason.OUTSIDE_SUPPORTED_COUNTRY,
            viewModel.locationSetup.value.requiredReason(),
        )
    }

    @Test
    fun `a stored area the dataset no longer contains is asked for again`() = runTest(mainDispatcher) {
        storedLocation.value = unknownArea

        val viewModel = viewModel(FakeBangladeshAreaCatalog(syntheticDataset()))
        runCurrent()

        val state = viewModel.locationSetup.value
        assertEquals(LocationSetupReason.AREA_NOT_IN_DATASET, state.requiredReason())
        // The rejected location travels with the reason, so the screen can say what was refused.
        assertEquals(unknownArea, (state as LocationSetupState.Required).stored)
    }

    @Test
    fun `an unreadable dataset with nothing stored says so rather than showing an empty picker`() =
        runTest(mainDispatcher) {
            val viewModel = viewModel(FakeBangladeshAreaCatalog(dataset = null))
            runCurrent()

            val state = viewModel.locationSetup.value
            assertTrue(
                "expected DatasetUnavailable but was $state",
                state is LocationSetupState.DatasetUnavailable,
            )
            assertTrue("that state must open the setup screen", state.requiresSetup)
        }

    @Test
    fun `an unreadable dataset does not lock out a location that is already valid`() =
        runTest(mainDispatcher) {
            storedLocation.value = dhaka

            val viewModel = viewModel(FakeBangladeshAreaCatalog(dataset = null))
            runCurrent()

            // The coarse bounds check needs no boundary file, so a Bangladesh position is accepted and
            // the app is usable even though the area list failed to load.
            assertEquals(dhaka, viewModel.locationSetup.value.completeLocation())
        }

    @Test
    fun `the gate follows the stored location in both directions`() = runTest(mainDispatcher) {
        // The real dataset: this test moves `dhaka` in and out, and the synthetic square the other
        // tests use stops at 90.0°E, which Dhaka is outside.
        val viewModel = viewModel(FakeBangladeshAreaCatalog(BundledDataset.load()))
        runCurrent()
        assertEquals(LocationSetupReason.NO_LOCATION, viewModel.locationSetup.value.requiredReason())

        // Saving a location is the whole of "setup complete".
        storedLocation.value = dhaka
        runCurrent()
        assertEquals(dhaka, viewModel.locationSetup.value.completeLocation())

        // Clearing it — from the location screen, or from Android's own settings — opens the gate
        // again, because there is nothing left to derive a location-dependent value from.
        storedLocation.value = null
        runCurrent()
        assertEquals(LocationSetupReason.NO_LOCATION, viewModel.locationSetup.value.requiredReason())
    }

    @Test
    fun `a stored location is decided without waiting for the dataset read`() =
        runTest(mainDispatcher) {
            storedLocation.value = dhaka
            val catalog = HeldCatalog(BundledDataset.load())

            val viewModel = viewModel(catalog)
            runCurrent()

            // The dataset read is still suspended: nothing has been checked against the boundary yet,
            // and the location is accepted on the coarse bounds alone rather than holding the first
            // frame on a file it does not need.
            assertTrue("the dataset read should still be in flight", catalog.started)
            assertEquals(dhaka, viewModel.locationSetup.value.completeLocation())

            // When it arrives, the full check runs and the answer stands.
            catalog.gate.complete(Unit)
            runCurrent()
            assertEquals(dhaka, viewModel.locationSetup.value.completeLocation())
        }

    @Test
    fun `the gate stays undecided until the dataset has been read when nothing is stored`() =
        runTest(mainDispatcher) {
            val viewModel = viewModel(FakeBangladeshAreaCatalog(syntheticDataset()))

            assertTrue(
                "expected Checking before the dataset read, was ${viewModel.locationSetup.value}",
                viewModel.locationSetup.value is LocationSetupState.Checking,
            )

            runCurrent()
            assertEquals(LocationSetupReason.NO_LOCATION, viewModel.locationSetup.value.requiredReason())
        }

    private fun TestScope.viewModel(catalog: BangladeshAreaCatalog): AppViewModel = AppViewModel(
        userPreferencesRepository = FakeUserPreferencesRepository(),
        startup = StartupSnapshot(
            storedLocationSource = storedLocation,
            prayerSettingsSource = MutableStateFlow(PrayerSettings.DEFAULT),
            scope = backgroundScope,
        ),
        areaCatalog = catalog,
    )

    private fun LocationSetupState.requiredReason(): LocationSetupReason? =
        (this as? LocationSetupState.Required)?.reason

    private fun LocationSetupState.completeLocation(): LocationInfo? =
        (this as? LocationSetupState.Complete)?.location

    /** A catalog whose answer never arrives until the test says so. */
    private class HeldCatalog(private val dataset: BangladeshLocationDataset?) : BangladeshAreaCatalog {

        val gate = CompletableDeferred<Unit>()

        var started: Boolean = false
            private set

        override suspend fun dataset(): BangladeshLocationDataset? {
            started = true
            gate.await()
            return dataset
        }
    }
}
