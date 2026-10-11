package io.github.towkirahmedrony.salah

import android.app.Application
import io.github.towkirahmedrony.salah.core.domain.notification.ReconcileReason
import io.github.towkirahmedrony.salah.data.notification.AndroidNotificationChannels
import io.github.towkirahmedrony.salah.core.domain.notification.PrayerNotificationCoordinator
import io.github.towkirahmedrony.salah.di.salahModules
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.logger.Level

/**
 * Application entry point.
 *
 * Dependency wiring starts once here, before any UI exists, so every screen resolves its
 * dependencies from the container instead of constructing them itself. The same container is used
 * by the broadcast receivers, which is why the reboot and alarm paths share one scheduler with the
 * screens rather than each building their own.
 *
 * Two pieces of startup work happen here beyond wiring:
 *
 *  1. **Notification channels are created.** They must exist before the first notification is
 *     posted, and creating them at startup means a reminder that fires while the app is closed
 *     still has its channel. Channel creation is idempotent and never overwrites a setting the user
 *     has customised.
 *  2. **The schedule is reconciled.** An app launch is one of the events that can invalidate the
 *     alarm set — the date may have moved on while the device was off, a setting may have changed,
 *     or the alarms may have been dropped after an update. Reconciling here makes opening Salah a
 *     repair step rather than only a display step.
 *
 * Both are deliberately non-fatal: a failure in the notification subsystem must never stop the app
 * from starting.
 */
class SalahApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidLogger(Level.ERROR)
            androidContext(this@SalahApplication)
            modules(salahModules)
        }

        createNotificationChannels()
        reconcileSchedules()
    }

    /**
     * Creates the reminder channels.
     *
     * Wrapped because a channel-creation failure is a degraded notification experience, not a
     * reason to crash on launch. Nothing is swallowed silently: the coordinator logs the resulting
     * scheduling state, and a notification that cannot be posted is reported when delivery is
     * attempted.
     */
    private fun createNotificationChannels() {
        runCatching { GlobalContext.get().get<AndroidNotificationChannels>().ensureCreated() }
    }

    /**
     * Rebuilds the alarm set in the background.
     *
     * Launched on the application scope so startup is not blocked: reading preferences and
     * calculating two days of times takes microseconds, but it is I/O and arithmetic and has no
     * business on the main thread.
     */
    private fun reconcileSchedules() {
        val koin = GlobalContext.get()
        val scope = runCatching { koin.get<CoroutineScope>() }.getOrNull() ?: return
        val coordinator = runCatching { koin.get<PrayerNotificationCoordinator>() }.getOrNull() ?: return

        scope.launch {
            runCatching { coordinator.reconcile(ReconcileReason.APP_START) }
        }
    }
}
