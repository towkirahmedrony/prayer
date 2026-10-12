package io.github.towkirahmedrony.salah.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import io.github.towkirahmedrony.salah.R
import io.github.towkirahmedrony.salah.core.designsystem.CalendarGlyph
import io.github.towkirahmedrony.salah.core.designsystem.CrescentGlyph
import io.github.towkirahmedrony.salah.core.designsystem.LanternGlyph
import io.github.towkirahmedrony.salah.feature.common.PlannedFeatureScreen
import io.github.towkirahmedrony.salah.feature.home.HomeScreen
import io.github.towkirahmedrony.salah.feature.notifications.PrayerNotificationSettingsScreen
import io.github.towkirahmedrony.salah.feature.qibla.QiblaScreen
import io.github.towkirahmedrony.salah.feature.settings.SettingsScreen
import io.github.towkirahmedrony.salah.feature.tools.ToolsScreen

/**
 * The navigation graph.
 *
 * Home, Tools, Settings, prayer-notification settings and the Qibla compass
 * are implemented. Prayer, Calendar and Ramadan render explicit
 * "not implemented yet" screens.
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
            PlannedFeatureScreen(
                title = stringResource(R.string.feature_calendar_title),
                body = stringResource(R.string.feature_calendar_body),
                glyph = { tint -> CalendarGlyph(tint = tint) },
            )
        }

        composable(SalahDestination.TOOLS.route) {
            ToolsScreen(onNavigate = { route -> navController.navigate(route) })
        }

        composable(SalahDestination.RAMADAN.route) {
            PlannedFeatureScreen(
                title = stringResource(R.string.feature_ramadan_title),
                body = stringResource(R.string.feature_ramadan_body),
                glyph = { tint -> LanternGlyph(tint = tint) },
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
            )
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
