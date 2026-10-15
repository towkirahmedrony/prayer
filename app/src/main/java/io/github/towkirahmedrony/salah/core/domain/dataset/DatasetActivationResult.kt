package io.github.towkirahmedrony.salah.core.domain.dataset

/**
 * What happened when a candidate payload was offered for activation.
 *
 * Four outcomes, and the two that matter most are the ones that change nothing:
 *
 *  - [Rejected] means the payload failed validation. **It is not a failure state and it does not touch
 *    the active dataset** — it is the ordinary answer to a malformed, incomplete or incompatible
 *    candidate, and the app carries on with what it already had.
 *  - [AlreadyActive] means the candidate is the dataset already in use, so nothing was read, written
 *    or parsed. This is the outcome a future publication check will hit almost every time, and it is
 *    the reason an unchanged dataset costs no work.
 *
 * [Failed] is reserved for the environment rather than the payload: the storage could not be written.
 * It also leaves the active dataset untouched.
 */
sealed interface DatasetActivationResult {

    /** The fully-resolved identity of a candidate, for the two outcomes that leave it in place. */
    val active: ActiveDataset?

    /** The payload passed every check and is now the active dataset. */
    data class Activated(override val active: ActiveDataset) : DatasetActivationResult

    /** The payload is the dataset already in use. Nothing was written. */
    data class AlreadyActive(override val active: ActiveDataset) : DatasetActivationResult

    /** The payload is not acceptable. Every reason is reported, and the active dataset is unchanged. */
    data class Rejected(val problems: List<DatasetProblem>) : DatasetActivationResult {
        override val active: ActiveDataset? get() = null
    }

    /** The payload was acceptable but could not be stored. The active dataset is unchanged. */
    data class Failed(val detail: String) : DatasetActivationResult {
        override val active: ActiveDataset? get() = null
    }
}
