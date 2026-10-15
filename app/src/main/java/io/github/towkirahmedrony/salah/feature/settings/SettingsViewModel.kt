package io.github.towkirahmedrony.salah.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.towkirahmedrony.salah.core.domain.notification.PrayerNotificationCoordinator
import io.github.towkirahmedrony.salah.core.domain.notification.ReconcileReason
import io.github.towkirahmedrony.salah.core.domain.prayerconfig.PrayerConfigApplier
import io.github.towkirahmedrony.salah.core.domain.prayerconfig.PrayerConfigOrigin
import io.github.towkirahmedrony.salah.core.domain.repository.UserPreferencesRepository
import io.github.towkirahmedrony.salah.core.model.AppLanguage
import io.github.towkirahmedrony.salah.core.model.AsrCalculationMethod
import io.github.towkirahmedrony.salah.core.model.HighLatitudeRule
import io.github.towkirahmedrony.salah.core.model.PrayerCalculationMethod
import io.github.towkirahmedrony.salah.core.model.PrayerConfigOverrides
import io.github.towkirahmedrony.salah.core.model.PrayerName
import io.github.towkirahmedrony.salah.core.model.PrayerSettings
import io.github.towkirahmedrony.salah.core.model.ThemeMode
import io.github.towkirahmedrony.salah.core.model.TimeFormatPreference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * State and actions for the settings subpages that write to local preferences.
 *
 * One view model serves the General, Appearance and Prayer times screens because they read and write the
 * same preferences; a class per screen would be several copies of the same flows. State is the
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
    /**
     * The effective-configuration applier, or `null` for a caller that does not manage remote configuration.
     *
     * It is optional so the prayer-settings contract (settings are stored, the timetable is recalculated)
     * stays testable without standing up the whole remote pipeline. When it is present, the screen can also
     * report where the effective configuration came from and reset a user override back to its default; when
     * it is absent those two affordances simply do not appear.
     */
    private val applier: PrayerConfigApplier? = null,
) : ViewModel() {

    private val message = MutableStateFlow<SettingsMessage?>(null)

    init {
        // Reads the stored remote configuration once, so the origin flow has the active revision. It writes
        // no preference and blocks nothing.
        applier?.let { effective -> viewModelScope.launch { effective.refresh() } }
    }

    /**
     * Where the effective calculation configuration comes from, for the Prayer times screen.
     *
     * A bundled default when no remote configuration is stored and the user owns nothing; a published
     * global or area configuration when one applies; and a user override once the user has changed any
     * calculation field themselves.
     */
    val prayerConfigOrigin: StateFlow<PrayerConfigOrigin> =
        (applier?.origin ?: flowOf(PrayerConfigOrigin.BUNDLED)).stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(subscribeTimeoutMillis),
            initialValue = PrayerConfigOrigin.BUNDLED,
        )

    val uiState: StateFlow<SettingsUiState> = combine(
        userPreferencesRepository.themeMode,
        userPreferencesRepository.prayerSettings,
        userPreferencesRepository.appLanguage,
        message,
    ) { themeMode, prayerSettings, appLanguage, currentMessage ->
        SettingsUiState(
            themeMode = themeMode,
            prayerSettings = prayerSettings,
            appLanguage = appLanguage,
            message = currentMessage,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(subscribeTimeoutMillis),
        initialValue = SettingsUiState(
            themeMode = ThemeMode.SYSTEM,
            prayerSettings = PrayerSettings.DEFAULT,
            appLanguage = AppLanguage.DEFAULT,
            message = null,
        ),
    )

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { userPreferencesRepository.setThemeMode(mode) }
    }

    // --- Interface language -----------------------------------------------------

    /**
     * Stores the interface [language] the General screen's selector chose.
     *
     * **Nothing is rescheduled.** A language changes words, not times: it is not part of any
     * calculation, so the reminders are left alone and no timetable is invalidated. The one call this
     * deliberately does *not* make is [PrayerNotificationCoordinator.reconcile], which every other
     * control on these screens makes — rescheduling the alarm set because a label changed would be
     * work with no effect on any delivered reminder.
     *
     * **A failure is reported, never assumed away.** The control renders the *stored* language, so a
     * write that did not happen leaves the selection where it was; this catches the failure, clears any
     * earlier message and reports [SettingsMessage.LanguageSaveFailed] so the screen can say which
     * language is really in force instead of leaving the user with a selection that quietly did not
     * stick. [CancellationException] is rethrown because a cancelled scope is not a failed write.
     */
    fun setAppLanguage(language: AppLanguage) {
        viewModelScope.launch {
            try {
                userPreferencesRepository.setAppLanguage(language)
                message.value = null
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                message.value = SettingsMessage.LanguageSaveFailed
            }
        }
    }

    // --- Prayer calculation -----------------------------------------------------

    fun setCalculationMethod(method: PrayerCalculationMethod) =
        updateSettings(PrayerConfigOverrides.Field.METHOD) { it.copy(method = method) }

    fun setAsrMethod(asrMethod: AsrCalculationMethod) =
        updateSettings(PrayerConfigOverrides.Field.ASR_METHOD) { it.copy(asrMethod = asrMethod) }

    fun setHighLatitudeRule(rule: HighLatitudeRule) =
        updateSettings(PrayerConfigOverrides.Field.HIGH_LATITUDE_RULE) { it.copy(highLatitudeRule = rule) }

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
        updateSettings(PrayerConfigOverrides.Field.ADJUSTMENTS) { it.withAdjustment(prayer, minutes) }

    /** Shifts [prayer]'s correction by [deltaMinutes], relative to the stored value. */
    fun shiftAdjustment(prayer: PrayerName, deltaMinutes: Int) =
        updateSettings(PrayerConfigOverrides.Field.ADJUSTMENTS) {
            it.withAdjustment(prayer, it.adjustmentFor(prayer) + deltaMinutes)
        }

    /**
     * Clears every manual correction.
     *
     * The adjustment field stays user-owned: asking for zero is an explicit choice, and a later remote
     * revision must not quietly put the corrections back. Resetting the field back to its applicable
     * default is the separate "reset to defaults" action.
     */
    fun clearAdjustments() =
        updateSettings(PrayerConfigOverrides.Field.ADJUSTMENTS) { it.withoutAdjustments() }

    /**
     * Releases every calculation field the user owns back to its applicable default and reapplies.
     *
     * Only meaningful when an applier is present; it is a no-op otherwise. The reminders are rebuilt, because
     * the reset changes the calculated times.
     */
    fun resetToDefaults() {
        message.value = null
        viewModelScope.launch {
            applier?.resetAll()
            message.value = SettingsMessage.PrayerSettingsSaved
            coordinator.reconcile(ReconcileReason.SETTINGS_CHANGED)
        }
    }

    private fun updateSettings(
        field: PrayerConfigOverrides.Field?,
        transform: (PrayerSettings) -> PrayerSettings,
    ) {
        message.value = null
        viewModelScope.launch {
            // Marking the field first records the user's ownership before the value is written, so a sync
            // that runs in between cannot adopt a remote value over the choice being made now.
            if (field != null) {
                userPreferencesRepository.updatePrayerConfigOverrides { it.with(field) }
            }
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
