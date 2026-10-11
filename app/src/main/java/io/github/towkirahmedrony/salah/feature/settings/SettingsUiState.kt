package io.github.towkirahmedrony.salah.feature.settings

import io.github.towkirahmedrony.salah.core.model.LocationInfo
import io.github.towkirahmedrony.salah.core.model.PrayerSettings
import io.github.towkirahmedrony.salah.core.model.ThemeMode
import io.github.towkirahmedrony.salah.feature.common.LocationFormState

/** Transient feedback shown after a settings action. */
sealed interface SettingsMessage {
    data object LocationSaved : SettingsMessage
    data object LocationCleared : SettingsMessage
    data object LocationRejected : SettingsMessage

    /** Shown after any change that invalidates the current timetable, so the user is told why. */
    data object PrayerSettingsSaved : SettingsMessage
}

data class SettingsUiState(
    val themeMode: ThemeMode,
    val location: LocationInfo?,
    /** The persisted calculation settings; every control reads its selection from here. */
    val prayerSettings: PrayerSettings,
    val form: LocationFormState,
    val message: SettingsMessage?,
)
