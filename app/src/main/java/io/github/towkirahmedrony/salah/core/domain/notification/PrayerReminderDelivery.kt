package io.github.towkirahmedrony.salah.core.domain.notification

import io.github.towkirahmedrony.salah.core.model.NotificationSound
import io.github.towkirahmedrony.salah.core.model.TimeFormatPreference
import java.time.ZonedDateTime

/**
 * A reminder resolved against the user's **current** settings, ready to be shown.
 *
 * Note where the announcement fields come from: [sound] and [vibration] are read from the stored
 * preference at delivery time, not from the alarm that woke us up. An alarm scheduled last week
 * therefore announces itself the way the user wants it announced *now*, and a preference changed
 * after scheduling takes effect on the very next reminder rather than at the next reconciliation.
 */
data class PrayerReminderDelivery(
    val slot: PrayerAlarmSlot,
    /** The target's own time, in the prayer location's zone. */
    val eventAt: ZonedDateTime,
    /** Minutes before [eventAt] this reminder runs; `0` for an at-time reminder. */
    val advanceMinutes: Int,
    val sound: NotificationSound,
    val vibration: Boolean,
    /**
     * The user's clock-format preference, so a reminder shows its time exactly the way the Home
     * screen does. Read at delivery time for the same reason [sound] is.
     */
    val timeFormat: TimeFormatPreference,
) {
    /** The channel this reminder must be posted to. Android owns sound and vibration per channel. */
    val channelId: String get() = PrayerNotificationChannelId.of(sound, vibration)

    /** True when this reminder runs at the event itself rather than before it. */
    val isAtEventTime: Boolean get() = slot.kind == PrayerReminderKind.AT_TIME
}

/**
 * Posts a resolved reminder to the platform.
 *
 * An interface so the delivery rules — which preferences suppress a reminder, what happens without
 * notification permission, what qualifies as stale — can be tested without an Android
 * `NotificationManager`, and so the Android implementation stays a thin, obvious renderer.
 */
interface PrayerNotificationPresenter {

    /**
     * Shows [delivery].
     *
     * @return `true` when the platform accepted the notification. Implementations must not throw:
     *   a platform refusal is a `false`.
     */
    fun present(delivery: PrayerReminderDelivery): Boolean
}

/** Why a fired alarm produced no visible reminder. */
enum class SuppressionReason {
    /** The global master switch is off. */
    MASTER_DISABLED,

    /** This target is disabled for this user. */
    TARGET_DISABLED,

    /** Notification permission is not granted, or notifications are turned off for the app. */
    NOTIFICATIONS_NOT_ALLOWED,

    /** The alarm fired far later than it was scheduled for, so showing it would be misleading. */
    STALE,
}

/** What happened when a fired alarm was delivered. */
sealed interface DeliveryOutcome {

    /** A reminder was posted. [azan] is the playback result for an at-time reminder, if attempted. */
    data class Posted(
        val channelId: String,
        val azan: AzanPlaybackResult? = null,
    ) : DeliveryOutcome

    /** Nothing was shown, deliberately, for [reason]. */
    data class Suppressed(
        val reason: SuppressionReason,
        val detail: String,
    ) : DeliveryOutcome

    /** Delivery was attempted and failed. */
    data class Failed(val detail: String) : DeliveryOutcome

    companion object {
        /**
         * How late a reminder may arrive and still be shown.
         *
         * A reminder that fires hours after its prayer — because the device was powered off, or
         * because a Doze maintenance window landed much later — is not useful information, it is
         * noise. Salah suppresses it and records why, rather than showing a notification the user
         * cannot act on.
         */
        const val STALE_TOLERANCE_MILLIS: Long = 30L * 60L * 1000L
    }
}
