package io.github.towkirahmedrony.salah.core.domain.repository

import io.github.towkirahmedrony.salah.core.domain.dashboard.DashboardConfig
import io.github.towkirahmedrony.salah.core.model.AppLanguage
import io.github.towkirahmedrony.salah.core.model.CalendarSettings
import io.github.towkirahmedrony.salah.core.model.PrayerConfigOverrides
import io.github.towkirahmedrony.salah.core.model.PrayerSettings
import io.github.towkirahmedrony.salah.core.model.ThemeMode
import kotlinx.coroutines.flow.Flow

/** User preferences that must survive process death and work offline. */
interface UserPreferencesRepository {

    val themeMode: Flow<ThemeMode>

    suspend fun setThemeMode(mode: ThemeMode)

    /**
     * The language the interface is written in.
     *
     * A preference of its own, stored under its own key, so choosing a language cannot disturb the
     * theme, the calculation settings or the calendar settings — and because it is a preference the
     * interface is rebuilt from, not a build-time choice, it takes effect on the next emission rather
     * than on the next install. Like the other flows it always emits a value: a fresh install reads
     * [AppLanguage.DEFAULT], which is "follow the device".
     */
    val appLanguage: Flow<AppLanguage>

    /**
     * Stores the interface language.
     *
     * Writes exactly one key. A failure (an unwritable store) is thrown rather than swallowed, so a
     * caller that reports success can only do so when the value really was stored.
     */
    suspend fun setAppLanguage(language: AppLanguage)

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

    /**
     * The calendar display preferences.
     *
     * Read by the calendar screens, by the Home date header and by the notification coordinator —
     * which needs the Hijri offset to decide which dates are in Ramadan — so it lives here beside the
     * other calculation inputs rather than in a store of its own. Like
     * [prayerSettings] it always emits a value, so no consumer has to handle a missing preference.
     */
    val calendarSettings: Flow<CalendarSettings>

    /**
     * Atomically rewrites the calendar settings through [transform].
     *
     * The same read-modify-write-in-one-transaction guarantee as
     * [updatePrayerSettings], so a fast sequence of Hijri-adjustment taps cannot lose one another's
     * change.
     */
    suspend fun updateCalendarSettings(transform: (CalendarSettings) -> CalendarSettings)

    /**
     * Which calculation fields the user has explicitly chosen, so a published remote configuration can
     * supply defaults without ever overwriting a value the user set.
     *
     * Stored under its own key in the same DataStore as [prayerSettings], so a remote update can be applied
     * to the settings without disturbing any other preference. The flow always emits a value: a store written
     * before this feature existed yields the fields inferred from [prayerSettings] (see
     * [PrayerConfigOverrides.deriving]), which errs on the side of preserving a change the user made.
     */
    val prayerConfigOverrides: Flow<PrayerConfigOverrides>

    /**
     * Atomically rewrites [prayerConfigOverrides] through [transform], with the same
     * read-modify-write-in-one-transaction guarantee as [updatePrayerSettings].
     */
    suspend fun updatePrayerConfigOverrides(transform: (PrayerConfigOverrides) -> PrayerConfigOverrides)

    /**
     * The Home dashboard layout: which cards are shown and in what order.
     *
     * Its own preference group, independent of the prayer calculation settings, the location, the
     * language and the reminder settings — hiding a card must never disable the feature behind it,
     * so those values are not touched here. The flow always emits a value (the default layout on a
     * fresh install), and the value it emits is always [DashboardConfig.normalized], so a store
     * written by an older build cannot put the dashboard into a state it cannot render.
     */
    val dashboardConfig: Flow<DashboardConfig>

    /**
     * Atomically rewrites [dashboardConfig] through [transform], with the same
     * read-modify-write-in-one-transaction guarantee as [updatePrayerSettings], so two quick taps —
     * or a toggle and a reorder — cannot lose one another's change.
     */
    suspend fun updateDashboardConfig(transform: (DashboardConfig) -> DashboardConfig)
}
