package io.github.towkirahmedrony.salah.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import io.github.towkirahmedrony.salah.feature.calendar.CalendarScreen
import io.github.towkirahmedrony.salah.feature.calendar.CalendarSettingsScreen
import io.github.towkirahmedrony.salah.feature.home.HomeScreen
import io.github.towkirahmedrony.salah.feature.home.PrayerScreen
import io.github.towkirahmedrony.salah.feature.location.LocationSelectionContext
import io.github.towkirahmedrony.salah.feature.location.LocationSelectionScreen
import io.github.towkirahmedrony.salah.feature.notifications.PrayerNotificationSettingsScreen
import io.github.towkirahmedrony.salah.feature.qibla.QiblaScreen
import io.github.towkirahmedrony.salah.feature.ramadan.RamadanScreen
import io.github.towkirahmedrony.salah.feature.settings.SettingsAboutScreen
import io.github.towkirahmedrony.salah.feature.settings.SettingsAppearanceScreen
import io.github.towkirahmedrony.salah.feature.settings.SettingsGeneralScreen
import io.github.towkirahmedrony.salah.feature.settings.SettingsPrayerTimesScreen
import io.github.towkirahmedrony.salah.feature.settings.SettingsScreen
import io.github.towkirahmedrony.salah.feature.tasbih.TasbihScreen
import io.github.towkirahmedrony.salah.feature.tools.ToolsScreen

/**
 * The navigation graph.
 *
 * The four bottom-bar destinations — Home, Calendar, Tools and Settings, in `SalahDestination`'s order —
 * are declared here with Calendar's settings, Ramadan, the Prayer timetable, Settings' category screens,
 * prayer-notification settings and the Qibla compass.
 *
 * Every secondary screen is reached by a single `navigate` and left by `popBackStack`, so back always
 * returns to the screen that opened it — the Settings overview, the Calendar screen, Ramadan, Home or
 * Tools — and no screen has to know which one it was. Nothing writes to a back stack of its own: the
 * graph's is the only one, so a repeated visit cannot leave a duplicate copy of a screen behind.
 *
 * **Where the Prayer screen is reached from.** Not from the bar: its row on Tools and Home's "View all"
 * and "View details" lead here. Home's two links go through [navigateTo], which pops back to Home, keeps
 * a single instance and restores the state that destination had; Tools goes through [navigateToTool],
 * which is the same single-instance guarantee without Home's state juggling, because a tool is opened
 * *from* Tools and back should return there.
 */
@Composable
fun SalahNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = SalahDestination.HOME.route,
        modifier = modifier,
    ) {
        composable(SalahDestination.HOME.route) {
            HomeScreen(onNavigate = { route -> navController.navigateTo(route) })
        }

        composable(SalahDestination.PRAYER.route) {
            PrayerScreen()
        }

        composable(SalahDestination.CALENDAR.route) {
            CalendarScreen(
                onOpenSettings = {
                    navController.navigate(SalahDestination.CALENDAR_SETTINGS.route)
                },
                onOpenRamadan = { navController.navigate(SalahDestination.RAMADAN.route) },
            )
        }

        composable(SalahDestination.CALENDAR_SETTINGS.route) {
            CalendarSettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenNotifications = {
                    navController.navigate(SalahDestination.NOTIFICATIONS.route)
                },
            )
        }

        composable(SalahDestination.TOOLS.route) {
            ToolsScreen(onOpenTool = { entry -> navController.navigateToTool(entry.destination.route) })
        }

        composable(SalahDestination.RAMADAN.route) {
            RamadanScreen(
                onOpenNotifications = {
                    navController.navigate(SalahDestination.NOTIFICATIONS.route)
                },
                onOpenCalendar = { navController.navigate(SalahDestination.CALENDAR.route) },
            )
        }

        composable(SalahDestination.QIBLA.route) {
            QiblaScreen()
        }

        // The Tasbih counter. Opened from the Tools screen and left by the back gesture, which returns to
        // Tools through the same `popBackStack` every other tool uses — there is no second back stack, so a
        // repeated visit cannot leave a duplicate of the counter behind.
        composable(SalahDestination.TASBIH.route) {
            TasbihScreen(onBack = { navController.popBackStack() })
        }

        // Settings: the overview, then one destination per category. Each category screen is declared
        // separately rather than resolved from a route pattern, so the graph stays the list of screens
        // that exist — and `SettingsCategory.destination` is what the overview navigates with.
        composable(SalahDestination.SETTINGS.route) {
            SettingsScreen(
                onOpenCategory = { category -> navController.navigate(category.destination.route) },
            )
        }

        composable(SalahDestination.SETTINGS_GENERAL.route) {
            SettingsGeneralScreen(onBack = { navController.popBackStack() })
        }

        composable(SalahDestination.SETTINGS_APPEARANCE.route) {
            SettingsAppearanceScreen(onBack = { navController.popBackStack() })
        }

        composable(SalahDestination.SETTINGS_PRAYER_TIMES.route) {
            SettingsPrayerTimesScreen(onBack = { navController.popBackStack() })
        }

        composable(SalahDestination.SETTINGS_ABOUT.route) {
            SettingsAboutScreen(onBack = { navController.popBackStack() })
        }

        // The app's one location selection destination. Settings' Location category navigates here, and
        // so does every other feature that offers a change; the first-launch gate in `SalahApp` renders
        // the same composable with the other context, so there is no second destination to keep in step.
        composable(SalahDestination.LOCATION.route) {
            LocationSelectionScreen(
                context = LocationSelectionContext.CHANGE,
                onBack = { navController.popBackStack() },
            )
        }

        composable(SalahDestination.NOTIFICATIONS.route) {
            PrayerNotificationSettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}

private fun NavHostController.navigateTo(route: String) {
    navigate(route) {
        popUpTo(SalahDestination.HOME.route) {
            saveState = true
        }
        launchSingleTop = true
        restoreState = true
    }
}

/**
 * Opens a tool from the Tools screen and leaves Tools underneath it.
 *
 * Deliberately not [navigateTo]: a tool is not a top-level destination, so nothing is popped and no
 * state is saved or restored — back returns to the Tools list the user tapped from, which is the one
 * thing they can reasonably expect. `launchSingleTop` is what keeps two taps on the same row from
 * stacking two copies of the screen behind them.
 */
private fun NavHostController.navigateToTool(route: String) {
    navigate(route) {
        launchSingleTop = true
    }
}
