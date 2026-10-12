package io.github.towkirahmedrony.salah.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.towkirahmedrony.salah.core.model.AsrCalculationMethod
import io.github.towkirahmedrony.salah.core.model.CalendarSettings
import io.github.towkirahmedrony.salah.core.model.CalendarSystem
import io.github.towkirahmedrony.salah.core.model.DateLanguagePreference
import io.github.towkirahmedrony.salah.core.model.HighLatitudeRule
import io.github.towkirahmedrony.salah.core.model.PrayerCalculationMethod
import io.github.towkirahmedrony.salah.core.model.PrayerName
import io.github.towkirahmedrony.salah.core.model.PrayerSettings
import io.github.towkirahmedrony.salah.core.model.ThemeMode
import io.github.towkirahmedrony.salah.core.model.TimeFormatPreference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Exercises the real DataStore implementation for settings persistence, not a fake.
 *
 * This is the guarantee the Settings screen depends on: what the user picks is written to disk,
 * read back after a restart, and can never come back in a state the UI cannot represent.
 */
class DataStoreUserPreferencesRepositoryTest {

    @get:Rule
    val temporaryFolder: TemporaryFolder = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var repository: DataStoreUserPreferencesRepository

    @Before
    fun setUp() {
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        dataStore = PreferenceDataStoreFactory.create(scope = scope) {
            // DataStore requires this exact extension on the file it owns.
            temporaryFolder.newFile("salah-preferences.preferences_pb")
        }
        repository = DataStoreUserPreferencesRepository(dataStore)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `defaults to the documented settings before anything is stored`() = runTest {
        assertEquals(PrayerSettings.DEFAULT, repository.prayerSettings.first())
        assertEquals(ThemeMode.SYSTEM, repository.themeMode.first())
    }

    @Test
    fun `persists and restores every calculation setting`() = runTest {
        val chosen = PrayerSettings(
            method = PrayerCalculationMethod.EGYPTIAN,
            asrMethod = AsrCalculationMethod.STANDARD,
            highLatitudeRule = HighLatitudeRule.SEVENTH_OF_THE_NIGHT,
            adjustmentsMinutes = mapOf(PrayerName.FAJR to 3, PrayerName.ISHA to -2),
            timeFormat = TimeFormatPreference.TWENTY_FOUR_HOUR,
        )

        repository.updatePrayerSettings { chosen }

        // A fresh repository over the same store stands in for a process restart.
        val restarted = DataStoreUserPreferencesRepository(dataStore)
        assertEquals(chosen, restarted.prayerSettings.first())
    }

    @Test
    fun `applies an update to the stored value rather than to a stale copy`() = runTest {
        repository.updatePrayerSettings { it.copy(method = PrayerCalculationMethod.KUWAIT) }
        repository.updatePrayerSettings { it.copy(asrMethod = AsrCalculationMethod.STANDARD) }
        repository.updatePrayerSettings { it.withAdjustment(PrayerName.ASR, 4) }

        val stored = repository.prayerSettings.first()

        assertEquals(PrayerCalculationMethod.KUWAIT, stored.method)
        assertEquals(AsrCalculationMethod.STANDARD, stored.asrMethod)
        assertEquals(4, stored.adjustmentFor(PrayerName.ASR))
    }

    @Test
    fun `clearing an adjustment removes it from storage`() = runTest {
        repository.updatePrayerSettings { it.withAdjustment(PrayerName.DHUHR, 5) }
        repository.updatePrayerSettings { it.withAdjustment(PrayerName.DHUHR, 0) }

        val stored = repository.prayerSettings.first()

        assertFalse(stored.adjustmentsMinutes.containsKey(PrayerName.DHUHR))
        assertTrue(stored.adjustmentsMinutes.isEmpty())
    }

    @Test
    fun `keeps prayer settings and the theme in one store`() = runTest {
        repository.setThemeMode(ThemeMode.DARK)
        repository.updatePrayerSettings { it.copy(method = PrayerCalculationMethod.SINGAPORE) }

        assertEquals(ThemeMode.DARK, repository.themeMode.first())
        assertEquals(PrayerCalculationMethod.SINGAPORE, repository.prayerSettings.first().method)
    }

    @Test
    fun `falls back safely on an unknown or out-of-range stored value`() = runTest {
        // Simulates a store written by a different version, or edited by hand.
        dataStore.edit { preferences ->
            preferences[stringPreferencesKey("prayer_calculation_method")] = "SOMETHING_ELSE"
            preferences[stringPreferencesKey("prayer_asr_method")] = "MYSTERY"
            preferences[stringPreferencesKey("prayer_high_latitude_rule")] = "NOPE"
            preferences[stringPreferencesKey("time_format")] = "NOPE"
            preferences[intPreferencesKey("prayer_adjustment_FAJR")] = 9_999
            preferences[intPreferencesKey("prayer_adjustment_ISHA")] = 0
        }

        val stored = repository.prayerSettings.first()

        assertEquals(PrayerCalculationMethod.DEFAULT, stored.method)
        assertEquals(PrayerSettings.DEFAULT.asrMethod, stored.asrMethod)
        assertEquals(PrayerSettings.DEFAULT.highLatitudeRule, stored.highLatitudeRule)
        assertEquals(PrayerSettings.DEFAULT.timeFormat, stored.timeFormat)
        assertEquals(PrayerSettings.MAX_ADJUSTMENT_MINUTES, stored.adjustmentFor(PrayerName.FAJR))
        assertFalse(stored.adjustmentsMinutes.containsKey(PrayerName.ISHA))
    }

    @Test
    fun `falls back on an unknown theme value`() = runTest {
        dataStore.edit { preferences ->
            preferences[stringPreferencesKey("theme_mode")] = "SEPIA"
        }

        assertEquals(ThemeMode.SYSTEM, repository.themeMode.first())
    }

    // --- Calendar -----------------------------------------------------------------

    @Test
    fun `calendar preferences default to the documented values`() = runTest {
        val settings = repository.calendarSettings.first()

        assertEquals(CalendarSettings.DEFAULT, settings)
        assertEquals(CalendarSystem.GREGORIAN, settings.preferredSystem)
        assertEquals(DateLanguagePreference.SYSTEM, settings.dateLanguage)
        // No offset: Salah shows its own unconverted estimate until the user asks otherwise.
        assertEquals(0, settings.hijriAdjustmentDays)
    }

    @Test
    fun `calendar preferences survive a repository recreation`() = runTest {
        repository.updateCalendarSettings { current ->
            current.copy(
                preferredSystem = CalendarSystem.HIJRI,
                dateLanguage = DateLanguagePreference.BANGLA,
                hijriAdjustmentDays = -1,
            )
        }

        // A second repository over the same file, as after a process restart.
        val reopened = DataStoreUserPreferencesRepository(dataStore).calendarSettings.first()

        assertEquals(CalendarSystem.HIJRI, reopened.preferredSystem)
        assertEquals(DateLanguagePreference.BANGLA, reopened.dateLanguage)
        assertEquals(-1, reopened.hijriAdjustmentDays)
    }

    @Test
    fun `an update applies to the stored value rather than to a stale copy`() = runTest {
        // Two taps in quick succession: the second must see the first one's write.
        repository.updateCalendarSettings { it.shiftHijriAdjustment(1) }
        repository.updateCalendarSettings { it.shiftHijriAdjustment(1) }

        assertEquals(2, repository.calendarSettings.first().hijriAdjustmentDays)
    }

    @Test
    fun `a Hijri adjustment is clamped on write and on read`() = runTest {
        repository.updateCalendarSettings { it.copy(hijriAdjustmentDays = 99) }
        assertEquals(
            CalendarSettings.MAX_HIJRI_ADJUSTMENT_DAYS,
            repository.calendarSettings.first().hijriAdjustmentDays,
        )

        // And a value written by something else is normalised on read, so the screen can always
        // represent what it is asked to show.
        dataStore.edit { preferences ->
            preferences[intPreferencesKey("calendar_hijri_adjustment_days")] = -50
        }
        assertEquals(
            -CalendarSettings.MAX_HIJRI_ADJUSTMENT_DAYS,
            repository.calendarSettings.first().hijriAdjustmentDays,
        )
    }

    @Test
    fun `an unknown calendar or language name falls back instead of failing`() = runTest {
        dataStore.edit { preferences ->
            preferences[stringPreferencesKey("calendar_preferred_system")] = "MAYAN"
            preferences[stringPreferencesKey("calendar_date_language")] = "KLINGON"
        }

        val settings = repository.calendarSettings.first()

        assertEquals(CalendarSystem.GREGORIAN, settings.preferredSystem)
        assertEquals(DateLanguagePreference.SYSTEM, settings.dateLanguage)
    }

    @Test
    fun `calendar and prayer preferences do not overwrite each other`() = runTest {
        repository.updatePrayerSettings { it.copy(method = PrayerCalculationMethod.EGYPTIAN) }
        repository.updateCalendarSettings { it.copy(preferredSystem = CalendarSystem.BANGLA) }

        assertEquals(PrayerCalculationMethod.EGYPTIAN, repository.prayerSettings.first().method)
        assertEquals(CalendarSystem.BANGLA, repository.calendarSettings.first().preferredSystem)
        // The theme is a third, independent value in the same store.
        repository.setThemeMode(ThemeMode.DARK)
        assertEquals(ThemeMode.DARK, repository.themeMode.first())
        assertEquals(CalendarSystem.BANGLA, repository.calendarSettings.first().preferredSystem)
    }
}
