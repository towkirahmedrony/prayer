package io.github.towkirahmedrony.salah.core.domain.notification

import io.github.towkirahmedrony.salah.core.model.NotificationSound
import io.github.towkirahmedrony.salah.core.model.PrayerName
import io.github.towkirahmedrony.salah.core.model.PrayerNotificationSettings
import io.github.towkirahmedrony.salah.core.model.PrayerSchedule
import io.github.towkirahmedrony.salah.core.model.SolarEvent
import java.time.LocalDate
import java.time.ZonedDateTime

/**
 * Pure decision logic for prayer notifications.
 *
 * This is the whole of the "what should be scheduled" question, with no Android type, no
 * coroutine, no clock and no repository in sight: it takes a set of real prayer schedules, the
 * user's notification settings and the current instant, and returns the reminders that must run.
 * Everything hard about the feature — duplicates, stale alarms, disabled prayers, past events —
 * is decided here and is therefore directly unit-testable.
 *
 * Guarantees, each of which is asserted in `PrayerNotificationPlannerTest`:
 *
 *  - **Nothing is invented.** A reminder exists only where a complete schedule supplies the time.
 *    A missing schedule yields no reminders, never a guessed one.
 *  - **Disabled means absent.** A disabled target produces no reminder at all, so there is nothing
 *    to cancel later and nothing to leak.
 *  - **Only the future is scheduled.** A trigger at or before [plan]'s `now` is dropped, so an
 *    already-passed prayer is never queued to fire immediately.
 *  - **At most one reminder per slot.** Output is de-duplicated by [PrayerAlarmSlot], so a caller
 *    that passes overlapping schedules still cannot produce a duplicated identity.
 *  - **Deterministic order.** Output is sorted by trigger instant and then by identity, so the
 *    same inputs always produce the same list and results are comparable in tests.
 *  - **The window is a fixed cross-product.** [windowSlots] enumerates every slot a date could
 *    possibly use, independently of the current settings. That is what lets the coordinator cancel
 *    a *superset* of what it is about to schedule and know it has missed nothing.
 */
class PrayerNotificationPlanner {

    /**
     * The dates a single reconciliation covers: today and the next [WINDOW_DAYS] - 1 days.
     *
     * Two days is deliberate. One day is not enough: after today's Isha the next reminder belongs
     * to tomorrow, and a device that spends the night in Doze must already have tomorrow's alarms
     * registered. More than two days widens the cancellation set and the number of live alarms for
     * no benefit, because a daily reconciliation is guaranteed by the date-change broadcast and by
     * the app-start reconcile.
     */
    fun windowDates(today: LocalDate, days: Int = WINDOW_DAYS): List<LocalDate> =
        (0 until days).map { offset -> today.plusDays(offset.toLong()) }

    /**
     * Every slot [dates] could use, in a stable order.
     *
     * Independent of the user's settings on purpose: this is the cancellation set, and it has to
     * cover slots that were scheduled under *older* settings — a prayer the user has since
     * disabled still needs its previously scheduled alarm cancelled.
     */
    fun windowSlots(dates: List<LocalDate>): List<PrayerAlarmSlot> = dates.flatMap { date ->
        ReminderTarget.entries.flatMap { target ->
            PrayerReminderKind.entries.map { kind -> PrayerAlarmSlot(date, target, kind) }
        }
    }

    /**
     * The reminders that must be scheduled for [schedules] under [settings].
     *
     * @param schedules one entry per date in the window; entries that are missing, incomplete or
     *   for a different date than the slot identity would need are simply not used, because
     *   [PrayerAlarmSlot.date] is taken from the schedule's own date.
     * @param now the current instant. Converted into each schedule's own zone before comparison,
     *   so a device in a different zone from the location still gets the correct answer.
     */
    fun plan(
        settings: PrayerNotificationSettings,
        schedules: List<PrayerSchedule>,
        now: ZonedDateTime,
    ): List<PlannedPrayerNotification> {
        if (!settings.masterEnabled) return emptyList()

        val planned = schedules
            .asSequence()
            .filter { it.isComplete }
            .flatMap { schedule -> planForSchedule(settings, schedule, now) }
            .distinctBy { it.slot }
            .sortedWith(compareBy({ it.triggerAt.toInstant() }, { it.slot.identity }))
            .toList()

        return planned
    }

