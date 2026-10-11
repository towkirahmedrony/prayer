package io.github.towkirahmedrony.salah.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.towkirahmedrony.salah.core.domain.repository.LocationRepository
import io.github.towkirahmedrony.salah.core.domain.repository.UserPreferencesRepository
import io.github.towkirahmedrony.salah.core.model.AsrCalculationMethod
import io.github.towkirahmedrony.salah.core.model.HighLatitudeRule
import io.github.towkirahmedrony.salah.core.model.PrayerCalculationMethod
import io.github.towkirahmedrony.salah.core.model.PrayerName
import io.github.towkirahmedrony.salah.core.model.PrayerSettings
import io.github.towkirahmedrony.salah.core.model.ThemeMode
import io.github.towkirahmedrony.salah.core.model.TimeFormatPreference
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
 * Theme, manual location and every calculation setting are stored on the device; no network call
 * and no location permission is involved. Invalid input is rejected by the repository and
 * reported as a message instead of being silently dropped.
 *
 * Prayer settings are never written as a whole object read earlier: each action goes through
 * [UserPreferencesRepository.updatePrayerSettings], which reads and writes inside one DataStore
 * transaction. Two quick taps therefore both take effect, and the UI state cannot disagree with
 * what is stored.
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
        userPreferencesRepository.prayerSettings,
        form,
        message,
    ) { themeMode, location, prayerSettings, currentForm, currentMessage ->
        SettingsUiState(
            themeMode = themeMode,
            location = location,
            prayerSettings = prayerSettings,
            form = currentForm.withStoredLocation(location),
            message = currentMessage,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(subscribeTimeoutMillis),
        initialValue = SettingsUiState(
            themeMode = ThemeMode.SYSTEM,
            location = null,
            prayerSettings = PrayerSettings.DEFAULT,
            form = LocationFormState(),
            message = null,
        ),
    )

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { userPreferencesRepository.setThemeMode(mode) }
    }

    // --- Prayer calculation -----------------------------------------------------

    fun setCalculationMethod(method: PrayerCalculationMethod) =
        updateSettings { it.copy(method = method) }

    fun setAsrMethod(asrMethod: AsrCalculationMethod) =
        updateSettings { it.copy(asrMethod = asrMethod) }

    fun setHighLatitudeRule(rule: HighLatitudeRule) =
        updateSettings { it.copy(highLatitudeRule = rule) }

    fun setTimeFormat(preference: TimeFormatPreference) =
        updateSettings { it.copy(timeFormat = preference) }

    /** Sets [prayer]'s manual correction to [minutes]; the model clamps it to the valid range. */
    fun setAdjustment(prayer: PrayerName, minutes: Int) =
        updateSettings { it.withAdjustment(prayer, minutes) }

    /** Shifts [prayer]'s correction by [deltaMinutes], relative to the stored value. */
    fun shiftAdjustment(prayer: PrayerName, deltaMinutes: Int) =
        updateSettings { it.withAdjustment(prayer, it.adjustmentFor(prayer) + deltaMinutes) }

    fun clearAdjustments() = updateSettings { it.withoutAdjustments() }

    private fun updateSettings(transform: (PrayerSettings) -> PrayerSettings) {
        message.value = null
        viewModelScope.launch {
            userPreferencesRepository.updatePrayerSettings(transform)
            // Any of these changes alters the calculated times, so the user is told that the
            // timetable is being recalculated rather than left to wonder why it moved.
            message.value = SettingsMessage.PrayerSettingsSaved
        }
    }

    // --- Location ---------------------------------------------------------------

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
