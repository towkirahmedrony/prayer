package io.github.towkirahmedrony.salah.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import io.github.towkirahmedrony.salah.core.domain.NextPrayerResolver
import io.github.towkirahmedrony.salah.core.domain.prayer.PrayerTimesProvider
import io.github.towkirahmedrony.salah.core.domain.repository.CalendarRepository
import io.github.towkirahmedrony.salah.core.domain.repository.LocationRepository
import io.github.towkirahmedrony.salah.core.domain.repository.PrayerScheduleRepository
import io.github.towkirahmedrony.salah.core.domain.repository.ProhibitedPeriodsRepository
import io.github.towkirahmedrony.salah.core.domain.repository.UserPreferencesRepository
import io.github.towkirahmedrony.salah.core.model.PrayerCalculationProfile
import io.github.towkirahmedrony.salah.data.calendar.DeviceClockCalendarRepository
import io.github.towkirahmedrony.salah.data.location.DataStoreLocationRepository
import io.github.towkirahmedrony.salah.data.preferences.DataStoreUserPreferencesRepository
import io.github.towkirahmedrony.salah.data.preferences.salahPreferencesDataStore
import io.github.towkirahmedrony.salah.data.prayer.ProviderBackedPrayerScheduleRepository
import io.github.towkirahmedrony.salah.data.prayer.UnconfiguredLocalPrayerTimesProvider
import io.github.towkirahmedrony.salah.data.prohibited.UnverifiedProhibitedPeriodsRepository
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

    single<Clock> { Clock.systemDefaultZone() }

    // Local persistence -------------------------------------------------------
    single<DataStore<Preferences>> { androidContext().salahPreferencesDataStore }
    single<UserPreferencesRepository> { DataStoreUserPreferencesRepository(get()) }
    single<LocationRepository> { DataStoreLocationRepository(get()) }

    // Calendar ----------------------------------------------------------------
    single<CalendarRepository> { DeviceClockCalendarRepository(get()) }

    // Prayer data -------------------------------------------------------------
    single<PrayerTimesProvider> { UnconfiguredLocalPrayerTimesProvider() }
    single<PrayerCalculationProfile> { PrayerCalculationProfile.DEFAULT_KARACHI_HANAFI }
    single<PrayerScheduleRepository> {
        ProviderBackedPrayerScheduleRepository(
            provider = get(),
            clock = get(),
            profile = get(),
        )
    }
    single<ProhibitedPeriodsRepository> { UnverifiedProhibitedPeriodsRepository() }

    // Domain ------------------------------------------------------------------
    single { NextPrayerResolver() }
    single { HomeUiStateFactory(nextPrayerResolver = get(), clock = get()) }

    // Presentation ------------------------------------------------------------
    viewModel { AppViewModel(userPreferencesRepository = get()) }
    viewModel {
        HomeViewModel(
            locationRepository = get(),
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
