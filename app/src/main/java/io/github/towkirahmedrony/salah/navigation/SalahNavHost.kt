package io.github.towkirahmedrony.salah.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import io.github.towkirahmedrony.salah.R
import io.github.towkirahmedrony.salah.core.designsystem.CrescentGlyph
import io.github.towkirahmedrony.salah.feature.calendar.CalendarScreen
import io.github.towkirahmedrony.salah.feature.calendar.CalendarSettingsScreen
import io.github.towkirahmedrony.salah.feature.common.PlannedFeatureScreen
import io.github.towkirahmedrony.salah.feature.home.HomeScreen
import io.github.towkirahmedrony.salah.feature.location.LocationScreen
import io.github.towkirahmedrony.salah.feature.notifications.PrayerNotificationSettingsScreen
import io.github.towkirahmedrony.salah.feature.qibla.QiblaScreen
import io.github.towkirahmedrony.salah.feature.ramadan.RamadanScreen
import io.github.towkirahmedrony.salah.feature.settings.SettingsScreen
import io.github.towkirahmedrony.salah.feature.tools.ToolsScreen

/**
 * The navigation graph.
 *
 * Home, Calendar (with its settings), Ramadan, Tools, Settings, prayer-notification settings and the
 * Qibla compass are implemented. Only the Prayer destination still renders an explicit "not
 * implemented yet" screen, and it says so rather than showing placeholder data.
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
            PlannedFeatureScreen(
                title = stringResource(R.string.feature_prayer_title),
                body = stringResource(R.string.feature_prayer_body),
                glyph = { tint -> CrescentGlyph(tint = tint) },
            )
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

        composable(SalahDestination.SETTINGS.route) {
            SettingsScreen(
                onOpenNotifications = {
                    navController.navigate(SalahDestination.NOTIFICATIONS.route)
                },
                onOpenLocation = {
                    navController.navigate(SalahDestination.LOCATION.route)
                },
            )
        }

        composable(SalahDestination.LOCATION.route) {
            LocationScreen()
        }

        composable(SalahDestination.NOTIFICATIONS.route) {
            PrayerNotificationSettingsScreen()
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
