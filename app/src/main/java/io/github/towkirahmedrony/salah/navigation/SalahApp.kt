package io.github.towkirahmedrony.salah.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.github.towkirahmedrony.salah.core.designsystem.CalendarGlyph
import io.github.towkirahmedrony.salah.core.designsystem.DomeGlyph
import io.github.towkirahmedrony.salah.core.designsystem.GearGlyph
import io.github.towkirahmedrony.salah.core.designsystem.TasbihGlyph
import io.github.towkirahmedrony.salah.core.designsystem.theme.ForestGreenPrimary
import io.github.towkirahmedrony.salah.core.designsystem.theme.SalahTheme
import io.github.towkirahmedrony.salah.core.domain.location.LocationSetupState
import io.github.towkirahmedrony.salah.feature.app.AppViewModel
import io.github.towkirahmedrony.salah.feature.app.ProvideAppLanguageStrings
import io.github.towkirahmedrony.salah.feature.home.components.HomeSkeleton
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
 *
 * The gate is a decision about the *stored location*, not a flag: [AppViewModel.locationSetup]
 * derives it from the location and the bundled dataset, so "setup is complete" and "a usable
 * location exists" can never disagree. Nothing here marks setup as finished — saving a location is
 * what does that, and it is also what makes this screen disappear.
 *
 * **The appearance preference.** The theme comes from the stored [AppViewModel.themeMode] rather than
 * from a parameter with a default, so the Appearance screen's choice is what actually re-themes the
 * shell: the preference is read here, applied here, and a change reaches the UI on the next emission
 * without a restart. Passing a fixed mode instead would leave the stored value alive in the store and
 * the radio group, and nowhere else.
 *
 * **The language preference.** Applied here for the same reason and in the same way: the shell reads
 * [AppViewModel.appLanguage] and wraps everything it draws in [ProvideAppLanguageStrings]. Every
 * string under the shell — the bottom bar, the Settings overview, Home, Calendar — is therefore
 * resolved in the chosen language, and the choice reaches the UI on the next emission with no
 * activity recreation. Nothing below this point reads the language itself, so the language cannot be
 * applied on one screen and not another.
 */
@Composable
fun SalahApp(
    appViewModel: AppViewModel = koinViewModel(),
) {
    val themeMode by appViewModel.themeMode.collectAsStateWithLifecycle()
    val appLanguage by appViewModel.appLanguage.collectAsStateWithLifecycle()
    val setupState by appViewModel.locationSetup.collectAsStateWithLifecycle()

    ProvideAppLanguageStrings(language = appLanguage) {
        SalahTheme(themeMode = themeMode) {
            when {
                setupState is LocationSetupState.Complete -> AppShell()

                setupState.requiresSetup -> Scaffold(
                    containerColor = MaterialTheme.colorScheme.background,
                ) { innerPadding ->
                    LocationSetupScreen(modifier = Modifier.padding(innerPadding))
                }

                // Still deciding. A skeleton of the screen that normally follows, rather than a spinner:
                // the gate is short-lived, and the Home layout arriving into a screen that already has
                // its shape is a smaller change than a spinner being swapped out for a whole screen.
                else -> Scaffold(
                    containerColor = MaterialTheme.colorScheme.background,
                ) { innerPadding ->
                    HomeSkeleton(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

/** The navigation graph with the bottom bar, shown once a usable location exists. */
@Composable
private fun AppShell() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = ForestGreenPrimary,
                contentColor = Color.White,
                tonalElevation = 0.dp,
            ) {
                // The items, their order and their labels come from the destination enum, so the bar
                // cannot show a tab the graph does not declare and reordering it is one edit there.
                SalahDestination.bottomBarDestinations.forEach { dest ->
                    val selected = currentRoute == dest.route
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(dest.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            val tint = if (selected) Color.White else Color.White.copy(alpha = 0.8f)
                            // Prayer's lantern moved with the feature: it now marks the prayer-times
                            // row on Tools, and Tools carries Salah's own Tasbih mark here.
                            when (dest) {
                                SalahDestination.HOME -> DomeGlyph(tint = tint)
                                SalahDestination.CALENDAR -> CalendarGlyph(tint = tint)
                                SalahDestination.TOOLS -> TasbihGlyph(tint = tint)
                                SalahDestination.SETTINGS -> GearGlyph(tint = tint)
                                else -> DomeGlyph(tint = tint)
                            }
                        },
                        label = {
                            Text(
                                text = stringResource(dest.labelRes),
                                color = if (selected) Color.White else Color.White.copy(alpha = 0.8f),
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = Color.White.copy(alpha = 0.15f),
                        ),
                    )
                }
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background),
        ) {
            SalahNavHost(
                navController = navController,
            )
        }
    }
}
