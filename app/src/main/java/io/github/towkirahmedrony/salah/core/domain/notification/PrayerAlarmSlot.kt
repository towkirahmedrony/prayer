package io.github.towkirahmedrony.salah.core.domain.notification

import java.time.LocalDate

/**
 * When a reminder runs relative to the event it is about.
 *
 * There are exactly two kinds, and both are defined product behaviour:
 *
 *  - [AT_TIME] fires at the target's own time — the prayer time for a prayer, sunrise for the
 *    solar target.
 *  - [ADVANCE] fires [io.github.towkirahmedrony.salah.core.model.PrayerNotificationPreference.advanceMinutes]
 *    before the target's time.
 *
 * There is no "after the prayer" kind. Salah does not implement a post-prayer reminder because no
 * product behaviour defines what one would say or when it would run, and inventing one would be
 * guessing at religious guidance rather than scheduling a notification.
 *
 * The declaration order is part of the alarm identity scheme in [PrayerNotificationIds] and must
 * not be reordered once alarms exist.
 */
enum class PrayerReminderKind {
    AT_TIME,
    ADVANCE,
}

/**
 * One addressable alarm slot: a calendar date, a target and a kind.
 *
 * This is the unit of identity for the whole notification subsystem. Every alarm Salah ever
 * creates corresponds to exactly one slot, the set of slots for a date is a fixed, enumerable
 * cross-product ([PrayerNotificationPlanner.windowSlots]), and that is what makes "no duplicate
 * alarms" and "cancel the obsolete ones" checkable properties rather than hopes.
 *
 * [date] is the calendar date **in the prayer location's zone**, which is the same day the prayer
 * schedule was calculated for. It is therefore never affected by a change to the device's own
 * time zone.
 */
data class PrayerAlarmSlot(
    val date: LocalDate,
    val target: ReminderTarget,
    val kind: PrayerReminderKind,
) {
    /** Stable, human-readable identity, used for logging and for notification tags. */
    val identity: String
        get() = "prayer-reminder/${date}/${target.name.lowercase()}/${kind.name.lowercase()}"
}
