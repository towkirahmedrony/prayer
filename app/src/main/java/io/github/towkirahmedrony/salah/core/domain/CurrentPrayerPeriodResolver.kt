package io.github.towkirahmedrony.salah.core.domain

import io.github.towkirahmedrony.salah.core.model.PrayerName
import io.github.towkirahmedrony.salah.core.model.PrayerSchedule
import io.github.towkirahmedrony.salah.core.model.SolarEvent
import io.github.towkirahmedrony.salah.core.model.UnavailabilityReason
import java.time.Duration
import java.time.ZonedDateTime

/**
 * The boundary that closes a prayer period.
 *
 * A period never ends by itself: it ends when the next boundary of the day is reached, and that
 * boundary is either another prayer or a solar event. Keeping the two apart is what stops sunrise
 * from being presented as one of the five prayers while it still closes Fajr's window.
 */
sealed interface PeriodBoundary {

    /**
     * Another obligatory prayer begins.
     *
     * @param isNextDay `true` when the boundary is on the following local date, which is the case
     *   for Isha's period: it runs until the next day's Fajr.
     */
    data class Prayer(
        val prayer: PrayerName,
        val time: ZonedDateTime,
        val isNextDay: Boolean = false,
    ) : PeriodBoundary

    /** A solar event occurs. Sunrise and sunset are astronomical events, not prayers. */
    data class Solar(
        val event: SolarEvent,
        val time: ZonedDateTime,
    ) : PeriodBoundary
}

/**
 * The prayer whose period contains the moment being resolved.
 *
 * @param startsAt when the period began, or `null` when it began before the schedule in hand — the
 *   Isha period of the previous day, which is still running in the small hours. The start is
 *   genuinely not on today's schedule, so the absence is reported instead of being filled in with
 *   a carried-over timetable.
 * @param endsAt when the period closes.
 * @param endsWith the boundary that closes it.
 * @param remaining time from the resolved moment to [endsAt].
 */
data class CurrentPrayerPeriod(
    val prayer: PrayerName,
    val startsAt: ZonedDateTime?,
    val endsAt: ZonedDateTime,
    val endsWith: PeriodBoundary,
    val remaining: Duration,
)

/**
 * What is happening at a given moment in a given day's timetable.
 *
 * [Between] is a real, expected outcome rather than an error: under the convention this application
 * uses, Fajr's period closes at sunrise and Dhuhr's does not open until the sun has passed its
 * zenith, so the middle of the morning belongs to no obligatory prayer's window.
 */
sealed interface CurrentPrayerStatus {

    /** A prayer period is open right now. */
    data class Active(val period: CurrentPrayerPeriod) : CurrentPrayerStatus

    /**
     * No prayer period is open; the next one opens at [nextPrayerTime].
     *
     * Not an unavailability: it is the correct reading of the timetable between two periods.
     */
    data class Between(
        val nextPrayer: PrayerName,
        val nextPrayerTime: ZonedDateTime,
        val remaining: Duration,
    ) : CurrentPrayerStatus

    /** The timetable does not carry the data needed to answer, so no period is reported. */
    data class Unavailable(
        val reason: UnavailabilityReason,
        val detail: String? = null,
    ) : CurrentPrayerStatus
}

/**
 * Resolves which prayer period is currently open, and how long is left in it.
 *
 * Pure domain logic in the same sense as [NextPrayerResolver]: no astronomy, no Android API, and no
 * read of the system clock — the moment is passed in, so every boundary case is reproducible in a
 * test. It consumes the schedule the engine already produced and does not calculate anything of its
 * own, so there is exactly one prayer-time engine in the application.
 *
 * **Period convention** (the application's configured convention; see
 * `docs/CURRENT_PRAYER_STATUS.md`):
 *
 * | Period | Opens | Closes |
 * |---|---|---|
 * | Fajr | Fajr time | sunrise (a solar event) |
 * | Dhuhr | Dhuhr time | Asr time |
 * | Asr | Asr time | Maghrib time |
 * | Maghrib | Maghrib time | Isha time |
 * | Isha | Isha time | the next day's Fajr time |
 *
 * **Boundary convention.** Every period is half-open, `[start, end)`: at the exact instant a
 * boundary occurs the period that *begins* there is the one reported, and the period that ends
 * there has already closed. A moment exactly at sunrise is therefore between Fajr and Dhuhr, and a
 * moment exactly at Dhuhr belongs to Dhuhr.
 *
 * **What the remaining time is not.** It is the time until the next boundary in the timetable. It
 * is not a ruling about how long a prayer may be performed or whether one must be repeated, and
 * nothing here should be presented as one.
 *
 * @param nextDayFajr the following local date's Fajr time, when the caller can supply it. It is
 *   only consulted once Isha's period is open, which is the one period whose closing boundary is
 *   not on the day's own schedule. When it is absent that period is reported as
 *   [CurrentPrayerStatus.Unavailable] rather than ended at a boundary Salah cannot name.
 */
class CurrentPrayerPeriodResolver {

