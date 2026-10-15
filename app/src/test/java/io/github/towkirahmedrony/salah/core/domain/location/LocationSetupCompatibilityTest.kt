package io.github.towkirahmedrony.salah.core.domain.location

import io.github.towkirahmedrony.salah.core.domain.dataset.DatasetActivationResult
import io.github.towkirahmedrony.salah.core.domain.dataset.DatasetPayload
import io.github.towkirahmedrony.salah.core.domain.dataset.DatasetVersion
import io.github.towkirahmedrony.salah.core.model.BangladeshAreaRef
import io.github.towkirahmedrony.salah.core.model.LocationInfo
import io.github.towkirahmedrony.salah.core.model.LocationOrigin
import io.github.towkirahmedrony.salah.data.dataset.LocalDatasetStore
import io.github.towkirahmedrony.salah.testing.FakeBangladeshAreaCatalog
import io.github.towkirahmedrony.salah.testing.RecordingLogger
import io.github.towkirahmedrony.salah.testing.syntheticDataset
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * The guarantee that matters to a user who has already chosen a place: **updating the dataset must not
 * cost them their location.**
 *
 * `LocationSetup.evaluate` turns a stored location the dataset no longer contains into
 * `Required(AREA_NOT_IN_DATASET)`, and the shell answers that by replacing the whole navigation graph
 * with the setup screen. That is the correct response to a location that genuinely cannot be resolved —
 * and it is a terrible response to one that could have been, if the dataset update had been refused
 * first. These tests hold the two apart:
 *
 *  - a *compatible* update activates, and the saved location keeps resolving;
 *  - an *incompatible* update is refused, so the saved location never becomes unresolvable in the first
 *    place;
 *  - and a refusal changes nothing about the user's location at all.
 *
 * The last point is structural rather than incidental: [LocationSetupState] is derived from the stored
 * location, and [LocalDatasetStore] takes that location only as an *input* to validation. It has no way
 * to write one, so no failed update can clear it.
 */
class LocationSetupCompatibilityTest {

    @get:Rule
    val temporaryFolder: TemporaryFolder = TemporaryFolder()

    private lateinit var root: File
    private lateinit var bundledDataset: BangladeshLocationDataset

    /** A stored location in Kumarkhali, Kushtia — an area present in the bundled fixture. */
    private lateinit var storedLocation: LocationInfo

    @Before
    fun setUp() {
        root = temporaryFolder.newFolder("bd_locations")
        bundledDataset = syntheticDataset(version = "bundled-v1")

        val division = requireNotNull(bundledDataset.area("BD10"))
        val district = requireNotNull(bundledDataset.area("BD1001"))
        val upazila = requireNotNull(bundledDataset.area("BD1001001"))
        storedLocation = requireNotNull(
            LocationInfo.create(
                label = "Kumarkhali",
                latitude = 23.86,
                longitude = 89.24,
                timeZoneId = "Asia/Dhaka",
                origin = LocationOrigin.AREA_SELECTION,
                area = BangladeshAreaRef.of(division, district, upazila),
            ),
        )
    }

    private fun store(logger: RecordingLogger = RecordingLogger()): LocalDatasetStore = LocalDatasetStore(
        root = root,
        bundled = FakeBangladeshAreaCatalog(bundledDataset),
        logger = logger,
        clock = CLOCK,
    )

    @Test
    fun `a saved location resolves before any update`() {
        val dataset = bundledDataset

        assertEquals(LocationSetupState.Complete(storedLocation), LocationSetup.evaluate(storedLocation, dataset))
    }

    @Test
    fun `a saved location still resolves after a compatible dataset is activated`() = runTest {
        val store = store()
        val compatible = payload(
            version = "v2",
            areas = areasTextOf(bundledDataset) + "\n" + extraUpazilaLine(),
        )

        val result = store.activate(compatible, storedArea = storedLocation.area)

        assertTrue(result.toString(), result is DatasetActivationResult.Activated)
        assertEquals(
            "the saved location must keep resolving against the new dataset",
            LocationSetupState.Complete(storedLocation),
            LocationSetup.evaluate(storedLocation, store.dataset()),
        )
    }

    @Test
    fun `the saved location is resolved by identity, not by its label`() = runTest {
        // The same places, renamed and repositioned slightly — a label correction and a coordinate
        // refinement are ordinary data edits and must not disturb a saved location.
        val renamed = payload(
            version = "v2",
            areas = areasTextOf(bundledDataset)
                .replace("Kumarkhali", "Kumarkhali (renamed)")
                .replace("23.86", "23.88"),
        )

        val result = store().activate(renamed, storedArea = storedLocation.area)

        assertTrue(result.toString(), result is DatasetActivationResult.Activated)
        assertEquals(
            LocationSetupState.Complete(storedLocation),
            LocationSetup.evaluate(storedLocation, store().dataset()),
        )
    }

