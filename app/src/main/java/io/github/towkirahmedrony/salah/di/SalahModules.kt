package io.github.towkirahmedrony.salah.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import io.github.towkirahmedrony.salah.core.domain.NextPrayerResolver
import io.github.towkirahmedrony.salah.core.domain.prayer.PrayerTimesProvider
import io.github.towkirahmedrony.salah.core.domain.prohibited.ProhibitedPeriodCalculator
import io.github.towkirahmedrony.salah.core.domain.prohibited.ProhibitedRuleBook
import io.github.towkirahmedrony.salah.core.domain.repository.CalendarRepository
import io.github.towkirahmedrony.salah.core.domain.repository.LocationRepository
import io.github.towkirahmedrony.salah.core.domain.repository.PrayerScheduleRepository
import io.github.towkirahmedrony.salah.core.domain.repository.ProhibitedPeriodsRepository
import io.github.towkirahmedrony.salah.core.domain.repository.UserPreferencesRepository
import io.github.towkirahmedrony.salah.data.calendar.DeviceClockCalendarRepository
import io.github.towkirahmedrony.salah.data.location.DataStoreLocationRepository
import io.github.towkirahmedrony.salah.data.preferences.DataStoreUserPreferencesRepository
import io.github.towkirahmedrony.salah.data.preferences.salahPreferencesDataStore
import io.github.towkirahmedrony.salah.data.prayer.AdhanPrayerTimesProvider
import io.github.towkirahmedrony.salah.data.prayer.ProviderBackedPrayerScheduleRepository
import io.github.towkirahmedrony.salah.data.prohibited.RuleBookProhibitedPeriodsRepository
import io.github.towkirahmedrony.salah.feature.app.AppViewModel
import io.github.towkirahmedrony.salah.feature.home.HomeUiStateFactory
import io.github.towkirahmedrony.salah.feature.home.HomeViewModel
import io.github.towkirahmedrony.salah.feature.settings.SettingsViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module
import java.time.Clock

/**
 * Single dependency container for Salah.
 *
 * Bindings are declared against domain interfaces, so swapping the prayer provider or the
 * persistence layer is a change here and nowhere else.
 */
val salahModules = module {

    // UTC rather than the default zone: every consumer converts to the zone it actually needs
    // (the prayer location's zone, or the device's current zone) at the moment it needs it, so a
    // device time-zone change takes effect immediately instead of after a process restart.
    single<Clock> { Clock.systemUTC() }

    // Local persistence -------------------------------------------------------
    single<DataStore<Preferences>> { androidContext().salahPreferencesDataStore }
    single<UserPreferencesRepository> { DataStoreUserPreferencesRepository(get()) }
    single<LocationRepository> { DataStoreLocationRepository(get()) }

    // Calendar ----------------------------------------------------------------
    single<CalendarRepository> { DeviceClockCalendarRepository(get()) }

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

    // Domain ------------------------------------------------------------------
    single { NextPrayerResolver() }
    single { HomeUiStateFactory(nextPrayerResolver = get()) }

    // Presentation ------------------------------------------------------------
    viewModel { AppViewModel(userPreferencesRepository = get()) }
    viewModel {
        HomeViewModel(
            locationRepository = get(),
            userPreferencesRepository = get(),
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
}
