package io.github.towkirahmedrony.salah.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import io.github.towkirahmedrony.salah.BuildConfig
import io.github.towkirahmedrony.salah.core.domain.CurrentPrayerPeriodResolver
import io.github.towkirahmedrony.salah.core.domain.DiagnosticsLogger
import io.github.towkirahmedrony.salah.core.domain.NextPrayerResolver
import io.github.towkirahmedrony.salah.core.domain.calendar.BanglaCalendar
import io.github.towkirahmedrony.salah.core.domain.calendar.CalendarFormatting
import io.github.towkirahmedrony.salah.core.domain.calendar.CalendarMonthResolver
import io.github.towkirahmedrony.salah.core.domain.calendar.FastingCountdownResolver
import io.github.towkirahmedrony.salah.core.domain.calendar.GregorianCalendarGrid
import io.github.towkirahmedrony.salah.core.domain.calendar.HijriCalendar
import io.github.towkirahmedrony.salah.core.domain.calendar.RamadanCalendar
import io.github.towkirahmedrony.salah.core.domain.location.LocationDayZone
import io.github.towkirahmedrony.salah.core.domain.night.NightPeriodCalculator
import io.github.towkirahmedrony.salah.core.domain.solar.SolarDayResolver
import io.github.towkirahmedrony.salah.core.domain.notification.AudioPlaybackEngine
import io.github.towkirahmedrony.salah.core.domain.notification.AzanAudioSource
import io.github.towkirahmedrony.salah.core.domain.notification.AzanPlaybackManager
import io.github.towkirahmedrony.salah.core.domain.notification.NotificationPermissionStatusProvider
import io.github.towkirahmedrony.salah.core.domain.notification.NotificationSoundPreviewer
import io.github.towkirahmedrony.salah.core.domain.notification.PrayerNotificationPlanner
import io.github.towkirahmedrony.salah.core.domain.notification.PrayerNotificationPresenter
import io.github.towkirahmedrony.salah.core.domain.notification.PrayerNotificationScheduler
import io.github.towkirahmedrony.salah.core.domain.prayer.PrayerTimesProvider
import io.github.towkirahmedrony.salah.core.domain.prohibited.ConfiguredProhibitedRuleSet
import io.github.towkirahmedrony.salah.core.domain.prohibited.ProhibitedPeriodCalculator
import io.github.towkirahmedrony.salah.core.domain.prohibited.ProhibitedPeriodStatusResolver
import io.github.towkirahmedrony.salah.core.domain.qibla.CompassSensorSource
import io.github.towkirahmedrony.salah.core.domain.qibla.GeomagneticDeclinationProvider
import io.github.towkirahmedrony.salah.core.domain.qibla.LocationPermissionChecker
import io.github.towkirahmedrony.salah.core.domain.qibla.LocationProvider
import io.github.towkirahmedrony.salah.core.domain.qibla.QiblaCalculator
import io.github.towkirahmedrony.salah.core.domain.prohibited.ProhibitedRuleBook
import io.github.towkirahmedrony.salah.core.domain.dataset.ActiveDatasetStore
import io.github.towkirahmedrony.salah.core.domain.dataset.remote.DatasetRemoteConfig
import io.github.towkirahmedrony.salah.core.domain.dataset.remote.RemoteDatasetSource
import io.github.towkirahmedrony.salah.core.domain.dataset.sync.DatasetSyncStateStore
import io.github.towkirahmedrony.salah.core.domain.dataset.sync.DatasetSynchronizer
import io.github.towkirahmedrony.salah.core.domain.prayerconfig.ActivePrayerConfigStore
import io.github.towkirahmedrony.salah.core.domain.prayerconfig.PrayerConfigApplier
import io.github.towkirahmedrony.salah.core.domain.prayerconfig.PrayerConfigResolver
import io.github.towkirahmedrony.salah.core.domain.prayerconfig.remote.PrayerConfigRemoteConfig
import io.github.towkirahmedrony.salah.core.domain.prayerconfig.remote.RemotePrayerConfigSource
import io.github.towkirahmedrony.salah.core.domain.prayerconfig.sync.PrayerConfigSyncStateStore
import io.github.towkirahmedrony.salah.core.domain.prayerconfig.sync.PrayerConfigSynchronizer
import io.github.towkirahmedrony.salah.core.domain.repository.BangladeshAreaCatalog
import io.github.towkirahmedrony.salah.core.domain.repository.CalendarRepository
import io.github.towkirahmedrony.salah.core.domain.repository.LocationRepository
import io.github.towkirahmedrony.salah.core.domain.repository.PrayerNotificationPreferencesRepository
import io.github.towkirahmedrony.salah.core.domain.repository.PrayerScheduleRepository
import io.github.towkirahmedrony.salah.core.domain.repository.ProhibitedPeriodsRepository
import io.github.towkirahmedrony.salah.core.domain.repository.UserPreferencesRepository
import io.github.towkirahmedrony.salah.core.domain.startup.StartupSnapshot
import io.github.towkirahmedrony.salah.data.calendar.DeviceClockCalendarRepository
import io.github.towkirahmedrony.salah.data.dataset.LocalDatasetStore
import io.github.towkirahmedrony.salah.data.dataset.remote.HttpTransport
import io.github.towkirahmedrony.salah.data.dataset.remote.SupabaseRemoteDatasetSource
import io.github.towkirahmedrony.salah.data.dataset.remote.UrlConnectionHttpTransport
import io.github.towkirahmedrony.salah.data.dataset.sync.FileDatasetSyncStateStore
import io.github.towkirahmedrony.salah.data.location.AssetBangladeshAreaCatalog
import io.github.towkirahmedrony.salah.data.location.DataStoreLocationRepository
import io.github.towkirahmedrony.salah.data.notification.AlarmManagerPrayerNotificationScheduler
import io.github.towkirahmedrony.salah.data.notification.AndroidDiagnosticsLogger
import io.github.towkirahmedrony.salah.data.notification.AndroidNotificationChannels
import io.github.towkirahmedrony.salah.data.notification.AndroidNotificationPermissionStatusProvider
import io.github.towkirahmedrony.salah.data.notification.AndroidNotificationSoundPreviewer
import io.github.towkirahmedrony.salah.data.notification.AndroidPrayerNotificationPresenter
import io.github.towkirahmedrony.salah.data.notification.BundledAzanAudioSource
import io.github.towkirahmedrony.salah.data.notification.DataStorePrayerNotificationPreferencesRepository
import io.github.towkirahmedrony.salah.data.notification.MediaPlayerAudioPlaybackEngine
import io.github.towkirahmedrony.salah.core.domain.notification.PrayerNotificationCoordinator
import io.github.towkirahmedrony.salah.core.domain.notification.PrayerNotificationDeliverer
import io.github.towkirahmedrony.salah.data.preferences.DataStoreUserPreferencesRepository
import io.github.towkirahmedrony.salah.data.preferences.salahPreferencesDataStore
import io.github.towkirahmedrony.salah.data.prayer.AdhanPrayerTimesProvider
import io.github.towkirahmedrony.salah.data.prayer.ProviderBackedPrayerScheduleRepository
import io.github.towkirahmedrony.salah.data.prayerconfig.FilePrayerConfigStore
import io.github.towkirahmedrony.salah.data.prayerconfig.remote.SupabaseRemotePrayerConfigSource
import io.github.towkirahmedrony.salah.data.prayerconfig.sync.FilePrayerConfigSyncStateStore
import io.github.towkirahmedrony.salah.data.prohibited.RuleBookProhibitedPeriodsRepository
import io.github.towkirahmedrony.salah.data.qibla.AndroidCompassSensorSource
import io.github.towkirahmedrony.salah.data.qibla.AndroidGeomagneticDeclinationProvider
import io.github.towkirahmedrony.salah.data.qibla.AndroidLocationPermissionChecker
import io.github.towkirahmedrony.salah.data.qibla.AndroidLocationProvider
import io.github.towkirahmedrony.salah.feature.app.AppViewModel
import io.github.towkirahmedrony.salah.feature.calendar.CalendarSettingsViewModel
import io.github.towkirahmedrony.salah.feature.calendar.CalendarUiStateFactory
import io.github.towkirahmedrony.salah.feature.calendar.CalendarViewModel
import io.github.towkirahmedrony.salah.feature.home.HomeUiStateFactory
import io.github.towkirahmedrony.salah.feature.home.HomeViewModel
import io.github.towkirahmedrony.salah.feature.location.LocationViewModel
import io.github.towkirahmedrony.salah.feature.notifications.PrayerNotificationSettingsViewModel
import io.github.towkirahmedrony.salah.feature.qibla.QiblaViewModel
import io.github.towkirahmedrony.salah.feature.ramadan.RamadanUiStateFactory
import io.github.towkirahmedrony.salah.feature.ramadan.RamadanViewModel
import io.github.towkirahmedrony.salah.feature.settings.SettingsViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module
import java.io.File
import java.time.Clock

