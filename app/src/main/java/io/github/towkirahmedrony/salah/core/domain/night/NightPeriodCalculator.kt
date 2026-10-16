package io.github.towkirahmedrony.salah.core.domain.night

import io.github.towkirahmedrony.salah.core.model.NightPeriodConvention
import io.github.towkirahmedrony.salah.core.model.NightPeriods
import io.github.towkirahmedrony.salah.core.model.NightPeriodsState
import io.github.towkirahmedrony.salah.core.model.PrayerName
import io.github.towkirahmedrony.salah.core.model.PrayerSchedule
import io.github.towkirahmedrony.salah.core.model.UnavailabilityReason
import java.time.Duration
import java.time.ZonedDateTime

/**
 * The next night boundary a moment is approaching.
 *
 * @param boundary which boundary it is.
 * @param at the instant it occurs.
 * @param remaining the time from the resolved moment to [at].
 */
data class UpcomingNightBoundary(
    val boundary: NightBoundary,
    val at: ZonedDateTime,
    val remaining: Duration,
)

/**
 * Derives the night interval and its boundaries from two already calculated schedules.
 *
 * The calculator is deliberately dumb about religion and exact about arithmetic: it takes the
 * **effective** Maghrib from today's schedule and the **effective** Fajr from the following day's
 * schedule and derives every other instant from those two. It never reads a clock, so a result is a
 * pure function of the two schedules and can be replayed in a test at any moment. It performs no
 * astronomy of its own — the two boundary instants come from the one engine that produced the
 * timetable — so it cannot disagree with the prayer times shown beside it.
 *
 * **Definitions.** With `N = endsAt - startsAt`:
 *
 *  - Islamic midnight = `startsAt + N/2` — the exact midpoint of the interval.
 *  - the last third begins at `endsAt - N/3`.
 *  - Tahajjud begins at the point the convention selects (the last third by default).
 *
 * Nothing here is civil midnight and nothing here is the astronomical solar midnight: the midpoint
 * of a Maghrib-to-Fajr interval falls at a different clock time from both.
 *
 * **Boundary convention.** Every boundary is half-open in the same sense as the prayer periods and
 * the prohibited windows: a moment exactly at a boundary belongs to the period that *begins* there.
 *
 * @param convention the convention to record on, and to derive Tahajjud under.
 */
class NightPeriodCalculator(
    private val convention: NightPeriodConvention = ConfiguredNightConvention.DEFAULT,
) {

    /**
     * Builds the night state from today's and the following day's schedules.
     *
     * @param schedule today's schedule, or `null` when none has been produced yet.
     * @param nextDaySchedule the following local date's schedule, or `null` when it has not been
     *   produced. It is required because Islamic midnight, the last third and the interval's own end
     *   are all anchored to the *following* Fajr.
     */
    fun calculate(
        schedule: PrayerSchedule?,
        nextDaySchedule: PrayerSchedule?,
    ): NightPeriodsState {
        if (schedule == null) {
            return NightPeriodsState.NotAvailable(
                reason = UnavailabilityReason.UNKNOWN,
                detail = MISSING_TODAY,
            )
        }
        val maghrib = schedule.timeOf(PrayerName.MAGHRIB)
            ?: return NightPeriodsState.NotAvailable(
                reason = UnavailabilityReason.UNKNOWN,
                detail = MISSING_MAGHRIB,
            )
        val fajr = nextDaySchedule?.timeOf(PrayerName.FAJR)
            ?.withZoneSameInstant(schedule.zoneId)
            ?: return NightPeriodsState.NotAvailable(
                reason = UnavailabilityReason.UNKNOWN,
                detail = MISSING_FAJR,
            )

        if (!fajr.isAfter(maghrib)) {
            return NightPeriodsState.NotAvailable(
                reason = UnavailabilityReason.UNKNOWN,
                detail = INVALID_INTERVAL,
            )
        }

        return NightPeriodsState.Available(
            periods = periods(maghrib, fajr),
            convention = convention,
        )
    }

    /**
     * Derives the boundaries from two instants.
     *
     * Exposed so a caller that already holds the two effective times — and so is not tied to a
     * [PrayerSchedule] — can build the same interval, and so the arithmetic can be tested directly.
     *
     * @throws IllegalArgumentException when [fajr] is not after [maghrib]. Unlike the
     *   schedule-based entry point, this one is called with values a caller asserted, so a bad pair
     *   is a programming error rather than an availability outcome.
     */
    fun periods(maghrib: ZonedDateTime, fajr: ZonedDateTime): NightPeriods {
        require(fajr.isAfter(maghrib)) {
            "The night interval must have Fajr after Maghrib."
        }
        val duration = Duration.between(maghrib, fajr)
        val half = duration.dividedBy(2L)
        val third = duration.dividedBy(3L)
        return NightPeriods(
            startsAt = maghrib,
            endsAt = fajr,
            duration = duration,
            islamicMidnight = maghrib.plus(half),
            lastThirdStartsAt = fajr.minus(third),
            tahajjudStartsAt = if (convention.tahajjudAnchoredToLastThird) {
                fajr.minus(third)
            } else {
                maghrib.plus(half)
            },
        )
    }

    /**
     * Where [now] sits inside [periods], or `null` when it is outside the interval.
     *
     * The comparison is done on absolute instants, so a caller may pass a moment in any zone. A
     * moment exactly at a boundary belongs to the phase that begins there (`[start, end)`).
     */
    fun phaseAt(periods: NightPeriods, now: ZonedDateTime): NightPhase? = when {
        now.isBefore(periods.startsAt) -> null
        now.isBefore(periods.islamicMidnight) -> NightPhase.EARLY_NIGHT
        now.isBefore(periods.lastThirdStartsAt) -> NightPhase.LATE_NIGHT
        now.isBefore(periods.endsAt) -> NightPhase.LAST_THIRD
        else -> null
    }

    /**
     * The next boundary strictly after [now], or `null` when the moment is at or past Fajr.
     *
     * `now` exactly at a boundary is treated as being *at* it, so the boundary it is at is not
     * reported as upcoming — the following one is. This keeps the countdown positive and matches the
     * half-open convention the phases use.
     */
    fun upcomingBoundary(periods: NightPeriods, now: ZonedDateTime): UpcomingNightBoundary? {
        val candidates = listOf(
            NightBoundary.ISLAMIC_MIDNIGHT to periods.islamicMidnight,
            NightBoundary.LAST_THIRD_STARTS to periods.lastThirdStartsAt,
            NightBoundary.FAJR to periods.endsAt,
        )
        val (boundary, at) = candidates.firstOrNull { (_, instant) -> instant.isAfter(now) }
            ?: return null
        return UpcomingNightBoundary(
            boundary = boundary,
            at = at,
            remaining = Duration.between(now, at),
        )
    }

    private companion object {
        const val MISSING_TODAY =
            "Night periods are derived from the day's Maghrib and the following Fajr, and no " +
                "schedule is available yet."

        const val MISSING_MAGHRIB =
            "The day's schedule does not contain Maghrib, so the night interval cannot be opened."

        const val MISSING_FAJR =
            "The following day's Fajr is not available yet, so the night interval cannot be closed."

        const val INVALID_INTERVAL =
            "The following day's Fajr is not after this day's Maghrib, so the night interval is invalid."
    }
}
