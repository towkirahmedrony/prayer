package io.github.towkirahmedrony.salah.data.prayerconfig.sync

import io.github.towkirahmedrony.salah.core.domain.prayerconfig.sync.PrayerConfigSyncState
import io.github.towkirahmedrony.salah.testing.RecordingLogger
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The prayer-configuration state file.
 *
 * Same contract as the dataset state file: machine state, outside the backup set, total on read (a corrupt
 * or absent file degrades to the default), atomic on write, and forward/backward-compatible — a file from
 * before the freshness fields existed reads those fields as defaults.
 */
class FilePrayerConfigSyncStateStoreTest {

    @get:Rule
    val temporaryFolder: TemporaryFolder = TemporaryFolder()

    private val logger = RecordingLogger()

    @Test
    fun `with no file the default state is returned`() = runTest {
        val store = store(File(temporaryFolder.root, "absent.sync"))

        assertEquals(PrayerConfigSyncState(), store.read())
    }

    @Test
    fun `every field round-trips`() = runTest {
        val store = store(File(temporaryFolder.root, "state.sync"))
        val state = PrayerConfigSyncState(
            lastAttemptAtEpochMillis = 200L,
            lastAttemptedRevisionId = "rev-0004",
            lastFailureCode = "content_unavailable",
            lastSuccessAtEpochMillis = 100L,
            consecutiveFailures = 2,
        )

        store.write(state)

        assertEquals(state, store.read())
    }

    @Test
    fun `an older file reads the freshness fields as defaults`() = runTest {
        val file = File(temporaryFolder.root, "old.sync")
        file.writeText(
            "# header\n" +
                "last_attempt_at_epoch_millis\t1700000000000\n" +
                "last_attempted_revision_id\trev-0001\n",
        )

        val state = store(file).read()

        assertEquals(1_700_000_000_000L, state.lastAttemptAtEpochMillis)
        assertEquals("rev-0001", state.lastAttemptedRevisionId)
        assertNull(state.lastSuccessAtEpochMillis)
        assertEquals(0, state.consecutiveFailures)
    }

    @Test
    fun `a corrupt file degrades to the default rather than throwing`() = runTest {
        val file = File(temporaryFolder.root, "corrupt.sync")
        file.writeText("not a state file\u0000\u0001")

        assertEquals(PrayerConfigSyncState(), store(file).read())
    }

    @Test
    fun `writing leaves no temporary file behind`() = runTest {
        val file = File(temporaryFolder.root, "state.sync")
        val store = store(file)

        store.write(PrayerConfigSyncState(lastAttemptAtEpochMillis = 1L))
        store.write(PrayerConfigSyncState(lastAttemptAtEpochMillis = 2L))

        assertTrue(file.isFile)
        assertEquals(2L, store.read().lastAttemptAtEpochMillis)
    }

    private fun store(file: File): FilePrayerConfigSyncStateStore =
        FilePrayerConfigSyncStateStore(file, logger)
}
