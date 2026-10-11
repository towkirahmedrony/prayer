package io.github.towkirahmedrony.salah.core.domain.repository

import io.github.towkirahmedrony.salah.core.model.PrayerSettings
import io.github.towkirahmedrony.salah.core.model.ThemeMode
import kotlinx.coroutines.flow.Flow

/** User preferences that must survive process death and work offline. */
interface UserPreferencesRepository {

    val themeMode: Flow<ThemeMode>

    suspend fun setThemeMode(mode: ThemeMode)

    /**
     * The prayer-calculation and display settings.
     *
     * Salah's single preference store, so these values are read without a network call and
     * survive process death. The flow always emits a value: a fresh install gets
     * [PrayerSettings.DEFAULT] rather than null, so the Home screen never has to guess.
     */
    val prayerSettings: Flow<PrayerSettings>

    /**
     * Atomically rewrites the prayer settings through [transform].
     *
     * The read and the write happen inside one DataStore transaction, so two quick taps cannot
     * lose one another's change and no separate in-memory copy of the settings can drift from
     * what is stored. Callers never write a whole settings object they read earlier.
     */
    suspend fun updatePrayerSettings(transform: (PrayerSettings) -> PrayerSettings)
}
