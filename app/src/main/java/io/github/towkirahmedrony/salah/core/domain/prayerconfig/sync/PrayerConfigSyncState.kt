package io.github.towkirahmedrony.salah.core.domain.prayerconfig.sync

import io.github.towkirahmedrony.salah.core.domain.prayerconfig.ActivePrayerConfig
import io.github.towkirahmedrony.salah.core.domain.prayerconfig.PrayerConfigProblem

/**
 * What set a synchronisation attempt in motion.
 *
 * Recorded and logged; it changes behaviour only in that a user-initiated check is never suppressed by the
 * throttle. A check is otherwise the same sequence whoever asked for it, keeping the number of code paths
 * that can touch the active configuration equal to one.
 */
enum class PrayerConfigSyncTrigger {
    APP_START,
    FOREGROUND,
    MANUAL,
}

/** Why a check did not produce a new active configuration. Mirrors the dataset vocabulary. */
enum class PrayerConfigFailureReason(val code: String, val transient: Boolean) {

    /** The manifest could not be fetched (network or an HTTP error). */
    MANIFEST_UNAVAILABLE("manifest_unavailable", transient = true),

    /**
     * The manifest endpoint itself does not exist (PostgREST 404). An empty slot is `200 []` and is
     * "nothing published"; a 404 means no manifest was read at all and must not be reported as up to date.
     */
    ENDPOINT_NOT_FOUND("endpoint_not_found", transient = false),

    /** The project refused the read (HTTP 401/403): a key, policy or grant problem, not a network one. */
    ACCESS_DENIED("access_denied", transient = false),

    /** The request exceeded its timeout. Transient, and distinct from a refused connection. */
    TIMEOUT("timeout", transient = true),

    MANIFEST_MALFORMED("manifest_malformed", transient = false),
    UNSUPPORTED_SCHEMA("unsupported_schema_version", transient = false),
    PUBLICATION_NOT_PUBLISHED("publication_not_published", transient = false),
    CONFIGURATION_ID_MISMATCH("configuration_id_mismatch", transient = false),
    INVALID_CHECKSUM("invalid_checksum", transient = false),
    REVISION_REUSED("revision_reused_with_different_content", transient = false),
    STALE_REVISION("stale_revision", transient = false),
    CONTENT_UNAVAILABLE("content_unavailable", transient = true),

    /**
     * The payload for the named revision is not served (404, or a revision that stopped being current
     * between the manifest and the content request). Not transient: the same fetch would fail again.
     */
    CONTENT_NOT_FOUND("content_not_found", transient = false),
    CONTENT_MALFORMED("content_malformed", transient = false),
    CHECKSUM_MISMATCH("checksum_mismatch", transient = false),
    INVALID_CONFIGURATION("invalid_configuration", transient = false),
    ACTIVATION_FAILED("activation_failed", transient = false),
    STORAGE_FAILED("storage_failed", transient = true),
}

/** A *terminal* failure cannot be fixed by repeating the same fetch, so it is not retried until the revision changes. */
fun PrayerConfigFailureReason.isTerminal(): Boolean = !transient

/**
 * The outcome of one synchronisation attempt.
 *
 * "No update available" ([UpToDate]) and "the sync failed" ([Failed]) are different outcomes, as are
 * "the publication was unacceptable" ([Rejected]) and "the environment failed" ([Failed]). Keeping them
 * apart is what lets Diagnostics and the tests tell a normal no-op from a problem worth investigating.
 */
sealed interface PrayerConfigSyncOutcome {

    /** No remote configuration is configured. The app runs on its bundled defaults exactly as before. */
    data object NotConfigured : PrayerConfigSyncOutcome

    /** A check ran too recently, so nothing was fetched. */
    data object Throttled : PrayerConfigSyncOutcome

    /**
     * The device is clearly offline, so no request was attempted. Distinct from [Failed]: nothing failed,
     * the last-success timestamp is unchanged, and it is not counted as an attempt.
     */
    data object Offline : PrayerConfigSyncOutcome

    /** Nothing needed to change: the accepted revision already matches, or the publication was already declined. */
    data class UpToDate(val active: ActivePrayerConfig?) : PrayerConfigSyncOutcome

    /**
     * A validated publication is now the active configuration.
     *
     * [droppedAssignments] is non-empty when the publication contained area overrides that named an
     * unknown area; those assignments were dropped and the rest was applied.
     */
    data class Activated(
        val active: ActivePrayerConfig,
        val droppedAssignments: List<PrayerConfigProblem> = emptyList(),
    ) : PrayerConfigSyncOutcome

