package io.github.towkirahmedrony.salah.feature.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.towkirahmedrony.salah.StartupTrace
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * App-level state shared above the navigation graph: the theme, and whether Salah has a location it
 * can work with. The gate reports [LocationSetupState.Checking] until both the dataset and the stored
 * location have arrived.
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
        StartupTrace.mark("vm_created")
        viewModelScope.launch {
            val loaded = runCatching { areaCatalog.dataset() }.getOrNull()
            StartupTrace.mark("dataset_done")
            dataset.value = loaded
            datasetRead.value = true
        }
        viewModelScope.launch {
            locationRepository.location.first()
            StartupTrace.mark("stored_location_first")
        }
    }
}