/**
 * Where an applied (validated, non-bundled) dataset is kept, under `filesDir`.
 *
 * Namespaced by dataset so a second independently published dataset can be added later without moving
 * the first one. The bundled assets are unaffected by anything here.
 */
private const val APPLIED_DATASET_DIRECTORY = "datasets/bd_locations"

/**
 * Where the remote synchronisation state is kept, beside the applied dataset and outside the backup set.
 * See [io.github.towkirahmedrony.salah.data.dataset.sync.FileDatasetSyncStateStore].
 */
private const val DATASET_SYNC_STATE_FILE = "datasets/bd_locations.sync"

/**
 * Where the accepted prayer configuration is kept, under `filesDir` and outside the backup set.
 *
 * Namespaced by slot so a second independently published configuration can be added later without moving
 * the first one. See [io.github.towkirahmedrony.salah.data.prayerconfig.FilePrayerConfigStore].
 */
private const val APPLIED_PRAYER_CONFIG_DIRECTORY = "prayer_config"

/** Where the prayer-configuration synchronisation state is kept, beside the stored configuration. */
private const val PRAYER_CONFIG_SYNC_STATE_FILE = "prayer_config.sync"

/**
 * Single dependency container for Salah.
 *
 * Bindings are declared against domain interfaces, so swapping the prayer provider, the
 * persistence layer, the alarm backing or the Azan audio source is a change here and nowhere else.
 * That is what lets the notification scheduler be unit-tested with fakes for every one of its
 * collaborators while the app runs against the real platform implementations.
 */
