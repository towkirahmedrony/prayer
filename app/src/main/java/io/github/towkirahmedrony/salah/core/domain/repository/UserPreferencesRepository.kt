package io.github.towkirahmedrony.salah.core.domain.repository

import io.github.towkirahmedrony.salah.core.model.ThemeMode
import kotlinx.coroutines.flow.Flow

/** User preferences that must survive process death and work offline. */
interface UserPreferencesRepository {

    val themeMode: Flow<ThemeMode>

    suspend fun setThemeMode(mode: ThemeMode)
}
