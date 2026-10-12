package io.github.towkirahmedrony.salah.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.github.towkirahmedrony.salah.R
import io.github.towkirahmedrony.salah.core.designsystem.CalendarGlyph
import io.github.towkirahmedrony.salah.core.designsystem.CrescentGlyph
import io.github.towkirahmedrony.salah.core.designsystem.DomeGlyph
import io.github.towkirahmedrony.salah.core.designsystem.GearGlyph
import io.github.towkirahmedrony.salah.core.designsystem.LanternGlyph
import io.github.towkirahmedrony.salah.core.designsystem.QiblaGlyph
import io.github.towkirahmedrony.salah.core.designsystem.ToolsGlyph
import io.github.towkirahmedrony.salah.core.designsystem.theme.SalahTheme
import io.github.towkirahmedrony.salah.core.domain.location.LocationSetupState
import io.github.towkirahmedrony.salah.feature.app.AppViewModel
import io.github.towkirahmedrony.salah.feature.location.LocationSetupScreen
import org.koin.androidx.compose.koinViewModel

/**
 * Application shell.
 *
 * **The location gate.** Everything Salah shows is derived from a place, so the shell decides in one
 * spot whether a usable location exists: while none does, the location setup screen *is* the app —
 * no navigation graph, no bottom bar, and therefore no way to reach a screen that would have to
 * invent a time. Once a location exists the graph is shown, and if the location is later cleared the
 * gate closes again on that same recomposition.
 */
@Composable
fun SalahApp() {
    val appViewModel: AppViewModel = koinViewModel()
    val themeMode by appViewModel.themeMode.collectAsStateWithLifecycle()
    val setupState by appViewModel.locationSetup.collectAsStateWithLifecycle()

    SalahTheme(themeMode = themeMode) {
        when {
            setupState is LocationSetupState.Complete -> AppShell()

            setupState.requiresSetup -> Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
            ) { innerPadding ->
                LocationSetupScreen(modifier = Modifier.padding(innerPadding))
            }

            else -> Scaffold(containerColor = MaterialTheme.colorScheme.background) { innerPadding ->
                LoadingGate(modifier = Modifier.padding(innerPadding))
            }
        }
    }
}

/** Shown while the stored location and the bundled dataset are still being read. */
@Composable
private fun LoadingGate(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.state_loading),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

/** The navigation graph with the bottom bar, shown once a usable location exists. */
@Composable
private fun AppShell() {
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
        SalahDestination.CALENDAR -> CalendarGlyph(tint = tint)
        SalahDestination.TOOLS -> ToolsGlyph(tint = tint)
        SalahDestination.SETTINGS -> GearGlyph(tint = tint)
        // The destinations below are not in the bottom bar; the glyphs only keep this `when`
        // exhaustive and match the section each one belongs to.
        SalahDestination.CALENDAR_SETTINGS -> CalendarGlyph(tint = tint)
        SalahDestination.PRAYER -> CrescentGlyph(tint = tint)
        SalahDestination.RAMADAN -> LanternGlyph(tint = tint)
        SalahDestination.QIBLA -> QiblaGlyph(tint = tint)
        SalahDestination.NOTIFICATIONS -> CrescentGlyph(tint = tint)
        SalahDestination.LOCATION -> DomeGlyph(tint = tint)
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
