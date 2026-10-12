package io.github.towkirahmedrony.salah.data.notification

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.towkirahmedrony.salah.MainActivity
import io.github.towkirahmedrony.salah.R
import io.github.towkirahmedrony.salah.core.domain.notification.PrayerNotificationIds
import io.github.towkirahmedrony.salah.core.domain.notification.PrayerNotificationPresenter
import io.github.towkirahmedrony.salah.core.domain.notification.PrayerReminderDelivery
import io.github.towkirahmedrony.salah.core.domain.notification.ReminderTarget
import io.github.towkirahmedrony.salah.core.domain.timeFormatterFor
import io.github.towkirahmedrony.salah.core.model.PrayerName
import java.util.Locale

/**
 * Renders a resolved reminder with `NotificationCompat`.
 *
 * The renderer is deliberately dumb: it formats text and posts. Every decision about *whether* a
 * reminder should appear was already made upstream (settings, permission, staleness) and the
 * channel was chosen from the same settings, so this class has no policy to get wrong.
 *
 * Deliberate choices:
 *
 *  - **Identity is `(tag, id)` from [PrayerNotificationIds].** Re-delivering the same slot replaces
 *    the existing notification rather than stacking a second one, so even a duplicated broadcast
 *    cannot produce duplicate visible reminders.
 *  - **No full-screen intent.** A prayer reminder must not take over the screen; it is a
 *    notification, and if the user is in a call or driving, Android's own heads-up behaviour — not
 *    Salah — decides what happens.
 *  - **`setSound`/`setVibrate` are not called.** Since API 26 the channel owns both, and calling
 *    them would be misleading no-ops.
 *  - **Notification text names the prayer and the real calculated time**, taken from the schedule
 *    the reminder was planned from. No religious claim is made about the user's obligation, and no
 *    wording implies Salah verified anything it did not.
 *  - **Tapping opens Salah.** One shared content intent, because every reminder leads to the same
 *    place.
 */
class AndroidPrayerNotificationPresenter(
    private val context: Context,
) : PrayerNotificationPresenter {

    /**
     * Posts the reminder.
     *
     * Permission is checked here as well as upstream. This is the last gate before the platform
     * call, and `notify` is annotated as requiring `POST_NOTIFICATIONS` on API 33+, so the explicit
     * granted-check is what makes the suppression annotation below truthful rather than a way of
     * silencing a lint warning.
     */
    @SuppressLint("MissingPermission")
    override fun present(delivery: PrayerReminderDelivery): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false

        return try {
            val notification = NotificationCompat.Builder(context, delivery.channelId)
                .setSmallIcon(R.drawable.ic_notification_prayer)
                .setContentTitle(title(delivery))
                .setContentText(body(delivery))
                .setStyle(NotificationCompat.BigTextStyle().bigText(body(delivery)))
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setPriority(
                    if (delivery.sound.isSilent) {
                        NotificationCompat.PRIORITY_DEFAULT
                    } else {
                        NotificationCompat.PRIORITY_HIGH
                    },
                )
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openAppIntent())
                .build()

            NotificationManagerCompat.from(context).notify(
                PrayerNotificationIds.tag(delivery.slot),
                PrayerNotificationIds.notificationId(delivery.slot),
                notification,
            )
            true
        } catch (error: SecurityException) {
            // Raised when POST_NOTIFICATIONS was revoked between the check above and the post.
            false
        }
    }

    private fun title(delivery: PrayerReminderDelivery): String {
        when (delivery.slot.target) {
            // Sehri is always a lead-in, so its title never claims to be the moment itself.
            ReminderTarget.SEHRI -> return context.getString(
                R.string.notification_title_sehri_advance,
                minutesLabel(delivery.advanceMinutes),
            )

            ReminderTarget.IFTAR -> return if (delivery.isAtEventTime) {
                context.getString(R.string.notification_title_iftar_time)
            } else {
                context.getString(
                    R.string.notification_title_iftar_advance,
                    minutesLabel(delivery.advanceMinutes),
                )
            }

            else -> Unit
        }

        val name = targetName(delivery)

        if (delivery.isAtEventTime) {
            return if (delivery.slot.target == ReminderTarget.SOLAR) {
                context.getString(R.string.notification_title_sunrise)
            } else {
                context.getString(R.string.notification_title_prayer_time, name)
            }
        }

        return context.getString(
            R.string.notification_title_prayer_advance,
            name,
            minutesLabel(delivery.advanceMinutes),
        )
    }

    /** `10 minutes` as a plural resource, so a locale with different plural rules gets the right form. */
    private fun minutesLabel(minutes: Int): String =
        context.resources.getQuantityString(R.plurals.notification_minutes, minutes, minutes)

    private fun body(delivery: PrayerReminderDelivery): String {
        val time = timeFormatterFor(
            zone = delivery.eventAt.zone,
            locale = Locale.getDefault(),
            preference = delivery.timeFormat,
        ).format(delivery.eventAt)

        when (delivery.slot.target) {
            // Every Ramadan body names the prayer time Sehri or Iftar is derived from, so the user can
            // see that Salah did not invent a separate Sehri or Iftar timetable.
            ReminderTarget.SEHRI -> return context.getString(R.string.notification_body_sehri_advance, time)
            ReminderTarget.IFTAR -> return context.getString(
                if (delivery.isAtEventTime) {
                    R.string.notification_body_iftar_time
                } else {
                    R.string.notification_body_iftar_advance
                },
                time,
            )

            else -> Unit
        }

        return if (delivery.slot.target == ReminderTarget.SOLAR) {
            // Sunrise is stated as a solar event so a reminder can never read as a call to an
            // obligatory prayer.
            context.getString(R.string.notification_body_sunrise, time)
        } else if (delivery.isAtEventTime) {
            context.getString(R.string.notification_body_prayer_time, targetName(delivery), time)
        } else {
            context.getString(R.string.notification_body_prayer_advance, targetName(delivery), time)
        }
    }

    private fun targetName(delivery: PrayerReminderDelivery): String {
        val prayer: PrayerName? = delivery.slot.target.prayer
        return when {
            prayer != null -> context.getString(prayerNameRes(prayer))
            delivery.slot.target == ReminderTarget.SEHRI -> context.getString(R.string.calendar_sehri_end)
            delivery.slot.target == ReminderTarget.IFTAR -> context.getString(R.string.calendar_iftar)
            else -> context.getString(R.string.notification_title_sunrise)
        }
    }

    private fun prayerNameRes(prayer: PrayerName): Int = when (prayer) {
        PrayerName.FAJR -> R.string.prayer_fajr
        PrayerName.DHUHR -> R.string.prayer_dhuhr
        PrayerName.ASR -> R.string.prayer_asr
        PrayerName.MAGHRIB -> R.string.prayer_maghrib
        PrayerName.ISHA -> R.string.prayer_isha
    }

    /** One shared content intent: every reminder opens the same screen. */
    private fun openAppIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            CONTENT_INTENT_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private companion object {
        const val CONTENT_INTENT_REQUEST_CODE = 1000
    }
}
