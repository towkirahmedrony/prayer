package io.github.towkirahmedrony.salah.data.dataset

import io.github.towkirahmedrony.salah.core.domain.dataset.DatasetActivationResult
import io.github.towkirahmedrony.salah.core.domain.dataset.DatasetPayload
import io.github.towkirahmedrony.salah.core.domain.dataset.DatasetSource
import io.github.towkirahmedrony.salah.core.domain.dataset.DatasetVersion
import io.github.towkirahmedrony.salah.core.domain.SalahDiagnosticEvents
import io.github.towkirahmedrony.salah.core.domain.location.BangladeshLocationDataset
import io.github.towkirahmedrony.salah.core.model.BangladeshAreaRef
import io.github.towkirahmedrony.salah.testing.FakeBangladeshAreaCatalog
import io.github.towkirahmedrony.salah.testing.RecordingLogger
import io.github.towkirahmedrony.salah.testing.syntheticDataset
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
 * The local dataset foundation, against the real filesystem.
 *
 * Every test here exercises [LocalDatasetStore] as it will actually behave — writing payload
 * directories, renaming a metadata record, reading the bytes back — rather than against a fake. The
 * guarantees worth having are all about what survives an interruption, and an interruption cannot be
 * faked convincingly: what is being asserted is that *the files on disk* still describe a usable
 * dataset. So the tests corrupt and truncate real files, and build a second store to model the app
 * being started again.
 *
 * Payload texts are produced by re-serialising the bundled fixture rather than by typing tab-separated
 * rows by hand. That is deliberate: it makes the identity of a candidate exactly the identity of the
 * dataset it was derived from, so a test that then removes one area is testing the removal and nothing
 * else.
 */
class LocalDatasetStoreTest {

    @get:Rule
    val temporaryFolder: TemporaryFolder = TemporaryFolder()

    private lateinit var root: File
    private lateinit var bundledDataset: BangladeshLocationDataset
    private lateinit var bundledCatalog: FakeBangladeshAreaCatalog
    private lateinit var logger: RecordingLogger
    private val clock = MutableClock()

    /** The area of the "saved location" used by the compatibility tests. */
    private val storedArea = BangladeshAreaRef(
        divisionId = "BD10",
        divisionName = "Khulna",
        districtId = "BD1001",
        districtName = "Kushtia",
        upazilaId = "BD1001001",
        upazilaName = "Kumarkhali",
    )

    @Before
    fun setUp() {
        root = temporaryFolder.newFolder("bd_locations")
        bundledDataset = syntheticDataset(version = "bundled-v1")
        bundledCatalog = FakeBangladeshAreaCatalog(bundledDataset)
        logger = RecordingLogger()
    }

    // The floor ------------------------------------------------------------------------------------

    @Test
    fun `with nothing applied the bundled dataset is the active one`() = runTest {
        val store = store()

        val active = store.activeDataset()

        assertNotNull(active)
        assertEquals(DatasetVersion.of("bundled-v1"), active!!.version)
        assertEquals(DatasetSource.BUNDLED, active.source)
        assertNull("the bundled dataset carries no checksum", active.checksum)
        assertEquals(bundledDataset, store.dataset())
    }

    @Test
    fun `with nothing applied and no readable bundled dataset there is no dataset`() = runTest {
        bundledCatalog.dataset = null

        val store = store()

        assertNull(store.dataset())
        assertNull(store.activeDataset())
    }

    @Test
    fun `the bundled dataset is read once per process`() = runTest {
        val store = store()

        store.dataset()
        store.dataset()
        store.dataset()

        assertEquals("the parsed dataset must be cached", 1, bundledCatalog.loads)
    }

    // Activation and persistence ------------------------------------------------------------------

    @Test
    fun `a valid payload becomes the active dataset`() = runTest {
        val store = store()
        val candidate = payload(version = "v2")

        val result = store.activate(candidate)

        assertTrue(result.toString(), result is DatasetActivationResult.Activated)
        val active = result.active!!
        assertEquals(DatasetVersion.of("v2"), active.version)
        assertEquals(DatasetSource.APPLIED, active.source)
        assertEquals(candidate.checksum(), active.checksum)
        assertEquals("v2", store.dataset()?.version)
    }

