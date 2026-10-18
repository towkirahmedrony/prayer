package io.github.towkirahmedrony.salah.core.domain.dataset.sync

import io.github.towkirahmedrony.salah.core.domain.dataset.ActiveDataset
import io.github.towkirahmedrony.salah.core.domain.dataset.DatasetProblem

/**
 * What set a synchronisation attempt in motion.
 *
 * Recorded and logged, and used by the throttle policy only to decide whether the *user* asked (in which
 * case nothing is suppressed). The trigger does not otherwise change behaviour: a check is the same
 * sequence of steps whoever asked for it, which is what keeps the count of code paths that can touch the
 * active dataset equal to one.
 */
enum class DatasetSyncTrigger {
    /** Application start-up, before the user has done anything. */
    APP_START,

    /** The app came to the foreground. */
    FOREGROUND,

    /** The user asked for a check explicitly. */
    MANUAL,
}

/** Why a check did not produce a new active dataset. */
enum class SyncFailureReason(val code: String, val transient: Boolean) {

    /** The manifest could not be fetched (network or an HTTP error). */
    MANIFEST_UNAVAILABLE("manifest_unavailable", transient = true),

    /**
     * The manifest endpoint itself does not exist — PostgREST answered 404 for the view.
     *
     * **Not the same as "no update".** An empty publication makes PostgREST answer `200 []`, which the
     * parser reports as no publication; a 404 means the view (or the grant that exposes it) is absent, so
     * no manifest was read at all. Reporting that as [DatasetSyncOutcome.UpToDate] would claim a
     * synchronisation that never happened. The active dataset is untouched either way.
     */
    ENDPOINT_NOT_FOUND("endpoint_not_found", transient = false),

    /**
     * The project refused the read (HTTP 401 or 403): a key, policy or grant problem rather than a
     * network one. It is not transient — repeating the same request cannot fix it — but it is retried on
     * the ordinary failure interval because a deployment change can.
     */
    ACCESS_DENIED("access_denied", transient = false),

    /** The request exceeded its timeout. Transient, and reported separately from a refused connection. */
    TIMEOUT("timeout", transient = true),

    /** The manifest response could not be read as a manifest. */
    MANIFEST_MALFORMED("manifest_malformed", transient = false),

    /** The manifest describes a payload layout this build cannot read. */
    UNSUPPORTED_SCHEMA("unsupported_schema_version", transient = false),

    /** The publication is not published, so it must not be served to an app client. */
    PUBLICATION_NOT_PUBLISHED("publication_not_published", transient = false),

    /** The payload object could not be fetched. */
    PAYLOAD_UNAVAILABLE("payload_unavailable", transient = true),

    /**
     * The payload object named by the publication does not exist (HTTP 404).
     *
     * A publication row that points at a missing object is an integrity problem with the release, not a
     * transient network failure: re-downloading the same path would fail identically, so it is reported
     * as terminal and not retried on every check.
     */
    PAYLOAD_NOT_FOUND("payload_not_found", transient = false),

    /** The payload exceeded the size this build accepts. */
    PAYLOAD_TOO_LARGE("payload_too_large", transient = false),

    /** The payload archive could not be decoded into a dataset. */
    PAYLOAD_MALFORMED("payload_malformed", transient = false),

    /** The decoded payload's checksum is not the one that was published for it. */
    CHECKSUM_MISMATCH("checksum_mismatch", transient = false),

    /** The payload's record counts disagree with the ones the publication asserted. */
    RECORD_COUNT_MISMATCH("record_count_mismatch", transient = false),

    /** The payload passed every check but the local store could not commit it. */
    ACTIVATION_FAILED("activation_failed", transient = false),

    /** The local synchronisation state could not be read or written. */
    STORAGE_FAILED("storage_failed", transient = true),
}

/**
 * The outcome of one synchronisation attempt.
 *
 * **"No update available" and "the sync failed" are different outcomes** ([UpToDate] versus [Failed]),
 * and so are "the payload was unacceptable" ([Rejected]) and "the environment failed" ([Failed]). Keeping
 * them apart is what lets Diagnostics and the tests distinguish a normal no-op from a problem worth
 * investigating, and it is why the result is a value the caller inspects rather than a thrown exception.
 */
