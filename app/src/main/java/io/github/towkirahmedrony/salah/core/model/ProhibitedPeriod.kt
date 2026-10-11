package io.github.towkirahmedrony.salah.core.model

import io.github.towkirahmedrony.salah.core.domain.prohibited.ProhibitedWindowKind
import java.time.ZonedDateTime

/**
 * A window of time during which prayer is prohibited, together with the rule set that decided it.
 *
 * A window is only ever produced by
 * [io.github.towkirahmedrony.salah.core.domain.prohibited.ProhibitedPeriodCalculator] from a
 * reviewed rule set. No production rule set has been reviewed yet, so no instance of this type
 * reaches the UI; [ProhibitedPeriodsState.NotAvailable] is reported instead. The type exists so
 * that the boundary is in place, the state is representable, and no invented interval can ever
 * be shown in its place.
 *
 * Times are [ZonedDateTime] in the prayer location's zone, exactly like [PrayerSchedule], so a
 * window cannot be misread through the device's own time zone.
 */
data class ProhibitedPeriod(
    val startsAt: ZonedDateTime,
    val endsAt: ZonedDateTime,
    /** Identifier of the reviewed rule set this window was derived from. */
    val ruleSetId: String,
    /** Which rule produced this window. */
    val kind: ProhibitedWindowKind,
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