    @Test
    fun `the active dataset is identified after a restart`() = runTest {
        store().activate(payload(version = "v2"))

        // A second instance with its own fresh cache: this is what the app does when it starts again.
        val restarted = store()

        val active = restarted.activeDataset()
        assertNotNull(active)
        assertEquals(DatasetVersion.of("v2"), active!!.version)
        assertEquals(DatasetSource.APPLIED, active.source)
        assertEquals("v2", restarted.dataset()?.version)
        assertTrue(
            "a payload that parsed cleanly must reload cleanly",
            restarted.dataset()?.rejectedLines?.isEmpty() == true,
        )
    }

    @Test
    fun `the record names the payload checksum that was written`() = runTest {
        val candidate = payload(version = "v2")

        store().activate(candidate)

        val record = File(root, "active.tsv").readText()
        assertTrue("the record must name the payload: $record", record.contains(candidate.checksum()))
        assertTrue("the record must name the version: $record", record.contains("v2"))
        assertTrue(
            "the payload directory must exist under its checksum",
            File(File(root, "payloads"), candidate.checksum()).isDirectory,
        )
    }

    @Test
    fun `offering the dataset that is already active changes nothing at all`() = runTest {
        val store = store()
        val candidate = payload(version = "v2")
        store.activate(candidate)

        val recordBefore = recordBytes()
        val payloadsBefore = payloadDirectories()

        // A fresh instance, so the in-memory cache cannot be what answers.
        val result = store().activate(candidate)

        assertTrue(result.toString(), result is DatasetActivationResult.AlreadyActive)
        assertEquals(DatasetVersion.of("v2"), result.active!!.version)
        assertEquals("the record must not be rewritten", recordBefore.toList(), recordBytes().toList())
        assertEquals("no payload directory may be added", payloadsBefore, payloadDirectories())
    }

    @Test
    fun `a payload with an unknown version is still accepted when the contents are new`() = runTest {
        // The version is opaque, so nothing here tries to order two of them; a different version is
        // simply a different dataset, and what makes it acceptable is the validation, not the string.
        val store = store()

        val result = store.activate(payload(version = "BGD-COD-AB v04 (2027-01-01)"))

        assertTrue(result.toString(), result is DatasetActivationResult.Activated)
    }

    // Rejection leaves everything alone ------------------------------------------------------------

    @Test
    fun `a payload that would break the stored location is rejected`() = runTest {
        val store = store()

        // The upazila the saved location depends on is gone.
        val candidate = payload(
            version = "v2",
            areas = areasTextOf(bundledDataset).filterOut("BD1001001"),
        )

        val result = store.activate(candidate, storedArea = storedArea)

        assertTrue(result.toString(), result is DatasetActivationResult.Rejected)
        val codes = (result as DatasetActivationResult.Rejected).problems.map { it.code }
        assertTrue("expected a stored-location problem, got $codes", "stored_location_would_break" in codes)
    }

    @Test
    fun `a rejected payload leaves the active dataset and the record untouched`() = runTest {
        val store = store()
        store.activate(payload(version = "v2"))
        val recordBefore = recordBytes()

        val rejected = store.activate(
            payload = payload(version = "v3", areas = areasTextOf(bundledDataset).filterOut("BD1001001")),
            storedArea = storedArea,
        )

        assertTrue(rejected.toString(), rejected is DatasetActivationResult.Rejected)
        assertEquals("the record must not change", recordBefore.toList(), recordBytes().toList())
        assertEquals("the active dataset must not change", "v2", store().dataset()?.version)
    }

    @Test
    fun `a payload whose contents do not match the published checksum is rejected`() = runTest {
        val result = store().activate(
            payload = payload(version = "v2"),
            expectedChecksum = "0000000000000000000000000000000000000000000000000000000000000000",
        )

        assertTrue(result.toString(), result is DatasetActivationResult.Rejected)
        assertEquals(
            listOf("checksum_mismatch"),
            (result as DatasetActivationResult.Rejected).problems.map { it.code },
        )
    }

