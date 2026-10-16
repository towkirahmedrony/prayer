package io.github.towkirahmedrony.salah.core.domain.dataset.sync

import org.junit.Assert.assertEquals
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

    // Exponential backoff ------------------------------------------------------

    @Test
    fun `consecutive transient failures back off exponentially`() {
        val policy = SyncThrottlePolicy(
            successIntervalMillis = 1000,
            failureIntervalMillis = 100,
            maxFailureIntervalMillis = 10_000,
        )

        assertEquals(100L, policy.delayMillis(failing(consecutiveFailures = 0)))
        assertEquals(200L, policy.delayMillis(failing(consecutiveFailures = 1)))
        assertEquals(400L, policy.delayMillis(failing(consecutiveFailures = 2)))
        assertEquals(800L, policy.delayMillis(failing(consecutiveFailures = 3)))
    }

    @Test
    fun `the backoff is capped so it cannot grow without bound`() {
        val policy = SyncThrottlePolicy(
            successIntervalMillis = 1000,
            failureIntervalMillis = 100,
            maxFailureIntervalMillis = 500,
        )

        assertEquals(500L, policy.delayMillis(failing(consecutiveFailures = 99)))
    }

    @Test
    fun `the number of failures does not change the successful interval`() {
        val policy = SyncThrottlePolicy(successIntervalMillis = 1000, failureIntervalMillis = 100)
        val state = DatasetSyncState(
            lastAttemptAtEpochMillis = 0,
            lastFailureCode = null,
            consecutiveFailures = 4,
        )

        assertEquals(1000L, policy.delayMillis(state))
    }

    @Test
    fun `injected jitter is added to the computed delay`() {
        val policy = SyncThrottlePolicy(
            successIntervalMillis = 1000,
            failureIntervalMillis = 100,
            jitter = { base -> base / 10 },
        )

        val success = DatasetSyncState(lastAttemptAtEpochMillis = 0, lastFailureCode = null)
        assertEquals(1100L, policy.delayMillis(success))
        assertEquals(110L, policy.delayMillis(failing(consecutiveFailures = 0)))
    }

    @Test
    fun `backoff suppresses a check that a single failure would have allowed`() {
        val policy = SyncThrottlePolicy(
            successIntervalMillis = 1000,
            failureIntervalMillis = 100,
            maxFailureIntervalMillis = 10_000,
        )
        // 100 * 2^4 = 1600ms, so at 1000ms the retry is still suppressed even though one failure allows it.
        val state = failing(consecutiveFailures = 4)

        assertFalse(policy.shouldCheck(state, nowEpochMillis = 1000, forced = false))
        assertTrue(policy.shouldCheck(state, nowEpochMillis = 1600, forced = false))
    }

    @Test
    fun `a capped backoff still eventually permits a retry`() {
        val policy = SyncThrottlePolicy(
            successIntervalMillis = 1000,
            failureIntervalMillis = 100,
            maxFailureIntervalMillis = 500,
            maxBackoffSteps = 3,
        )
        val state = failing(consecutiveFailures = 50)

        assertFalse(policy.shouldCheck(state, nowEpochMillis = 499, forced = false))
        assertTrue(policy.shouldCheck(state, nowEpochMillis = 500, forced = false))
    }

    private fun failing(consecutiveFailures: Int): DatasetSyncState = DatasetSyncState(
        lastAttemptAtEpochMillis = 0,
        lastFailureCode = "manifest_unavailable",
        consecutiveFailures = consecutiveFailures,
    )
}
