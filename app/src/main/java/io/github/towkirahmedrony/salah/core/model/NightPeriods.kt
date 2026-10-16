package io.github.towkirahmedrony.salah.core.model

import java.time.Duration
import java.time.ZonedDateTime

/**
 * The night interval for one local date, together with the boundaries derived from it.
 *
 * **What the night interval is.** It is the span from the day's *effective* Maghrib to the
 * *following* day's *effective* Fajr — the two prayer times the timetable actually shows, including
 * any method offset or manual correction, because that is what the user's mosque timetable follows.
 * It is therefore **not** the astronomical night (sunset to sunrise) and **not** the civil night
 * (clock midnight to clock midnight); the three are described separately in
 * `docs/SOLAR_AND_NIGHT_PERIODS.md`.
 *
 * Times are [ZonedDateTime] in the prayer location's zone, exactly like [PrayerSchedule], so the
 * interval cannot be misread through the device's own time zone.
 *
 * @param startsAt the effective Maghrib that opens the interval.
 * @param endsAt the following effective Fajr that closes it.
 * @param duration the length of the interval.
 * @param islamicMidnight the exact midpoint of the interval.
 * @param lastThirdStartsAt the instant the final third of the interval begins.
 * @param tahajjudStartsAt the instant the Tahajjud period begins, under the applicable convention.
 */
data class NightPeriods(
    val startsAt: ZonedDateTime,
    val endsAt: ZonedDateTime,
    val duration: Duration,
    val islamicMidnight: ZonedDateTime,
    val lastThirdStartsAt: ZonedDateTime,
    val tahajjudStartsAt: ZonedDateTime,
)

/**
 * The convention the boundaries in a [NightPeriods] were produced under.
 *
 * A night period is a derived calculation, not a quoted ruling: the *midpoint* and the *last third*
 * are arithmetic, but which of those a reader calls "Tahajjud" is a convention. It travels with the
 * periods so a screen can state which convention is in force instead of presenting one as universal.
 */
data class NightPeriodConvention(
    val id: String,
    /** Human readable name of the convention. */
    val label: String,
    /**
     * True when the Tahajjud period is anchored to the start of the last third of the night.
     *
     * This is the convention Salah ships (see `ConfiguredNightConvention`). It is carried on the
     * convention rather than read as a constant so a different anchoring is a data change, and so a
     * screen can say which one is being applied.
     */
    val tahajjudAnchoredToLastThird: Boolean,
)

/**
 * State of the night-period section.
 *
 * [NotAvailable] is a real outcome, not an error: the interval needs both the day's Maghrib and the
 * following day's Fajr, and either can legitimately be absent (no location, a polar schedule, or a
 * following day that has not been calculated). Salah reports the gap instead of inventing a boundary.
 */
sealed interface NightPeriodsState {
    data class Available(
        val periods: NightPeriods,
        val convention: NightPeriodConvention,
    ) : NightPeriodsState

    data class NotAvailable(
        val reason: UnavailabilityReason,
        val detail: String? = null,
    ) : NightPeriodsState
}
