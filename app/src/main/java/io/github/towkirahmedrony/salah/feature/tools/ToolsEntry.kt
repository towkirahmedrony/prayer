package io.github.towkirahmedrony.salah.feature.tools

import androidx.annotation.StringRes
import io.github.towkirahmedrony.salah.R
import io.github.towkirahmedrony.salah.navigation.SalahDestination

/**
 * The tools the Tools screen offers.
 *
 * **One entry is one row and one screen.** The screen renders these in declaration order and opens
 * [destination] when a row is tapped, so the rows, the labels and the routes are one declaration:
 * a tool cannot be listed without somewhere to go, and no route string is written down here.
 *
 * [destination] is the enum member itself rather than a copied route. A route spelled twice is a route
 * that can be spelled differently in the two places, and navigating to a route the graph does not
 * declare fails quietly — the tap simply does nothing. `SalahBottomNavigationTest` asserts that every
 * entry opens a declared destination of its own, that it is not the Tools screen itself, and that it is
 * not a bottom-bar tab the user already has.
 *
 * Only features the repository actually implements are listed. Every entry below opens a screen that
 * already existed before Tools became a destination, which is why there is no Quran, Dua or Tasbih row:
 * Salah has no such feature, and a row for one would be a button that opens nothing. The screen states
 * that absence rather than filling the space.
 *
 * **Why the prayer screen is here.** Prayer used to be a bottom-bar item, and it is still the only place
 * the live prayer period and the full timetable are shown together. Removing the tab without a row here
 * would have stranded that screen; instead its first row is the entry point, and Home's "View all" and
 * "View details" still lead to it as before.
 */
enum class ToolsEntry(
    val destination: SalahDestination,
    @StringRes val titleRes: Int,
    @StringRes val descriptionRes: Int,
) {

    /** The live prayer period and the whole day's timetable, with its calculation provenance. */
    PRAYER_TIMES(
        destination = SalahDestination.PRAYER,
        titleRes = R.string.tools_prayer_times_title,
        descriptionRes = R.string.tools_prayer_times_description,
    ),

    /** The compass screen: bearing to the Kaaba, the sensor in use and its limits. */
    QIBLA(
        destination = SalahDestination.QIBLA,
        titleRes = R.string.shortcut_qibla,
        descriptionRes = R.string.shortcut_qibla_subtitle,
    ),

    /** The Ramadan calendar and its daily Sehri and Iftar table. */
    RAMADAN(
        destination = SalahDestination.RAMADAN,
        titleRes = R.string.nav_ramadan,
        descriptionRes = R.string.shortcut_ramadan_subtitle,
    ),
    ;
}
