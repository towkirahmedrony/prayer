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
import io.github.towkirahmedrony.salah.core.designsystem.QiblaGlyph
import io.github.towkirahmedrony.salah.feature.common.PlannedFeatureScreen
import io.github.towkirahmedrony.salah.feature.home.HomeScreen
import io.github.towkirahmedrony.salah.feature.settings.SettingsScreen

/**
 * The navigation graph.
 *
 * Home and Settings are implemented; Prayer, Calendar, Ramadan and Qibla are declared so the
 * boundaries exist, and each renders an explicit "not implemented yet" screen.
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

        composable(SalahDestination.RAMADAN.route) {
            PlannedFeatureScreen(
                title = stringResource(R.string.feature_ramadan_title),
                body = stringResource(R.string.feature_ramadan_body),
                glyph = { tint -> LanternGlyph(tint = tint) },
            )
        }

        composable(SalahDestination.QIBLA.route) {
            PlannedFeatureScreen(
                title = stringResource(R.string.feature_qibla_title),
                body = stringResource(R.string.feature_qibla_body),
                glyph = { tint -> QiblaGlyph(tint = tint) },
            )
        }

        composable(SalahDestination.SETTINGS.route) {
            SettingsScreen()
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
