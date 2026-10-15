package io.github.towkirahmedrony.salah

import android.app.Application
import io.github.towkirahmedrony.salah.core.domain.dataset.sync.DatasetSyncTrigger
import io.github.towkirahmedrony.salah.core.domain.dataset.sync.DatasetSynchronizer
import io.github.towkirahmedrony.salah.core.domain.location.BangladeshLocationPolicy
import io.github.towkirahmedrony.salah.core.domain.location.GeoPoint
import io.github.towkirahmedrony.salah.core.domain.notification.PrayerNotificationCoordinator
import io.github.towkirahmedrony.salah.core.domain.notification.ReconcileReason
import io.github.towkirahmedrony.salah.core.domain.prayerconfig.PrayerConfigApplier
import io.github.towkirahmedrony.salah.core.domain.prayerconfig.sync.PrayerConfigSyncTrigger
import io.github.towkirahmedrony.salah.core.domain.prayerconfig.sync.PrayerConfigSynchronizer
import io.github.towkirahmedrony.salah.core.domain.prayer.PrayerScheduleRequest
import io.github.towkirahmedrony.salah.core.domain.prayer.PrayerScheduleState
import io.github.towkirahmedrony.salah.core.domain.repository.BangladeshAreaCatalog
import io.github.towkirahmedrony.salah.core.domain.repository.PrayerScheduleRepository
import io.github.towkirahmedrony.salah.core.domain.startup.StartupSnapshot
import io.github.towkirahmedrony.salah.core.model.LocationInfo
import io.github.towkirahmedrony.salah.data.notification.AndroidNotificationChannels
import io.github.towkirahmedrony.salah.di.salahModules
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.Koin
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.logger.Level
import java.time.Clock
import java.time.ZonedDateTime

