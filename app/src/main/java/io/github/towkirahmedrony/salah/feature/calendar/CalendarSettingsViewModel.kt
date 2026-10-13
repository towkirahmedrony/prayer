package io.github.towkirahmedrony.salah.feature.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.towkirahmedrony.salah.core.domain.notification.PrayerNotificationCoordinator
import io.github.towkirahmedrony.salah.core.domain.notification.ReconcileReason
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
 *
 * **Only the Hijri offset reschedules anything.** Which Gregorian dates are in Ramadan is decided by
 * that offset, and the Sehri and Iftar reminders are planned from the calendar's Ramadan window — so
 * moving the offset is followed by [PrayerNotificationCoordinator.reconcile], and the reminders move
 * with the dates the screen is now showing instead of waiting for the next app start. The default
 * view and the date language change no time, so they reconcile nothing.
 */
class CalendarSettingsViewModel(
    private val userPreferencesRepository: UserPreferencesRepository,
    private val coordinator: PrayerNotificationCoordinator,
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
        viewModelScope.launch {
            // Whether the offset moved is decided inside the transaction, against the value that is
            // actually stored: a stepper tap that clamps back to the same number must not reschedule,
            // and a read taken outside the transaction could not tell the two apart.
            var hijriOffsetChanged = false
            userPreferencesRepository.updateCalendarSettings { current ->
                val updated = transform(current)
                if (updated.hijriAdjustmentDays != current.hijriAdjustmentDays) {
                    hijriOffsetChanged = true
                }
                updated
            }
            if (hijriOffsetChanged) {
                coordinator.reconcile(ReconcileReason.SETTINGS_CHANGED)
            }
        }
    }

    private companion object {
        const val SUBSCRIBE_TIMEOUT_MILLIS = 5_000L
    }
}
