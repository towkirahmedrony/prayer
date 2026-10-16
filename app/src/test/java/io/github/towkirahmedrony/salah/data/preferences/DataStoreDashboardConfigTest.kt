package io.github.towkirahmedrony.salah.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.towkirahmedrony.salah.core.domain.dashboard.DashboardConfig
import io.github.towkirahmedrony.salah.core.model.AppLanguage
import io.github.towkirahmedrony.salah.core.model.DashboardCard
import io.github.towkirahmedrony.salah.core.model.PrayerCalculationMethod
import io.github.towkirahmedrony.salah.core.model.ThemeMode
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
 * Exercises the real DataStore implementation for the Home dashboard layout.
 *
 * The properties the dashboard depends on are the ones asserted here: the layout is written to disk
 * and read back after a restart, a store written by another version cannot put the screen into a
 * state it cannot render, and — critically — writing the layout never touches the prayer settings,
 * the location, the language or the theme.
 */
class DataStoreDashboardConfigTest {

    @get:Rule
    val temporaryFolder: TemporaryFolder = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var repository: DataStoreUserPreferencesRepository

    @Before
    fun setUp() {
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        dataStore = PreferenceDataStoreFactory.create(scope = scope) {
            temporaryFolder.newFile("salah-preferences.preferences_pb")
        }
        repository = DataStoreUserPreferencesRepository(dataStore)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `defaults to the documented layout before anything is stored`() = runTest {
        val config = repository.dashboardConfig.first()

        assertEquals(DashboardConfig.DEFAULT, config)
        assertEquals(DashboardCard.entries.map { it.id }, config.order)
        assertTrue(config.isEnabled(DashboardCard.NEXT_PRAYER))
        assertFalse(config.isEnabled(DashboardCard.SOLAR_GEOMETRY))
    }

    @Test
    fun `persists the enabled state and the order and restores them after a restart`() = runTest {
        repository.updateDashboardConfig { config ->
            config
                .withCardEnabled(DashboardCard.QIBLA, enabled = true)
                .withCardEnabled(DashboardCard.FORBIDDEN_TIMES, enabled = false)
                .moved(DashboardCard.DAILY_TOOLS, delta = -1)
        }

        // A fresh repository over the same file stands in for a process restart.
        val restarted = DataStoreUserPreferencesRepository(dataStore).dashboardConfig.first()

        assertTrue(restarted.isEnabled(DashboardCard.QIBLA))
        assertFalse(restarted.isEnabled(DashboardCard.FORBIDDEN_TIMES))
        // Moving daily tools up by one put it just before night periods.
        assertEquals(
            restarted.order.indexOf(DashboardCard.DAILY_TOOLS.id) + 1,
            restarted.order.indexOf(DashboardCard.NIGHT_PERIODS.id),
        )
    }

    @Test
    fun `stores the stable card ids rather than any display label`() = runTest {
        repository.updateDashboardConfig { config ->
            config.withCardEnabled(DashboardCard.QIBLA, enabled = true)
        }

        val stored = dataStore.data.first()[stringPreferencesKey("dashboard_card_order")].orEmpty()
        assertTrue("the stored layout must use the card's stable id", stored.contains(DashboardCard.QIBLA.id))
        assertEquals(DashboardConfig.CURRENT_VERSION, dataStore.data.first()[intPreferencesKey("dashboard_config_version")])
    }

    @Test
    fun `recovers a stored layout with unknown and duplicated identifiers`() = runTest {
        // Simulates a store written by a different version or edited by hand.
        dataStore.edit { preferences ->
            preferences[stringPreferencesKey("dashboard_card_order")] =
                "ghost_card,next_prayer,next_prayer,today_timetable"
            preferences[stringPreferencesKey("dashboard_hidden_cards")] = "ghost_card"
            preferences[intPreferencesKey("dashboard_config_version")] = 42
        }

        val recovered = repository.dashboardConfig.first()

        assertFalse(recovered.order.contains("ghost_card"))
        assertFalse(recovered.hidden.contains("ghost_card"))
        assertEquals(1, recovered.order.count { it == DashboardCard.NEXT_PRAYER.id })
        assertEquals(DashboardConfig.CURRENT_VERSION, recovered.version)
        // The two known ids kept their saved order, and the rest were appended.
        assertEquals(
            listOf(DashboardCard.NEXT_PRAYER.id, DashboardCard.TODAY_TIMETABLE.id),
            recovered.order.take(2),
        )
        assertEquals(DashboardCard.entries.map { it.id }.toSet(), recovered.order.toSet())
    }

    @Test
    fun `reordering and hiding cards leaves the other preferences untouched`() = runTest {
        repository.setThemeMode(ThemeMode.DARK)
        repository.updatePrayerSettings { it.copy(method = PrayerCalculationMethod.KUWAIT) }
        repository.setAppLanguage(AppLanguage.BANGLA)

        repository.updateDashboardConfig { config ->
            config
                .withCardEnabled(DashboardCard.QIBLA, enabled = true)
                .moved(DashboardCard.DAILY_TOOLS, delta = -2)
        }

        assertEquals(ThemeMode.DARK, repository.themeMode.first())
        assertEquals(PrayerCalculationMethod.KUWAIT, repository.prayerSettings.first().method)
        assertEquals(AppLanguage.BANGLA, repository.appLanguage.first())
    }

    @Test
    fun `a dashboard write adds only dashboard keys and rewrites nothing else`() = runTest {
        repository.setThemeMode(ThemeMode.DARK)
        repository.updatePrayerSettings { it.copy(method = PrayerCalculationMethod.KUWAIT) }
        val before = dataStore.data.first().asMap().keys

        repository.updateDashboardConfig { config ->
            config.withCardEnabled(DashboardCard.QIBLA, enabled = true)
        }

        val added = dataStore.data.first().asMap().keys - before
        assertEquals(
            setOf(
                stringPreferencesKey("dashboard_card_order"),
                stringPreferencesKey("dashboard_hidden_cards"),
                intPreferencesKey("dashboard_config_version"),
            ),
            added,
        )
    }

    @Test
    fun `restoring the default layout resets only the dashboard`() = runTest {
        repository.setThemeMode(ThemeMode.DARK)
        repository.updatePrayerSettings { it.copy(method = PrayerCalculationMethod.TURKEY) }
        repository.setAppLanguage(AppLanguage.ENGLISH)
        repository.updateDashboardConfig { config ->
            config.withCardEnabled(DashboardCard.QIBLA, enabled = true).withCardEnabled(
                DashboardCard.NEXT_PRAYER,
                enabled = false,
            )
        }

        repository.updateDashboardConfig { config -> config.resetToDefault() }

        assertEquals(DashboardConfig.DEFAULT, repository.dashboardConfig.first())
        // Unrelated settings survive the reset: only the layout was restored.
        assertEquals(ThemeMode.DARK, repository.themeMode.first())
        assertEquals(PrayerCalculationMethod.TURKEY, repository.prayerSettings.first().method)
        assertEquals(AppLanguage.ENGLISH, repository.appLanguage.first())
    }
}
