package io.github.towkirahmedrony.salah.core.domain.dataset.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The throttle, which is what stops "check at start-up and on foreground" from being a poll.
 *
 * Two intervals because the two cases cost different things: a successful check has just confirmed the
 * current publication, while a failed one may simply have been offline and deserves a prompt retry.
 * A user-initiated check is never suppressed.
 */
class SyncThrottlePolicyTest {

    private val policy = SyncThrottlePolicy(
        successIntervalMillis = 1000,
        failureIntervalMillis = 100,
    )

    @Test
    fun `with no prior attempt a check runs`() {
        assertTrue(policy.shouldCheck(DatasetSyncState(), nowEpochMillis = 0, forced = false))
    }

    @Test
    fun `a recent success suppresses the next check`() {
        val state = DatasetSyncState(lastAttemptAtEpochMillis = 10_000, lastFailureCode = null)

        assertFalse(policy.shouldCheck(state, nowEpochMillis = 10_500, forced = false))
        assertTrue(policy.shouldCheck(state, nowEpochMillis = 11_000, forced = false))
    }

    @Test
    fun `a recent failure still allows an early retry`() {
        val state = DatasetSyncState(lastAttemptAtEpochMillis = 10_000, lastFailureCode = "manifest_unavailable")

        assertFalse(policy.shouldCheck(state, nowEpochMillis = 10_050, forced = false))
        assertTrue(policy.shouldCheck(state, nowEpochMillis = 10_100, forced = false))
    }

    @Test
    fun `a forced check is never suppressed`() {
        val state = DatasetSyncState(lastAttemptAtEpochMillis = 10_000, lastFailureCode = null)

        assertTrue(policy.shouldCheck(state, nowEpochMillis = 10_001, forced = true))
    }

    @Test
    fun `the default intervals are hours for success and minutes for failure`() {
        val defaults = SyncThrottlePolicy()

        assertTrue(defaults.successIntervalMillis >= defaults.failureIntervalMillis)
        assertTrue(defaults.failureIntervalMillis in 1..(60L * 60 * 1000))
    }
}
