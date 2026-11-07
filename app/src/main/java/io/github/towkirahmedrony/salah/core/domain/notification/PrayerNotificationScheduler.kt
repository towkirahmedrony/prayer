package io.github.towkirahmedrony.salah.core.domain.notification

/**
 * Registers and removes prayer reminders with the platform alarm service.
 *
 * A port, not an implementation, for three reasons: the coordinator can be unit-tested against a
 * recording fake; the scheduling policy (what to schedule, what to cancel) stays in
 * [PrayerNotificationPlanner] where it is pure; and switching to a different time-critical
 * mechanism later would not touch the planner, the coordinator or the UI.
 *
 * Contract:
 *
 *  - [schedule] is idempotent per [PrayerAlarmSlot]. Registering the same slot twice replaces the
 *    previous alarm, so calling it during every reconciliation cannot accumulate alarms.
 *  - [cancel] removes the alarm for a slot whether or not one is currently registered.
 *  - Neither method throws for an expected platform refusal; a refusal is reported through the
 *    logger and the caller continues, because a single failed alarm must not abort the rest.
 *
 * **Why `AlarmManager` and not `WorkManager`.** A prayer reminder is time-critical: the point is
 * that it arrives at the prayer time. `WorkManager` is designed for deferrable work and its
 * scheduling is explicitly at the platform's discretion, so using it here would silently trade
 * punctuality for battery — the opposite of what the feature needs. `WorkManager` is not used
 * anywhere in Salah's notification path.
 */
interface PrayerNotificationScheduler {

    /** Registers [notification] for its trigger instant. Replaces any alarm for the same slot. */
    fun schedule(notification: PlannedPrayerNotification)

    /** Removes the alarm for [slot], if one exists. */
    fun cancel(slot: PrayerAlarmSlot)

    companion object {
        /**
         * The most lateness the inexact fallback is expected to add, in minutes, and the figure the
         * settings screen quotes.
         *
         * Not a contract — the platform decides — but the honest order of magnitude for
         * `setAndAllowWhileIdle`: it guarantees a wakeup, not a precise instant. Salah says "may be
         * a few minutes late" rather than pretending otherwise.
         */
        const val INEXACT_FALLBACK_EXPECTED_LATENESS_MINUTES: Int = 10
    }
}
