package io.github.towkirahmedrony.salah.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.github.towkirahmedrony.salah.core.designsystem.CalendarGlyph
import io.github.towkirahmedrony.salah.core.designsystem.CrescentGlyph
import io.github.towkirahmedrony.salah.core.designsystem.DomeGlyph
import io.github.towkirahmedrony.salah.core.designsystem.GearGlyph
import io.github.towkirahmedrony.salah.core.designsystem.LanternGlyph
import io.github.towkirahmedrony.salah.core.designsystem.QiblaGlyph
import io.github.towkirahmedrony.salah.core.designsystem.theme.SalahTheme
import io.github.towkirahmedrony.salah.feature.app.AppViewModel
import org.koin.androidx.compose.koinViewModel

/**
 * Application shell: theme, scaffold and the bottom navigation that draws the five
 * destinations of Salah.
 */
@Composable
fun SalahApp() {
    val appViewModel: AppViewModel = koinViewModel()
    val themeMode by appViewModel.themeMode.collectAsStateWithLifecycle()

    SalahTheme(themeMode = themeMode) {
        val navController = rememberNavController()
        val backStackEntry by navController.currentBackStackEntryAsState()
        val currentRoute = backStackEntry?.destination?.route

        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                SalahBottomBar(
                    currentRoute = currentRoute,
                    onSelect = { destination -> navController.navigateToTopLevel(destination) },
                )
            },
        ) { innerPadding ->
            SalahNavHost(
                navController = navController,
                modifier = Modifier.padding(innerPadding),
            )
        }
    }
}

@Composable
private fun SalahBottomBar(
    currentRoute: String?,
    onSelect: (SalahDestination) -> Unit,
) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
        SalahDestination.bottomBarDestinations.forEach { destination ->
            NavigationBarItem(
                selected = currentRoute == destination.route,
                onClick = { onSelect(destination) },
                icon = {
                    DestinationGlyph(
                        destination = destination,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                },
                label = { Text(text = stringResource(destination.labelRes)) },
            )
        }
    }
}

@Composable
private fun DestinationGlyph(destination: SalahDestination, tint: Color) {
    when (destination) {
        SalahDestination.HOME -> DomeGlyph(tint = tint)
        SalahDestination.PRAYER -> CrescentGlyph(tint = tint)
        SalahDestination.CALENDAR -> CalendarGlyph(tint = tint)
        SalahDestination.RAMADAN -> LanternGlyph(tint = tint)
        SalahDestination.SETTINGS -> GearGlyph(tint = tint)
        SalahDestination.QIBLA -> QiblaGlyph(tint = tint)
    }
}

private fun NavHostController.navigateToTopLevel(destination: SalahDestination) {
    navigate(destination.route) {
        popUpTo(SalahDestination.HOME.route) {
            saveState = true
        }
        launchSingleTop = true
        restoreState = true
    }
}
