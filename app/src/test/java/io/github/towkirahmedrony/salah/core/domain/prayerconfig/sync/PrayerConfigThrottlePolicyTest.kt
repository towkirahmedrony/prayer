package io.github.towkirahmedrony.salah.core.domain.prayerconfig.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The prayer-configuration throttle, mirroring the dataset throttle's contract.
 *
 * It exists as its own test rather than being folded into the synchroniser test because the policy is pure:
 * the properties that matter — a success suppresses for hours, the first failure retries in minutes, each
 * further consecutive failure doubles the wait, the wait is capped, and a forced check is never suppressed
 * — are all decidable without a source, a store or a clock.
 */
class PrayerConfigThrottlePolicyTest {

    @Test
    fun `with no prior attempt a check runs`() {
        assertTrue(PrayerConfigThrottlePolicy().shouldCheck(PrayerConfigSyncState(), 0, forced = false))
    }

    @Test
    fun `a successful check is suppressed for the success interval`() {
        val policy = PrayerConfigThrottlePolicy(successIntervalMillis = 1000, failureIntervalMillis = 100)
        val state = PrayerConfigSyncState(lastAttemptAtEpochMillis = 10_000, lastFailureCode = null)

        assertFalse(policy.shouldCheck(state, 10_500, forced = false))
        assertTrue(policy.shouldCheck(state, 11_000, forced = false))
    }

    @Test
    fun `consecutive failures back off exponentially and cap`() {
        val policy = PrayerConfigThrottlePolicy(
            successIntervalMillis = 1000,
            failureIntervalMillis = 100,
            maxFailureIntervalMillis = 400,
        )

        assertEquals(100L, policy.delayMillis(failing(0)))
        assertEquals(200L, policy.delayMillis(failing(1)))
        assertEquals(400L, policy.delayMillis(failing(2)))
        assertEquals(400L, policy.delayMillis(failing(20)))
    }

    @Test
    fun `a forced check is never suppressed even deep into a backoff`() {
        val policy = PrayerConfigThrottlePolicy(successIntervalMillis = 1000, failureIntervalMillis = 100)
        val state = failing(consecutiveFailures = 10)

        assertTrue(policy.shouldCheck(state, nowEpochMillis = 1, forced = true))
    }

    @Test
    fun `injected jitter is added to the computed delay`() {
        val policy = PrayerConfigThrottlePolicy(
            successIntervalMillis = 1000,
            failureIntervalMillis = 100,
            jitter = { base -> base / 10 },
        )

        assertEquals(1100L, policy.delayMillis(PrayerConfigSyncState(lastAttemptAtEpochMillis = 0, lastFailureCode = null)))
    }

    private fun failing(consecutiveFailures: Int): PrayerConfigSyncState = PrayerConfigSyncState(
        lastAttemptAtEpochMillis = 0,
        lastFailureCode = "manifest_unavailable",
        consecutiveFailures = consecutiveFailures,
    )
}
