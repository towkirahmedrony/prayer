package io.github.towkirahmedrony.salah.core.model

/**
 * A window of time during which prayer is prohibited, together with the rule set that decided it.
 *
 * No instance of this type is produced anywhere in this phase: the rules are not verified yet.
 * The type exists so the boundary is in place and the UI can report the honest state through
 * [ProhibitedPeriodsState.NotAvailable] instead of showing invented intervals.
 */
data class ProhibitedPeriod(
    val startsAt: java.time.Instant,
    val endsAt: java.time.Instant,
    /** Identifier of the reviewed rule set this window was derived from. */
    val ruleSetId: String,
    /** Human readable explanation shown to the user. */
    val explanation: String,
)

/** State of the prohibited prayer-time section. */
sealed interface ProhibitedPeriodsState {
    data class Available(val periods: List<ProhibitedPeriod>) : ProhibitedPeriodsState

    data class NotAvailable(
        val reason: UnavailabilityReason,
        val detail: String? = null,
    ) : ProhibitedPeriodsState
}
