package io.github.towkirahmedrony.salah.feature.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.towkirahmedrony.salah.R
import io.github.towkirahmedrony.salah.core.designsystem.components.SalahSectionCard
import io.github.towkirahmedrony.salah.core.model.ThemeMode
import org.koin.androidx.compose.koinViewModel

/**
 * Appearance settings.
 *
 * The theme is the only appearance preference Salah has, and it is offered as three named modes rather
 * than two switches because "follow the device" is a third, distinct answer: it is what a user who has
 * set their phone to switch at sunset actually wants, and it is the default. The choice is written
 * through the same DataStore key it always was, so the shell re-themes on the next emission without a
 * restart and the selection survives process death.
 *
 * Nothing else is here. Salah has one type scale, one colour scheme per mode and no font-size or density
 * setting, so no control is invented to fill the screen: an appearance page with a single real
 * preference is complete, and one with decorative extras would be a page of claims.
 */
@Composable
fun SettingsAppearanceScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    SettingsSubpage(
        title = stringResource(R.string.settings_theme_title),
        onBack = onBack,
        modifier = modifier,
    ) {
        Text(
            text = stringResource(R.string.settings_theme_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SalahSectionCard {
            SettingsOptionGroup(
                title = stringResource(R.string.settings_theme_mode_title),
                body = null,
                options = ThemeMode.entries,
                selected = uiState.themeMode,
                label = { mode -> themeModeLabelRes(mode) },
                hint = { null },
                onSelect = viewModel::setThemeMode,
            )
            Spacer(modifier = Modifier.height(2.dp))
        }
    }
}

private fun themeModeLabelRes(mode: ThemeMode): Int = when (mode) {
    ThemeMode.SYSTEM -> R.string.settings_theme_system
    ThemeMode.LIGHT -> R.string.settings_theme_light
    ThemeMode.DARK -> R.string.settings_theme_dark
}