/**
 * Application entry point.
 *
 * Startup work is staged **Home-first**: the data the Home screen must have before it can show
 * correct prayer times is started and finished first, and everything else runs afterwards so it can
 * never hold Home on its skeleton.
 *
 *  1. The stored location and the prayer settings, read once into [StartupSnapshot]. The snapshot is
 *     created here rather than left to the first screen that needs it, because the gate and Home read
 *     it during the first composition: the values have to be in place before that, not looked for
 *     during it.
 *  2. Today's schedule for that location, calculated off the main thread. This overlaps the gate and
 *     Compose setup, so when Home opens its request is a cache hit rather than a fresh calculation.
 *  3. The bundled Bangladesh dataset and the notification reconciliation, in the background. Home does
 *     not depend on either, so neither is awaited before the phases above.
 *
 * Everything here is non-fatal: a failure in any phase just means the corresponding screen does its
 * own work when it appears.
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

        // Resolving the snapshot here is what starts the one shared read of the stored location and
        // the prayer settings. Left to a lazy `get()` from a view model it would not start until the
        // first screen asked for it — which is the first composition, the one moment it is too late
        // to spare.
        val snapshot = runCatching { koin.get<StartupSnapshot>() }.getOrNull()

        scope.launch {
            // Started in parallel; only the Home-critical values are awaited on this path.
            val dataset = async(Dispatchers.IO) {
                runCatching { koin.get<BangladeshAreaCatalog>().dataset() }
            }

            // `null` means no location is configured, which the gate handles; it is not a failure.
            val storedLocation = runCatching { snapshot?.awaitStoredLocation() }.getOrNull()
            StartupTrace.mark("prefetch_location_done")

            // Home-critical: today's schedule, so it is already cached when Home opens.
            warmHomeSchedule(koin, storedLocation, snapshot)

            // Non-Home work. The dataset is needed by the setup screen and later screens, never by
            // Home, so it is awaited here and not before the schedule warm-up.
            StartupTrace.mark("background_dataset_start")
            dataset.await()
            StartupTrace.mark("background_dataset_done")

            // Remote prayer configuration: fetch, validate and adopt a published revision, then reapply
            // the effective settings. It runs before the reconciliation so the reminders are planned from
            // the effective profile. It can never block or break anything: a missing configuration, no
            // network or an invalid response all leave the existing settings in force.
            val prayerConfigSynchronizer = runCatching { koin.get<PrayerConfigSynchronizer>() }.getOrNull()
            if (prayerConfigSynchronizer != null) {
                StartupTrace.mark("prayer_config_sync_start")
                withContext(Dispatchers.IO) {
                    runCatching {
                        prayerConfigSynchronizer.synchronize(PrayerConfigSyncTrigger.APP_START)
                    }
                }
                StartupTrace.mark("prayer_config_sync_done")
            }
            runCatching { koin.get<PrayerConfigApplier>() }.getOrNull()?.let { applier ->
                withContext(Dispatchers.IO) { runCatching { applier.apply() } }
                StartupTrace.mark("prayer_config_applied")
            }

            val coordinator = runCatching { koin.get<PrayerNotificationCoordinator>() }.getOrNull()
            if (coordinator != null) {
                StartupTrace.mark("reconcile_start")
                // On Dispatchers.IO rather than the CPU pool: the reconciliation is dominated by
                // blocking platform calls (AlarmManager, NotificationManager) and DataStore I/O, which
                // is what IO is for. Running it off Dispatchers.Default leaves that pool free for the
                // prayer-time calculation Home and the warm-up share.
                withContext(Dispatchers.IO) {
                    runCatching { coordinator.reconcile(ReconcileReason.APP_START) }
                }
                StartupTrace.mark("reconcile_done")
            }

            // Remote dataset publication check. Last and non-blocking on purpose: it must never delay
            // the location gate, Home or its schedules, so it runs after everything else. A missing
            // configuration, no connection or a failed check all simply leave the local dataset in
            // force — the bundled assets are the permanent floor. See docs/ARCHITECTURE.md section 13.
            val synchronizer = runCatching { koin.get<DatasetSynchronizer>() }.getOrNull()
            if (synchronizer != null) {
                StartupTrace.mark("dataset_sync_start")
                withContext(Dispatchers.IO) {
                    runCatching { synchronizer.synchronize(DatasetSyncTrigger.APP_START) }
                }
                StartupTrace.mark("dataset_sync_done")
            }
        }
    }

    /**
     * Calculates today's schedule for the stored location, ahead of the Home screen.
     *
     * With a valid location but no cached schedule, the schedule is produced off the main thread as
     * soon as the location is known, so the calculation overlaps the location gate and Compose setup
     * instead of starting after the gate opens. It uses the same repository and the same request key
     * the Home screen will use — the date in the location's own zone, the location, and the persisted
     * calculation profile — so Home only receives the entry when it genuinely matches the location and
     * settings in force.
     *
     * A location outside the supported country is skipped: the gate will send the user to setup and
     * nothing would ever display those times. Non-fatal throughout — a failure here just means Home
     * calculates on open, exactly as it would without this warm-up.
     */
    private suspend fun warmHomeSchedule(
        koin: Koin,
        location: LocationInfo?,
        snapshot: StartupSnapshot?,
    ) {
        if (location == null || snapshot == null) return
        val insideSupportedCountry = BangladeshLocationPolicy.isWithinCoarseBounds(
            GeoPoint(latitude = location.latitude, longitude = location.longitude),
        )
        if (!insideSupportedCountry) return

        val schedules = runCatching { koin.get<PrayerScheduleRepository>() }.getOrNull() ?: return
        // From the snapshot, so this warm-up and Home calculate the profile the same way instead of
        // reading the store twice and agreeing only by coincidence.
        val settings = runCatching { snapshot.awaitPrayerSettings() }.getOrNull() ?: return

        val request = PrayerScheduleRequest(
            date = ZonedDateTime.ofInstant(koin.get<Clock>().instant(), location.zoneId).toLocalDate(),
            location = location,
            profile = settings.toProfile(),
        )

        val state = runCatching { schedules.ensureSchedule(request).first() }.getOrNull()
        if (state is PrayerScheduleState.Available) {
            StartupTrace.mark("home_cached_schedule_ready")
        }
    }
}
