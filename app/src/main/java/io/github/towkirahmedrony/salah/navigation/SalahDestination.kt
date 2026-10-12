package io.github.towkirahmedrony.salah.navigation

import androidx.annotation.StringRes
import io.github.towkirahmedrony.salah.R

/**
 * Navigation boundaries of Salah.
 *
 * Each route has an explicit entry in the app navigation graph. UI labels are string resources so
 * the bottom bar and destination screens use the app's localized names consistently.
 *
 * The bottom bar shows Home, Prayer, Calendar and Settings. Qibla and Ramadan are available from
 * the Home screen's daily tools, and notification settings are reached from Settings.
 */
enum class SalahDestination(
    val route: String,
    @StringRes val labelRes: Int,
    val inBottomBar: Boolean = true,
) {
    HOME("home", R.string.nav_home),

    /** Live prayer-period and timetable screen, also available from the bottom bar. */
    PRAYER("prayer", R.string.nav_prayer),

    CALENDAR("calendar", R.string.nav_calendar),

    /**
     * Calendar preferences: default view, Hijri offset and date language.
     *
     * Reached from the Calendar screen, and not in the bottom bar because it is configuration rather
     * than a place the user reads dates. Kept off the general Settings screen so everything a date can
     * be affected by sits next to the dates themselves.
     */
    CALENDAR_SETTINGS("calendar_settings", R.string.calendar_settings_title, inBottomBar = false),

    /** Secondary tools remain reachable from the Home screen rather than occupying a tab. */
    TOOLS("tools", R.string.nav_tools, inBottomBar = false),

    RAMADAN("ramadan", R.string.nav_ramadan, inBottomBar = false),
    SETTINGS("settings", R.string.nav_settings),

    /**
     * Prayer notification settings.
     *
     * A destination of its own rather than a section of Settings: it carries per-prayer detail, two
     * independent platform permission states and the live schedule status, which is more than a
     * settings list can show honestly. Reached from Settings, and not in the bottom bar because it
     * is a configuration screen, not a place the user works.
     */
    NOTIFICATIONS("notifications", R.string.nav_notifications, inBottomBar = false),

    /**
     * Location setup and management.
     *
     * A destination as well as a gate: the same screen is what the shell renders while no usable
     * location exists, and what Settings opens later to inspect or change it. One route rather than
     * two, so the first launch and every later change cannot diverge.
     */
    LOCATION("location", R.string.nav_location, inBottomBar = false),

    QIBLA("qibla", R.string.shortcut_qibla, inBottomBar = false),
    ;

    companion object {
        val bottomBarDestinations: List<SalahDestination>
            get() = entries.filter { it.inBottomBar }

        fun fromRoute(route: String?): SalahDestination? =
            entries.firstOrNull { it.route == route }
    }
}