    @Test
    fun `a payload that reuses the active version with different contents is rejected`() = runTest {
        val store = store()
        store.activate(payload(version = "v2"))

        val result = store().activate(
            payload = payload(
                version = "v2",
                areas = areasTextOf(bundledDataset) + "\n" + extraUpazilaLine(),
            ),
        )

        assertTrue(result.toString(), result is DatasetActivationResult.Rejected)
        assertEquals(
            listOf("version_reused_with_different_content"),
            (result as DatasetActivationResult.Rejected).problems.map { it.code },
        )
    }

    @Test
    fun `a rejection never falls through to a different dataset`() = runTest {
        val store = store()

        val result = store.activate(DatasetPayload(version = DatasetVersion.of("v2"), areasText = "", boundaryText = ""))

        assertTrue(result.toString(), result is DatasetActivationResult.Rejected)
        assertNull("a rejected candidate must not be reported as active", result.active)
        assertEquals("bundled-v1", store.dataset()?.version)
    }

    // Crash and corruption recovery -----------------------------------------------------------------

    @Test
    fun `an interrupted write leaves a stray staging directory that is ignored and cleaned up`() = runTest {
        store().activate(payload(version = "v2"))

        // A write that died between creating its staging directory and publishing it. Nothing refers
        // to it, which is the whole reason a partial payload cannot become the active one.
        val staging = File(File(root, "payloads"), ".staging-999-abcdef01")
        staging.mkdirs()
        File(staging, DatasetPayload.AREAS_PART).writeText("DIVISION\tbroken\tBD\tHalf written")

        val restarted = store()

        assertEquals("the active dataset must be unaffected", "v2", restarted.dataset()?.version)
        assertTrue("the staging directory must be discarded", !staging.exists())
    }

    @Test
    fun `a corrupt active payload recovers to the previous dataset`() = runTest {
        val v2 = payload(version = "v2")
        val v3 = payload(version = "v3", areas = areasTextOf(bundledDataset) + "\n" + extraUpazilaLine())
        store().activate(v2)
        store().activate(v3)

        // Truncate the payload that is now active, as an interrupted restore or storage damage would.
        File(File(File(root, "payloads"), v3.checksum()), DatasetPayload.AREAS_PART).writeText("DIVISION\tBD10")

        val restarted = store()

        assertEquals("the previous dataset must be used", "v2", restarted.dataset()?.version)
        assertEquals(DatasetVersion.of("v2"), restarted.activeDataset()?.version)
    }

    @Test
    fun `a corrupt active and previous payload recovers to the bundled dataset`() = runTest {
        val v2 = payload(version = "v2")
        val v3 = payload(version = "v3", areas = areasTextOf(bundledDataset) + "\n" + extraUpazilaLine())
        store().activate(v2)
        store().activate(v3)

        for (candidate in listOf(v2, v3)) {
            File(File(File(root, "payloads"), candidate.checksum()), DatasetPayload.AREAS_PART).writeText("junk")
        }

        val restarted = store()

        assertEquals("bundled-v1", restarted.dataset()?.version)
        assertEquals(DatasetSource.BUNDLED, restarted.activeDataset()?.source)
    }

    @Test
    fun `a payload whose bytes do not match its directory name is not loaded`() = runTest {
        // The directory is named by the checksum of its contents, so editing a file in place makes the
        // name a false claim — and the name is what the record pointed at.
        val v2 = payload(version = "v2")
        store().activate(v2)

        val areasFile = File(File(File(root, "payloads"), v2.checksum()), DatasetPayload.AREAS_PART)
        areasFile.writeText(areasFile.readText().replace("Kumarkhali", "Tampered"))

        val restarted = store()

        assertEquals("bundled-v1", restarted.dataset()?.version)
        assertTrue(
            "the recovery must be reported",
            logger.has(SalahDiagnosticEvents.DATASET_APPLIED_UNAVAILABLE),
        )
    }

    @Test
    fun `a record naming a payload that is not on disk falls back to the bundled dataset`() = runTest {
        // The metadata record is a pointer, and it is checked rather than trusted: this one names a
        // payload directory that was never published.
        val absentKey = "a".repeat(64)
        File(root, "active.tsv").writeText(
            "# written by a test\n" +
                "active\t$absentKey\tv9\t0\n",
        )

        val store = store()

        assertEquals("bundled-v1", store.dataset()?.version)
        assertEquals(DatasetSource.BUNDLED, store.activeDataset()?.source)
    }

