package io.github.towkirahmedrony.salah.data.prayerconfig

import io.github.towkirahmedrony.salah.core.domain.SalahDiagnosticEvents
import io.github.towkirahmedrony.salah.core.domain.prayerconfig.PrayerConfigActivationResult
import io.github.towkirahmedrony.salah.core.model.PrayerCalculationMethod
import io.github.towkirahmedrony.salah.core.model.PrayerName
import io.github.towkirahmedrony.salah.testing.RecordingLogger
import io.github.towkirahmedrony.salah.testing.sampleSnapshot
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The local store: revision rules enforced on disk, and a corrupt payload detected rather than loaded.
 *
 * Each read uses a fresh store instance so it goes back to the files rather than to the in-memory cache,
 * which is what makes these tests statements about what was actually persisted.
 */
class FilePrayerConfigStoreTest {

    private lateinit var root: File
    private val logger = RecordingLogger()

    @Before
    fun setUp() {
        root = Files.createTempDirectory("salah-prayer-config").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun store(): FilePrayerConfigStore = FilePrayerConfigStore(root, logger)

    @Test
    fun `an activated snapshot is read back`() = runTest {
        val snapshot = sampleSnapshot(
            method = PrayerCalculationMethod.EGYPTIAN,
            adjustments = mapOf(PrayerName.FAJR to 3),
        )

        val result = store().activate(snapshot)

        assertTrue(result.toString(), result is PrayerConfigActivationResult.Activated)
        val active = store().active()
        assertEquals(snapshot, active?.snapshot)
        assertEquals(snapshot.revisionId, active?.revisionId)
        assertEquals(snapshot.revisionNumber, active?.revisionNumber)
    }

    @Test
    fun `a newer revision replaces the stored one`() = runTest {
        store().activate(sampleSnapshot(revisionId = "rev-0001", revisionNumber = 1))
        store().activate(sampleSnapshot(revisionId = "rev-0002", revisionNumber = 2))

        assertEquals("rev-0002", store().active()?.revisionId)
    }

    @Test
    fun `an identical revision is already active`() = runTest {
        val snapshot = sampleSnapshot(revisionId = "rev-0001", revisionNumber = 1)
        store().activate(snapshot)

        val result = store().activate(snapshot)

        assertTrue(result.toString(), result is PrayerConfigActivationResult.AlreadyActive)
    }

    @Test
    fun `reusing a revision id with different content is refused`() = runTest {
        store().activate(sampleSnapshot(revisionId = "rev-0001", revisionNumber = 1))

        val result = store().activate(
            sampleSnapshot(
                revisionId = "rev-0001",
                revisionNumber = 1,
                method = PrayerCalculationMethod.TURKEY,
            ),
        )

        assertTrue(result.toString(), result is PrayerConfigActivationResult.Rejected)
        // And the stored revision is unchanged.
        assertEquals(PrayerCalculationMethod.KARACHI, store().active()?.snapshot?.globalProfile?.method)
    }

    @Test
    fun `a stale revision is refused`() = runTest {
        store().activate(sampleSnapshot(revisionId = "rev-0005", revisionNumber = 5))

        val result = store().activate(sampleSnapshot(revisionId = "rev-0002", revisionNumber = 2))

        assertTrue(result.toString(), result is PrayerConfigActivationResult.Rejected)
        assertEquals("rev-0005", store().active()?.revisionId)
    }

    @Test
    fun `a corrupt payload is reported rather than loaded`() = runTest {
        store().activate(sampleSnapshot())

        File(root, "payload.json").writeText("{ this is not json")

        assertNull(store().active())
        assertTrue(logger.has(SalahDiagnosticEvents.PRAYER_CONFIG_STORED_UNAVAILABLE))
    }

    @Test
    fun `a store with no configuration reports none`() = runTest {
        assertNull(store().active())
    }
}
