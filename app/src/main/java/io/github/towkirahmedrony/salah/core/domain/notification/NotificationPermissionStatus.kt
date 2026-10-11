package io.github.towkirahmedrony.salah.core.domain.notification

/**
 * The two independent platform permissions prayer notifications depend on.
 *
 * They are modelled as two separate booleans because Android treats them as two separate
 * permissions with two separate capabilities:
 *
 *  - [notificationsAllowed] is `POST_NOTIFICATIONS` (Android 13 / API 33 and later). Without it,
 *    a posted notification is recorded by Salah but never shown by the system. On API 32 and
 *    below the permission does not exist, and this reports
 *    `NotificationManagerCompat.areNotificationsEnabled()` instead — which the user can also turn
 *    off per app at any Android version.
 *  - [exactAlarmsAllowed] is `SCHEDULE_EXACT_ALARM` (Android 12 / API 31 and later). Without it,
 *    `setExactAndAllowWhileIdle` throws and Salah falls back to a windowed alarm — see
 *    [exactAlarmFallbackInUse].
 *
 * Conflating them is the classic bug this type exists to prevent: granting notifications does
 * **not** grant exact alarms, and a device can have either, both or neither.
 */
data class NotificationPermissionStatus(
    val notificationsAllowed: Boolean,
    val exactAlarmsAllowed: Boolean,
) {
    /**
     * True when Salah is scheduling windowed alarms because exact alarms are not permitted.
     *
     * The user is told what this means in the notification settings screen: reminders still
     * arrive, but the platform may deliver them up to
     * [PrayerNotificationScheduler.EXACT_ALARM_FALLBACK_WINDOW_MILLIS] late, and a device in Doze
     * may delay them further. That is a genuine limitation and Salah states it rather than
     * implying second-accurate delivery.
     */
    val exactAlarmFallbackInUse: Boolean get() = !exactAlarmsAllowed

    companion object {
        /** The state before any platform query: assume nothing is granted. */
        val UNKNOWN = NotificationPermissionStatus(
            notificationsAllowed = false,
            exactAlarmsAllowed = false,
        )
    }
}

/**
 * Reads the current platform permission state.
 *
 * An interface so scheduling and delivery can be tested against a fixed permission state instead
 * of a mocked framework, and so nothing in the domain layer needs an Android `Context`.
 */
interface NotificationPermissionStatusProvider {

    /** The current state. Must not throw; an unreadable state is reported as not granted. */
    fun status(): NotificationPermissionStatus
}
