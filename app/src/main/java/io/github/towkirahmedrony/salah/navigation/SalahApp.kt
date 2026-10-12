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
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.github.towkirahmedrony.salah.core.designsystem.CalendarGlyph
import io.github.towkirahmedrony.salah.core.designsystem.CrescentGlyph
import io.github.towkirahmedrony.salah.core.designsystem.DomeGlyph
import io.github.towkirahmedrony.salah.core.designsystem.GearGlyph
import io.github.towkirahmedrony.salah.core.designsystem.theme.ForestGreenPrimary
import io.github.towkirahmedrony.salah.core.designsystem.theme.SalahTheme
import io.github.towkirahmedrony.salah.core.model.ThemeMode

@Composable
fun SalahApp(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    SalahTheme(themeMode = themeMode) {
        Scaffold(
            bottomBar = {
                NavigationBar(
                    containerColor = ForestGreenPrimary,
                    contentColor = Color.White,
                    tonalElevation = 0.dp,
                ) {
                    val destinations: List<SalahDestination> = listOf(
                        SalahDestination.Home,
                        SalahDestination.Calendar,
                        SalahDestination.Ramadan,
                        SalahDestination.Settings,
                    )

                    destinations.forEach { dest ->
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
                                val tint = if (selected) Color.White else Color.White.copy(alpha = 0.6f)
                                when (dest) {
                                    SalahDestination.Home -> DomeGlyph(tint = tint)
                                    SalahDestination.Calendar -> CrescentGlyph(tint = tint)
                                    SalahDestination.Ramadan -> CalendarGlyph(tint = tint)
                                    SalahDestination.Settings -> GearGlyph(tint = tint)
                                    else -> DomeGlyph(tint = tint)
                                }
                            },
                            label = {
                                Text(
                                    text = when (dest) {
                                        SalahDestination.Home -> "Home"
                                        SalahDestination.Calendar -> "Prayer"
                                        SalahDestination.Ramadan -> "Calendar"
                                        SalahDestination.Settings -> "Settings"
                                        else -> dest.route
                                    },
                                    color = if (selected) Color.White else Color.White.copy(alpha = 0.6f),
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
}
