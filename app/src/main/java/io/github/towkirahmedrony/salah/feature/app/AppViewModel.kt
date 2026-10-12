package io.github.towkirahmedrony.salah.feature.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.towkirahmedrony.salah.StartupTrace
import io.github.towkirahmedrony.salah.core.domain.location.BangladeshLocationDataset
import io.github.towkirahmedrony.salah.core.domain.location.LocationSetup
import io.github.towkirahmedrony.salah.core.domain.location.LocationSetupState
import io.github.towkirahmedrony.salah.core.domain.repository.BangladeshAreaCatalog
import io.github.towkirahmedrony.salah.core.domain.repository.UserPreferencesRepository
import io.github.towkirahmedrony.salah.core.domain.startup.StartupSnapshot
import io.github.towkirahmedrony.salah.core.domain.startup.StoredLocationState
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
 * **Optimistic gate.** A stored location is accepted as soon as it arrives, using only the coarse
 * bounds check (which needs no dataset). The dataset then loads in the background and the full
 * validation (boundary polygon, area still in the dataset) re-runs when it arrives, so a stored
 * location that fails it still returns the app to setup. Without a stored location the gate still
 * waits for the dataset, because the setup screen needs it.
 *
 * **Read from the start-up snapshot, not from the repository.** The gate runs during the first
 * composition, so it must be able to read the stored location without waiting for a dispatch: a
 * cold repository flow can only deliver its first value through one, and while the first frame is
 * being composed that dispatch sits behind the frame. That is what kept the gate on
 * [LocationSetupState.Checking] — and Home on its skeleton — after the location had already been
 * read. The snapshot is already populated by then, so the gate can decide on the first pass.
 */
class AppViewModel(
    userPreferencesRepository: UserPreferencesRepository,
    private val startup: StartupSnapshot,
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
        startup.storedLocation,
        dataset,
        datasetRead,
    ) { stored, loadedDataset, hasRead ->
        val location = (stored as? StoredLocationState.Loaded)?.location
        when {
            hasRead -> LocationSetup.evaluate(stored = location, dataset = loadedDataset)
            location != null -> LocationSetup.evaluate(stored = location, dataset = null)
            else -> LocationSetupState.Checking
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
            // Suspends until the store has actually been read once, so the mark still means "the
            // stored location is known" rather than "a coroutine was started".
            startup.awaitStoredLocation()
            StartupTrace.mark("stored_location_first")
        }
    }
}
