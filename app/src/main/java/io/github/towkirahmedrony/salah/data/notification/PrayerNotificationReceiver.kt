package io.github.towkirahmedrony.salah.data.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.towkirahmedrony.salah.core.domain.notification.PrayerNotificationCoordinator
import io.github.towkirahmedrony.salah.core.domain.notification.PrayerNotificationDeliverer
import io.github.towkirahmedrony.salah.core.domain.notification.PrayerNotificationRequest
import io.github.towkirahmedrony.salah.core.domain.notification.ReconcileReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * The alarm that fires when a prayer reminder is due.
 *
 * Declared `android:exported="false"` and targeted by an explicit component, so only Salah's own
 * `PendingIntent`s can reach it — no other app can trigger a prayer notification.
 *
 * `onReceive` runs on the main thread with a hard time budget, so all the real work — reading
 * settings, checking permission, posting — happens on an application-scoped coroutine while
 * [goAsync] keeps the process alive. That is the supported way to do asynchronous work from a
 * receiver; no service is started and nothing keeps running afterwards.
 *
 * After delivering, the receiver asks for a reconciliation. A reminder firing is the one moment
 * Salah is guaranteed to be awake, so it is the natural point to roll the two-day window forward —
 * and if an alarm somehow arrived when the window had lapsed, this repairs the schedule without
 * waiting for the next system broadcast.
 *
 * A malformed or foreign intent is ignored: [PrayerNotificationRequest.decode] returns `null`
 * rather than throwing, so nothing a broadcast can contain will crash the app.
 */
class PrayerNotificationReceiver : BroadcastReceiver(), KoinComponent {

    private val deliverer: PrayerNotificationDeliverer by inject()
    private val coordinator: PrayerNotificationCoordinator by inject()
    private val applicationScope: CoroutineScope by inject()

    override fun onReceive(context: Context, intent: Intent) {
        val request = PrayerNotificationRequest.decode(
            slotUri = intent.data?.toString(),
            payload = intent.getStringExtra(EXTRA_PAYLOAD),
        ) ?: return

        val pendingResult = goAsync()
        applicationScope.launch {
            try {
                deliverer.deliver(request)
                // Deliberately after delivery: the notification must not wait on a recalculation.
                coordinator.reconcile(ReconcileReason.AFTER_DELIVERY)
            } finally {
                // Must run on every path, or the platform holds the process against its budget.
                pendingResult.finish()
            }
        }
    }

    companion object {
        /** The extra that carries the encoded [PrayerNotificationRequest] payload. */
        const val EXTRA_PAYLOAD = "io.github.towkirahmedrony.salah.extra.PRAYER_REMINDER"
    }
}
