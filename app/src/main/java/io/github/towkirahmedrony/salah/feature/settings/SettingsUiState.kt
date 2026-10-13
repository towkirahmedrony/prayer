package io.github.towkirahmedrony.salah.feature.settings

import io.github.towkirahmedrony.salah.core.model.AppLanguage
import io.github.towkirahmedrony.salah.core.model.PrayerSettings
import io.github.towkirahmedrony.salah.core.model.ThemeMode

/**
 * Transient feedback shown after a settings action.
 *
 * A message is never a substitute for state: everything that can be changed is also shown by the
 * control that changed it, and this only explains a consequence the control cannot — that the
 * timetable was recalculated, or that a change the control appeared to accept was not stored.
 * Location outcomes are not listed here any more: the location screen reports its own, beside the
 * fields that produced them.
 */
sealed interface SettingsMessage {
    /** Shown after any change that invalidates the current timetable, so the user is told why. */
    data object PrayerSettingsSaved : SettingsMessage

    /**
     * Shown when the interface language could not be stored.
     *
     * The one message on these screens that reports a *failure*, and it exists for a specific reason:
     * the language selector's selection is the stored preference, so a rejected write leaves the
     * control on the old value. Without this line the user would see a choice that silently did not
     * stick; with it, the app says which choice is in force. There is no matching success message —
     * a language that did change is visible in every word on the screen.
     */
    data object LanguageSaveFailed : SettingsMessage
}

/**
 * What the settings subpages render.
 *
 * All three preferences are the repository's, unmodified: the theme is read and written as one mode,
 * the calculation settings as one immutable object, and the interface language as one value. There is
 * no local editable copy, which is what makes it impossible for these screens to show a value that is
 * not stored.
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
    /**
     * The persisted interface language; the General screen's selector reads its selection from here.
     *
     * Independent of every calendar preference, including the calendar's own date language, so a
     * language change can never be confused with a calendar-system or date-script change.
     */
    val appLanguage: AppLanguage,
    val message: SettingsMessage?,
)
