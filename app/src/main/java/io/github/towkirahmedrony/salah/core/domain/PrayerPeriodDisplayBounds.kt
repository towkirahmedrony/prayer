package io.github.towkirahmedrony.salah.core.domain

import io.github.towkirahmedrony.salah.core.model.PrayerName
import io.github.towkirahmedrony.salah.core.model.PrayerSchedule
import io.github.towkirahmedrony.salah.core.model.SolarEvent
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/** Where one prayer's period closes, and whether that moment is on the following local date. */
data class PrayerPeriodEnd(
    val time: ZonedDateTime,
    val isNextDay: Boolean,
)

/**
 * The bounds a prayer period is *displayed* with.
 *
 * A period is half-open, `[start, end)`: it closes at the very instant the next one opens, so the
 * engine's Asr time is at once the end of Dhuhr's period and the start of Asr's. Printed at minute
 * precision in the inclusive-looking `start – end` range the timetable uses, that shared instant puts
 * the same minute on both sides of the pair — `11:46 – 15:40`, then `15:40 – 17:36` — which reads as
 * an overlap even though the periods only touch.
 *
 * This class keeps the Adhan times untouched and derives the *labels* from them:
 *
 *  - [endOf] reports the boundary that closes a period, exactly as [CurrentPrayerPeriodResolver]
 *    reports it;
 *  - [displayedStartOf] reports the first whole minute after the previous period's displayed end, so
 *    two consecutive rows never show the same minute. A period the timetable already separates from
 *    the one before it — Fajr, which opens the day, and Dhuhr, which follows the sunrise gap — keeps
 *    its own minute.
 *
 * Nothing here feeds a countdown, a notification or a stored time: those keep reading the engine's
 * instants. This is a presentation rule for whole-minute intervals, kept in the domain so it is
 * testable without a UI.
 */
class PrayerPeriodDisplayBounds {

    /**
     * The boundary that closes [prayer]'s period, or `null` when the schedule cannot name it.
     *
     * Fajr closes at sunrise (a solar event, never a prayer), Dhuhr at Asr, Asr at Maghrib, Maghrib at
     * Isha and Isha at the next day's Fajr. The last is returned only when the caller supplied a
     * following-day Fajr that really is after Isha, so a boundary is never invented.
     */
    fun endOf(
        schedule: PrayerSchedule?,
        prayer: PrayerName,
        nextDayFajr: ZonedDateTime? = null,
    ): PrayerPeriodEnd? {
        if (schedule == null || !schedule.isComplete) return null
        return when (prayer) {
            PrayerName.FAJR -> schedule.timeOf(SolarEvent.SUNRISE)?.let { PrayerPeriodEnd(it, false) }
            PrayerName.DHUHR -> schedule.timeOf(PrayerName.ASR)?.let { PrayerPeriodEnd(it, false) }
            PrayerName.ASR -> schedule.timeOf(PrayerName.MAGHRIB)?.let { PrayerPeriodEnd(it, false) }
            PrayerName.MAGHRIB -> schedule.timeOf(PrayerName.ISHA)?.let { PrayerPeriodEnd(it, false) }
            PrayerName.ISHA -> {
                val isha = schedule.timeOf(PrayerName.ISHA)
                val closing = nextDayFajr?.withZoneSameInstant(schedule.zoneId)
                if (isha != null && closing != null && closing.isAfter(isha)) {
                    PrayerPeriodEnd(closing, isNextDay = true)
                } else {
                    null
                }
            }
        }
    }

    /**
     * The minute [prayer]'s period is displayed as opening, or `null` when it cannot be resolved.
     *
     * The day's first period opens at its own time. Every later period opens at the first whole minute
     * after the minute the previous period's displayed end falls in, so the pair reads `… – 15:40`
     * followed by `15:41 – …`. When the timetable already separates the two — Dhuhr after the sunrise
     * gap — the period keeps its own time, which is why this does not shift every prayer by a minute.
     */
    fun displayedStartOf(
        schedule: PrayerSchedule?,
        prayer: PrayerName,
        nextDayFajr: ZonedDateTime? = null,
    ): ZonedDateTime? {
        if (schedule == null || !schedule.isComplete) return null
        val start = schedule.timeOf(prayer) ?: return null
        // Fajr opens the timetable: the Isha period that precedes it closed on another day's
        // schedule, so there is no row for it here to share a minute with.
        val previous = PrayerName.entries.getOrNull(prayer.ordinal - 1) ?: return start
        val previousEnd = endOf(schedule, previous, nextDayFajr)?.time ?: return start
        val zone = start.zone
        val previousClosingMinute = previousEnd.withZoneSameInstant(zone).truncatedTo(ChronoUnit.MINUTES)
        val openingMinute = start.truncatedTo(ChronoUnit.MINUTES)
        return if (openingMinute.isAfter(previousClosingMinute)) {
            start
        } else {
            previousClosingMinute.plusMinutes(MINIMUM_GAP_MINUTES)
        }
    }

    private companion object {
        /**
         * One displayed minute: the smallest gap that keeps the closing minute of one period out of the
         * opening label of the next. Not a claim about the prayer — the Adhan time itself is unchanged.
         */
        const val MINIMUM_GAP_MINUTES = 1L
    }
}
