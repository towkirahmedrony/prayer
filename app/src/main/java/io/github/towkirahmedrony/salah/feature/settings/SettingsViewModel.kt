package io.github.towkirahmedrony.salah.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.towkirahmedrony.salah.core.domain.repository.LocationRepository
import io.github.towkirahmedrony.salah.core.domain.repository.UserPreferencesRepository
import io.github.towkirahmedrony.salah.core.model.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Settings backed by local persistence only.
 *
 * Theme preference and the manual location are stored on the device; no network call and no
 * location permission is involved. Invalid input is rejected by the repository and reported as
 * a message instead of being silently dropped.
 */
class SettingsViewModel(
    private val userPreferencesRepository: UserPreferencesRepository,
    private val locationRepository: LocationRepository,
) : ViewModel() {

    private val form = MutableStateFlow(LocationFormState())
    private val message = MutableStateFlow<SettingsMessage?>(null)

    val uiState: StateFlow<SettingsUiState> = combine(
        userPreferencesRepository.themeMode,
        locationRepository.location,
        form,
        message,
    ) { themeMode, location, currentForm, currentMessage ->
        SettingsUiState(
            themeMode = themeMode,
            location = location,
            form = currentForm.withStoredLocation(location),
            message = currentMessage,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(subscribeTimeoutMillis),
        initialValue = SettingsUiState(
            themeMode = ThemeMode.SYSTEM,
            location = null,
            form = LocationFormState(),
            message = null,
        ),
    )

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { userPreferencesRepository.setThemeMode(mode) }
    }

    fun onFormChange(transform: (LocationFormState) -> LocationFormState) {
        message.value = null
        form.update { current -> transform(current).copy(isDirty = true) }
    }

    fun saveLocation() {
        val current = form.value
        val latitude = current.latitude.trim().toDoubleOrNull()
        val longitude = current.longitude.trim().toDoubleOrNull()

        viewModelScope.launch {
            val saved = latitude != null && longitude != null && locationRepository.saveManualLocation(
                label = current.label.trim(),
                latitude = latitude,
                longitude = longitude,
                timeZoneId = current.timeZoneId.trim(),
            )
            if (saved) {
                form.update { it.copy(isDirty = false, hasValidationError = false) }
                message.value = SettingsMessage.LocationSaved
            } else {
                form.update { it.copy(hasValidationError = true) }
                message.value = SettingsMessage.LocationRejected
            }
        }
    }

    fun clearLocation() {
        message.value = null
        viewModelScope.launch {
            locationRepository.clearLocation()
            form.value = LocationFormState()
            message.value = SettingsMessage.LocationCleared
        }
    }

    fun dismissMessage() {
        message.value = null
    }

    private companion object {
        const val subscribeTimeoutMillis = 5_000L
    }
}
