package io.github.towkirahmedrony.salah.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.towkirahmedrony.salah.core.domain.repository.UserPreferencesRepository
import io.github.towkirahmedrony.salah.core.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

/**
 * Local, offline user preferences.
 *
 * Unknown stored values fall back to a safe default instead of failing, so a downgraded or
 * corrupted store cannot break the app.
 */
class DataStoreUserPreferencesRepository(
    private val dataStore: DataStore<Preferences>,
) : UserPreferencesRepository {

    override val themeMode: Flow<ThemeMode> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { preferences ->
            val stored = preferences[Keys.THEME_MODE]
            ThemeMode.entries.firstOrNull { it.name == stored } ?: ThemeMode.SYSTEM
        }

    override suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { preferences ->
            preferences[Keys.THEME_MODE] = mode.name
        }
    }

    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
    }
}
