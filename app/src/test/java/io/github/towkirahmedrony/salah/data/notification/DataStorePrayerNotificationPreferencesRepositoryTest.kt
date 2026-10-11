package io.github.towkirahmedrony.salah.data.notification

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.towkirahmedrony.salah.core.model.NotificationSound
import io.github.towkirahmedrony.salah.core.model.PrayerName
import io.github.towkirahmedrony.salah.core.model.PrayerNotificationPreference
import io.github.towkirahmedrony.salah.core.model.PrayerNotificationSettings
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
 * Exercises the real DataStore implementation, not a fake.
 *
 * This is the guarantee the notification screen and the reboot path both depend on: what the user
 * picks is on disk, it comes back after a restart, and a corrupt or foreign store cannot make Salah
 * forget that reminders were on.
 */
class DataStorePrayerNotificationPreferencesRepositoryTest {

    @get:Rule
    val temporaryFolder: TemporaryFolder = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var repository: DataStorePrayerNotificationPreferencesRepository

    @Before
    fun setUp() {
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        dataStore = PreferenceDataStoreFactory.create(scope = scope) {
            temporaryFolder.newFile("salah-preferences.preferences_pb")
        }
        repository = DataStorePrayerNotificationPreferencesRepository(dataStore)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `defaults are the documented settings before anything is stored`() = runTest {
        assertEquals(PrayerNotificationSettings.DEFAULT, repository.settings.first())
    }

    @Test
    fun `per-prayer settings survive a repository recreation`() = runTest {
        repository.update { current ->
            current
                .withPreference(
                    PrayerName.FAJR,
                    PrayerNotificationPreference(
                        enabled = false,
                        sound = NotificationSound.SILENT,
                        vibration = false,
                        advanceMinutes = 15,
                    ),
                )
                .withPreference(
                    PrayerName.ISHA,
                    PrayerNotificationPreference(sound = NotificationSound.SALAH_CHIME),
                )
        }

        // A fresh repository over the same store stands in for a process restart.
        val restarted = DataStorePrayerNotificationPreferencesRepository(dataStore)
        val stored = restarted.settings.first()

        assertEquals(PrayerNotificationPreference(false, NotificationSound.SILENT, false, 15), stored.preferenceFor(PrayerName.FAJR))
        assertEquals(NotificationSound.SALAH_CHIME, stored.preferenceFor(PrayerName.ISHA).sound)
        // Untouched prayers keep the default rather than becoming whatever was written last.
        assertEquals(PrayerNotificationPreference.DEFAULT, stored.preferenceFor(PrayerName.ASR))
    }

    @Test
    fun `the master switch and the sunrise reminder are persisted`() = runTest {
        repository.update { it.copy(masterEnabled = false, sunriseReminderEnabled = true) }

        val stored = DataStorePrayerNotificationPreferencesRepository(dataStore).settings.first()

        assertFalse(stored.masterEnabled)
        assertTrue(stored.sunriseReminderEnabled)
    }

    @Test
    fun `updates are applied to the stored value rather than to a stale copy`() = runTest {
        repository.update { it.withPreference(PrayerName.FAJR, PrayerNotificationPreference(sound = NotificationSound.SILENT)) }
        repository.update { it.withPreference(PrayerName.DHUHR, PrayerNotificationPreference(vibration = false)) }

        val stored = repository.settings.first()

        assertEquals(NotificationSound.SILENT, stored.preferenceFor(PrayerName.FAJR).sound)
        assertFalse(stored.preferenceFor(PrayerName.DHUHR).vibration)
    }

    @Test
    fun `returning a prayer to its default removes the stored keys`() = runTest {
        repository.update { it.withPreference(PrayerName.ASR, PrayerNotificationPreference(enabled = false)) }
        repository.update { it.withPreference(PrayerName.ASR, PrayerNotificationPreference.DEFAULT) }

        val stored = repository.settings.first()

        assertTrue(stored.prayers.isEmpty())
        // Nothing is left behind in the store either, so a future change to the defaults is picked
        // up by users who never made a choice.
        val raw = dataStore.data.first()
        assertTrue(raw.asMap().keys.none { it.name.startsWith("notification_ASR") })
    }

    @Test
    fun `an unknown sound name falls back to the default instead of failing`() = runTest {
        dataStore.edit { preferences ->
            preferences[stringPreferencesKey("notification_FAJR_sound")] = "AIR_HORN"
            preferences[booleanPreferencesKey("notification_FAJR_enabled")] = false
        }

        val stored = repository.settings.first()

        assertEquals(NotificationSound.SYSTEM_DEFAULT, stored.preferenceFor(PrayerName.FAJR).sound)
        assertFalse(stored.preferenceFor(PrayerName.FAJR).enabled)
    }

    @Test
    fun `an out-of-range stored advance value is clamped on read`() = runTest {
        dataStore.edit { preferences ->
            preferences[intPreferencesKey("notification_ISHA_advance_minutes")] = 9_999
        }

        val stored = repository.settings.first()

        assertEquals(
            PrayerNotificationSettings.MAX_ADVANCE_MINUTES,
            stored.preferenceFor(PrayerName.ISHA).advanceMinutes,
        )
    }

    @Test
    fun `a stored negative advance value is treated as off`() = runTest {
        dataStore.edit { preferences ->
            preferences[intPreferencesKey("notification_MAGHRIB_advance_minutes")] = -30
        }

        val stored = repository.settings.first()

        assertEquals(0, stored.preferenceFor(PrayerName.MAGHRIB).advanceMinutes)
        assertTrue("a clamped-to-default value must not remain stored", stored.prayers.isEmpty())
    }

    @Test
    fun `notification settings and the other preferences do not overwrite each other`() = runTest {
        // The same store is written by two repositories; neither may clear the other's keys.
        dataStore.edit { preferences ->
            preferences[stringPreferencesKey("theme_mode")] = "DARK"
            preferences[intPreferencesKey("prayer_adjustment_FAJR")] = 3
        }

        repository.update { it.copy(masterEnabled = false) }

        val raw = dataStore.data.first()
        assertEquals("DARK", raw[stringPreferencesKey("theme_mode")])
        assertEquals(3, raw[intPreferencesKey("prayer_adjustment_FAJR")])
        assertFalse(repository.settings.first().masterEnabled)
    }
}
