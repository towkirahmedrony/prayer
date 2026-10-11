package io.github.towkirahmedrony.salah.data.notification

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import androidx.core.content.ContextCompat
import io.github.towkirahmedrony.salah.core.domain.DiagnosticsLogger
import io.github.towkirahmedrony.salah.core.domain.SalahDiagnosticEvents
import io.github.towkirahmedrony.salah.core.domain.notification.NotificationPermissionStatusProvider
import io.github.towkirahmedrony.salah.core.domain.notification.PlannedPrayerNotification
import io.github.towkirahmedrony.salah.core.domain.notification.PrayerAlarmSlot
import io.github.towkirahmedrony.salah.core.domain.notification.PrayerNotificationIds
import io.github.towkirahmedrony.salah.core.domain.notification.PrayerNotificationRequest
import io.github.towkirahmedrony.salah.core.domain.notification.PrayerNotificationScheduler

/**
 * [PrayerNotificationScheduler] over `AlarmManager`.
 *
 * **Why `AlarmManager`.** A prayer reminder is a wall-clock event: the user is told that Fajr is at
 * 05:12 and expects to hear about it then. `AlarmManager` is the platform mechanism for exactly
 * that. `WorkManager`, which Salah does use for nothing in this subsystem, is explicitly deferrable
 * and would silently trade punctuality for battery.
 *
 * **Why the alarm is an explicit broadcast to a non-exported receiver.** The `Intent` names
 * `PrayerNotificationReceiver` directly and carries no implicit action that another app could
 * resolve, so only Salah can deliver to it. The receiver is declared `android:exported="false"`,
 * which the system may still deliver to (it holds `SYSTEM_UID`) but no third-party app can.
 *
 * **Why the slot lives in the URI and the times in the extras.** `PendingIntent` equality ignores
 * extras. A stable per-slot data URI makes each alarm a distinct `PendingIntent`, while the moving
 * values ride in extras that `FLAG_UPDATE_CURRENT` refreshes in place. Both halves matter: without
 * the URI, two slots would collapse into one alarm; without extras-only times, rescheduling a moved
 * prayer time would orphan the old alarm instead of replacing it.
 *
 * **Exactness.** `setExactAndAllowWhileIdle` is used when `SCHEDULE_EXACT_ALARM` is granted, so a
 * reminder survives Doze. When it is not granted — which is the default state for an app targeting
 * API 33+ — Salah falls back to `setWindow` with a ten-minute window rather than throwing or
 * silently doing nothing. The fallback is real scheduling, honestly reported: the platform may run
 * it a few minutes late. Salah asks for the permission once, from the notification settings screen,
 * with an explanation, and never nags.
 *
 * Request codes and URIs come from [PrayerNotificationIds], so a slot has the same identity here,
 * in the receiver, and in the notification it produces.
 */
class AlarmManagerPrayerNotificationScheduler(
    private val context: Context,
    private val permissions: NotificationPermissionStatusProvider,
    private val logger: DiagnosticsLogger,
    private val now: () -> Long = System::currentTimeMillis,
) : PrayerNotificationScheduler {

    private val alarmManager: AlarmManager?
        get() = ContextCompat.getSystemService(context, AlarmManager::class.java)

    override fun schedule(notification: PlannedPrayerNotification) {
        val manager = alarmManager ?: return
        val triggerAtMillis = notification.triggerAt.toInstant().toEpochMilli()

        // A trigger that is already in the past must never be registered: `setExactAndAllowWhileIdle`
        // with a past time fires immediately, which is precisely the "stale reminder at the wrong
        // moment" failure this guards against. The planner already drops past events; this is the
        // second, independent check, because scheduling is the last moment it can be caught.
        if (triggerAtMillis <= now()) {
            logger.info(
                SalahDiagnosticEvents.ALARM_SKIPPED_PAST,
                mapOf("slot" to notification.slot.identity),
            )
            return
        }

        val pendingIntent = pendingIntentFor(notification.slot, request = notification)

        if (permissions.status().exactAlarmsAllowed) {
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
        } else {
            manager.setWindow(
                AlarmManager.RTC_WAKEUP,
                triggerAtMillis,
                PrayerNotificationScheduler.EXACT_ALARM_FALLBACK_WINDOW_MILLIS,
                pendingIntent,
            )
            logger.warn(
                SalahDiagnosticEvents.EXACT_ALARM_UNAVAILABLE,
                mapOf("slot" to notification.slot.identity),
            )
        }

        logger.info(
            SalahDiagnosticEvents.ALARM_SCHEDULED,
            mapOf(
                "slot" to notification.slot.identity,
                "advanceMinutes" to notification.advanceMinutes,
            ),
        )
    }

    override fun cancel(slot: PrayerAlarmSlot) {
        val manager = alarmManager ?: return
        // Cancelling needs a PendingIntent equal to the scheduled one. Equality is decided by the
        // request code and the Intent's action/data/component, so this matches the registered alarm
        // without any extras and without knowing whether one exists.
        val pendingIntent = pendingIntentFor(slot, request = null)
        manager.cancel(pendingIntent)
        pendingIntent.cancel()
        logger.info(SalahDiagnosticEvents.ALARM_CANCELLED, mapOf("slot" to slot.identity))
    }

    /**
     * Builds the alarm `PendingIntent` for [slot].
     *
     * @param request the values to carry, or `null` when only the identity is needed — the case
     *   `AlarmManager.cancel` needs. The data URI and the component are identical either way, which
     *   is what makes the cancellation target the same `PendingIntent` that was scheduled.
     */
    private fun pendingIntentFor(
        slot: PrayerAlarmSlot,
        request: PlannedPrayerNotification?,
    ): PendingIntent {
        val intent = Intent(context, PrayerNotificationReceiver::class.java).apply {
            // The stable identity. Unique per slot, unchanged when the slot is rescheduled.
            data = PrayerNotificationIds.uri(slot).toUri()
        }

        if (request != null) {
            val payload = PrayerNotificationRequest(
                slot = slot,
                triggerAtEpochMillis = request.triggerAt.toInstant().toEpochMilli(),
                eventAtEpochMillis = request.eventAt.toInstant().toEpochMilli(),
                advanceMinutes = request.advanceMinutes,
            )
            intent.putExtra(PrayerNotificationReceiver.EXTRA_PAYLOAD, payload.encode())
        }

        return PendingIntent.getBroadcast(
            context,
            PrayerNotificationIds.requestCode(slot),
            intent,
            // FLAG_UPDATE_CURRENT: rescheduling a slot replaces its alarm in place instead of
            // creating a second PendingIntent. FLAG_IMMUTABLE: the receiver never rewrites this.
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