    fun resolve(
        schedule: PrayerSchedule?,
        now: ZonedDateTime,
        nextDayFajr: ZonedDateTime? = null,
    ): CurrentPrayerStatus {
        if (schedule == null) {
            return CurrentPrayerStatus.Unavailable(reason = UnavailabilityReason.UNKNOWN)
        }
        if (!schedule.isComplete) {
            // A timetable missing one of the five prayers cannot say which period a moment is in,
            // and guessing one would be a fabricated prayer time.
            return CurrentPrayerStatus.Unavailable(
                reason = UnavailabilityReason.UNKNOWN,
                detail = "The day's schedule is incomplete, so no prayer period can be identified.",
            )
        }

        val zone = schedule.zoneId
        val moment = now.withZoneSameInstant(zone)
        if (moment.toLocalDate() != schedule.date) {
            // The schedule belongs to another day: it must not describe this moment.
            return CurrentPrayerStatus.Unavailable(
                reason = UnavailabilityReason.UNKNOWN,
                detail = "The schedule in hand is for ${schedule.date}, not for ${moment.toLocalDate()}.",
            )
        }

        val fajr = schedule.timeOf(PrayerName.FAJR)
        val dhuhr = schedule.timeOf(PrayerName.DHUHR)
        val asr = schedule.timeOf(PrayerName.ASR)
        val maghrib = schedule.timeOf(PrayerName.MAGHRIB)
        val isha = schedule.timeOf(PrayerName.ISHA)
        val sunrise = schedule.timeOf(SolarEvent.SUNRISE)

        if (fajr == null || dhuhr == null || asr == null || maghrib == null || isha == null) {
            return CurrentPrayerStatus.Unavailable(reason = UnavailabilityReason.UNKNOWN)
        }
        val ordered = listOf(fajr, dhuhr, asr, maghrib, isha)
        if (ordered.zipWithNext().any { (earlier, later) -> !earlier.isBefore(later) }) {
            // Out-of-order or duplicated times are invalid data, not a timetable.
            return CurrentPrayerStatus.Unavailable(
                reason = UnavailabilityReason.UNKNOWN,
                detail = "The day's prayer times are not in chronological order.",
            )
        }

        // Before Fajr the previous day's Isha period is still open. Its start is not on this
        // schedule, but its end is: today's Fajr.
        if (moment.isBefore(fajr)) {
            return CurrentPrayerStatus.Active(
                CurrentPrayerPeriod(
                    prayer = PrayerName.ISHA,
                    startsAt = null,
                    endsAt = fajr,
                    endsWith = PeriodBoundary.Prayer(prayer = PrayerName.FAJR, time = fajr),
                    remaining = Duration.between(moment, fajr),
                ),
            )
        }

        if (moment.isBefore(dhuhr)) {
            // Fajr's period closes at sunrise. Without sunrise the period cannot be closed, so the
            // whole morning is reported as unresolved rather than ended at the wrong boundary.
            if (sunrise == null) {
                return CurrentPrayerStatus.Unavailable(
                    reason = UnavailabilityReason.UNKNOWN,
                    detail = "Sunrise is missing from the schedule, so the Fajr period cannot be closed.",
                )
            }
            return if (moment.isBefore(sunrise)) {
                CurrentPrayerStatus.Active(
                    CurrentPrayerPeriod(
                        prayer = PrayerName.FAJR,
                        startsAt = fajr,
                        endsAt = sunrise,
                        endsWith = PeriodBoundary.Solar(event = SolarEvent.SUNRISE, time = sunrise),
                        remaining = Duration.between(moment, sunrise),
                    ),
                )
            } else {
                // Sunrise has passed and Dhuhr has not opened: between two periods.
                CurrentPrayerStatus.Between(
                    nextPrayer = PrayerName.DHUHR,
                    nextPrayerTime = dhuhr,
                    remaining = Duration.between(moment, dhuhr),
                )
            }
        }

        if (moment.isBefore(asr)) {
            return CurrentPrayerStatus.Active(
                CurrentPrayerPeriod(
                    prayer = PrayerName.DHUHR,
                    startsAt = dhuhr,
                    endsAt = asr,
                    endsWith = PeriodBoundary.Prayer(prayer = PrayerName.ASR, time = asr),
                    remaining = Duration.between(moment, asr),
                ),
            )
        }

        if (moment.isBefore(maghrib)) {
            return CurrentPrayerStatus.Active(
                CurrentPrayerPeriod(
                    prayer = PrayerName.ASR,
                    startsAt = asr,
                    endsAt = maghrib,
                    endsWith = PeriodBoundary.Prayer(prayer = PrayerName.MAGHRIB, time = maghrib),
                    remaining = Duration.between(moment, maghrib),
                ),
            )
        }

        if (moment.isBefore(isha)) {
            return CurrentPrayerStatus.Active(
                CurrentPrayerPeriod(
                    prayer = PrayerName.MAGHRIB,
                    startsAt = maghrib,
                    endsAt = isha,
                    endsWith = PeriodBoundary.Prayer(prayer = PrayerName.ISHA, time = isha),
                    remaining = Duration.between(moment, isha),
                ),
            )
        }

        // Isha's period runs to the next day's Fajr, which is not on this schedule.
        val closing = nextDayFajr?.withZoneSameInstant(zone)
        if (closing == null || !closing.isAfter(isha)) {
            return CurrentPrayerStatus.Unavailable(
                reason = UnavailabilityReason.UNKNOWN,
                detail = "Isha's period ends at the next day's Fajr, which is not available yet.",
            )
        }
        return CurrentPrayerStatus.Active(
            CurrentPrayerPeriod(
                prayer = PrayerName.ISHA,
                startsAt = isha,
                endsAt = closing,
                endsWith = PeriodBoundary.Prayer(
                    prayer = PrayerName.FAJR,
                    time = closing,
                    isNextDay = true,
                ),
                remaining = Duration.between(moment, closing),
            ),
        )
    }
}