    /** The candidate failed validation or the revision rules. The active configuration is unchanged. */
    data class Rejected(val problems: List<PrayerConfigProblem>) : PrayerConfigSyncOutcome

    /** The attempt failed for an environmental or integrity reason. The active configuration is unchanged. */
    data class Failed(
        val reason: PrayerConfigFailureReason,
        val detail: String? = null,
    ) : PrayerConfigSyncOutcome

    val isSuccess: Boolean get() = this is UpToDate || this is Activated
    val isFailure: Boolean get() = this is Failed || this is Rejected
}

/**
 * The small amount of state a synchronisation keeps between runs: when the last check ran, when one last
 * *succeeded*, which revision it evaluated, how many attempts in a row have failed, and the failure code
 * if it failed. Machine state only — not user data, and not backed up.
 *
 * Last attempt and last success are separate facts on purpose: a failed request must never be reported as
 * a fresh synchronisation, and the accepted configuration's freshness is the freshness of the last
 * revision that was actually applied.
 */
data class PrayerConfigSyncState(
    val lastAttemptAtEpochMillis: Long? = null,
    val lastAttemptedRevisionId: String? = null,
    val lastFailureCode: String? = null,

    /** When a check last confirmed or adopted the active configuration, or `null` when none ever has. */
    val lastSuccessAtEpochMillis: Long? = null,

    /** Consecutive *transient* failures since the last success; drives the exponential backoff. */
    val consecutiveFailures: Int = 0,
)

/** Where the synchroniser keeps [PrayerConfigSyncState]. A port so the synchroniser is testable in memory. */
interface PrayerConfigSyncStateStore {

    /** The stored state, or a default when nothing has been stored yet. Never throws. */
    suspend fun read(): PrayerConfigSyncState

    /** Replaces the stored state. */
    suspend fun write(state: PrayerConfigSyncState)
}

/**
 * How often a check may run, mirroring the dataset throttle policy and its constants.
 *
 * A successful check suppresses the next one for [successIntervalMillis]; a failed check is retried after
 * [failureIntervalMillis], doubled on each consecutive transient failure and capped at
 * [maxFailureIntervalMillis], so a device that stays offline is not retried at a fixed rate and does not
 * produce a retry storm when it reconnects. [jitter] spreads retries that would otherwise coincide; it is
 * injected so tests are deterministic. A user-initiated check (`forced`) is never suppressed.
 */
class PrayerConfigThrottlePolicy(
    val successIntervalMillis: Long = DEFAULT_SUCCESS_INTERVAL_MILLIS,
    val failureIntervalMillis: Long = DEFAULT_FAILURE_INTERVAL_MILLIS,
    val maxFailureIntervalMillis: Long = DEFAULT_MAX_FAILURE_INTERVAL_MILLIS,
    val maxBackoffSteps: Int = DEFAULT_MAX_BACKOFF_STEPS,
    val jitter: (Long) -> Long = { 0L },
) {

    fun shouldCheck(state: PrayerConfigSyncState, nowEpochMillis: Long, forced: Boolean): Boolean {
        if (forced) return true
        val lastAttempt = state.lastAttemptAtEpochMillis ?: return true
        return nowEpochMillis - lastAttempt >= delayMillis(state)
    }

    /** The suppression window after the last attempt, in milliseconds, including any injected jitter. */
    fun delayMillis(state: PrayerConfigSyncState): Long {
        val base = if (state.lastFailureCode == null) {
            successIntervalMillis.coerceAtLeast(0L)
        } else {
            failureBackoffMillis(state.consecutiveFailures)
        }
        return base + jitter(base).coerceAtLeast(0L)
    }

    private fun failureBackoffMillis(consecutiveFailures: Int): Long {
        val ceiling = maxFailureIntervalMillis.coerceAtLeast(0L)
        var value = failureIntervalMillis.coerceAtLeast(0L).coerceAtMost(ceiling)
        var steps = consecutiveFailures.coerceIn(0, maxBackoffSteps)
        while (steps-- > 0 && value < ceiling) {
            value = (value * 2).coerceAtMost(ceiling)
        }
        return value
    }

    companion object {
        const val DEFAULT_SUCCESS_INTERVAL_MILLIS: Long = 12L * 60 * 60 * 1000
        const val DEFAULT_FAILURE_INTERVAL_MILLIS: Long = 15L * 60 * 1000
        const val DEFAULT_MAX_FAILURE_INTERVAL_MILLIS: Long = 6L * 60 * 60 * 1000
        const val DEFAULT_MAX_BACKOFF_STEPS: Int = 5
    }
}
