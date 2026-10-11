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
 * Semantics: at the exact instant a prayer begins, that prayer counts as started and the
 * *following* prayer is returned. After the last prayer of the day, `null` is returned; the
 * next day's schedule is a separate calculation and is not guessed here.
 */
class NextPrayerResolver {

    fun resolve(schedule: PrayerSchedule?, now: ZonedDateTime): NextPrayer? {
        if (schedule == null) return null
        if (!schedule.isComplete) return null

        val nowInScheduleZone = now.withZoneSameInstant(schedule.zoneId)
        if (nowInScheduleZone.toLocalDate() != schedule.date) return null

        val upcoming = schedule.prayers
            .sortedBy { it.time.toInstant() }
            .firstOrNull { it.time.isAfter(nowInScheduleZone) }
            ?: return null

        return NextPrayer(
            prayer = upcoming.prayer,
            time = upcoming.time,
            remaining = Duration.between(nowInScheduleZone, upcoming.time),
        )
    }
}
