package io.github.towkirahmedrony.salah.navigation

import androidx.annotation.StringRes
import io.github.towkirahmedrony.salah.R

/**
 * Navigation boundaries of Salah.
 *
 * Every destination exists so the graph is complete and the user can see what the product will
 * contain. Destinations that are not implemented yet render a clearly labelled
 * [io.github.towkirahmedrony.salah.feature.common.PlannedFeatureScreen], never fake content.
 */
enum class SalahDestination(
    val route: String,
    @StringRes val labelRes: Int,
    val inBottomBar: Boolean = true,
) {
    HOME("home", R.string.nav_home),
    PRAYER("prayer", R.string.nav_prayer),
    CALENDAR("calendar", R.string.nav_calendar),
    RAMADAN("ramadan", R.string.nav_ramadan),
    SETTINGS("settings", R.string.nav_settings),
    QIBLA("qibla", R.string.shortcut_qibla, inBottomBar = false),
    ;

    companion object {
        val bottomBarDestinations: List<SalahDestination>
            get() = entries.filter { it.inBottomBar }

        fun fromRoute(route: String?): SalahDestination? =
            entries.firstOrNull { it.route == route }
    }
}
