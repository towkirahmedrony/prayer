package io.github.towkirahmedrony.salah.feature.settings

import androidx.annotation.StringRes
import io.github.towkirahmedrony.salah.R
import io.github.towkirahmedrony.salah.navigation.SalahDestination

/**
 * The categories the Settings overview is divided into.
 *
 * **One entry is one row and one screen.** The overview renders these in declaration order and opens
 * [destination] when a row is tapped, so adding a category is one entry here plus its destination —
 * neither the overview nor the navigation graph holds a hand-written copy of the list, and a category
 * cannot appear on the overview without a screen to open.
 *
 * [destination] is the enum member itself rather than a copied route string. A route spelled twice is a
 * route that can be spelled differently in the two places, and navigating to a route the graph does not
 * declare fails quietly — the tap simply does nothing. `SettingsCategoryTest` asserts every entry maps
 * to its own destination and that none of them is the Settings screen itself or a bottom-bar tab.
 *
 * Only categories with real settings behind them are declared. There is no "Account" entry because
 * Salah has no accounts, and no separate "Language" entry because the interface language is a single
 * three-way choice and lives on [GENERAL] with the other app-wide preference — a whole category and an
 * extra tap would make the one control a user who cannot read the current language most needs the
 * hardest to reach. The calendar's *date* language is a different preference and stays with the
 * calendar, where the dates it affects are.
 */
enum class SettingsCategory(
    val destination: SalahDestination,
    @StringRes val titleRes: Int,
    @StringRes val descriptionRes: Int,
) {

    /** Interface language and time format: the preferences that apply to the app as a whole. */
    GENERAL(
        destination = SalahDestination.SETTINGS_GENERAL,
        titleRes = R.string.settings_category_general_title,
        descriptionRes = R.string.settings_category_general_description,
    ),

    /** Light, dark or the device's own setting. */
    APPEARANCE(
        destination = SalahDestination.SETTINGS_APPEARANCE,
        titleRes = R.string.settings_theme_title,
        descriptionRes = R.string.settings_category_appearance_description,
    ),

    /** How the times themselves are calculated, and any correction the user applies to them. */
    PRAYER_TIMES(
        destination = SalahDestination.SETTINGS_PRAYER_TIMES,
        titleRes = R.string.settings_category_prayer_times_title,
        descriptionRes = R.string.settings_category_prayer_times_description,
    ),

    /** Per-prayer reminders, sound and vibration: the existing notifications screen. */
    NOTIFICATIONS(
        destination = SalahDestination.NOTIFICATIONS,
        titleRes = R.string.settings_category_notifications_title,
        descriptionRes = R.string.settings_category_notifications_description,
    ),

    /** The stored location, a device fix, the manual area picker and manual coordinates. */
    LOCATION(
        destination = SalahDestination.LOCATION,
        titleRes = R.string.nav_location,
        descriptionRes = R.string.settings_category_location_description,
    ),

    /** Default calendar, Hijri adjustment and date language: the existing calendar settings. */
    CALENDAR(
        destination = SalahDestination.CALENDAR_SETTINGS,
        titleRes = R.string.calendar_settings_title,
        descriptionRes = R.string.calendar_settings_body,
    ),

    /** Version, the prayer engine and what Salah does not claim. */
    ABOUT(
        destination = SalahDestination.SETTINGS_ABOUT,
        titleRes = R.string.settings_about_title,
        descriptionRes = R.string.settings_category_about_description,
    ),
    ;
}
