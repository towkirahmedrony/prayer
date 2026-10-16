package io.github.towkirahmedrony.salah.navigation

import androidx.annotation.StringRes
import io.github.towkirahmedrony.salah.R

/**
 * Navigation boundaries of Salah.
 *
 * Each route has an explicit entry in the app navigation graph. UI labels are string resources so
 * the bottom bar and destination screens use the app's localized names consistently.
 *
 * The bottom bar shows Home, Calendar, Tools and Settings. Qibla and Ramadan are available from the
 * Tools screen and from the Home screen's daily tools; the Prayer screen is reached from Tools and
 * from Home's "View all" and "View details", and the Tasbih counter from Tools alone.
 *
 * Settings is an overview of categories rather than a list of every preference, so each category is
 * a destination of its own: the four declared below, plus prayer notifications, location and the
 * calendar's own preferences, which already had screens. Nothing here is in the bottom bar —
 * configuration is not a place the user works.
 *
 * **[bottomBarDestinations] is the bottom bar.** The items, their order and their labels all come from
 * this enum, so the bar cannot list a destination the graph does not declare, and moving a feature off
 * the bar is a change to one flag here rather than a deletion of its route: `prayer` left the bar when
 * Tools took its place, and every entry point into it still navigates to the same route.
 */
enum class SalahDestination(
    val route: String,
    @StringRes val labelRes: Int,
    val inBottomBar: Boolean = true,
) {
    HOME("home", R.string.nav_home),

    /**
     * Live prayer-period and timetable screen.
     *
     * No longer a bottom-bar item — Tools took that place — but still a screen, still on its original
     * route, and still the target of Home's "View all" and "View details" and of the Tools screen's
     * prayer-times entry. Removing the tab reorganised the bar; it did not remove a destination.
     */
    PRAYER("prayer", R.string.nav_prayer, inBottomBar = false),

    CALENDAR("calendar", R.string.nav_calendar),

    /**
     * Calendar preferences: default view, Hijri offset and date language.
     *
     * Reached from the Calendar screen, and not in the bottom bar because it is configuration rather
     * than a place the user reads dates. Kept off the general Settings screen so everything a date can
     * be affected by sits next to the dates themselves.
     */
    CALENDAR_SETTINGS("calendar_settings", R.string.calendar_settings_title, inBottomBar = false),

    /**
     * The Tools destination: a landing screen for the tools that are not places the user dwells.
     *
     * A bottom-bar item as well as a screen, so the utilities that used to be reachable only from
     * Home's daily tools now have a fixed entry point. It holds no data of its own — every row opens a
     * destination declared here.
     */
    TOOLS("tools", R.string.nav_tools),

    RAMADAN("ramadan", R.string.nav_ramadan, inBottomBar = false),
    SETTINGS("settings", R.string.nav_settings),

    /**
     * The settings categories, each reached from the Settings overview and nowhere else.
     *
     * One destination per category rather than one screen with sections, because a settings list that
     * grows without end is what this structure exists to avoid: a category can gain controls without
     * making the overview longer or denser. `SettingsCategory` names them on the overview side and holds
     * the destination it opens — the enum member itself, not a copy of the route — so the two cannot
     * drift apart.
     */
    SETTINGS_GENERAL("settings_general", R.string.settings_category_general_title, inBottomBar = false),
    SETTINGS_APPEARANCE("settings_appearance", R.string.settings_theme_title, inBottomBar = false),
    SETTINGS_PRAYER_TIMES("settings_prayer_times", R.string.settings_category_prayer_times_title, inBottomBar = false),
    SETTINGS_ABOUT("settings_about", R.string.settings_about_title, inBottomBar = false),

    /**
     * Prayer notification settings: the "Notifications & Adhan" category of Settings.
     *
     * A destination of its own rather than a section of Settings: it carries per-prayer detail, two
     * independent platform permission states and the live schedule status, which is more than a
     * settings list can show honestly. Reachable from the Settings overview and from the calendar and
     * Ramadan screens, which say where the Ramadan reminders live rather than growing their own.
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

    /**
     * The Tasbih counter: the count, the target, and the history of finished sessions.
     *
     * A tool rather than a tab, and reached from the Tools screen alone. Counting is something a user
     * opens when they mean to count and leaves when they are done — it is not a place to dwell, and it has
     * no time or date on it that would go stale while it was not being looked at. Its state is kept anyway,
     * because the one thing a counter must survive is the user leaving the screen mid-count. See
     * `docs/TASBIH_COUNTER.md`.
     */
    TASBIH("tasbih", R.string.tasbih_title, inBottomBar = false),
    ;

    companion object {
        /**
         * The bottom bar, in the order it draws: Home, Calendar, Tools, Settings.
         *
         * Declaration order rather than a second hand-written list, and `SalahBottomNavigationTest`
         * pins it, so a destination gaining `inBottomBar` cannot appear at the end by accident.
         */
        val bottomBarDestinations: List<SalahDestination>
            get() = entries.filter { it.inBottomBar }

        fun fromRoute(route: String?): SalahDestination? =
            entries.firstOrNull { it.route == route }
    }
}
