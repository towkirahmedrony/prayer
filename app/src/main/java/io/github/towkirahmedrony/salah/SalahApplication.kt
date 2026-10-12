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

/** Application entry point: wires dependencies, creates channels, reconciles alarms in the background. */
class SalahApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        StartupTrace.mark("app_onCreate_start")
        startKoin {
            androidLogger(Level.ERROR)
            androidContext(this@SalahApplication)
            modules(salahModules)
        }
        StartupTrace.mark("koin_started")

        createNotificationChannels()
        reconcileSchedules()
        StartupTrace.mark("app_onCreate_end")
    }

    private fun createNotificationChannels() {
        runCatching { GlobalContext.get().get<AndroidNotificationChannels>().ensureCreated() }
    }

    private fun reconcileSchedules() {
        val koin = GlobalContext.get()
        val scope = runCatching { koin.get<CoroutineScope>() }.getOrNull() ?: return
        val coordinator = runCatching { koin.get<PrayerNotificationCoordinator>() }.getOrNull() ?: return

        scope.launch {
            runCatching { coordinator.reconcile(ReconcileReason.APP_START) }
        }
    }
}
