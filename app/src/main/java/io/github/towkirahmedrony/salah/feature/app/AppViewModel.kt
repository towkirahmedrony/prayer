package io.github.towkirahmedrony.salah.feature.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.towkirahmedrony.salah.core.domain.location.BangladeshLocationDataset
import io.github.towkirahmedrony.salah.core.domain.location.LocationSetup
import io.github.towkirahmedrony.salah.core.domain.location.LocationSetupState
import io.github.towkirahmedrony.salah.core.domain.repository.BangladeshAreaCatalog
import io.github.towkirahmedrony.salah.core.domain.repository.LocationRepository
import io.github.towkirahmedrony.salah.core.domain.repository.UserPreferencesRepository
import io.github.towkirahmedrony.salah.core.model.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * App-level state shared above the navigation graph: the theme, and whether Salah has a location it
 * can work with.
 *
 * The location answer lives here rather than inside a screen because it decides whether the screens
 * exist at all: [io.github.towkirahmedrony.salah.navigation.SalahApp] renders the location setup
 * instead of the navigation graph while no usable location exists, so a first launch cannot reach a
 * location-dependent screen — or the bottom bar that leads to one — before a location is chosen.
 *
 * The dataset is read once, off the main thread, and the gate reports
 * [LocationSetupState.Checking] until both it and the stored location have arrived, so the app never
 * flashes a setup screen at a user who already has a location.
 */
class AppViewModel(
    userPreferencesRepository: UserPreferencesRepository,
    private val locationRepository: LocationRepository,
    private val areaCatalog: BangladeshAreaCatalog,
) : ViewModel() {

    val themeMode: StateFlow<ThemeMode> = userPreferencesRepository.themeMode.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = ThemeMode.SYSTEM,
    )

    private val dataset = MutableStateFlow<BangladeshLocationDataset?>(null)
    private val datasetRead = MutableStateFlow(false)

    /**
     * Whether a usable location exists, re-derived whenever the stored location or the dataset
     * changes.
     *
     * Eager because it is the first thing the app has to decide, and derived rather than stored so a
     * location cleared from anywhere — this screen, Settings, the Qibla screen, or a wiped install —
     * returns the app to setup without anything having to remember to set a flag.
     */
    val locationSetup: StateFlow<LocationSetupState> = combine(
        locationRepository.location,
        dataset,
        datasetRead,
    ) { stored, loadedDataset, hasRead ->
        if (!hasRead) {
            LocationSetupState.Checking
        } else {
            LocationSetup.evaluate(stored = stored, dataset = loadedDataset)
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = LocationSetupState.Checking,
    )

    init {
        viewModelScope.launch {
            val loaded = runCatching { areaCatalog.dataset() }.getOrNull()
            dataset.value = loaded
            datasetRead.value = true
        }
    }
}