sealed interface DatasetSyncOutcome {

    /** True when the active dataset is unchanged because there was nothing to apply. */
    val leavesDataUsable: Boolean get() = true

    /**
     * No remote dataset is configured. A normal state, not a failure: the app runs offline on its bundled
     * data exactly as before this feature existed.
     */
    data object NotConfigured : DatasetSyncOutcome

    /** A check ran too recently, so nothing was fetched. */
    data object Throttled : DatasetSyncOutcome

    /**
     * The device is clearly offline, so no request was attempted.
     *
     * Distinct from [Failed]: nothing was tried, so nothing failed, and the last-success timestamp is
     * deliberately left unchanged. It is not counted as an attempt either, so returning to connectivity
     * does not have to wait out a backoff window before the next real check.
     */
    data object Offline : DatasetSyncOutcome

    /**
     * Nothing needed to change: either the active dataset already matches the remote publication, or the
     * remote publication is one that was already attempted and declined. Either way no payload was
     * downloaded.
     */
    data class UpToDate(val active: ActiveDataset?) : DatasetSyncOutcome

    /** A validated remote publication is now the active dataset. */
    data class Activated(val active: ActiveDataset) : DatasetSyncOutcome

    /** The candidate payload failed validation. The active dataset is unchanged. */
    data class Rejected(val problems: List<DatasetProblem>) : DatasetSyncOutcome

    /** The attempt failed for an environmental or integrity reason. The active dataset is unchanged. */
    data class Failed(val reason: SyncFailureReason, val detail: String? = null) : DatasetSyncOutcome

    /** True when the attempt produced or confirmed a usable active dataset. */
    val isSuccess: Boolean get() = this is UpToDate || this is Activated

    /** True when the attempt was a genuine attempt that failed. */
    val isFailure: Boolean get() = this is Failed || this is Rejected
}

/**
 * The small amount of state a synchronisation keeps between runs.
 *
 * **Why it is stored at all.** Four questions cannot be answered from the dataset alone: *when* the last
 * check happened (for throttling and backoff), *when* it last *succeeded* (so the freshness the UI
 * reports is the freshness of real data, not of a failed attempt), *which* remote publication was last
 * attempted (so a publication that was downloaded and then declined is not downloaded again on every
 * launch), and *how many* attempts in a row have failed (so consecutive transient failures back off
 * instead of retrying at a fixed rate). None of it is user data and none of it is backed up; see
 * `docs/ARCHITECTURE.md` §13 and `docs/DATA_RELIABILITY.md`.
 *
 * **Last attempt and last success are different facts.** [lastAttemptAtEpochMillis] moves on every
 * attempt, success or failure; [lastSuccessAtEpochMillis] moves only when the active dataset was
 * confirmed or replaced by a validated publication. Reporting the former as if it were the latter is
 * how a UI ends up claiming "synced just now" after a failed request — which is exactly the confusion
 * keeping them apart prevents.
 */
data class DatasetSyncState(
    /** When a check last ran — success or failure — or `null` when one never has. */
    val lastAttemptAtEpochMillis: Long? = null,

    /** The publication id the last attempt evaluated, or `null`. */
    val lastAttemptedPublicationId: String? = null,

    /** The failure code of the last attempt, or `null` when the last attempt succeeded. */
    val lastFailureCode: String? = null,

    /**
     * When a check last confirmed or adopted the active dataset, or `null` when none ever has.
     *
     * Left untouched by a failed or refused attempt, so valid-but-stale data is reported as stale rather
     * than as freshly synchronised.
     */
    val lastSuccessAtEpochMillis: Long? = null,

    /**
     * How many consecutive *transient* failures have occurred since the last success.
     *
     * Drives the exponential backoff in [SyncThrottlePolicy]. Reset to `0` by any success. A terminal
     * failure (a malformed manifest, a checksum mismatch, a refused candidate) does not grow it: repeating
     * the same fetch could not fix it, so backing off further would only delay a legitimate new publication.
     */
    val consecutiveFailures: Int = 0,
)

