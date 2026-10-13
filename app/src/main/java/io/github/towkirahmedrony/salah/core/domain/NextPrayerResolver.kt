package io.github.towkirahmedrony.salah.core.domain

import io.github.towkirahmedrony.salah.core.model.PrayerName
import io.github.towkirahmedrony.salah.core.model.PrayerSchedule
import java.time.Duration
import java.time.ZonedDateTime

/** The next upcoming prayer, relative to a point in time. */
data class NextPrayer(
    val prayer: PrayerName,
    val time: ZonedDateTime,
    val remaining: Duration,
)

/**
 * Resolves the next prayer from a schedule.
 *
 * This is pure domain logic: it performs no astronomical calculation, touches no Android API
 * and never invents data. It returns `null` whenever the answer cannot be derived from a
 * complete schedule for the current day, so the UI cannot show a fabricated countdown.
 *
 * **Semantics.** At the exact instant a prayer begins, that prayer counts as started and the
 * *following* prayer is returned.
 *
 * **After the last prayer.** The day's own schedule has nothing left to offer, but the next prayer is
 * still a known thing — the *following day's Fajr* — whenever the caller can supply that day's own
 * schedule. [resolve] therefore takes it as an optional second schedule ([nextDaySchedule]) and
 * returns tomorrow's Fajr rather than nothing. That is not a guess: the schedule carries its own date,
 * location and profile, and it is accepted only when all three match the day in hand, so the value
 * returned is one the same engine produced for the same place and the same settings.
 *
 * With no next-day schedule — [nextDaySchedule] omitted or `null`, which is what a caller that has not
 * resolved tomorrow passes — the behaviour is unchanged: `null`. Nothing is inferred from today's
 * numbers, and today's Fajr is never reused as tomorrow's.
 */
class NextPrayerResolver {

    /**
     * The next prayer at [now].
     *
     * @param schedule the schedule for the day [now] falls on, or `null` when it is not available.
     * @param nextDaySchedule the schedule for the day after [schedule], or `null` when it is not
     *   available. Used only when [schedule] has no prayer left after [now].
     */
    fun resolve(
        schedule: PrayerSchedule?,
        now: ZonedDateTime,
        nextDaySchedule: PrayerSchedule? = null,
    ): NextPrayer? {
        if (schedule == null) return null
        if (!schedule.isComplete) return null

        val nowInScheduleZone = now.withZoneSameInstant(schedule.zoneId)
        if (nowInScheduleZone.toLocalDate() != schedule.date) return null

        val upcoming = schedule.prayers
            .sortedBy { it.time.toInstant() }
            .firstOrNull { it.time.isAfter(nowInScheduleZone) }

        if (upcoming != null) {
            return NextPrayer(
                prayer = upcoming.prayer,
                time = upcoming.time,
                remaining = Duration.between(nowInScheduleZone, upcoming.time),
            )
        }

        val fajr = nextDayFajr(schedule, nextDaySchedule, nowInScheduleZone) ?: return null
        return NextPrayer(
            prayer = PrayerName.FAJR,
            time = fajr,
            remaining = Duration.between(nowInScheduleZone, fajr),
        )
    }

    /**
     * The following day's Fajr in [schedule]'s zone, or `null` when it cannot be trusted.
     *
     * Accepted only when the candidate is a complete schedule for exactly the next day, for the same
     * location and the same calculation profile — the three things that decide a prayer time. A
     * schedule that fails any of them is another day's or another place's timetable, and using its Fajr
     * would silently answer a different question.
     */
    private fun nextDayFajr(
        schedule: PrayerSchedule,
        nextDaySchedule: PrayerSchedule?,
        nowInScheduleZone: ZonedDateTime,
    ): ZonedDateTime? {
        if (nextDaySchedule == null) return null
        if (!nextDaySchedule.isComplete) return null
        if (nextDaySchedule.date != schedule.date.plusDays(1)) return null
        if (nextDaySchedule.location != schedule.location) return null
        if (nextDaySchedule.profile != schedule.profile) return null

        val fajr = nextDaySchedule.timeOf(PrayerName.FAJR) ?: return null
        val fajrInScheduleZone = fajr.withZoneSameInstant(schedule.zoneId)
        // The boundary of the next day is always after the current moment for a complete schedule, but
        // a malformed or hand-built one could disagree; a countdown to the past is not a countdown.
        if (!fajrInScheduleZone.isAfter(nowInScheduleZone)) return null
        return fajrInScheduleZone
    }
}