val salahModules = module {

    // UTC rather than the default zone: every consumer converts to the zone it actually needs
    // (the prayer location's zone, or the device's current zone) at the moment it needs it, so a
    // device time-zone change takes effect immediately instead of after a process restart.
    single<Clock> { Clock.systemUTC() }

    /**
     * Application-lifetime scope for work that must outlive a broadcast receiver.
     *
     * A manifest receiver gets a short time budget, so the coordinator runs here under `goAsync()`
     * instead of on the receiver's main thread. One shared scope, so nothing can leak a scope per
     * broadcast.
     */
    single<CoroutineScope> { CoroutineScope(SupervisorJob() + Dispatchers.Default) }

    // Diagnostics -------------------------------------------------------------
    // Background behaviour — alarms, receivers, reboot recovery — has no screen, so the log is the
    // only evidence it ran. The port keeps the domain testable with a recording fake.
    single<DiagnosticsLogger> { AndroidDiagnosticsLogger() }

    // Local persistence -------------------------------------------------------
    // One DataStore for every preference in Salah: calculation settings, theme, location and
    // notification settings. A second store could disagree with the first about whether
    // notifications are on.
    single<DataStore<Preferences>> { androidContext().salahPreferencesDataStore }
    single<UserPreferencesRepository> { DataStoreUserPreferencesRepository(get()) }
    single<LocationRepository> { DataStoreLocationRepository(get()) }
    // The Bangladesh geography the app runs on: divisions, districts, upazilas and thanas with their
    // published reference coordinates, plus the national boundary the country policy checks against.
    //
    // Two sources, one port. The bundled assets are parsed by AssetBangladeshAreaCatalog exactly as
    // they always were, and LocalDatasetStore puts a validated, applied dataset in front of them when
    // one exists — falling back to the bundled assets when none does, or when the applied one can no
    // longer be read. Callers still ask a BangladeshAreaCatalog for "the dataset" and get whichever is
    // in force, so nothing above this line has to know the concept exists.
    //
    // The applied dataset lives under filesDir, not in DataStore: it is replaceable data whose
    // consistency with the files on disk is the whole point, so it is kept beside them. See
    // docs/ARCHITECTURE.md section 12 and docs/LOCATION_SYSTEM.md section 5.
    single {
        LocalDatasetStore(
            root = File(androidContext().filesDir, APPLIED_DATASET_DIRECTORY),
            bundled = AssetBangladeshAreaCatalog(androidContext(), get()),
            logger = get(),
            clock = get(),
        )
    }
    single<BangladeshAreaCatalog> { get<LocalDatasetStore>() }
    // The same instance, seen through the port the remote synchroniser applies payloads through. Binding
    // it twice keeps exactly one store — and therefore one activation system — while letting the
    // synchroniser depend on the domain port instead of the filesystem implementation.
    single<ActiveDatasetStore> { get<LocalDatasetStore>() }

    // Remote dataset publication ---------------------------------------------
    // Supabase is the publication source for future remotely managed data; this phase wires only the
    // location dataset. The endpoint and publishable key are compiled in (see app/build.gradle.kts) and
    // are empty by default, which is the supported offline configuration: the synchroniser reports
    // "not configured" and never reaches the network. No privileged key is ever placed here.
    single {
        DatasetRemoteConfig(
            baseUrl = BuildConfig.SALAH_SUPABASE_URL,
            anonKey = BuildConfig.SALAH_SUPABASE_ANON_KEY,
        )
    }
    single<HttpTransport> { UrlConnectionHttpTransport() }
    single<RemoteDatasetSource> { SupabaseRemoteDatasetSource(transport = get(), config = get()) }
    single<DatasetSyncStateStore> {
        FileDatasetSyncStateStore(
            file = File(androidContext().filesDir, DATASET_SYNC_STATE_FILE),
            logger = get(),
        )
    }
    // The one place a remote publication can become the active dataset. It reads the user's saved area
    // only as an input to activation and has no way to write user data, so a failed sync cannot clear it.
    single {
        DatasetSynchronizer(
            config = get(),
            source = get(),
            store = get<ActiveDatasetStore>(),
            storedArea = { get<LocationRepository>().location.first()?.area },
            stateStore = get(),
            logger = get(),
            clock = get(),
        )
    }

    // Remote prayer-configuration publication --------------------------------
    // The same non-secret Supabase project and publishable key as the dataset source, and the same
    // HttpTransport: this phase adds a second published slot, not a second networking client. The default
    // build compiles the URL and key empty, so PrayerConfigRemoteConfig.isConfigured is false and the
    // synchroniser reports "not configured" without touching the network.
    single {
        PrayerConfigRemoteConfig(
            baseUrl = BuildConfig.SALAH_SUPABASE_URL,
            anonKey = BuildConfig.SALAH_SUPABASE_ANON_KEY,
        )
    }
    single<RemotePrayerConfigSource> { SupabaseRemotePrayerConfigSource(transport = get(), config = get()) }
    single<ActivePrayerConfigStore> {
        FilePrayerConfigStore(
            root = File(androidContext().filesDir, APPLIED_PRAYER_CONFIG_DIRECTORY),
            logger = get(),
        )
    }
    single<PrayerConfigSyncStateStore> {
        FilePrayerConfigSyncStateStore(
            file = File(androidContext().filesDir, PRAYER_CONFIG_SYNC_STATE_FILE),
            logger = get(),
        )
    }
    single { PrayerConfigResolver() }
    // Resolves the effective profile once and writes the non-overridden fields back into the one settings
    // store, so every existing reader of PrayerSettings needs no change. It writes exactly one preference
    // group and never touches the time format, the theme, the calendar settings or the location.
    single {
        PrayerConfigApplier(
            resolver = get(),
            userPreferences = get(),
            store = get<ActivePrayerConfigStore>(),
            locations = get(),
            logger = get(),
        )
    }
    single {
        PrayerConfigSynchronizer(
            config = get(),
            source = get(),
            store = get<ActivePrayerConfigStore>(),
            // The active location dataset, read to validate area references in a publication. It is an
            // input to validation and is never written by this path.
            areaDataset = { get<BangladeshAreaCatalog>().dataset() },
            stateStore = get(),
            logger = get(),
            clock = get(),
            // After a validated revision becomes active, the effective settings are reapplied so the
            // recalculation and the reminder reconciliation that follow use the new profile.
            onActivated = { get<PrayerConfigApplier>().apply() },
        )
    }

    single<PrayerNotificationPreferencesRepository> {
        DataStorePrayerNotificationPreferencesRepository(get())
    }

    // Start-up snapshot -------------------------------------------------------
    // The two values the location gate and Home need before they can render anything correct, read
    // once and published hot so the first composition can read them without waiting for a dispatch
    // the first frame would queue behind. Resolved during application start-up; see StartupSnapshot.
    single {
        StartupSnapshot(
            storedLocationSource = get<LocationRepository>().location,
            prayerSettingsSource = get<UserPreferencesRepository>().prayerSettings,
            scope = get(),
        )
    }

    // Location-local day ------------------------------------------------------
    // One authoritative answer to "which zone is a location-dependent civil day decided in": the
    // configured location's own zone, or the device's own when nothing is configured. The calendar
    // repository and the Calendar screen both read this same instance, so the date line above the
    // prayer times and the times themselves cannot land on two different civil days.
    //
    // It projects StartupSnapshot — the same read of the same LocationRepository that the location gate
    // and Home already use — because a screen may need the answer synchronously while it is being
    // constructed (the Calendar screen picks the month to open on before it can collect anything), and
    // that snapshot is exactly the value this application publishes hot for that purpose.
    single { LocationDayZone(storedLocation = get<StartupSnapshot>().storedLocation) }

    // Calendar ----------------------------------------------------------------
    // The four converters are pure, stateless and cheap, so they are singletons: two instances could
    // only ever produce the same answer, and sharing them keeps the repository, the coordinator and the
    // Ramadan screen on exactly the same rules.
    single { BanglaCalendar() }
    single { HijriCalendar() }
    single { RamadanCalendar(hijriCalendar = get()) }
    // Resolves the next fasting event from the calculated schedules; pure, stateless and cheap, so the
    // Ramadan screen can refresh it on a one-second tick without touching the prayer engine.
    single { FastingCountdownResolver() }
    single { GregorianCalendarGrid() }
    single { CalendarFormatting() }
    // The two converters decide which Gregorian days the selected calendar system's month covers.
    single { CalendarMonthResolver(banglaCalendar = get(), hijriCalendar = get()) }
    single<CalendarRepository> {
        DeviceClockCalendarRepository(
            clock = get(),
            // The repository's *inputs* are the stored calendar settings, so moving the Hijri offset or
            // the date language re-emits today and the displayed month instead of waiting for a restart.
            settings = get<UserPreferencesRepository>().calendarSettings,
            // The interface language is the locale "follow the device" now means for today's date
            // lines, so Home's weekday and dates are written in the language the rest of the screen is
            // in. An explicit date-language choice still wins. See docs/LOCALIZATION.md section 6.
            interfaceLanguage = get<UserPreferencesRepository>().appLanguage,
            // The day is a *location-local* one, so the date lines above the prayer times and the times
            // themselves cannot name two different civil days. See docs/CALCULATION_CORRECTNESS_REVIEW.md §3.
            dayZone = get(),
            banglaCalendar = get(),
            hijriCalendar = get(),
            ramadanCalendar = get(),
            grid = get(),
            formatting = get(),
            monthResolver = get(),
        )
    }
    single { CalendarUiStateFactory(formatting = get()) }
    single { RamadanUiStateFactory(formatting = get()) }

    // Prayer data -------------------------------------------------------------
    // The local astronomical engine. Offline, no account, no permission.
    single<PrayerTimesProvider> { AdhanPrayerTimesProvider(clock = get()) }
    single<PrayerScheduleRepository> {
        ProviderBackedPrayerScheduleRepository(
            provider = get(),
            clock = get(),
        )
    }
    // Prohibited prayer windows. The rule book carries the application's own documented policy,
    // whose boundaries are explicit parameters and whose verification state is CONFIGURED rather
    // than REVIEWED — the screens disclose that. Swapping in a reviewed rule set is a data change
    // here and does not touch the calculator, the view model or the UI.
    // See docs/FORBIDDEN_PRAYER_TIMES.md.
    single { ConfiguredProhibitedRuleSet.ruleBook() }
    single { ProhibitedPeriodCalculator() }
    single { ProhibitedPeriodStatusResolver() }
    single<ProhibitedPeriodsRepository> {
        RuleBookProhibitedPeriodsRepository(ruleBook = get(), calculator = get())
    }

    // Solar geometry and the night interval -----------------------------------
    // Both are pure functions of the schedule the engine already produced: the solar resolver reads
    // the offset-free solar events, and the night calculator reads the effective Maghrib and the
    // following day's effective Fajr. Neither performs astronomy of its own, so Salah still has
    // exactly one prayer-time engine. See docs/SOLAR_AND_NIGHT_PERIODS.md.
    single { SolarDayResolver() }
    single { NightPeriodCalculator() }

    // Notifications -----------------------------------------------------------
    // The planner is pure: it decides what should be scheduled and nothing else.
    single { PrayerNotificationPlanner() }
    single<NotificationPermissionStatusProvider> { AndroidNotificationPermissionStatusProvider(androidContext()) }
    single { AndroidNotificationChannels(androidContext()) }
    single<PrayerNotificationScheduler> {
        AlarmManagerPrayerNotificationScheduler(
            context = androidContext(),
            permissions = get(),
            logger = get(),
        )
    }
    single<PrayerNotificationPresenter> { AndroidPrayerNotificationPresenter(androidContext()) }
    single<NotificationSoundPreviewer> { AndroidNotificationSoundPreviewer(androidContext()) }

    // Azan playback. The audio *source* is what is missing, not the machinery: no licensed
    // recording is bundled, so `resolve()` returns null and playback is reported as unavailable.
    // See docs/PRAYER_NOTIFICATIONS.md §5.
    single<AzanAudioSource> { BundledAzanAudioSource() }
    single<AudioPlaybackEngine> { MediaPlayerAudioPlaybackEngine(androidContext()) }
    single { AzanPlaybackManager(audioSource = get(), engine = get(), logger = get()) }

    // The delivery path: one class decides whether a fired alarm becomes a visible reminder.
    single {
        PrayerNotificationDeliverer(
            notificationSettings = get(),
            userPreferences = get(),
            locations = get(),
            permissions = get(),
            presenter = get(),
            azanPlayback = get(),
            logger = get(),
            clock = get(),
        )
    }

    // The single reconciliation entry point every trigger funnels through: app start, settings
    // change, location change, reboot, clock or time-zone change, app update, and delivery.
    single {
        PrayerNotificationCoordinator(
            planner = get(),
            notificationSettings = get(),
            userPreferences = get(),
            locations = get(),
            schedules = get(),
            scheduler = get(),
            permissions = get(),
            logger = get(),
            clock = get(),
            // Which dates are in Ramadan, from the same converter the calendar screens use. Moving the
            // Hijri offset therefore moves the Sehri and Iftar reminders on the next reconciliation.
            ramadanCalendar = get(),
        )
    }

    // Qibla -------------------------------------------------------------------
    // The bearing is pure geography: a calculator plus the coordinates it is given. It needs no
    // permission, no network and no sensor, which is why it is bound independently of the compass.
    single { QiblaCalculator() }

    // The device sensors and the platform location service, behind ports so the screen is testable
    // and so a device without a magnetometer degrades to "bearing only" rather than failing.
    single<CompassSensorSource> { AndroidCompassSensorSource(androidContext(), get()) }
    single<GeomagneticDeclinationProvider> { AndroidGeomagneticDeclinationProvider() }
    single<LocationPermissionChecker> { AndroidLocationPermissionChecker(androidContext()) }
    single<LocationProvider> { AndroidLocationProvider(androidContext(), get(), get()) }

    // Domain ------------------------------------------------------------------
    single { NextPrayerResolver() }
    single { CurrentPrayerPeriodResolver() }
    single {
        HomeUiStateFactory(
            nextPrayerResolver = get(),
            currentPrayerResolver = get(),
            prohibitedStatusResolver = get(),
            solarDayResolver = get(),
            nightPeriodCalculator = get(),
            // The Home Qibla card reads the same bearing calculator the Qibla screen does, so the two
            // can never disagree about the direction to the Kaaba.
            qiblaCalculator = get(),
        )
    }

    // Presentation ------------------------------------------------------------
    viewModel {
        AppViewModel(
            userPreferencesRepository = get(),
            startup = get(),
            areaCatalog = get(),
        )
    }
    // One view model for the first-launch location setup and the location screen: they differ in what
    // they say, not in what they do, so both use the same policy, the same repository write and the
    // same notification reconciliation.
    viewModel {
        LocationViewModel(
            locations = get(),
            catalog = get(),
            locationProvider = get(),
            permissionChecker = get(),
            coordinator = get(),
            logger = get(),
            clock = get(),
        )
    }
    viewModel {
        HomeViewModel(
            startup = get(),
            prayerScheduleRepository = get(),
            calendarRepository = get(),
            prohibitedPeriodsRepository = get(),
            uiStateFactory = get(),
            // The dashboard layout is read and written through the one preference store, so it
            // survives process death and stays independent of the prayer and location settings.
            userPreferencesRepository = get(),
            clock = get(),
        )
    }
    // One view model for the General, Appearance and Prayer times screens: they read and write the same
    // two preferences (the theme and the calculation settings), and the location has moved to
    // LocationViewModel, which is where every write of a location now happens.
    viewModel {
        // The coordinator is injected because a calculation change moves every scheduled time; the
        // reconciliation that follows the write is what keeps the reminders in step with it.
        SettingsViewModel(userPreferencesRepository = get(), coordinator = get(), applier = get())
    }
    viewModel {
        CalendarViewModel(
            calendarRepository = get(),
            userPreferencesRepository = get(),
            uiStateFactory = get(),
            clock = get(),
            // The same zone source the repository reads, so the month this screen opens on contains the
            // day the repository marks as today.
            dayZone = get(),
            // The location and prayer settings, read hot, and the same offline-first schedule repository
            // Home and the reminders use, so the times beside the selected date are the timetable's own.
            startup = get(),
            prayerScheduleRepository = get(),
        )
    }
    // The coordinator is injected for the Hijri offset, which moves the Ramadan window the Sehri and
    // Iftar reminders are planned from.
    viewModel { CalendarSettingsViewModel(userPreferencesRepository = get(), coordinator = get()) }
    viewModel {
        RamadanViewModel(
            calendarRepository = get(),
            userPreferencesRepository = get(),
            locationRepository = get(),
            prayerScheduleRepository = get(),
            notificationPreferences = get(),
            coordinator = get(),
            uiStateFactory = get(),
            countdownResolver = get(),
            clock = get(),
        )
    }
    viewModel {
        QiblaViewModel(
            locations = get(),
            areaCatalog = get(),
            compassSensor = get(),
            locationProvider = get(),
            permissionChecker = get(),
            declinationProvider = get(),
            qiblaCalculator = get(),
            // The screen writes the app's one location, so an adopted fix must reschedule the reminders.
            coordinator = get(),
            logger = get(),
            clock = get(),
        )
    }
    viewModel {
        PrayerNotificationSettingsViewModel(
            notificationPreferences = get(),
            locationRepository = get(),
            permissions = get(),
            coordinator = get(),
            soundPreviewer = get(),
            azanPlayback = get(),
        )
    }
}
