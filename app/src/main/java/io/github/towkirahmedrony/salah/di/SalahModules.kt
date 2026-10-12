package io.github.towkirahmedrony.salah.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import io.github.towkirahmedrony.salah.core.domain.DiagnosticsLogger
import io.github.towkirahmedrony.salah.core.domain.NextPrayerResolver
import io.github.towkirahmedrony.salah.core.domain.calendar.BanglaCalendar
import io.github.towkirahmedrony.salah.core.domain.calendar.CalendarFormatting
import io.github.towkirahmedrony.salah.core.domain.calendar.GregorianCalendarGrid
import io.github.towkirahmedrony.salah.core.domain.calendar.HijriCalendar
import io.github.towkirahmedrony.salah.core.domain.calendar.RamadanCalendar
import io.github.towkirahmedrony.salah.core.domain.notification.AudioPlaybackEngine
import io.github.towkirahmedrony.salah.core.domain.notification.AzanAudioSource
import io.github.towkirahmedrony.salah.core.domain.notification.AzanPlaybackManager
import io.github.towkirahmedrony.salah.core.domain.notification.NotificationPermissionStatusProvider
import io.github.towkirahmedrony.salah.core.domain.notification.NotificationSoundPreviewer
import io.github.towkirahmedrony.salah.core.domain.notification.PrayerNotificationPlanner
import io.github.towkirahmedrony.salah.core.domain.notification.PrayerNotificationPresenter
import io.github.towkirahmedrony.salah.core.domain.notification.PrayerNotificationScheduler
import io.github.towkirahmedrony.salah.core.domain.prayer.PrayerTimesProvider
import io.github.towkirahmedrony.salah.core.domain.prohibited.ProhibitedPeriodCalculator
import io.github.towkirahmedrony.salah.core.domain.qibla.CompassSensorSource
import io.github.towkirahmedrony.salah.core.domain.qibla.GeomagneticDeclinationProvider
import io.github.towkirahmedrony.salah.core.domain.qibla.LocationPermissionChecker
import io.github.towkirahmedrony.salah.core.domain.qibla.LocationProvider
import io.github.towkirahmedrony.salah.core.domain.qibla.QiblaCalculator
import io.github.towkirahmedrony.salah.core.domain.prohibited.ProhibitedRuleBook
import io.github.towkirahmedrony.salah.core.domain.repository.BangladeshAreaCatalog
import io.github.towkirahmedrony.salah.core.domain.repository.CalendarRepository
import io.github.towkirahmedrony.salah.core.domain.repository.LocationRepository
import io.github.towkirahmedrony.salah.core.domain.repository.PrayerNotificationPreferencesRepository
import io.github.towkirahmedrony.salah.core.domain.repository.PrayerScheduleRepository
import io.github.towkirahmedrony.salah.core.domain.repository.ProhibitedPeriodsRepository
import io.github.towkirahmedrony.salah.core.domain.repository.UserPreferencesRepository
import io.github.towkirahmedrony.salah.core.domain.startup.StartupSnapshot
import io.github.towkirahmedrony.salah.data.calendar.DeviceClockCalendarRepository
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
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module
import java.time.Clock

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
    // The Bangladesh geography bundled with the app: divisions, districts, upazilas and thanas with
    // their published reference coordinates, plus the national boundary the country policy checks
    // against. Read from assets and parsed once; see docs/LOCATION_SYSTEM.md section 5.
    single<BangladeshAreaCatalog> { AssetBangladeshAreaCatalog(androidContext(), get()) }
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

    // Calendar ----------------------------------------------------------------
    // The four converters are pure, stateless and cheap, so they are singletons: two instances could
    // only ever produce the same answer, and sharing them keeps the repository, the coordinator and the
    // Ramadan screen on exactly the same rules.
    single { BanglaCalendar() }
    single { HijriCalendar() }
    single { RamadanCalendar(hijriCalendar = get()) }
    single { GregorianCalendarGrid() }
    single { CalendarFormatting() }
    single<CalendarRepository> {
        DeviceClockCalendarRepository(
            clock = get(),
            // The repository's *inputs* are the stored calendar settings, so moving the Hijri offset or
            // the date language re-emits today and the displayed month instead of waiting for a restart.
            settings = get<UserPreferencesRepository>().calendarSettings,
            banglaCalendar = get(),
            hijriCalendar = get(),
            ramadanCalendar = get(),
            grid = get(),
            formatting = get(),
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
    // Prohibited prayer windows: the rule book is empty until a rule set is reviewed, so the UI
    // reports the honest unavailability state. See docs/PRAYER_CALCULATION.md §7.
    single { ProhibitedRuleBook() }
    single { ProhibitedPeriodCalculator() }
    single<ProhibitedPeriodsRepository> {
        RuleBookProhibitedPeriodsRepository(ruleBook = get(), calculator = get())
    }

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
    single { HomeUiStateFactory(nextPrayerResolver = get()) }

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
            clock = get(),
        )
    }
    viewModel {
        SettingsViewModel(
            userPreferencesRepository = get(),
            locationRepository = get(),
        )
    }
    viewModel {
        CalendarViewModel(
            calendarRepository = get(),
            userPreferencesRepository = get(),
            uiStateFactory = get(),
            clock = get(),
        )
    }
    viewModel { CalendarSettingsViewModel(userPreferencesRepository = get()) }
    viewModel {
        RamadanViewModel(
            calendarRepository = get(),
            userPreferencesRepository = get(),
            locationRepository = get(),
            prayerScheduleRepository = get(),
            notificationPreferences = get(),
            coordinator = get(),
            uiStateFactory = get(),
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