    @Test
    fun `a record whose version disagrees with its payload is not trusted`() = runTest {
        val v2 = payload(version = "v2")
        store().activate(v2)

        // Relabel the active payload. The payload carries its own version, so the record claiming a
        // different one is detectable — and the app must not report a dataset it is not running.
        val key = v2.checksum()
        File(root, "active.tsv").writeText(
            "# written by a test\n" +
                "active\t$key\tv99\t0\n",
        )

        val store = store()

        assertEquals("bundled-v1", store.dataset()?.version)
    }

    @Test
    fun `a record with no active slot falls back to the bundled dataset`() = runTest {
        store().activate(payload(version = "v2"))
        File(root, "active.tsv").writeText("# empty record\n")

        assertEquals("bundled-v1", store().dataset()?.version)
    }

    @Test
    fun `an unreadable record is treated as no record`() = runTest {
        val restarted = store()

        // A directory where the record file should be: reading it cannot succeed, and the app must
        // survive that rather than fail to start.
        File(root, "active.tsv").mkdirs()

        assertNull(restarted.activeDataset()?.checksum)
        assertEquals("bundled-v1", restarted.dataset()?.version)
    }

    // Housekeeping -----------------------------------------------------------------------------------

    @Test
    fun `a superseded payload is pruned and the current one is kept`() = runTest {
        val v2 = payload(version = "v2")
        val v3 = payload(version = "v3", areas = areasTextOf(bundledDataset) + "\n" + extraUpazilaLine())
        store().activate(v2)
        store().activate(v3)

        assertEquals(
            "only the active and the previous payload may remain",
            setOf(v2.checksum(), v3.checksum()),
            payloadDirectories(),
        )
    }

    @Test
    fun `the dataset is never left without a usable version`() = runTest {
        val store = store()
        store.activate(payload(version = "v2"))

        // The invariant that matters most in this class: whatever the state of local storage, asking
        // for the dataset yields one that is usable.
        for (ignored in 0 until 3) {
            val dataset = store().dataset()
            assertNotNull(dataset)
            assertTrue("a dataset must always be usable", dataset!!.isUsable)
        }
    }

    // Helpers ---------------------------------------------------------------------------------------

    private fun store(): LocalDatasetStore = LocalDatasetStore(
        root = root,
        bundled = bundledCatalog,
        logger = logger,
        clock = clock,
    )

    private fun payload(
        version: String,
        areas: String = areasTextOf(bundledDataset),
        localities: String = localitiesTextOf(bundledDataset),
    ): DatasetPayload = DatasetPayload(
        version = DatasetVersion.of(version),
        areasText = areas,
        boundaryText = boundaryTextOf(bundledDataset),
        localitiesText = localities,
    )

    private fun recordBytes(): ByteArray = File(root, "active.tsv").readBytes()

    private fun payloadDirectories(): Set<String> =
        File(root, "payloads").listFiles().orEmpty()
            .filter { it.isDirectory && !it.name.startsWith(".") }
            .map { it.name }
            .toSet()

    /** Re-serialises the hierarchy, so a derived payload has exactly the identity it came from. */
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

    private fun localitiesTextOf(dataset: BangladeshLocationDataset): String =
        dataset.localities.joinToString("\n") { locality ->
            listOf(
                // The dataset spells kinds in upper case, which is what the parser's lookup expects.
                locality.kind.name,
                locality.districtId,
                locality.name,
                locality.latitude.toString(),
                locality.longitude.toString(),
            ).joinToString("\t")
        }

    /** An upazila the bundled fixture does not contain, so adding it is a pure addition. */
    private fun extraUpazilaLine(): String = listOf(
        "UPAZILA", "BD1001003", "BD1001", "Newly added", "", "23.70", "89.10",
    ).joinToString("\t")

    /** Removes the line defining [id], leaving the rest of the hierarchy alone. */
    private fun String.filterOut(id: String): String =
        lineSequence().filterNot { it.split('\t').getOrNull(1)?.trim() == id }.joinToString("\n")

    /** A clock a test can hold still, so an activation can be asserted rather than observed. */
    private class MutableClock : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = Instant.parse("2026-10-15T00:00:00Z")
    }
}
