package io.github.towkirahmedrony.salah.data.dataset.sync

import io.github.towkirahmedrony.salah.core.domain.dataset.sync.DatasetSyncState
import io.github.towkirahmedrony.salah.testing.RecordingLogger
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The synchronisation state file.
 *
 * It is machine state, not user data, and it lives outside the backup set — so the properties that matter
 * are that a lost or corrupt file degrades to the default rather than throwing, and that a write is
 * atomic enough that a reader never sees a truncated value.
 */
class FileDatasetSyncStateStoreTest {

    @get:Rule
    val temporaryFolder: TemporaryFolder = TemporaryFolder()

    private val logger = RecordingLogger()

    @Test
    fun `with no file the default state is returned`() = runTest {
        val store = store(File(temporaryFolder.root, "absent.sync"))

        assertEquals(DatasetSyncState(), store.read())
    }

    @Test
    fun `a written state is read back`() = runTest {
        val store = store(File(temporaryFolder.root, "state.sync"))
        val state = DatasetSyncState(
            lastAttemptAtEpochMillis = 1_700_000_000_000L,
            lastAttemptedPublicationId = "bd_locations-2026-10-15",
            lastFailureCode = "checksum_mismatch",
        )

        store.write(state)

        assertEquals(state, store.read())
    }

    @Test
    fun `a successful state carries no failure code`() = runTest {
        val store = store(File(temporaryFolder.root, "state.sync"))

        store.write(DatasetSyncState(lastAttemptAtEpochMillis = 5L, lastFailureCode = null))

        assertNull(store.read().lastFailureCode)
    }

    @Test
    fun `a corrupt file is treated as the default state`() = runTest {
        val file = File(temporaryFolder.root, "corrupt.sync")
        file.writeText("not a state file at all\u0000\u0001")

        assertEquals(DatasetSyncState(), store(file).read())
    }

    @Test
    fun `a partially written line is ignored rather than parsed`() = runTest {
        val file = File(temporaryFolder.root, "partial.sync")
        file.writeText(
            "# header\n" +
                "last_attempt_at_epoch_millis\tnot-a-number\n" +
                "last_failure_code\tmanifest_unavailable\n",
        )

        val state = store(file).read()

        assertNull(state.lastAttemptAtEpochMillis)
        assertEquals("manifest_unavailable", state.lastFailureCode)
    }

    @Test
    fun `writing leaves no temporary file behind`() = runTest {
        val file = File(temporaryFolder.root, "state.sync")
        val store = store(file)

        store.write(DatasetSyncState(lastAttemptAtEpochMillis = 1L))
        store.write(DatasetSyncState(lastAttemptAtEpochMillis = 2L))

        assertTrue(file.isFile)
        assertFalse(File(temporaryFolder.root, "state.sync.tmp").exists())
        assertEquals(2L, store.read().lastAttemptAtEpochMillis)
    }

    private fun store(file: File): FileDatasetSyncStateStore = FileDatasetSyncStateStore(file, logger)
}