/**
 * The outcome of a failed attempt, in the only two categories the retry policy distinguishes.
 *
 * A *terminal* failure is one that repeating the same fetch cannot fix — a malformed manifest, an
 * unsupported schema, an invalid checksum, a payload that fails validation. Retrying it on every launch
 * would spend the user's data on a download that will fail identically, so it is not re-attempted until
 * the remote publication changes. Everything else is transient and is retried after the short interval.
 */
fun SyncFailureReason.isTerminal(): Boolean = !transient

/**
 * Where the synchroniser keeps [DatasetSyncState]. A port so the synchroniser is testable with an
 * in-memory implementation and so the file format stays in the data layer.
 */
interface DatasetSyncStateStore {

    /** The stored state, or a default when nothing has been stored yet. Never throws. */
    suspend fun read(): DatasetSyncState

    /** Replaces the stored state. */
    suspend fun write(state: DatasetSyncState)
}

/**
 * How often a check may run.
 *
 * Two regimes, because the two cases cost very different things:
 *
 *  - a **successful** check has just adopted (or confirmed) the current publication, so there is nothing
 *    to gain from asking again soon and the next one is suppressed for [successIntervalMillis];
 *  - a **failed** check may simply have been offline, so it is retried after [failureIntervalMillis] —
 *    but each *consecutive* transient failure doubles that wait (exponential backoff, capped at
 *    [maxFailureIntervalMillis]), which is what stops a device that has been offline for an hour from
 *    issuing a retry on every foreground trip and producing a retry storm the moment connectivity returns.
 *
 * A user-initiated check (`forced`) is never suppressed — an explicit "check now" that silently did
 * nothing would be worse than a redundant request.
 *
 * **Jitter.** [jitter] adds a small, bounded amount to the computed delay so two devices that failed at
 * the same instant do not retry at the same instant. It is injected rather than drawn from a global RNG so
 * a test can pin it to zero and assert exact thresholds.
 *
 * This is deliberately not a poll: the app checks at start-up and on foreground, at most once per computed
 * interval, and never on a timer or a recomposition.
 */
class SyncThrottlePolicy(
    /** How long a successful check suppresses the next one. Twelve hours by default. */
    val successIntervalMillis: Long = DEFAULT_SUCCESS_INTERVAL_MILLIS,

    /** The delay after the first failed check, doubled on each further consecutive failure. */
    val failureIntervalMillis: Long = DEFAULT_FAILURE_INTERVAL_MILLIS,

    /** The ceiling the exponential failure backoff approaches but never exceeds. Six hours by default. */
    val maxFailureIntervalMillis: Long = DEFAULT_MAX_FAILURE_INTERVAL_MILLIS,

    /** The largest number of doublings applied, so the growth is provably bounded. */
    val maxBackoffSteps: Int = DEFAULT_MAX_BACKOFF_STEPS,

    /**
     * Extra milliseconds added to a computed delay, as a function of that delay (so it scales with the
     * interval). Defaults to none, which is what the deterministic tests rely on; the production wiring
     * supplies a small random fraction.
     */
    val jitter: (Long) -> Long = { 0L },
) {

    /** True when a check should run now. */
    fun shouldCheck(state: DatasetSyncState, nowEpochMillis: Long, forced: Boolean): Boolean {
        if (forced) return true
        val lastAttempt = state.lastAttemptAtEpochMillis ?: return true
        return nowEpochMillis - lastAttempt >= delayMillis(state)
    }

    /**
     * The suppression window after the last attempt, in milliseconds.
     *
     * Exposed so the retry policy is observable and directly testable rather than only implied by
     * [shouldCheck]. The value is the base interval for a success, or the exponentially-backed-off
     * interval for a failure, plus the injected [jitter].
     */
    fun delayMillis(state: DatasetSyncState): Long {
        val base = if (state.lastFailureCode == null) {
            successIntervalMillis.coerceAtLeast(0L)
        } else {
            failureBackoffMillis(state.consecutiveFailures)
        }
        return base + jitter(base).coerceAtLeast(0L)
    }

    /** The failure interval after [consecutiveFailures] consecutive transient failures, capped. */
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
