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
import io.github.towkirahmedrony.salah.feature.location.LocationScreen
import io.github.towkirahmedrony.salah.feature.notifications.PrayerNotificationSettingsScreen
import io.github.towkirahmedrony.salah.feature.qibla.QiblaScreen
import io.github.towkirahmedrony.salah.feature.ramadan.RamadanScreen
import io.github.towkirahmedrony.salah.feature.settings.SettingsAboutScreen
import io.github.towkirahmedrony.salah.feature.settings.SettingsAppearanceScreen
import io.github.towkirahmedrony.salah.feature.settings.SettingsGeneralScreen
import io.github.towkirahmedrony.salah.feature.settings.SettingsPrayerTimesScreen
import io.github.towkirahmedrony.salah.feature.settings.SettingsScreen
import io.github.towkirahmedrony.salah.feature.tools.ToolsScreen

/**
 * The navigation graph.
 *
 * Home, Prayer, Calendar (with its settings), Ramadan, Tools, Settings with its category screens,
 * prayer-notification settings and the Qibla compass are implemented.
 *
 * Every configuration screen is reached by a single `navigate` and left by `popBackStack`, so back
 * always returns to the screen that opened it — the Settings overview, the Calendar screen or Ramadan —
 * and no screen has to know which one it was. Nothing writes to a back stack of its own: the graph's is
 * the only one, so a repeated visit cannot leave a duplicate copy of a screen behind.
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
            ToolsScreen(onNavigate = { route -> navController.navigate(route) })
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

        composable(SalahDestination.LOCATION.route) {
            LocationScreen(onBack = { navController.popBackStack() })
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
