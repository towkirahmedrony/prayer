package io.github.towkirahmedrony.salah

import android.app.Application
import io.github.towkirahmedrony.salah.core.domain.notification.PrayerNotificationCoordinator
import io.github.towkirahmedrony.salah.core.domain.notification.ReconcileReason
import io.github.towkirahmedrony.salah.core.domain.repository.BangladeshAreaCatalog
import io.github.towkirahmedrony.salah.core.domain.repository.LocationRepository
import io.github.towkirahmedrony.salah.data.notification.AndroidNotificationChannels
import io.github.towkirahmedrony.salah.di.salahModules
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.logger.Level

/**
 * Application entry point.
 *
 * Startup work off the main thread: the bundled dataset and the stored location are read straight
 * away, in parallel with the Activity and Compose setup, because the first screen waits for them.
 * The alarm reconciliation runs only after both have finished, so it does not compete with them
 * for CPU. Everything here is non-fatal.
 */
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
        StartupTrace.mark("channels_created")
        warmUpThenReconcile()
        StartupTrace.mark("app_onCreate_end")
    }

    private fun createNotificationChannels() {
        runCatching { GlobalContext.get().get<AndroidNotificationChannels>().ensureCreated() }
    }

    private fun warmUpThenReconcile() {
        val koin = GlobalContext.get()
        val scope = runCatching { koin.get<CoroutineScope>() }.getOrNull() ?: return

        scope.launch {
            val dataset = async(Dispatchers.IO) {
                runCatching { koin.get<BangladeshAreaCatalog>().dataset() }
            }
            val location = async(Dispatchers.IO) {
                runCatching { koin.get<LocationRepository>().location.first() }
            }
            dataset.await()
            StartupTrace.mark("prefetch_dataset_done")
            location.await()
            StartupTrace.mark("prefetch_location_done")

            val coordinator = runCatching { koin.get<PrayerNotificationCoordinator>() }.getOrNull()
            if (coordinator != null) {
                StartupTrace.mark("reconcile_start")
                runCatching { coordinator.reconcile(ReconcileReason.APP_START) }
                StartupTrace.mark("reconcile_done")
            }
        }
    }
}