    @Test
    fun `an update that removes the saved location is refused`() = runTest {
        val store = store()

        val result = store.activate(
            payload = payload(version = "v2", areas = areasTextOf(bundledDataset).filterOut("BD1001001")),
            storedArea = storedLocation.area,
        )

        assertTrue(result.toString(), result is DatasetActivationResult.Rejected)
        val codes = (result as DatasetActivationResult.Rejected).problems.map { it.code }
        assertTrue("expected the stored location to be protected, got $codes", "stored_location_would_break" in codes)
    }

    @Test
    fun `after a refused update the saved location is untouched and still resolves`() = runTest {
        val store = store()
        val before = storedLocation

        store.activate(
            payload = payload(version = "v2", areas = areasTextOf(bundledDataset).filterOut("BD1001001")),
            storedArea = storedLocation.area,
        )

        // The dataset in force is still the one that contains the area, so the user is not sent back to
        // setup — which is the failure this whole seam exists to prevent.
        assertEquals(before, storedLocation)
        assertEquals(
            LocationSetupState.Complete(storedLocation),
            LocationSetup.evaluate(storedLocation, store().dataset()),
        )
    }

    @Test
    fun `an update that removes the whole district is refused`() = runTest {
        // A coarser removal than losing the upazila: the district the location names is gone too.
        val result = store().activate(
            payload = payload(
                version = "v2",
                areas = areasTextOf(bundledDataset)
                    .filterOut("BD1001001")
                    .filterOut("BD1001"),
            ),
            storedArea = storedLocation.area,
        )

        assertTrue(result.toString(), result is DatasetActivationResult.Rejected)
    }

    @Test
    fun `a first run still requires setup when an applied dataset is active`() = runTest {
        val store = store()
        store.activate(payload(version = "v2", areas = areasTextOf(bundledDataset) + "\n" + extraUpazilaLine()))

        // No location has been chosen, and that must still be reported as "choose one" — never as an
        // unreadable dataset, which is a different problem with a different message.
        val state = LocationSetup.evaluate(stored = null, dataset = store.dataset())

        assertEquals(LocationSetupState.Required(reason = LocationSetupReason.NO_LOCATION), state)
        assertTrue(state.requiresSetup)
    }

    @Test
    fun `the state reported for an unresolvable area carries the location it rejected`() {
        val ghost = requireNotNull(
            LocationInfo.create(
                label = "Ghost",
                latitude = 23.86,
                longitude = 89.24,
                timeZoneId = "Asia/Dhaka",
                origin = LocationOrigin.AREA_SELECTION,
                area = BangladeshAreaRef("BD10", "Khulna", "BD1001", "Kushtia", "BD9999999", "Ghost"),
            ),
        )

        val state = LocationSetup.evaluate(ghost, bundledDataset)

        assertTrue(state is LocationSetupState.Required)
        val required = state as LocationSetupState.Required
        assertEquals(LocationSetupReason.AREA_NOT_IN_DATASET, required.reason)
        // The rejected location travels with the state, which is what lets the screen explain itself
        // instead of showing a bare first-launch prompt.
        assertNotNull(required.stored)
        assertEquals("Ghost", required.stored?.label)
    }

    // Helpers ---------------------------------------------------------------------------------------

    private fun payload(version: String, areas: String): DatasetPayload = DatasetPayload(
        version = DatasetVersion.of(version),
        areasText = areas,
        boundaryText = boundaryTextOf(bundledDataset),
    )

    private fun areasTextOf(dataset: BangladeshLocationDataset): String = dataset.areas.joinToString("\n") { area ->
        listOf(
            area.level.name,
            area.id,
            area.parentId,
            area.name,
            area.banglaName.orEmpty(),
            area.latitude.toString(),
            area.longitude.toString(),
        ).joinToString("\t")
    }

    private fun boundaryTextOf(dataset: BangladeshLocationDataset): String =
        dataset.boundaryRings.flatMapIndexed { index, ring ->
            ring.map { point -> "$index\t${point.latitude}\t${point.longitude}" }
        }.joinToString("\n")

    private fun extraUpazilaLine(): String = listOf(
        "UPAZILA", "BD1001003", "BD1001", "Newly added", "", "23.70", "89.10",
    ).joinToString("\t")

    private fun String.filterOut(id: String): String =
        lineSequence().filterNot { it.split('\t').getOrNull(1)?.trim() == id }.joinToString("\n")

    private class StillClock : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = Instant.parse("2026-10-15T00:00:00Z")
    }

    private companion object {
        val CLOCK: Clock = StillClock()
    }
}
