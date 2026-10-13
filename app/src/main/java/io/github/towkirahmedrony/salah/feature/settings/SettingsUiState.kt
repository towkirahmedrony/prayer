package io.github.towkirahmedrony.salah.feature.settings

import io.github.towkirahmedrony.salah.core.model.PrayerSettings
import io.github.towkirahmedrony.salah.core.model.ThemeMode

/**
 * Transient feedback shown after a settings action.
 *
 * A message is never a substitute for state: everything that can be changed is also shown by the
 * control that changed it, and this only explains a consequence the control cannot — that the
 * timetable was recalculated. Location outcomes are not listed here any more: the location screen
 * reports its own, beside the fields that produced them.
 */
sealed interface SettingsMessage {
    /** Shown after any change that invalidates the current timetable, so the user is told why. */
    data object PrayerSettingsSaved : SettingsMessage
}

/**
 * What the settings subpages render.
 *
 * Both preferences are the repository's, unmodified: the theme is read and written as one mode, and the
 * calculation settings as one immutable object. There is no local editable copy, which is what makes it
 * impossible for these screens to show a value that is not stored.
 *
 * The location and the manual-coordinate form used to live here as well. They belong to the location
 * screen, which owns the same state for the area picker and a device fix — so the app now has one place
 * that writes a location instead of two, and this screen has one fewer reason to exist beyond the
 * preferences it actually owns.
 */
data class SettingsUiState(
    val themeMode: ThemeMode,
    /** The persisted calculation settings; every control reads its selection from here. */
    val prayerSettings: PrayerSettings,
    val message: SettingsMessage?,
)