    /**
     * The slots in [dates] that [planned] does not cover, i.e. exactly the alarms that must be
     * cancelled.
     *
     * Because [windowSlots] is the full cross-product for the window, this is computed rather than
     * remembered: there is no registry that can disagree with reality, and a slot that was
     * scheduled under settings the user has since changed is still found and cancelled.
     */
    fun obsoleteSlots(
        dates: List<LocalDate>,
        planned: List<PlannedPrayerNotification>,
    ): List<PrayerAlarmSlot> {
        val keep = planned.mapTo(mutableSetOf()) { it.slot }
        return windowSlots(dates).filterNot { it in keep }
    }

    private fun planForSchedule(
        settings: PrayerNotificationSettings,
        schedule: PrayerSchedule,
        now: ZonedDateTime,
    ): List<PlannedPrayerNotification> {
        val nowAtLocation = now.withZoneSameInstant(schedule.zoneId)
        val build = mutableListOf<PlannedPrayerNotification>()

        PrayerName.entries.forEach { prayer ->
            val preference = settings.preferenceFor(prayer)
            if (!preference.enabled) return@forEach

            val eventAt = schedule.timeOf(prayer) ?: return@forEach
            val target = ReminderTarget.of(prayer)
            build += atTime(schedule, target, eventAt, preference.sound, preference.vibration, nowAtLocation)
            build += advance(
                schedule = schedule,
                target = target,
                eventAt = eventAt,
                advanceMinutes = preference.advanceMinutes,
                sound = preference.sound,
                vibration = preference.vibration,
                nowAtLocation = nowAtLocation,
            )
        }

        // Sunrise is a solar event: it is only ever offered through its own switch, it is never
        // given an advance reminder, and it is never treated as one of the five prayers.
        if (settings.sunriseReminderEnabled) {
            schedule.timeOf(SolarEvent.SUNRISE)?.let { sunriseAt ->
                build += atTime(
                    schedule = schedule,
                    target = ReminderTarget.SOLAR,
                    eventAt = sunriseAt,
                    sound = NotificationSound.SYSTEM_DEFAULT,
                    vibration = true,
                    nowAtLocation = nowAtLocation,
                )
            }
        }

        return build
    }

    private fun atTime(
        schedule: PrayerSchedule,
        target: ReminderTarget,
        eventAt: ZonedDateTime,
        sound: NotificationSound,
        vibration: Boolean,
        nowAtLocation: ZonedDateTime,
    ): List<PlannedPrayerNotification> {
        if (!eventAt.isAfter(nowAtLocation)) return emptyList()
        return listOf(
            PlannedPrayerNotification(
                slot = PrayerAlarmSlot(schedule.date, target, PrayerReminderKind.AT_TIME),
                triggerAt = eventAt,
                eventAt = eventAt,
                advanceMinutes = 0,
                sound = sound,
                vibration = vibration,
            ),
        )
    }

    private fun advance(
        schedule: PrayerSchedule,
        target: ReminderTarget,
        eventAt: ZonedDateTime,
        advanceMinutes: Int,
        sound: NotificationSound,
        vibration: Boolean,
        nowAtLocation: ZonedDateTime,
    ): List<PlannedPrayerNotification> {
        if (advanceMinutes <= 0) return emptyList()
        val triggerAt = eventAt.minusMinutes(advanceMinutes.toLong())
        if (!triggerAt.isAfter(nowAtLocation)) return emptyList()
        return listOf(
            PlannedPrayerNotification(
                slot = PrayerAlarmSlot(schedule.date, target, PrayerReminderKind.ADVANCE),
                triggerAt = triggerAt,
                eventAt = eventAt,
                advanceMinutes = advanceMinutes,
                sound = sound,
                vibration = vibration,
            ),
        )
    }

    companion object {
        /**
         * How many days one reconciliation covers, including today.
         *
         * See [windowDates] for why this is exactly two.
         */
        const val WINDOW_DAYS: Int = 2
    }
}
