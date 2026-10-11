package io.github.towkirahmedrony.salah.core.domain.notification

import io.github.towkirahmedrony.salah.core.model.NotificationSound
import java.time.ZonedDateTime

/**
 * One reminder that Salah intends to deliver, resolved far enough that scheduling it is a
 * mechanical step.
 *
 * Produced only by [PrayerNotificationPlanner] from a complete, real prayer schedule. Every field
 * is a value the planner read — there is no default time, no "about now" and no placeholder, so a
 * `PlannedPrayerNotification` can only exist when a prayer time actually exists.
 */
data class PlannedPrayerNotification(
    /** The addressable identity of this reminder. Unique within a reconciliation window. */
    val slot: PrayerAlarmSlot,

    /** The absolute instant at which the reminder must run, in the prayer location's zone. */
    val triggerAt: ZonedDateTime,

    /** The target's own time — the prayer time, or sunrise. Shown in the notification body. */
    val eventAt: ZonedDateTime,

    /** Minutes before [eventAt] this reminder runs; `0` for an at-time reminder. */
    val advanceMinutes: Int,

    /** Sound the user chose for this target. Selects the notification channel. */
    val sound: NotificationSound,

    /** Whether the user asked for vibration, where the device permits it. */
    val vibration: Boolean,
) {
    /** True when this reminder runs at the event itself rather than before it. */
    val isAtEventTime: Boolean get() = slot.kind == PrayerReminderKind.AT_TIME
}
