package io.github.towkirahmedrony.salah.core.model

import io.github.towkirahmedrony.salah.core.domain.prohibited.ProhibitedWindowKind
import java.time.ZonedDateTime

/**
 * A window of time during which prayer is prohibited, together with the rule set that decided it.
 *
 * A window is only ever produced by
 * [io.github.towkirahmedrony.salah.core.domain.prohibited.ProhibitedPeriodCalculator] from an
 * applicable rule set — the application's configured policy, or one a qualified reviewer
 * confirmed. Nothing derives a window from a rule set that was never configured, so no invented
 * interval can be shown in place of a real one.
 *
 * Times are [ZonedDateTime] in the prayer location's zone, exactly like [PrayerSchedule], so a
 * window cannot be misread through the device's own time zone.
 */
data class ProhibitedPeriod(
    val startsAt: ZonedDateTime,
    val endsAt: ZonedDateTime,
    /** Identifier of the rule set this window was derived from. */
    val ruleSetId: String,
    /** Which rule produced this window. */
    val kind: ProhibitedWindowKind,
    /** Human readable explanation shown to the user. */
    val explanation: String,
)

/**
 * The convention the windows in a state were produced under.
 *
 * It travels with the windows so a screen can state which policy is in force, and whether anyone
 * has confirmed it, instead of the user having to assume that a displayed interval is
 * authoritative. See `docs/FORBIDDEN_PRAYER_TIMES.md`.
 */
data class ProhibitedConvention(
    val ruleSetId: String,
    /** Human readable name of the rule set, e.g. the application's own default policy. */
    val label: String,
    /** True only when the boundaries were confirmed by a qualified reviewer. */
    val isReviewed: Boolean,
    /** True when the rule set carries at least one reference that was verified. */
    val hasVerifiedSources: Boolean,
)

/** State of the prohibited prayer-time section. */
sealed interface ProhibitedPeriodsState {
    data class Available(
        val periods: List<ProhibitedPeriod>,
        /** The policy these windows were calculated under. */
        val convention: ProhibitedConvention,
    ) : ProhibitedPeriodsState

    data class NotAvailable(
        val reason: UnavailabilityReason,
        val detail: String? = null,
    ) : ProhibitedPeriodsState
}
