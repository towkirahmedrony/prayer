package io.github.towkirahmedrony.salah.data.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.towkirahmedrony.salah.core.domain.notification.PrayerNotificationCoordinator
import io.github.towkirahmedrony.salah.core.domain.notification.ReconcileReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Rebuilds the alarm schedule when the world changes underneath it.
 *
 * Registered for the system broadcasts that can invalidate a schedule, and nothing else:
 *
 * | Broadcast | Why it matters |
 * |---|---|
 * | `BOOT_COMPLETED`, `LOCKED_BOOT_COMPLETED` | Alarms do **not** survive a reboot. Without this, notifications stop silently until the user next opens the app. |
 * | `MY_PACKAGE_REPLACED` | An app update drops the app's alarms; they are rebuilt immediately rather than at the next launch. |
 * | `TIME_SET` | The user corrected the clock, so every absolute trigger instant moved. |
 * | `TIMEZONE_CHANGED` | The local date may have changed, and with it which schedule applies. |
 * | `DATE_CHANGED` | Fired at local midnight: the rolling two-day window must advance so tomorrow's reminders are registered. |
 *
 * All four time-related broadcasts map to one [ReconcileReason.TIME_OR_DATE_CHANGED] because the
 * correct response is identical for every one of them: discard the derived window and rebuild it
 * from the current instant.
 *
 * `LOCKED_BOOT_COMPLETED` is handled so that a phone rebooted before first unlock still recovers.
 * It only fires because Salah's receiver is *not* `directBootAware`-dependent: the work is deferred
 * to the application scope and reads DataStore, which is available once the user unlocks. If the
 * alarm set cannot be rebuilt at that moment it is rebuilt at the next trigger — boot recovery is
 * idempotent, so a missed round is not a lost feature.
 *
 * The receiver does no database migration, no network request and no heavy work inline: it defers
 * to the application scope under [goAsync], so it stays well inside the platform's receiver budget.
 *
 * It is declared `exported="true"` because these are broadcasts the *system* sends to manifest
 * receivers; every action listed is a protected broadcast that only the system may send, so
 * exporting it does not let another app trigger a reconciliation. The work it does is idempotent
 * regardless.
 */
class SystemEventReconciliationReceiver : BroadcastReceiver(), KoinComponent {

    private val coordinator: PrayerNotificationCoordinator by inject()
    private val applicationScope: CoroutineScope by inject()

    override fun onReceive(context: Context, intent: Intent) {
        val reason = intent.action.toReconcileReason()
        val pendingResult = goAsync()
        applicationScope.launch {
            try {
                coordinator.reconcile(reason)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun String?.toReconcileReason(): ReconcileReason = when (this) {
        Intent.ACTION_BOOT_COMPLETED,
        Intent.ACTION_LOCKED_BOOT_COMPLETED,
        -> ReconcileReason.BOOT_COMPLETED

        Intent.ACTION_MY_PACKAGE_REPLACED -> ReconcileReason.PACKAGE_REPLACED

        Intent.ACTION_TIME_CHANGED,
        Intent.ACTION_TIMEZONE_CHANGED,
        Intent.ACTION_DATE_CHANGED,
        -> ReconcileReason.TIME_OR_DATE_CHANGED

        // Anything else is not a trigger Salah recognises; reconciling anyway is harmless but
        // logging it as an explicit request keeps the reason codes honest.
        else -> ReconcileReason.EXPLICIT
    }
}
