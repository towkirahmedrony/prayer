package io.github.towkirahmedrony.salah.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.towkirahmedrony.salah.core.domain.repository.UserPreferencesRepository
import io.github.towkirahmedrony.salah.core.model.AsrCalculationMethod
import io.github.towkirahmedrony.salah.core.model.HighLatitudeRule
import io.github.towkirahmedrony.salah.core.model.PrayerCalculationMethod
import io.github.towkirahmedrony.salah.core.model.PrayerName
import io.github.towkirahmedrony.salah.core.model.PrayerSettings
import io.github.towkirahmedrony.salah.core.model.ThemeMode
import io.github.towkirahmedrony.salah.core.model.TimeFormatPreference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

/**
 * Local, offline user preferences.
 *
 * This is the only class in Salah that writes user settings, and it writes all of them: the
 * theme, the prayer-calculation configuration and the time format share one DataStore file, so
 * prayer settings cannot end up in a second store that could disagree with the first.
 *
 * Unknown or out-of-range stored values fall back to a safe default instead of failing, so a
 * downgraded or corrupted store cannot break the app and cannot put the calculator into a state
 * the settings screen cannot represent.
 */
class DataStoreUserPreferencesRepository(
    private val dataStore: DataStore<Preferences>,
) : UserPreferencesRepository {

    override val themeMode: Flow<ThemeMode> = dataStore.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { preferences ->
            val stored = preferences[Keys.THEME_MODE]
            ThemeMode.entries.firstOrNull { it.name == stored } ?: ThemeMode.SYSTEM
        }

    override suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { preferences -> preferences[Keys.THEME_MODE] = mode.name }
    }

    override val prayerSettings: Flow<PrayerSettings> = dataStore.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { preferences -> preferences.toPrayerSettings() }

    override suspend fun updatePrayerSettings(transform: (PrayerSettings) -> PrayerSettings) {
        // Read-modify-write inside one transaction: concurrent updates cannot lose each other,
        // and no cached copy of the settings can be written back over a newer one.
        dataStore.edit { preferences ->
            val updated = transform(preferences.toPrayerSettings())
            preferences[Keys.CALCULATION_METHOD] = updated.method.name
            preferences[Keys.ASR_METHOD] = updated.asrMethod.name
            preferences[Keys.HIGH_LATITUDE_RULE] = updated.highLatitudeRule.name
            preferences[Keys.TIME_FORMAT] = updated.timeFormat.name
            PrayerName.entries.forEach { prayer ->
                val minutes = updated.adjustmentFor(prayer)
                if (minutes == 0) {
                    preferences.remove(Keys.adjustment(prayer))
                } else {
                    preferences[Keys.adjustment(prayer)] = minutes
                }
            }
        }
    }

    private fun Preferences.toPrayerSettings(): PrayerSettings = PrayerSettings(
        method = PrayerCalculationMethod.entries
            .firstOrNull { it.name == this[Keys.CALCULATION_METHOD] }
            ?: PrayerCalculationMethod.DEFAULT,
        asrMethod = AsrCalculationMethod.entries
            .firstOrNull { it.name == this[Keys.ASR_METHOD] }
            ?: PrayerSettings.DEFAULT.asrMethod,
        highLatitudeRule = HighLatitudeRule.entries
            .firstOrNull { it.name == this[Keys.HIGH_LATITUDE_RULE] }
            ?: PrayerSettings.DEFAULT.highLatitudeRule,
        timeFormat = TimeFormatPreference.entries
            .firstOrNull { it.name == this[Keys.TIME_FORMAT] }
            ?: PrayerSettings.DEFAULT.timeFormat,
        adjustmentsMinutes = PrayerName.entries
            .mapNotNull { prayer ->
                // Clamped on read as well as on write, so a hand-edited or corrupted store can
                // never move a prayer time by an unsupported amount.
                val stored = this[Keys.adjustment(prayer)] ?: return@mapNotNull null
                val clamped = PrayerSettings.clampAdjustment(stored)
                if (clamped == 0) null else prayer to clamped
            }
            .toMap(),
    )

    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val CALCULATION_METHOD = stringPreferencesKey("prayer_calculation_method")
        val ASR_METHOD = stringPreferencesKey("prayer_asr_method")
        val HIGH_LATITUDE_RULE = stringPreferencesKey("prayer_high_latitude_rule")
        val TIME_FORMAT = stringPreferencesKey("time_format")

        fun adjustment(prayer: PrayerName) = intPreferencesKey("prayer_adjustment_${prayer.name}")
    }
}
