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

    /** The manifest could not be fetched (network, timeout or an HTTP error). */
    MANIFEST_UNAVAILABLE("manifest_unavailable", transient = true),

    /** The manifest response could not be read as a manifest. */
    MANIFEST_MALFORMED("manifest_malformed", transient = false),

    /** The manifest describes a payload layout this build cannot read. */
    UNSUPPORTED_SCHEMA("unsupported_schema_version", transient = false),

    /** The publication is not published, so it must not be served to an app client. */
    PUBLICATION_NOT_PUBLISHED("publication_not_published", transient = false),

    /** The payload object could not be fetched. */
    PAYLOAD_UNAVAILABLE("payload_unavailable", transient = true),

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
 * **Why it is stored at all.** Two questions cannot be answered from the dataset alone: *when* the last
 * check happened (for throttling) and *which* remote publication was last attempted (so a publication
 * that was downloaded and then declined is not downloaded again on every launch). Neither is user data
 * and neither is backed up; see `docs/ARCHITECTURE.md` §13.
 */
data class DatasetSyncState(
    /** When a check last ran, or `null` when one never has. */
    val lastAttemptAtEpochMillis: Long? = null,

    /** The publication id the last attempt evaluated, or `null`. */
    val lastAttemptedPublicationId: String? = null,

    /** The failure code of the last attempt, or `null` when the last attempt succeeded. */
    val lastFailureCode: String? = null,
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
 * Two intervals, because the two cases cost very different things: a *successful* check has just adopted
 * (or confirmed) the current publication and there is nothing to gain from asking again soon, while a
 * *failed* check may simply have been offline and deserves a prompt retry. A user-initiated check
 * (`forced`) is never suppressed — an explicit "check now" that silently did nothing would be worse than
 * a redundant request.
 *
 * This is deliberately not a poll: the app checks at start-up and on foreground, at most once per
 * interval, and never on a timer or a recomposition.
 */
class SyncThrottlePolicy(
    /** How long a successful check suppresses the next one. Twelve hours by default. */
    val successIntervalMillis: Long = DEFAULT_SUCCESS_INTERVAL_MILLIS,

    /** How long a failed check suppresses the next one. Fifteen minutes by default. */
    val failureIntervalMillis: Long = DEFAULT_FAILURE_INTERVAL_MILLIS,
) {

    /** True when a check should run now. */
    fun shouldCheck(state: DatasetSyncState, nowEpochMillis: Long, forced: Boolean): Boolean {
        if (forced) return true
        val lastAttempt = state.lastAttemptAtEpochMillis ?: return true
        val interval = if (state.lastFailureCode != null) failureIntervalMillis else successIntervalMillis
        return nowEpochMillis - lastAttempt >= interval
    }

    companion object {
        const val DEFAULT_SUCCESS_INTERVAL_MILLIS: Long = 12L * 60 * 60 * 1000
        const val DEFAULT_FAILURE_INTERVAL_MILLIS: Long = 15L * 60 * 1000
    }
}
