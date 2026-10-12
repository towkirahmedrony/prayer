package io.github.towkirahmedrony.salah.feature.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.towkirahmedrony.salah.core.domain.repository.UserPreferencesRepository
import io.github.towkirahmedrony.salah.core.model.CalendarSettings
import io.github.towkirahmedrony.salah.core.model.CalendarSystem
import io.github.towkirahmedrony.salah.core.model.DateLanguagePreference
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Calendar preferences: the default view, the Hijri day offset and the date language.
 *
 * Every action writes through [UserPreferencesRepository.updateCalendarSettings], which reads and
 * writes inside one DataStore transaction, so two quick taps on the Hijri adjustment both take effect
 * and the screen cannot show a value that is not stored. There is no local copy of the settings here:
 * the screen renders the repository's flow, so what the user sees is what is persisted.
 *
 * The offset is clamped by the model rather than by this class, which is what keeps a stored value
 * always representable by the controls that write it.
 */
class CalendarSettingsViewModel(
    private val userPreferencesRepository: UserPreferencesRepository,
) : ViewModel() {

    val settings: StateFlow<CalendarSettings> = userPreferencesRepository.calendarSettings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(SUBSCRIBE_TIMEOUT_MILLIS),
        initialValue = CalendarSettings.DEFAULT,
    )

    /** Chooses which calendar the Calendar screen opens on. */
    fun setPreferredSystem(system: CalendarSystem) = update { it.copy(preferredSystem = system) }

    /** Chooses the script and numerals dates are rendered in. */
    fun setDateLanguage(preference: DateLanguagePreference) = update { it.copy(dateLanguage = preference) }

    /** Moves the Hijri offset by [days], clamped to the supported range by the model. */
    fun shiftHijriAdjustment(days: Int) = update { it.shiftHijriAdjustment(days) }

    /** Returns the Hijri offset to `0`, so Salah shows its own unconverted estimate again. */
    fun resetHijriAdjustment() = update { it.copy(hijriAdjustmentDays = 0) }

    private fun update(transform: (CalendarSettings) -> CalendarSettings) {
        viewModelScope.launch { userPreferencesRepository.updateCalendarSettings(transform) }
    }

    private companion object {
        const val SUBSCRIBE_TIMEOUT_MILLIS = 5_000L
    }
}
