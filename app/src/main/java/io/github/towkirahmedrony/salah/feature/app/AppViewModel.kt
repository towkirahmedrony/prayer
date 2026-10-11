package io.github.towkirahmedrony.salah.feature.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.towkirahmedrony.salah.core.domain.repository.UserPreferencesRepository
import io.github.towkirahmedrony.salah.core.model.ThemeMode
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * App-level state shared above the navigation graph.
 *
 * On first composition the theme mode reads from local preferences; until then the system
 * setting is used, so the theme never flashes the wrong variant.
 */
class AppViewModel(
    userPreferencesRepository: UserPreferencesRepository,
) : ViewModel() {

    val themeMode: StateFlow<ThemeMode> = userPreferencesRepository.themeMode.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = ThemeMode.SYSTEM,
    )
}
