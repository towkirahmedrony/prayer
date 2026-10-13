package io.github.towkirahmedrony.salah.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.towkirahmedrony.salah.core.domain.notification.PrayerNotificationCoordinator
import io.github.towkirahmedrony.salah.core.domain.notification.ReconcileReason
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
import kotlinx.coroutines.launch

/**
 * State and actions for the settings subpages that write to local preferences.
 *
 * One view model serves the General, Appearance and Prayer times screens because they read and write the
 * same two preferences; a class per screen would be three copies of the same two flows. State is the
 * repository's own, so the controls cannot show a value that is not stored, and each action writes
 * through the repository rather than a local copy.
 *
 * Prayer settings are never written as a whole object read earlier: each action goes through
 * [UserPreferencesRepository.updatePrayerSettings], which reads and writes inside one DataStore
 * transaction. Two quick taps therefore both take effect, and the UI state cannot disagree with what is
 * stored.
 *
 * The location used to be here too. It is not any more: the location screen owns the coordinates, the
 * area picker and the device fix together, so a location is written from one place in the app rather
 * than two, and every write goes through the same validation and the same reminder reconciliation.
 *
 * **The scheduler is reconciled from here.** Every control on the Prayer times screen changes the
 * calculated times, and the reminders are planned from those times — so the write is followed by
 * [PrayerNotificationCoordinator.reconcile], the same call the notification screen, the location
 * screen and the reboot receiver make. Without it the alarms would keep announcing the previous
 * profile until the next app start or date change. The time format is the one exception and says so
 * below: it changes no calculated time and therefore reschedules nothing.
 */
class SettingsViewModel(
    private val userPreferencesRepository: UserPreferencesRepository,
    private val coordinator: PrayerNotificationCoordinator,
) : ViewModel() {

    private val message = MutableStateFlow<SettingsMessage?>(null)

    val uiState: StateFlow<SettingsUiState> = combine(
        userPreferencesRepository.themeMode,
        userPreferencesRepository.prayerSettings,
        message,
    ) { themeMode, prayerSettings, currentMessage ->
        SettingsUiState(
            themeMode = themeMode,
            prayerSettings = prayerSettings,
            message = currentMessage,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(subscribeTimeoutMillis),
        initialValue = SettingsUiState(
            themeMode = ThemeMode.SYSTEM,
            prayerSettings = PrayerSettings.DEFAULT,
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

    /**
     * How times are written everywhere in the app, including its reminders.
     *
     * It sits with the calculation settings rather than beside the theme because it is stored with them
     * and read by the same screens, but it is the one setting here that changes no calculated time: the
     * engine computes the same instants and only the formatter differs.
     *
     * Which is why it reports nothing. `PrayerCalculationProfile` — the value a cached timetable is
     * validated against — does not contain the time format, so no schedule is invalidated and nothing is
     * recalculated; the times are re-rendered in place. Announcing a recalculation here would be the one
     * claim on this screen that is not true.
     */
    fun setTimeFormat(preference: TimeFormatPreference) {
        message.value = null
        viewModelScope.launch {
            userPreferencesRepository.updatePrayerSettings { it.copy(timeFormat = preference) }
        }
    }

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
            // And the reminders are rebuilt from those times. The coordinator reads the persisted
            // profile itself, so it plans against the value just written rather than against a copy
            // read before the change; and because its plan is derived from the repository, repeating
            // the call replaces the alarm set instead of duplicating it.
            //
            // CancellationException is the only thing it propagates; a platform refusal or a missing
            // schedule is returned as a value, so an unavailable timetable cannot break this screen.
            coordinator.reconcile(ReconcileReason.SETTINGS_CHANGED)
        }
    }

    private companion object {
        const val subscribeTimeoutMillis = 5_000L
    }
}
